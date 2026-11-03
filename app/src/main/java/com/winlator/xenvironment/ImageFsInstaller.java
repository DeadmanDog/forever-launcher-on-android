package com.winlator.xenvironment;

import android.content.Context;
import android.content.res.AssetManager;


import com.winlator.PrefManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 32;
    private static final Object INSTALL_LOCK = new Object();

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            container.putExtra("imgVersion", null);
            container.putExtra("dxwrapper", null);
            container.putExtra("appVersion", null);
            container.saveData();
        }
    }

    // Since 1.4.3 Wine preloads the app's own exec shim (libwowexec.so from the APK); GameNative's closed libredirect copies that
    // older versions put into the image are no longer used, so they are removed.
    private static final String[] OLD_REDIRECT_LIBS = {"libredirect.so", "libredirect-bionic.so", "libredirect-bionic-wx.so",
            "libredirect-bionic-wx-minimal.so"};

    public static void removeOldRedirectLibs(File imagefs) {
        for (String name : OLD_REDIRECT_LIBS) {
            File old = new File(imagefs, "usr/lib/" + name);
            if (old.isFile() && !old.delete()) android.util.Log.w("ImageFsInstaller", "Could not remove " + old);
        }
    }

    private static Future<Boolean> installFromAssetsFuture(
            final Context context,
            AssetManager assetManager,
            String containerVariant,
            String wineVersion,
            Callback<Integer> onProgress
    ) {
        ImageFs imageFs = ImageFs.find(context);
        final File rootDir = imageFs.getRootDir();

        PrefManager.init(context);

        return com.winlator.core.OneShotTasks.submit(() -> {
          // One writer for the root file system: a run whose launch was aborted keeps extracting, and the next try
          // must wait for it instead of clearing the same folder under it.
          synchronized (INSTALL_LOCK) {
            // The aborted run may have finished the image while this one waited.
            if (imageFs.isValid() && imageFs.getVersion() >= LATEST_VERSION && imageFs.getVariant().equals(containerVariant)) {
                removeOldRedirectLibs(rootDir);
                return true;
            }
            clearRootDir(context, rootDir);
            ensureSharedHomeRoot(context, rootDir);
            ensureProtonVersionSymlink(context, rootDir, wineVersion);

            final byte compressionRatio = 22;
            String imagefsFile = "imagefs_bionic.txz";

            boolean success = false;

            if (Arrays.asList(context.getAssets().list("")).contains(imagefsFile) == true){
                final long contentLength = (long) (FileUtils.getSize(assetManager, imagefsFile) * (100.0f / compressionRatio));
                AtomicLong totalSizeRef = new AtomicLong();

                success = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, assetManager, imagefsFile, rootDir, (file, size) -> {
                    if (size > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        if (onProgress != null) {
                            final int progress = contentLength > 0
                                    ? (int) Math.min(100L, (long) (((double) totalSize / contentLength) * 100))
                                    : 0;
                            onProgress.call(progress);
                        }
                    }
                    return file;
                });
            }

            if (success) {
                imageFs.createImgVersionFile(LATEST_VERSION);
                resetContainerImgVersions(context);
            }
            return success;
          }
        });
    }


    public static Future<Boolean> installIfNeededFuture(final Context context, AssetManager assetManager, Container container, Callback<Integer> onProgress) {
        ImageFs imageFs = ImageFs.find(context);
        String wineVersion = container.getWineVersion();
        if (!ImageFSLegacyMigrator.migrateLegacyDirsIfNeeded(context, imageFs.getRootDir(), wineVersion)) {
            return com.winlator.core.OneShotTasks.submit(() -> false);
        }
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION || !imageFs.getVariant().equals(container.getContainerVariant())) {
            return installFromAssetsFuture(
                    context,
                    assetManager,
                    container.getContainerVariant(),
                    wineVersion,
                    onProgress
            );
        } else {
            return com.winlator.core.OneShotTasks.submit(() -> {
                synchronized (INSTALL_LOCK) {
                    removeOldRedirectLibs(imageFs.getRootDir());
                    return true;
                }
            });
        }
    }

    private static boolean isWineProtonDir(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.startsWith("wine-") || lowerName.startsWith("proton-");
    }

    private static void clearOptDir(Context context, File optDir) {
        File[] files = optDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            String fileName = file.getName();

            if (fileName.equals("installed-wine")) continue;

            // Keep imported Wine/Proton installations
            if (isWineProtonDir(fileName)) {
                continue;
            }

            // Delete everything else
            FileUtils.delete(file);
        }
    }

    private static void clearRootDir(Context context, File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                        // Preserve imported Wine/Proton installations in opt/
                        if (name.equals("opt")) {
                            clearOptDir(context, file);
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }

    /**
     * Ensures that:
     * - A shared home backing directory exists at imagefs_shared/home (containing xuser, etc.)
     * - The given imagefs rootDir exposes /home as a symlink to that shared root.
     *
     * This allows the same user home (e.g. .wine, .cache) to be shared across variants.
     */
    public static void ensureSharedHomeRoot(Context context, File rootDir) {
        File sharedHomeRoot = new File(ImageFs.getImageFsSharedDir(context), "home");
        if (!sharedHomeRoot.exists()) {
            sharedHomeRoot.mkdirs();
        }

        File homePathInImageFs = new File(rootDir, "home");
        if (FileUtils.isSymlink(homePathInImageFs)) {
            // Already symlinked: /imagefs/home is a symlink to imagefs_shared/home.
            return;
        }

        FileUtils.symlink(sharedHomeRoot.getPath(), homePathInImageFs.getPath());
    }

    /**
     * For Bionic: ensures rootDir/opt/<protonVersion> points to imagefs_shared/proton/<protonVersion>.
     * This keeps only the active Proton version linked in opt, matching pre-branch layout.
     */
    public static void ensureProtonVersionSymlink(Context context, File rootDir, String protonVersion) {
        if (protonVersion == null || protonVersion.isEmpty() || !protonVersion.startsWith("proton-")) return;
        File optDir = new File(rootDir, "opt");
        if (!optDir.exists() && !optDir.mkdirs()) {
            return;
        }
        removeCurrentProtonSymlink(optDir, protonVersion);

        File targetVersionDir = resolveInstalledProtonDir(context, protonVersion);
        if (!targetVersionDir.isDirectory()) {
            return;
        }
        File optVersionLink = new File(optDir, protonVersion);
        try {
            File desiredTarget = targetVersionDir.getCanonicalFile();
            if (isLinkAlreadyCorrect(optVersionLink, desiredTarget)) return;
            if (!deleteExistingPathIfPresent(optVersionLink)) return;

            FileUtils.symlink(targetVersionDir.getAbsolutePath(), optVersionLink.getAbsolutePath());
            if (!Files.isSymbolicLink(optVersionLink.toPath())) {
                return;
            }
            File linkedTarget = optVersionLink.getCanonicalFile();
            if (!linkedTarget.equals(desiredTarget)) {
                return;
            }
        } catch (Exception e) {
        }
    }

    private static boolean isLinkAlreadyCorrect(File optVersionLink, File desiredTarget) {
        if (!Files.isSymbolicLink(optVersionLink.toPath())) return false;
        try {
            return optVersionLink.getCanonicalFile().equals(desiredTarget);
        } catch (IOException ignored) {
            // Dangling symlink (or inaccessible target): treat as incorrect and replace.
            return false;
        }
    }

    private static boolean deleteExistingPathIfPresent(File path) {
        if (!Files.isSymbolicLink(path.toPath()) && !path.exists()) return true;
        if (FileUtils.delete(path)) return true;
        return false;
    }

    private static File resolveInstalledProtonDir(Context context, String protonVersion) {
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        ContentProfile profile = contentsManager.getProfileByEntryName(protonVersion);
        if (profile != null && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            return ContentsManager.getInstallDir(context, profile);
        }
        return new File(ImageFs.getSharedProtonDir(context), protonVersion);
    }

    /**
     * Removes the current Proton symlink(s) from opt/ so it can be replaced with the current proton
     * version symlink.
     */
    private static void removeCurrentProtonSymlink(File optDir, String activeProtonVersion) {
        File[] optEntries = optDir.listFiles();
        if (optEntries == null) {
            return;
        }

        for (File entry : optEntries) {
            if (!entry.getName().startsWith("proton-")) {
                continue;
            }
            if (entry.getName().equals(activeProtonVersion)) {
                continue;
            }
            if (FileUtils.isSymlink(entry)) {
                FileUtils.delete(entry);
            }
        }
    }
}
