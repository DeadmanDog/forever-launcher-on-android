package com.winlator.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD}

    private static void addFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, tar);
            }
            tar.closeArchiveEntry();
        }
        catch (Exception e) {
        }
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        catch (Exception e) {
        }
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (FileUtils.isSymlink(file)) {
                addLinkFile(tar, file, basePath+file.getName());
            }
            else if (file.isDirectory()) {
                String entryName = basePath+file.getName() + "/";
                tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                tar.closeArchiveEntry();
                addDirectory(tar, file, entryName);
            }
            else addFile(tar, file, basePath+file.getName());
        }
    }

    public static void compress(Type type, File file, File destination) {
        compress(type, file, destination, 3);
    }

    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level);
    }

    public static void compress(Type type, File[] files, File destination, int level) {
        try (OutputStream outStream = getCompressorOutputStream(type, destination, level);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                }
                else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath);
                }
                else addFile(tar, file, file.getName());
            }
            tar.finish();
        }
        catch (IOException e) {
        }
    }

    public static boolean extract(Type type, AssetManager assetManager, String assetFile, File destination) {
        return extract(type, assetManager, assetFile, destination, null);
    }

    public static boolean extract(Type type, AssetManager assetManager, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, assetManager.open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try {
            if (source.toString().startsWith("/")) {
                return extract(type, new FileInputStream(source.toString()), destination, onExtractFileListener);
            } else {
            return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
            }
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    /** Strict local-component extraction using the same TAR engine, before any archive writes. */
    public static boolean extractValidated(Type type, Context context, Uri source, File destination) {
        return extractValidated(type, context, source, destination, false);
    }

    public static boolean extractValidated(Type type, Context context, Uri source, File destination, boolean allowInternalLinks) {
        return extractValidated(type, context, source, destination, allowInternalLinks, false);
    }

    /** Legacy prefix packs carry device-specific DOS drive aliases; omit those from preparation. */
    public static boolean extractValidatedPrefix(Type type, Context context, Uri source, File destination) {
        return extractValidated(type, context, source, destination, true, true);
    }

    private static boolean extractValidated(Type type, Context context, Uri source, File destination, boolean allowInternalLinks, boolean sanitizeDrives) {
        if (source == null) return false;
        try (InputStream stream = source.toString().startsWith("/")
                    ? new FileInputStream(source.toString())
                    : context.getContentResolver().openInputStream(source)) {
            return extract(type, stream, destination, null, true, allowInternalLinks, sanitizeDrives);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, InputStream source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        return extract(type, source, destination, onExtractFileListener, false);
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener, boolean validatePaths) {
        return extract(type, source, destination, onExtractFileListener, validatePaths, false);
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener, boolean validatePaths, boolean allowInternalLinks) {
        return extract(type, source, destination, onExtractFileListener, validatePaths, allowInternalLinks, false);
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener, boolean validatePaths, boolean allowInternalLinks, boolean sanitizeDrives) {
        if (source == null) return false;
        try (InputStream ownedSource = source;
             InputStream inStream = getCompressorInputStream(type, ownedSource);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            java.util.Set<String> writtenPaths = new java.util.HashSet<>();
            // Every link this extraction made: a later entry can replace a link that an earlier link's check went through, so they
            // are all checked again once the whole tree is in place (see linksStayInside).
            java.util.List<File> createdLinks = new java.util.ArrayList<>();
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                // Skip macOS metadata files (._{filename}) and __MACOSX directories
                String entryName = entry.getName();
                String fileName = new File(entryName).getName();
                if(isMacPrefixFile(entryName, fileName))
                    continue;

                File file = new File(destination, entryName);

                if (sanitizeDrives && entry.isSymbolicLink()
                        && entryName.matches("(?:\\./)?\\.wine/dosdevices/[a-zA-Z]:")
                        && new File(entry.getLinkName()).isAbsolute()) continue;

                if (validatePaths) {
                    java.nio.file.Path root = destination.getCanonicalFile().toPath();
                    java.nio.file.Path target = file.getCanonicalFile().toPath();
                    if (!entry.isDirectory() && !writtenPaths.add(target.toString())) return false;
                    // DXVK packages contain regular files/directories, never archive links/devices.
                    if (new File(entryName).isAbsolute() || !target.startsWith(root)
                            || (entry.isSymbolicLink() && !allowInternalLinks) || entry.isLink()
                            || (!entry.isDirectory() && !entry.isFile() && !entry.isSymbolicLink())) return false;
                    if (entry.isSymbolicLink()) {
                        boolean wineRootLink = allowInternalLinks && entryName.endsWith("/dosdevices/z:") && "/".equals(entry.getLinkName());
                        if (!wineRootLink && (new File(entry.getLinkName()).isAbsolute()
                                || !new File(file.getParentFile(), entry.getLinkName()).getCanonicalFile().toPath().startsWith(root))) return false;
                        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) return false;
                    }
                }

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isSymbolicLink()) {
                        if (validatePaths) {
                            java.nio.file.Files.deleteIfExists(file.toPath());
                            java.nio.file.Files.createSymbolicLink(file.toPath(), java.nio.file.Paths.get(entry.getLinkName()));
                            createdLinks.add(file);
                        } else FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    }
                    else {
                        if (validatePaths && !file.getParentFile().isDirectory()
                                && !file.getParentFile().mkdirs()) return false;
                        if (file.exists() && !file.isDirectory()) file.delete();
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                if (!validatePaths || !entry.isSymbolicLink()) FileUtils.chmod(file, 0771);
            }
            if (validatePaths && !linksStayInside(destination, createdLinks)) return false;
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks every extracted link again against the finished tree: each must still be a link whose target, resolved through the
     * links as they are now, stays inside [destination] (the prefix's "dosdevices/z:" link to "/" excepted). A link that fails is
     * removed and the extraction counts as failed.
     */
    static boolean linksStayInside(File destination, java.util.List<File> links) throws IOException {
        java.nio.file.Path root = destination.getCanonicalFile().toPath();
        boolean ok = true;
        for (File link : links) {
            java.nio.file.Path path = link.toPath();
            if (!java.nio.file.Files.isSymbolicLink(path)) continue; // replaced by a later file or directory entry: checked as that
            String target = java.nio.file.Files.readSymbolicLink(path).toString();
            File parent = link.getAbsoluteFile().getParentFile();
            boolean wineRootLink = "/".equals(target) && link.getPath().replace('\\', '/').endsWith("/dosdevices/z:");
            if (wineRootLink) continue;
            boolean inside = parent != null && !new File(target).isAbsolute()
                    && parent.getCanonicalFile().toPath().startsWith(root)
                    && new File(parent.getCanonicalFile(), target).getCanonicalFile().toPath().startsWith(root);
            if (!inside) {
                java.nio.file.Files.deleteIfExists(path);
                ok = false;
            }
        }
        return ok;
    }

    //! Detects Mac Prefixes - Some builds are done with Macs and it trips up extraction algorithm
    private static boolean isMacPrefixFile(String entryName, String fileName) {
        return fileName.startsWith("._") || entryName.contains("__MACOSX/");
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return null;
    }

    private static OutputStream getCompressorOutputStream(Type type, File destination, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        return null;
    }
}
