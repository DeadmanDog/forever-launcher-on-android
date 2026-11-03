package com.winlator.core;

import android.os.Process;

import app.gamenative.BuildConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public abstract class ProcessHelper {

    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;
    private static final byte SIGKILL = 9;

    /** Set true when a pause sweep is in progress; cleared by resumeAllWineProcesses() so the
     *  background verification thread does not re-stop processes that were already resumed. */
    private static volatile boolean pauseActive = false;
    private static final Object pauseSignalLock = new Object();
    /** Identifies one pause sweep; guarded by [pauseSignalLock]. A sweep that is no longer the current one must send nothing more. */
    private static long pauseGeneration = 0;

    /** The process operations of the pause/resume logic; tests replace them to drive the interleavings deterministically. */
    interface ProcessOps {
        List<String> list();
        void stop(int pid);
        void cont(int pid);
        boolean stopped(int pid);
    }

    static volatile ProcessOps ops = new ProcessOps() {
        @Override public List<String> list() { return listRunningWineProcesses(); }
        @Override public void stop(int pid) { suspendProcess(pid); }
        @Override public void cont(int pid) { resumeProcess(pid); }
        @Override public boolean stopped(int pid) { return isProcessStopped(pid); }
    };

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, SIGKILL);
    }

    public static void killAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            killProcess(Integer.parseInt(process));
        }
    }

    public static void hardKillStaleWineProcesses() throws InterruptedException {

        long deadlineMs = System.currentTimeMillis() + 5000;
        List<String> stalePids = listRunningWineProcesses();

        if (stalePids.isEmpty()) {
            return;
        }

        killAllWineProcesses();

        List<String> remaining;
        do {
            Thread.sleep(100);
            remaining = listRunningWineProcesses();
        } while (!remaining.isEmpty() && System.currentTimeMillis() < deadlineMs);

        if (!remaining.isEmpty()) {
            throw new IllegalStateException(
                "Wine processes still present after hard-kill attempt: " + String.join(", ", remaining));
        }

    }

    public static void pauseAllWineProcesses() {
        final ProcessOps process = ops;
        final long generation;
        synchronized (pauseSignalLock) {
            pauseActive = true;
            generation = ++pauseGeneration;
        }
        final List<String> pids = process.list();
        final Set<String> pidSet = new HashSet<>(pids); // O(1) lookup in second sweep

        // Send SIGSTOP immediately on the calling thread so processes stop as fast as possible. Every signal is sent under the
        // lock and only while this sweep is still the current one: a resume (or a newer pause) that came in while the process
        // list was being read has already been answered, and a late SIGSTOP from here would leave the game stopped for good.
        for (String pid : pids) {
            synchronized (pauseSignalLock) {
                if (!pauseActive || pauseGeneration != generation) return;
                process.stop(Integer.parseInt(pid));
            }
        }

        // Verify + retry on a background thread — never block the caller (may be the main thread).
        //noinspection resource — shutdown() is called after execute(); try-with-resources not available on Android Java 8
        ExecutorService verifier = Executors.newSingleThreadExecutor();
        verifier.execute(() -> {
            // Give the kernel 50 ms to deliver SIGSTOP before reading /proc status.
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            // Retry any process whose signal was silently dropped (e.g. SELinux deny).
            for (String pid : pids) {
                synchronized (pauseSignalLock) {
                    if (!pauseActive || pauseGeneration != generation) return;
                    int parsedPid = Integer.parseInt(pid);
                    if (!process.stopped(parsedPid)) {
                        process.stop(parsedPid);
                    }
                }
            }

            // Second sweep: stop any Wine processes that spawned after the first scan.
            for (String pid : process.list()) {
                synchronized (pauseSignalLock) {
                    if (!pauseActive || pauseGeneration != generation) return;
                    if (!pidSet.contains(pid)) {
                        process.stop(Integer.parseInt(pid));
                    }
                }
            }
        });
        verifier.shutdown(); // no new tasks; existing task runs to completion
    }

    private static boolean isProcessStopped(int pid) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream("/proc/" + pid + "/status")))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("State:")) {
                    // Extract the single state character after "State:" and optional whitespace.
                    // 'T' = stopped (SIGSTOP), 't' = tracing stop — both mean suspended.
                    String trimmed = line.substring("State:".length()).trim();
                    char state = trimmed.isEmpty() ? 0 : trimmed.charAt(0);
                    return state == 'T' || state == 't';
                }
            }
        } catch (FileNotFoundException ignored) {
            // Process already gone — counts as stopped.
        } catch (IOException ignored) {
            // Unreadable status (unlikely for same-UID process) — assume not stopped so retry fires.
            return false;
        }
        return true;
    }

    public static void resumeAllWineProcesses() {
        // Clear the flag and send SIGCONT atomically with the pause-signal lock so the
        // background verifier thread cannot re-stop a process after we send SIGCONT.
        final ProcessOps process = ops;
        synchronized (pauseSignalLock) {
            pauseActive = false;
            pauseGeneration++;   // any pause sweep still in flight stands down
            for (String pid : process.list()) {
                process.cont(Integer.parseInt(pid));
            }
        }
        // A SIGCONT that was dropped would leave a Wine process stopped for good, which looks like the
        // game freezing after the menu closes. Look once more shortly afterwards and continue any
        // process that is still stopped (unless a new pause has started in the meantime).
        Thread verifier = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { return; }
            synchronized (pauseSignalLock) {
                if (pauseActive) return;
                for (String pid : process.list()) {
                    int parsedPid = Integer.parseInt(pid);
                    if (process.stopped(parsedPid)) process.cont(parsedPid);
                }
            }
        }, "WineResumeVerifier");
        verifier.setDaemon(true);
        verifier.start();
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    // Execute the command and capture its output.
    // Use ProcessBuilder to merge stderr into stdout
    // Deadlock-safe strategy:
    //   includeStderr=true  - redirectErrorStream(true): stderr merged into stdout at the OS
    //                         level, single pipe, zero deadlock risk.
    //   includeStderr=false - streams are kept separate; a daemon thread drains and discards
    //                         stderr concurrently so the 64 KB pipe buffer never fills while
    //                         the main thread reads stdout. This keeps stdout clean for callers
    //                         that parse it.
    public static String execWithOutput(String command, String[] envp, File workingDir, boolean includeStderr) {
        return execWithOutput(command, envp, workingDir, includeStderr, -1);
    }

    public static String execWithOutput(String command, String[] envp, File workingDir, boolean includeStderr, int timeoutSeconds) {
        StringBuilder output = new StringBuilder();
        final StringBuilder stdoutBuf = new StringBuilder();
        final StringBuilder stderrBuf = new StringBuilder();
        Thread stdoutDrainer = null;
        Thread stderrDrainer = null;
        try {
            command = "/system/bin/linker64 " + command;

            if (BuildConfig.DEBUG) {
            }

            ProcessBuilder pb = new ProcessBuilder(splitCommand(command));
            Map<String, String> env = pb.environment();
            env.clear();
            if (envp != null) {
                for (String kv : envp) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            if (workingDir != null) pb.directory(workingDir);

            java.lang.Process process = pb.start();

            final InputStream stdoutStream = process.getInputStream();
            stdoutDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(stdoutStream))) {
                    String l;
                    while ((l = r.readLine()) != null) stdoutBuf.append(l).append("\n");
                } catch (IOException ignored) {}
            }, "stdout-drainer");
            stdoutDrainer.setDaemon(true);
            stdoutDrainer.start();

            final InputStream stderrStream = process.getErrorStream();
            stderrDrainer = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(stderrStream))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        if (includeStderr) stderrBuf.append(l).append("\n");
                    }
                } catch (IOException ignored) {}
            }, "stderr-drainer");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            if (timeoutSeconds > 0) {
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    output.append("Error: Process timeout after ").append(timeoutSeconds).append(" seconds");
                }
            } else {
                process.waitFor();
            }

            try { stdoutStream.close(); } catch (IOException ignored) {}
            try { stderrStream.close(); } catch (IOException ignored) {}

            stdoutDrainer.join(5_000);
            if (stdoutDrainer.isAlive()) {
                stdoutDrainer.interrupt();
            }

            stderrDrainer.join(5_000);
            if (stderrDrainer.isAlive()) {
                stderrDrainer.interrupt();
            }

            output.append(stdoutBuf);
            if (includeStderr) output.append(stderrBuf);
        } catch (Exception e) {
            output.append("Error: ").append(e.getMessage());
        }

        return output.toString().trim();
    }

    public static class ProcessInfo {
        public final int pid;
        public final int ppid;
        public final String name;
        public final long rssBytes;

        public ProcessInfo(int pid, int ppid, String name) {
            this(pid, ppid, name, 0L);
        }

        public ProcessInfo(int pid, int ppid, String name, long rssBytes) {
            this.pid = pid;
            this.ppid = ppid;
            this.name = name;
            this.rssBytes = rssBytes;
        }
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        return exec(command, envp, workingDir, terminationCallback, null);
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback, File diagnosticDirectory) {
        int pid = -1;
        java.lang.Process process = null;
        try {
            command = "/system/bin/linker64 " + command;

            if (BuildConfig.DEBUG) {
            }

                process = Runtime.getRuntime().exec(splitCommand(command), envp, workingDir);

            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);

            RuntimeStartupLog startup = null;
            if (diagnosticDirectory != null) {
                try { startup = new RuntimeStartupLog(new File(diagnosticDirectory, "startup.log")); }
                catch (IOException ignored) { }
            }
            drainOutput(process.getInputStream(), diagnosticDirectory == null ? null : new File(diagnosticDirectory, "stdout.log"), startup);
            drainOutput(process.getErrorStream(), diagnosticDirectory == null ? null : new File(diagnosticDirectory, "stderr.log"), startup);

            if (terminationCallback != null) createWaitForThread(process, status -> {
                if (diagnosticDirectory != null) {
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(diagnosticDirectory, "exit.txt"))) {
                        out.write(("Exit status: " + status + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } catch (IOException ignored) { }
                }
                terminationCallback.call(status);
            });
        }
        catch (Exception e) {
            if (diagnosticDirectory != null) {
                try (RuntimeLog log = new RuntimeLog(new File(diagnosticDirectory, "launch-error.txt"), 4096)) {
                    byte[] message = (e.getClass().getSimpleName() + ": " + e.getMessage() + "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    log.append(message, message.length);
                } catch (IOException ignored) { }
            }
            if (process != null) process.destroyForcibly();
            if (terminationCallback != null) terminationCallback.call(-1);
        }
        return pid;
    }

    public static List<ProcessInfo> listSubProcesses() {
        List<ProcessInfo> processes = new ArrayList<>();
        String myUser = null;

        try {
            java.lang.Process idProcess = Runtime.getRuntime().exec("id");
            try (
                InputStreamReader isr = new InputStreamReader(idProcess.getInputStream());
                BufferedReader idReader = new BufferedReader(isr);
            ) {
                String idOutput = idReader.readLine();
                if (idOutput != null) {
                    int startIndex = idOutput.indexOf('(');
                    int endIndex = idOutput.indexOf(')');
                    if (startIndex != -1 && endIndex != -1) {
                        myUser = idOutput.substring(startIndex + 1, endIndex);
                    }
                }
            }
        } catch (IOException e) {
            return processes;
        }

        if (myUser == null) return processes;

        try {
            java.lang.Process process = Runtime.getRuntime().exec("ps -A -o USER,PID,PPID,VSZ,RSS,WCHAN,ADDR,S,NAME");
            try (
                InputStreamReader isr = new InputStreamReader(process.getInputStream());
                BufferedReader reader = new BufferedReader(isr);
            ) {
                String line;
                reader.readLine();

                while ((line = reader.readLine()) != null) {
                    String[] parts = line.trim().split("\\s+", 9);
                    if (parts.length >= 9) {
                        String user = parts[0];
                        int pid;
                        int ppid;
                        long rssKb;
                        try {
                            pid = Integer.parseInt(parts[1]);
                            ppid = Integer.parseInt(parts[2]);
                            rssKb = Long.parseLong(parts[4]);
                        } catch (NumberFormatException malformedLine) {
                            continue; // one odd ps line must not abort the whole process listing (and the caller's cleanup)
                        }
                        String processName = parts[8];

                        if (user.equals(myUser) && pid != Process.myPid()) {
                            ProcessInfo info = new ProcessInfo(pid, ppid, processName, rssKb * 1024L);
                            processes.add(info);
                        }
                    }
                }
            }
        } catch (IOException e) {
        }

        return processes;
    }

    // Drain child pipes without decoding, formatting, callback locks or log writes.
    // Leaving pipes unread can block Wine when their buffers fill.
    private static void drainOutput(final InputStream inputStream) {
        drainOutput(inputStream, null);
    }

    private static void drainOutput(final InputStream inputStream, final File logFile) {
        drainOutput(inputStream, logFile, null);
    }

    private static void drainOutput(final InputStream inputStream, final File logFile, final RuntimeStartupLog startup) {
        OneShotTasks.execute(() -> {
            RuntimeLog log = null;
            try {
                if (logFile != null) {
                    try { log = new RuntimeLog(logFile, RuntimeLog.MAX_BYTES, true); }
                    catch (IOException ignored) { /* Keep draining if storage is unavailable. */ }
                }
                try (InputStream stream = inputStream) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (startup != null) startup.append(buffer, count);
                        if (log != null) log.append(buffer, count);
                    }
                }
            } catch (IOException ignored) { }
            finally { if (log != null) log.close(); if (startup != null) startup.readerFinished(); }
        });
    }

    private static void createWaitForThread(java.lang.Process process, final Callback<Integer> terminationCallback) {
        OneShotTasks.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int status = process.waitFor();
                    terminationCallback.call(status);
                }
                catch (InterruptedException e) {
                }
            }
        });
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);
            char quoteChar = '"';

            if (startedQuotes) {
                if (currChar == quoteChar) {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += quoteChar;
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"' || currChar == '\'') {
                if (currChar == '\'') quoteChar = '\'';
                startedQuotes = true;
                value += quoteChar;
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static ArrayList<String> listRunningWineProcesses(){
        File proc = new File("/proc");
        String[] filters = {"wine", "exe"};
        String[] allPids;
        ArrayList<String> filteredPids = new ArrayList<String>();
        List<String> filterList = Arrays.asList(filters);
        allPids = proc.list(new FilenameFilter(){
            public boolean accept(File proc, String filename){
                return new File(proc, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });

        if (allPids == null) return filteredPids; // /proc unreadable
        String procFile = "/cmdline";
        for (int index = 0; index < allPids.length; index++){
            String data = "";
            try (
                FileInputStream fr = new FileInputStream(proc + "/" + allPids[index] + procFile);
                BufferedReader br = new BufferedReader(new InputStreamReader(fr));
            ) {
                String line = br.readLine();
                if (line != null) data = line;
            }
            catch (IOException e) {}
            for (String filter : filterList) {
                if (data.contains(filter)) {
                    filteredPids.add(allPids[index]); // once per process, not once per matching filter
                    break;
                }
            }
        }
        return filteredPids;
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            int index;
            try { index = Integer.parseInt(value.trim()); }
            catch (NumberFormatException malformed) { continue; }
            if (index >= 0 && index < 31) affinityMask |= 1 << index;
        }
        return affinityMask;
    }
}
