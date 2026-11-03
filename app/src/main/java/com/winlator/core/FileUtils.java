package com.winlator.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Stack;
import java.util.UUID;

public abstract class FileUtils {
    public static byte[] read(Context context, String assetFile) {
        try (InputStream inStream = context.getAssets().open(assetFile)) {
            return StreamUtils.copyToByteArray(inStream);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static byte[] read(File file) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(file))) {
            return StreamUtils.copyToByteArray(inStream);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static String readString(Context context, String assetFile) {
        return new String(read(context, assetFile), StandardCharsets.UTF_8);
    }

    public static String readString(File file) {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    public static boolean write(File file, byte[] data) {
        if (file.exists()) file.delete();
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data, 0, data.length);
            return true;
        }
        catch (IOException e) {
        }
        return false;
    }

    public static boolean writeString(File file, String data) {
        // Ensure parent directory exists
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Create temporary file in same directory
        File tempFile = createTempFile(parentDir != null ? parentDir : file.getAbsoluteFile().getParentFile(),
                                        getBasename(file.getPath()));

        boolean success = false;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(tempFile))) {
            bw.write(data);
            bw.flush();
            success = true;
        }
        catch (IOException e) {
            // Clean up temp file on failure
            tempFile.delete();
            return false;
        }

        // Atomically replace original file with temp file
        if (success) {
            try {
                // Atomic move - replaces target if it exists
                Files.move(tempFile.toPath(), file.toPath(),
                          StandardCopyOption.ATOMIC_MOVE,
                          StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            catch (IOException e) {
                tempFile.delete();
                return false;
            }
        }

        return false;
    }

    public static void symlink(File linkTarget, File linkFile) {
        symlink(linkTarget.getAbsolutePath(), linkFile.getAbsolutePath());
    }

    public static void symlink(String linkTarget, String linkFile) {
        try {
            (new File(linkFile)).delete();
            Os.symlink(linkTarget, linkFile);
        }
        catch (ErrnoException e) {
        }
    }

    public static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    public static boolean isSameFile(File a, File b) {
        try {
            return Files.isSameFile(a.toPath(), b.toPath());
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean delete(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            if (!isSymlink(targetFile)) if (!clear(targetFile)) return false;
        }
        return targetFile.delete();
    }

    public static boolean clear(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            File[] files = targetFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!delete(file)) return false;
                }
            }
        }
        return true;
    }

    public static boolean isEmpty(File targetFile) {
        if (targetFile == null) return true;
        if (targetFile.isDirectory()) {
            String[] files = targetFile.list();
            return files == null || files.length == 0;
        }
        else return targetFile.length() == 0;
    }

    public static boolean copy(File srcFile, File dstFile) {
        return copy(srcFile, dstFile, null);
    }

    public static boolean copy(File srcFile, File dstFile, Callback<File> callback) {
        if (isSymlink(srcFile)) return true;
        if (srcFile.isDirectory()) {
            if (!dstFile.exists() && !dstFile.mkdirs()) return false;
            if (callback != null) callback.call(dstFile);

            String[] filenames = srcFile.list();
            if (filenames != null) {
                for (String filename : filenames) {
                    if (!copy(new File(srcFile, filename), new File(dstFile, filename), callback)) {
                        return false;
                    }
                }
            }
        }
        else {
            File parent = dstFile.getParentFile();
            if (!srcFile.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) return false;

            try {
                if (dstFile.exists() && !dstFile.isDirectory()) dstFile.delete();
                try (FileInputStream input = new FileInputStream(srcFile);
                     FileOutputStream output = new FileOutputStream(dstFile);
                     FileChannel inChannel = input.getChannel();
                     FileChannel outChannel = output.getChannel()) {
                    long position = 0, size = inChannel.size();
                    while (position < size) {
                        long count = inChannel.transferTo(position, size - position, outChannel);
                        if (count <= 0) throw new IOException("Incomplete file copy: " + srcFile);
                        position += count;
                    }
                }

                if (callback != null) callback.call(dstFile);
                return dstFile.exists();
            }
            catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    public static void copy(Context context, String assetFile, File dstFile) {
        if (isDirectory(context, assetFile)) {
            if (!dstFile.isDirectory()) dstFile.mkdirs();
            try {
                String[] filenames = context.getAssets().list(assetFile);
                for (String filename : filenames) {
                    String relativePath = StringUtils.addEndSlash(assetFile)+filename;
                    if (isDirectory(context, relativePath)) {
                        copy(context, relativePath, new File(dstFile, filename));
                    }
                    else copy(context, relativePath, dstFile);
                }
            }
            catch (IOException e) {
            }
        }
        else {
            if (dstFile.isDirectory()) dstFile = new File(dstFile, FileUtils.getName(assetFile));
            File parent = dstFile.getParentFile();
            if (!parent.isDirectory()) parent.mkdirs();
            if (dstFile.exists()) dstFile.delete();
            try (InputStream inStream = context.getAssets().open(assetFile);
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(dstFile), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, outStream);
            }
            catch (IOException e) {
            }
        }
    }

    /**
     * Reads and returns the first line of {@code file} as UTF-8 text, or {@code null} if the file
     * is empty or cannot be read. The caller is responsible for any trimming or blank checks.
     * @param file The file to read.
     * @return The first line, or {@code null} if the file is empty or unreadable.
     */
    public static String readFirstLine(File file) {
        if (file == null) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            return new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8)).readLine();
        }
        catch (IOException e) {
            return null;
        }
    }

    public static String getName(String path) {
        if (path == null) return "";
        path = StringUtils.removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(index + 1);
    }

    public static String getBasename(String path) {
        return getName(path).replaceFirst("\\.[^\\.]+$", "");
    }

    public static String getDirname(String path) {
        if (path == null) return "";
        path = StringUtils.removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(0, index);
    }

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        }
        catch (ErrnoException e) {
        }
    }

    public static File createTempFile(File parent, String prefix) {
        File tempFile = null;
        boolean exists = true;
        while (exists) {
            tempFile = new File(parent, prefix+"-"+ UUID.randomUUID().toString().replace("-", "")+".tmp");
            exists = tempFile.exists();
        }
        return tempFile;
    }

    public static boolean contentEquals(File origin, File target) {
        if (origin.length() != target.length()) return false;

        try (InputStream inStream1 = new BufferedInputStream(new FileInputStream(origin), 65536);
             InputStream inStream2 = new BufferedInputStream(new FileInputStream(target), 65536)) {
            byte[] buf1 = new byte[65536];
            byte[] buf2 = new byte[65536];
            while (true) {
                int n1 = readFully(inStream1, buf1);
                int n2 = readFully(inStream2, buf2);
                if (n1 != n2) return false;
                if (n1 <= 0) return true;
                for (int i = 0; i < n1; i++) if (buf1[i] != buf2[i]) return false;
            }
        }
        catch (IOException e) {
            return false;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void getSize(File file, Callback<Long> callback) {
        if (file == null) return;
        if (file.isFile()) {
            callback.call(file.length());
            return;
        }

        Stack<File> stack = new Stack<>();
        stack.push(file);

        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] files = current.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isDirectory()) {
                    stack.push(f);
                }
                else {
                    long length = f.length();
                    if (length > 0) callback.call(length);
                }
            }
        }
    }

    public static long getSize(AssetManager assetManager, String assetFile) {
        try (InputStream inStream = assetManager.open(assetFile)) {
            return inStream.available();
        }
        catch (IOException e) {
            return 0;
        }
    }

    public static long getSize(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();     // fast path

        // directory walk (re-uses your callback helper)
        final long[] total = {0};
        getSize(file, size -> total[0] += size);
        return total[0];
    }

    public static boolean isDirectory(Context context, String assetFile) {
        try {
            String[] files = context.getAssets().list(assetFile);
            return files != null && files.length > 0;
        }
        catch (IOException e) {
            return false;
        }
    }

    public static int readInt(String path) {
        int result = 0;
        try {
            try (RandomAccessFile reader = new RandomAccessFile(path, "r")) {
                String line = reader.readLine();
                result = !line.isEmpty() ? Integer.parseInt(line) : 0;
            }
        }
        catch (Exception e) {
        }
        return result;
    }

    public static String readSymlink(File file) {
        try {
            return Files.readSymbolicLink(file.toPath()).toString();
        }
        catch (IOException e) {
            return "";
        }
    }

}
