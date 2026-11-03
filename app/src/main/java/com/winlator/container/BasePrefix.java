package com.winlator.container;

import android.content.Context;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public final class BasePrefix {
    private static final int BUILD_VERSION = 3;
    private static final int[] LEGACY_BUILD_VERSIONS = {1, 2};
    private static final java.util.List<String> BASE_DRIVES = java.util.Arrays.asList("c:", "z:");
    public static final String DIR_NAME = "base_prefix";
    static final String COMPLETE_MARKER = ".complete";
    static final String BUILDING_MARKER = ".building";
    private static final String STAGING_DIR = ".staging";
    private static final String OLD_DIR = ".wine.old";
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private BasePrefix() {}

    private static Object lockFor(String key) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    public static File getBaseDir(Context context, ContentsManager contentsManager, String wineVersion) {
        if (WineInfo.isMainWineVersion(wineVersion)) return null;
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) return null;
        return new File(wineInfo.path, DIR_NAME);
    }

    public static File ensure(Context context, ContentsManager contentsManager, String wineVersion) {

        File baseDir = getBaseDir(context, contentsManager, wineVersion);
        if (baseDir == null) {
            return null;
        }
        int imgVersion = ImageFs.find(context).getVersion();
        String identity = BUILD_VERSION + ":" + wineVersion + ":" + imgVersion;
        File wineDir = new File(baseDir, ".wine");
        synchronized (lockFor(baseDir.getAbsolutePath())) {
            if (isComplete(baseDir, identity)) { fallbackNotice = null; return wineDir; }
            for (int legacyVersion : LEGACY_BUILD_VERSIONS) {
                String legacyIdentity = legacyVersion + ":" + wineVersion + ":" + imgVersion;
                if (upgradeLegacyBase(baseDir, legacyIdentity, identity)) {
                    fallbackNotice = null;
                    return wineDir;
                }
            }
            ContainerOverlay.splash("Preparing " + wineVersion + " base...");
            if (build(context, contentsManager, wineVersion, baseDir, identity)) { fallbackNotice = null; return wineDir; }
            // The rebuild failed. The previous complete base still works, so it is handed out instead of failing the launch,
            // but never silently: the player and the diagnostics are told that an older base is in use.
            String previous = previousBaseNotice(baseDir, identity);
            if (previous != null) {
                announceFallback(context, wineVersion, previous);
                return wineDir;
            }
            return null;
        }

    }

    /** What the last launch had to do because the current base could not be built (null: nothing). Listed in the diagnostics. */
    private static volatile String fallbackNotice;

    public static String fallbackNotice() {
        return fallbackNotice;
    }

    /**
     * Describes the previous base that is still in place, or returns null when there is none (no base directory or no marker of a
     * complete build), in which case there is nothing to fall back to.
     */
    static String previousBaseNotice(File baseDir, String wanted) {
        File marker = new File(baseDir, COMPLETE_MARKER);
        if (!new File(baseDir, ".wine").isDirectory() || !marker.isFile()) return null;
        String found = FileUtils.readString(marker);
        return "wanted " + wanted + ", found " + (found == null || found.trim().isEmpty() ? "unknown" : found.trim());
    }

    private static void announceFallback(Context context, String wineVersion, String detail) {
        fallbackNotice = wineVersion + " (" + detail + ")";
        android.util.Log.w("BasePrefix", "Base prefix update failed, previous base in use: " + fallbackNotice);
        boolean hungarian = false;
        try {
            hungarian = app.gamenative.ui.screen.wow.WowUiLanguage.INSTANCE.isHungarian(
                    app.gamenative.ui.screen.wow.WowUiLanguage.INSTANCE.saved(context), java.util.Locale.getDefault().getLanguage());
        } catch (Throwable ignored) {
        }
        final String text = hungarian
                ? "Az alap prefix frissítése nem sikerült, a korábbi alap van használatban."
                : "Base prefix update failed; the previous base is in use.";
        ContainerOverlay.splash(text);
        // The project shows messages through its snackbar (android.widget.Toast is deliberately poisoned).
        try {
            app.gamenative.ui.util.SnackbarManager.INSTANCE.show(text);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Puts the freshly built [stagingWine] in place of the current base and marks it complete. The previous base (and its own marker)
     * is only deleted once the new marker is safely written: if anything fails, the previous base is restored untouched, so a
     * failed rebuild can never cost the working one.
     */
    static boolean swapIn(File baseDir, File staging, File stagingWine, String identity) {
        File wineDir = new File(baseDir, ".wine");
        File oldDir = new File(baseDir, OLD_DIR);
        FileUtils.delete(oldDir);
        if (wineDir.exists() && !wineDir.renameTo(oldDir)) return false;
        if (!stagingWine.renameTo(wineDir)) {
            if (oldDir.exists()) oldDir.renameTo(wineDir);
            return false;
        }
        if (!FileUtils.writeString(new File(baseDir, COMPLETE_MARKER), identity)) {
            // The new base cannot be marked complete: it must not stay in place. The old base still carries its own, untouched marker.
            FileUtils.delete(wineDir);
            if (oldDir.exists()) oldDir.renameTo(wineDir);
            return false;
        }
        FileUtils.delete(oldDir);
        return true;
    }

    static boolean isComplete(File baseDir, String identity) {
        File marker = new File(baseDir, COMPLETE_MARKER);
        if (!marker.isFile() || new File(baseDir, BUILDING_MARKER).exists()) return false;
        if (!new File(baseDir, ".wine").isDirectory()) return false;
        String content = FileUtils.readString(marker);
        return content != null && content.trim().equals(identity);
    }

    static boolean upgradeLegacyBase(File baseDir, String legacyIdentity, String identity) {
        if (!isComplete(baseDir, legacyIdentity)) return false;
        if (!normalize(new File(baseDir, ".wine"))) return false;
        return FileUtils.writeString(new File(baseDir, COMPLETE_MARKER), identity);
    }

    /**
     * Whether a staged prefix may become the base: the shared Windows files were extracted and the drive tidy-up worked. The tidy-up is
     * idempotent, so it gets one more try before it counts as failed. (Both results used to be dropped: a failed step still produced
     * a "complete" base and deleted the previous good one.)
     */
    static boolean stagingReady(boolean commonExtracted, java.util.function.BooleanSupplier normalize) {
        return commonExtracted && (normalize.getAsBoolean() || normalize.getAsBoolean());
    }

    public static boolean normalize(File wineDir) {
        return trimDosdevices(wineDir);
    }

    static boolean trimDosdevices(File wineDir) {
        File dosdevices = new File(wineDir, ContainerFiles.DOSDEVICES);
        File[] entries = dosdevices.listFiles();
        if (entries == null) return true;
        boolean ok = true;
        for (File entry : entries) {
            if (BASE_DRIVES.contains(entry.getName().toLowerCase())) continue;
            ok &= ContainerFiles.deleteRecursively(entry);
        }
        return ok & ensureRootDrive(wineDir);
    }

    public static boolean ensureRootDrive(File wineDir) {
        java.nio.file.Path link = new File(new File(wineDir, ContainerFiles.DOSDEVICES), "z:").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(link) && "/".equals(java.nio.file.Files.readSymbolicLink(link).toString())) return true;
            java.nio.file.Files.deleteIfExists(link);
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get("/"));
            return true;
        }
        catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean build(Context context, ContentsManager contentsManager, String wineVersion, File baseDir, String identity) {
        File staging = new File(baseDir, STAGING_DIR);
        File building = new File(baseDir, BUILDING_MARKER);
        File wineDir = new File(baseDir, ".wine");
        File oldDir = new File(baseDir, OLD_DIR);
        try {
            if (!baseDir.getParentFile().isDirectory()) {
                return false;
            }
            if (!baseDir.isDirectory() && !baseDir.mkdir()) return false;
            if (!FileUtils.writeString(building, identity)) {
                return false;
            }
            FileUtils.delete(staging);
            if (!staging.mkdirs()) return false;

            File stagingWine = new File(staging, ".wine");
            ContainerManager containerManager = new ContainerManager(context);
            if (!containerManager.extractContainerPatternFile(wineVersion, contentsManager, staging, null)) {
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }

            File commonRoot = new File(staging, "home/" + ImageFs.USER + "/.wine");
            String commonPrefix = commonRoot.getAbsolutePath();
            OnExtractFileListener commonRemap = (file, size) -> {
                String path = file.getAbsolutePath();
                if (!path.equals(commonPrefix) && !path.startsWith(commonPrefix + "/")) return null;
                return new File(stagingWine, path.substring(commonPrefix.length()));
            };
            boolean commonExtracted = containerManager.extractContainerPatternCommon(staging, commonRemap);
            FileUtils.delete(new File(staging, "home"));

            if (!new File(stagingWine, "drive_c/windows").isDirectory()) {
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }
            if (!stagingReady(commonExtracted, () -> normalize(stagingWine))) {
                // The shared files or the drive tidy-up failed: this is no complete base. The previous one stays where it is (the caller
                // hands it out with a notice), and the next launch builds again instead of trusting a half-made prefix.
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }

            if (!swapIn(baseDir, staging, stagingWine, identity)) {
                FileUtils.delete(staging);
                building.delete();
                if (!wineDir.exists()) baseDir.delete();
                return false;
            }
            building.delete();
            FileUtils.delete(staging);
            return true;
        }
        catch (Throwable t) {
            FileUtils.delete(staging);
            building.delete();
            return false;
        }
    }
}
