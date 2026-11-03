package com.winlator.xenvironment;

import android.content.Context;

import com.winlator.core.FileUtils;

import java.io.File;

public final class ImageFSLegacyMigrator {
    private ImageFSLegacyMigrator() {}

    /**
     * Migrate legacy directories if needed. After that, ensure the shared home and proton are symlinked.
     */
    public static boolean migrateLegacyDirsIfNeeded(Context context, File legacyImageFsRoot, String wineVersion) {

        if (!migrateLegacyHomeToShared(context, legacyImageFsRoot)) {
            return false;
        }
        if (!migrateLegacyProtonToShared(context, legacyImageFsRoot)) {
            return false;
        }
        ImageFsInstaller.ensureSharedHomeRoot(context, legacyImageFsRoot);
        ImageFsInstaller.ensureProtonVersionSymlink(context, legacyImageFsRoot, wineVersion);
        return true;

    }

    /**
     * Before deleting legacy files/imagefs, preserve /home contents by moving them into
     * files/imagefs_shared/home so first-run sync can reuse xuser/.wine safely.
     */
    private static boolean migrateLegacyHomeToShared(Context context, File legacyImageFsRoot) {
        File legacyHome = new File(legacyImageFsRoot, "home");
        File sharedHomeRoot = new File(ImageFs.getImageFsSharedDir(context), "home");

        if (FileUtils.isSymlink(legacyHome)) {
            // Already migrated: /imagefs/home is a symlink to imagefs_shared/home.
            return true;
        }

        if (!legacyHome.exists() || !legacyHome.isDirectory()) {
            // No need to migrate.
            return true;
        }

        if (sharedHomeRoot.exists()) {
            FileUtils.delete(sharedHomeRoot);
        }

        if (!legacyHome.renameTo(sharedHomeRoot)) {
            boolean copied = FileUtils.copy(legacyHome, sharedHomeRoot);
            if (copied) {
                FileUtils.delete(legacyHome);
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    /**
     * Before deleting legacy opt/proton-<version> directories, preserve them by moving them into
     * files/imagefs_shared/proton so they can be symlinked.
     */
    private static boolean migrateLegacyProtonToShared(Context context, File legacyImageFsRoot) {
        File optDir = new File(legacyImageFsRoot, "opt");
        File[] optEntries = optDir.listFiles();
        if (optEntries == null) {
            return true;
        }

        for (File entry : optEntries) {
            if (!entry.isDirectory() || FileUtils.isSymlink(entry) || !entry.getName().startsWith("proton-")) {
                continue;
            }

            File sharedProtonDir = new File(ImageFs.getSharedProtonDir(context), entry.getName());
            if (sharedProtonDir.exists()) {
                if (!FileUtils.delete(entry)) {
                    return false;
                }
                continue;
            }

            if (!entry.renameTo(sharedProtonDir)) {
                boolean copied = FileUtils.copy(entry, sharedProtonDir);
                if (copied) {
                    FileUtils.delete(entry);
                    continue;
                }
                return false;
            }

        }

        return true;
    }
}
