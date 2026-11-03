package com.winlator.xenvironment.components;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.core.content.ContextCompat;

import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exposes the Android microphone to Wine/Proton as a real PulseAudio *source*.
 *
 * How it works
 * ------------
 * {@link PulseAudioComponent} loads PulseAudio's stock {@code module-pipe-source}, which creates a
 * FIFO and registers a capture device ("source") that reads raw PCM from it. This component captures
 * from the Android microphone with {@link AudioRecord} and feeds that PCM into the FIFO.
 *
 * Wine's {@code winepulse.drv} enumerates every PulseAudio source it finds, so the microphone shows
 * up inside the container as a normal Windows recording device with no Wine-side patching needed.
 *
 * Safety / performance notes
 * --------------------------
 * - The FIFO is opened with {@code O_NONBLOCK}: if nothing inside the container is recording, the
 *   pipe fills up and writes fail fast with EAGAIN. We simply drop the chunk. We never block, never
 *   grow an unbounded buffer and never accumulate latency.
 * - Chunks are 20 ms (1920 bytes) which is below {@code PIPE_BUF} (4096), so every write is atomic:
 *   there is no risk of handing PulseAudio a torn frame.
 * - All work happens on one dedicated thread at {@code THREAD_PRIORITY_AUDIO}. It is driven by
 *   {@link AudioRecord#read} which blocks in the kernel, so it costs ~0% CPU and cannot stutter the
 *   game's render/emulation threads.
 * - Capture is fully released while the app is backgrounded (see {@link #pause()}/{@link #resume()}),
 *   so the mic indicator and the power cost only exist while the game is actually in the foreground.
 * - If the {@code RECORD_AUDIO} permission is missing, or {@code module-pipe-source.so} is not
 *   shipped, this component silently does nothing: audio output is never affected.
 */
public class MicrophoneComponent extends EnvironmentComponent {

    /** Must stay in sync with the module arguments written by {@link PulseAudioComponent}. */
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_COUNT = 1;

    private static final int BYTES_PER_FRAME = 2 * CHANNEL_COUNT; // s16le
    private static final int CHUNK_FRAMES = SAMPLE_RATE / 50;     // 20 ms
    private static final int CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME; // 1920 (< PIPE_BUF)

    /** How long we wait for PulseAudio to create the FIFO before giving up. */
    private static final long FIFO_WAIT_TIMEOUT_MS = 10_000;
    private static final long FIFO_POLL_INTERVAL_MS = 100;

    private static final int[] INPUT_PRESETS = {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, // echo cancel + noise suppression when available
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.DEFAULT
    };

    /** Raw microphone (no echo cancellation / noise suppression): the Voice settings switch. */
    private static final int[] RAW_INPUT_PRESETS = {
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.DEFAULT
    };

    /** F_SETPIPE_SZ (not in OsConstants): a small FIFO keeps the delay short and drops old audio when nobody reads. */
    private static final int F_SETPIPE_SZ = 1031;
    private static final int PIPE_SIZE_BYTES = 8192;

    private final File fifoFile;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    private volatile Thread worker;

    // Audio diagnostics (Settings -> Diagnostics -> audio): a bounded log of what the bridge does.
    private static final long LOG_LIMIT_BYTES = 512 * 1024;
    private java.io.FileOutputStream logOut;
    private long logBytes;
    private long statStartMs, reads, bytesRead, writesOk, dropped, readErrors;
    private int peak;
    private int lastErrno;
    // Two raw PCM samples (5 s each, s16le mono 48 kHz): when the game starts reading and ~20 s later.
    private static final int DUMP_BYTES = 5 * SAMPLE_RATE * 2;
    private java.io.FileOutputStream dumpA, dumpB;
    private int dumpedA, dumpedB;

    private void openLog() {
        try {
            Context context = environment != null ? environment.getContext() : null;
            if (context == null || !app.gamenative.ui.screen.wow.WowDiagnostics.audioDebug(context)) return;
            File file = new File(context.getFilesDir(), "diagnostics/wow/mic-bridge.log");
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            logOut = new java.io.FileOutputStream(file, false);
        } catch (Exception ignored) {
            logOut = null;
        }
    }

    private void log(String line) {
        java.io.FileOutputStream out = logOut;
        if (out == null || logBytes > LOG_LIMIT_BYTES) return;
        try {
            byte[] bytes = (System.currentTimeMillis() + " " + line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(bytes);
            logBytes += bytes.length;
        } catch (Exception ignored) { }
    }

    private void closeLog() {
        java.io.FileOutputStream out = logOut;
        logOut = null;
        closeDump();
        if (out == null) return;
        try { out.close(); } catch (Exception ignored) { }
    }

    private void openDumps() {
        if (logOut == null) return;
        try {
            Context context = environment.getContext();
            File dir = new File(context.getFilesDir(), "diagnostics/wow");
            dumpA = new java.io.FileOutputStream(new File(dir, "mic-dump-a.raw"), false);
            dumpB = new java.io.FileOutputStream(new File(dir, "mic-dump-b.raw"), false);
        } catch (Exception e) {
            dumpA = null;
            dumpB = null;
        }
    }

    private void closeDump() {
        for (java.io.FileOutputStream out : new java.io.FileOutputStream[] { dumpA, dumpB }) {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
        dumpA = null;
        dumpB = null;
    }

    private void dump(byte[] buffer, int length) {
        try {
            if (dumpA != null && dumpedA < DUMP_BYTES) {
                dumpA.write(buffer, 0, length);
                dumpedA += length;
            } else if (dumpB != null && writesOk >= 1000 && dumpedB < DUMP_BYTES) {
                dumpB.write(buffer, 0, length);
                dumpedB += length;
            }
        } catch (Exception ignored) { }
    }

    private void logStats(boolean force) {
        if (logOut == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - statStartMs < 2000) return;
        log("stats reads=" + reads + " bytes=" + bytesRead + " written=" + writesOk + " dropped=" + dropped
            + " readErrors=" + readErrors + " peak=" + peak + " lastErrno=" + lastErrno + " paused=" + paused.get());
        statStartMs = now;
        peak = 0;
    }

    public MicrophoneComponent(File fifoFile) {
        this.fifoFile = fifoFile;
    }

    @Override
    public void start() {
        if (fifoFile == null) return;
        if (!running.compareAndSet(false, true)) return;

        paused.set(false);
        openLog();
        log("start fifo=" + fifoFile);

        Thread thread = new Thread(this::captureLoop, "GN-MicrophoneBridge");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Releases the microphone while the activity is in the background. */
    public void pause() {
        log("pause");
        paused.set(true);
    }

    /** Re-acquires the microphone when the activity comes back to the foreground. */
    public void resume() {
        log("resume");
        paused.set(false);
    }

    private boolean hasPermission() {
        Context context = environment != null ? environment.getContext() : null;
        if (context == null) return false;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

        if (!hasPermission()) {
            log("no RECORD_AUDIO permission");
            running.set(false);
            closeLog();
            return;
        }

        long fifoStart = System.currentTimeMillis();
        FileDescriptor fd = awaitAndOpenFifo();
        if (fd == null) {
            log("FIFO not opened after " + (System.currentTimeMillis() - fifoStart) + " ms");
            running.set(false);
            closeLog();
            return;
        }
        log("FIFO opened after " + (System.currentTimeMillis() - fifoStart) + " ms");
        statStartMs = System.currentTimeMillis();
        shrinkPipe(fd);
        openDumps();

        AudioRecord recorder = null;
        final byte[] buffer = new byte[CHUNK_BYTES];

        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                if (paused.get()) {
                    recorder = releaseRecorder(recorder);
                    sleep(150);
                    continue;
                }

                if (recorder == null) {
                    recorder = createRecorder();
                    log(recorder == null ? "createRecorder failed" : "recorder started");
                    if (recorder == null) {
                        // Another app may hold exclusive access; back off instead of spinning.
                        sleep(1000);
                        continue;
                    }
                }

                int read = recorder.read(buffer, 0, CHUNK_BYTES);
                if (logOut != null) {
                    if (read > 0) {
                        reads++;
                        bytesRead += read;
                        for (int i = 0; i + 1 < read; i += 2) {
                            int sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                            if (sample < 0) sample = -sample;
                            if (sample > peak) peak = sample;
                        }
                    } else {
                        readErrors++;
                    }
                    logStats(false);
                }
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE
                        || read == AudioRecord.ERROR_DEAD_OBJECT) {
                        // Typically a device disconnect (USB/BT mic unplugged). Rebuild the stream.
                        recorder = releaseRecorder(recorder);
                        sleep(250);
                    }
                    continue;
                }

                boolean written;
                try {
                    written = writeChunk(fd, buffer, read);
                } catch (ErrnoException e) {
                    lastErrno = e.errno;
                    if (e.errno != OsConstants.EPIPE) throw e;
                    // The PulseAudio server closed its end (it restarted): release the microphone and wait for the new FIFO.
                    log("FIFO reader gone (EPIPE), waiting for the new FIFO");
                    recorder = releaseRecorder(recorder);
                    closeQuietly(fd);
                    fd = reopenFifo();
                    if (fd == null) break;
                    log("FIFO reopened");
                    continue;
                }
                if (!written) {
                    // Nobody inside the container is recording right now, the pipe is full.
                    dropped++;
                } else {
                    writesOk++;
                    dump(buffer, read);
                }
            }
        } catch (Throwable t) {
            log("capture loop ended: " + t);
        } finally {
            logStats(true);
            log("stop");
            closeLog();
            releaseRecorder(recorder);
            closeQuietly(fd);
            running.set(false);
        }
    }

    /**
     * PulseAudio creates the FIFO from {@code module-pipe-source}'s init. Wait for it, then open the
     * write end non-blocking. The module keeps the FIFO open O_RDWR, so opening never blocks and we
     * can never see EOF/EPIPE while the server is alive.
     */
    private FileDescriptor awaitAndOpenFifo() {
        long deadline = System.currentTimeMillis() + FIFO_WAIT_TIMEOUT_MS;

        while (running.get() && System.currentTimeMillis() < deadline) {
            if (fifoFile.exists()) {
                try {
                    return Os.open(fifoFile.getAbsolutePath(),
                        OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.ENXIO && e.errno != OsConstants.ENOENT) {
                        return null;
                    }
                    // Reader side not ready yet, retry.
                }
            }
            if (!sleep(FIFO_POLL_INTERVAL_MS)) return null;
        }

        return null;
    }

    private void shrinkPipe(FileDescriptor fd) {
        try {
            int size = Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_SIZE_BYTES);
            log("FIFO size set to " + size + " bytes");
        } catch (Exception e) {
            log("FIFO size not changed: " + e);
        }
    }

    /** Waits for a new FIFO after the reader is gone; null only when the component is stopping. */
    private FileDescriptor reopenFifo() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            FileDescriptor fd = awaitAndOpenFifo();
            if (fd != null) {
                shrinkPipe(fd);
                return fd;
            }
            if (!sleep(500)) return null;
        }
        return null;
    }

    /** @return true when the whole chunk was handed to PulseAudio, false when it was dropped. */
    private boolean writeChunk(FileDescriptor fd, byte[] buffer, int length) throws ErrnoException {
        int offset = 0;
        while (offset < length) {
            try {
                int written = Os.write(fd, buffer, offset, length - offset);
                if (written <= 0) return false;
                offset += written;
            } catch (ErrnoException e) {
                // On Linux EAGAIN == EWOULDBLOCK: the pipe is full, nothing is recording.
                lastErrno = e.errno;
                if (e.errno == OsConstants.EAGAIN) return false;
                if (e.errno == OsConstants.EINTR) continue;
                throw e;
            } catch (InterruptedIOException e) {
                return false;
            }
        }
        return true;
    }

    private AudioRecord createRecorder() {
        int minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) minBufferSize = CHUNK_BYTES * 4;
        int bufferSize = Math.max(minBufferSize, CHUNK_BYTES * 4);

        boolean raw = false;
        try {
            Context context = environment != null ? environment.getContext() : null;
            raw = context != null && context.getSharedPreferences("wow_voice", Context.MODE_PRIVATE).getBoolean("raw_mic", false);
        } catch (Exception ignored) { }
        log("input mode: " + (raw ? "raw microphone" : "voice communication (echo cancel)"));

        for (int source : (raw ? RAW_INPUT_PRESETS : INPUT_PRESETS)) {
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

                if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder.startRecording();
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        log("AudioRecord source=" + source + " buffer=" + bufferSize);
                        return recorder;
                    }
                    log("AudioRecord source=" + source + " did not start recording");
                } else {
                    log("AudioRecord source=" + source + " not initialized");
                }
            } catch (SecurityException e) {
                releaseRecorder(recorder);
                return null;
            } catch (Exception e) {
                log("AudioRecord source=" + source + " failed: " + e);
            }
            releaseRecorder(recorder);
        }

        return null;
    }

    private AudioRecord releaseRecorder(AudioRecord recorder) {
        if (recorder == null) return null;
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        return null;
    }

    private void closeQuietly(FileDescriptor fd) {
        if (fd == null) return;
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

