package com.winlator.contents;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ContentsManager {
    private static final Object LOCAL_INSTALL_LOCK = new Object();
    public static Object getLocalInstallLock() { return LOCAL_INSTALL_LOCK; }
    public static final String PROFILE_NAME = "profile.json";
    public static final String[] TURNIP_TRUST_FILES = {"${libdir}/libvulkan_freedreno.so", "${libdir}/libvulkan.so.1",
            "${sharedir}/vulkan/icd.d/freedreno_icd.aarch64.json", "${libdir}/libGL.so.1", "${libdir}/libglapi.so.0"};
    public static final String[] WRAPPER_TRUST_FILES = {"${libdir}/libvulkan_wrapper.so",
            "${sharedir}/vulkan/icd.d/wrapper_icd.aarch64.json", "${libdir}/libmain_hook.so", "${libdir}/libhook_impl.so",
            "${libdir}/libadrenotools.so", "${libdir}/libgsl_alloc_hook.so", "${libdir}/libfile_redirect_hook.so"};
    public static final String[] DXVK_TRUST_FILES = {"${system32}/d3d8.dll", "${system32}/d3d9.dll", "${system32}/d3d10.dll", "${system32}/d3d10_1.dll",
            "${system32}/d3d10core.dll", "${system32}/d3d11.dll", "${system32}/dxgi.dll", "${syswow64}/d3d8.dll", "${syswow64}/d3d9.dll", "${syswow64}/d3d10.dll",
            "${syswow64}/d3d10_1.dll", "${syswow64}/d3d10core.dll", "${syswow64}/d3d11.dll", "${syswow64}/dxgi.dll"};
    public static final String[] VKD3D_TRUST_FILES = {"${system32}/d3d12core.dll", "${system32}/d3d12.dll",
            "${syswow64}/d3d12core.dll", "${syswow64}/d3d12.dll"};
    private Map<String, String> dirTemplateMap;
    private Map<ContentProfile.ContentType, List<String>> trustedFilesMap;

    public enum InstallFailedReason {
        ERROR_NOSPACE,
        ERROR_BADTAR,
        ERROR_NOPROFILE,
        ERROR_BADPROFILE,
        ERROR_MISSINGFILES,
        ERROR_EXIST,
        ERROR_UNTRUSTPROFILE,
        ERROR_UNKNOWN
    }

    public enum ContentDirName {
        CONTENT_MAIN_DIR_NAME("contents"),
        CONTENT_WINE_DIR_NAME("wine"),
        CONTENT_TURNIP_DIR_NAME("turnip"),
        CONTENT_DXVK_DIR_NAME("dxvk"),
        CONTENT_VKD3D_DIR_NAME("vkd3d");

        private String name;

        ContentDirName(String name) {
            this.name = name;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }

    private final Context context;

    private HashMap<ContentProfile.ContentType, List<ContentProfile>> profilesMap;

    private ArrayList<ContentProfile> remoteProfiles;

    public ContentsManager(Context context) {
        this.context = context;
    }

    public interface OnInstallFinishedCallback {
        void onFailed(InstallFailedReason reason, @Nullable Exception e);

        void onSucceed(ContentProfile profile);
    }

    public void syncContents() {

        profilesMap = new HashMap<>();
        // Wine and Proton share a directory. Scan/read each physical path once per fresh sync.
        Map<String, File[]> directories = new HashMap<>();
        Map<String, ContentProfile> parsed = new HashMap<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            LinkedList<ContentProfile> profiles = new LinkedList<>();
            profilesMap.put(type, profiles);

            File typeFile = getContentTypeDir(context, type);
            String directoryKey = typeFile.getAbsolutePath();
            if (!directories.containsKey(directoryKey)) directories.put(directoryKey, typeFile.listFiles());
            File[] fileList = directories.get(directoryKey);
            if (fileList != null) {
                for (File file : fileList) {
                    if (file.getName().startsWith(".removed-")) continue;
                    File proFile = new File(file, PROFILE_NAME);
                    if (proFile.exists() && proFile.isFile()) {
                        String profileKey = proFile.getAbsolutePath();
                        if (!parsed.containsKey(profileKey)) parsed.put(profileKey, readProfile(proFile));
                        ContentProfile profile = parsed.get(profileKey);
                        // Wine and Proton are functionally equivalent - accept both when scanning either type
                        boolean isWineProtonMatch = (profile != null && profile.type == type) ||
                                (profile != null && type == ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) ||
                                (profile != null && type == ContentProfile.ContentType.CONTENT_TYPE_PROTON && profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE);
                        if (isWineProtonMatch) {
                            profiles.add(profile);
                        }
                    }
                }
            } else {
            }

            if (remoteProfiles != null) {
                for (ContentProfile remote : remoteProfiles) {
                    if (remote.type == type) {
                        boolean exists = false;
                        for (ContentProfile profile : profiles) {
                            if ((profile.verName.compareTo(remote.verName) == 0) && (profile.verCode == remote.verCode)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            profiles.add(remote);
                        }
                    }
                }
            }
        }

    }

    /** Expected-type imports validate archive paths before extraction and reject other components. */
    public void extraContentFile(Uri uri, ContentProfile.ContentType expectedType, OnInstallFinishedCallback callback) {
        cleanTmpDir(context);

        File file = getTmpDir(context);

        boolean ret;
        ret = expectedType == null
                ? TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, context, uri, file)
                : TarCompressorUtils.extractValidated(TarCompressorUtils.Type.XZ, context, uri, file, expectedType == ContentProfile.ContentType.CONTENT_TYPE_WINE);
        if (!ret) {
            if (expectedType != null) cleanTmpDir(context);
            ret = expectedType == null
                    ? TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, uri, file)
                    : TarCompressorUtils.extractValidated(TarCompressorUtils.Type.ZSTD, context, uri, file, expectedType == ContentProfile.ContentType.CONTENT_TYPE_WINE);
        }
        if (!ret) {
            callback.onFailed(InstallFailedReason.ERROR_BADTAR, null);
            return;
        }

        File proFile = new File(file, PROFILE_NAME);
        if (!proFile.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_NOPROFILE, null);
            return;
        }

        ContentProfile profile = readProfile(proFile);
        if (profile == null) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, null);
            return;
        }

        if (expectedType != null && profile.type != expectedType
                && !(expectedType == ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            callback.onFailed(InstallFailedReason.ERROR_BADPROFILE,
                    new IllegalArgumentException("Only " + expectedType + " WCP profiles can be imported here."));
            return;
        }

        String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
        java.util.Set<String> destinations = new java.util.HashSet<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            File tmpFile = new File(file, contentFile.source);
            if (!tmpFile.exists() || !tmpFile.isFile() || !isSubPath(file.getAbsolutePath(), tmpFile.getAbsolutePath())) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }

            String realPath = getPathFromTemplate(contentFile.target);
            if (expectedType != null) {
                try {
                    if (!destinations.add(new File(realPath).getCanonicalPath())) {
                        callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, new IllegalArgumentException("Duplicate component target."));
                        return;
                    }
                } catch (java.io.IOException e) { callback.onFailed(InstallFailedReason.ERROR_BADPROFILE, e); return; }
            }
            if (!isSubPath(imagefsPath, realPath) || isSubPath(ContentsManager.getContentDir(context).getAbsolutePath(), realPath) || realPath.contains("dosdevices")) {
                callback.onFailed(InstallFailedReason.ERROR_UNTRUSTPROFILE, null);
                return;
            }
        }

        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            File bin = new File(file, profile.wineBinPath);
            File lib = new File(file, profile.wineLibPath);
            File cp = new File(file, profile.winePrefixPack);

            if (!bin.exists() || !bin.isDirectory() || !lib.exists() || !lib.isDirectory() || !cp.exists() || !cp.isFile()) {
                callback.onFailed(InstallFailedReason.ERROR_MISSINGFILES, null);
                return;
            }
        }

        callback.onSucceed(profile);
    }

    public void finishInstallContent(ContentProfile profile, OnInstallFinishedCallback callback) {
        // Reject if a version with this name already exists (no version code incrementation)
        File installPath = getInstallDir(context, profile);
        if (installPath.exists()) {
            callback.onFailed(InstallFailedReason.ERROR_EXIST, null);
            return;
        }

        if (!installPath.mkdirs()) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }

        if (!getTmpDir(context).renameTo(installPath)) {
            callback.onFailed(InstallFailedReason.ERROR_UNKNOWN, null);
            return;
        }
        // For Wine/Proton, normalize directory structure and set executable permissions
        if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            // Normalize lib directory structure: ensure lib/wine/ subdirectory exists
            normalizeWineLibraryStructure(installPath, profile);

            File binDir = new File(installPath, profile.wineBinPath);
            if (binDir.exists() && binDir.isDirectory()) {
                setExecutablePermissionsRecursive(binDir);
            }
        }

        callback.onSucceed(profile);
    }

    public ContentProfile readProfile(File file) {

        try {
            ContentProfile profile = new ContentProfile();
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            String typeName = profileJSONObject.getString(ContentProfile.MARK_TYPE);
            String verName = profileJSONObject.getString(ContentProfile.MARK_VERSION_NAME);
            int verCode = profileJSONObject.getInt(ContentProfile.MARK_VERSION_CODE);
            String desc = profileJSONObject.getString(ContentProfile.MARK_DESC);

            JSONArray fileJSONArray = profileJSONObject.getJSONArray(ContentProfile.MARK_FILE_LIST);
            List<ContentProfile.ContentFile> fileList = new ArrayList<>();
            for (int i = 0; i < fileJSONArray.length(); i++) {
                JSONObject contentFileJSONObject = fileJSONArray.getJSONObject(i);
                ContentProfile.ContentFile contentFile = new ContentProfile.ContentFile();
                contentFile.source = contentFileJSONObject.getString(ContentProfile.MARK_FILE_SOURCE);
                contentFile.target = contentFileJSONObject.getString(ContentProfile.MARK_FILE_TARGET);
                fileList.add(contentFile);
            }
            // Both Wine and Proton can use either "wine" or "proton" key in profile.json
            if (typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())
                    || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString())) {
                // Try "proton" key first, then fall back to "wine" key for compatibility
                JSONObject wineJSONObject = null;
                if (profileJSONObject.has(ContentProfile.MARK_PROTON)) {
                    wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_PROTON);
                } else if (profileJSONObject.has(ContentProfile.MARK_WINE)) {
                    wineJSONObject = profileJSONObject.getJSONObject(ContentProfile.MARK_WINE);
                }

                if (wineJSONObject != null) {
                    profile.wineLibPath = wineJSONObject.getString(ContentProfile.MARK_WINE_LIBPATH);
                    profile.wineBinPath = wineJSONObject.getString(ContentProfile.MARK_WINE_BINPATH);
                    profile.winePrefixPack = wineJSONObject.getString(ContentProfile.MARK_WINE_PREFIX_PACK);
                }
            }

            profile.type = ContentProfile.ContentType.getTypeByName(typeName);

            // For Wine/Proton, ensure type is included in version name
            if ((typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_WINE.toString())
                    || typeName.equals(ContentProfile.ContentType.CONTENT_TYPE_PROTON.toString()))
                    && !verName.toLowerCase().startsWith(typeName.toLowerCase())) {
                profile.verName = typeName.toLowerCase() + "-" + verName;
            } else {
                profile.verName = verName;
            }

            profile.verCode = verCode;
            profile.desc = desc;
            profile.fileList = fileList;
            return profile;
        } catch (Exception e) {
            return null;
        }

    }

    public List<ContentProfile> getProfiles(ContentProfile.ContentType type) {
        if (profilesMap != null)
            return profilesMap.get(type);
        return null;
    }

    public static File getInstallDir(Context context, ContentProfile profile) {
        // Use version name only, no version code suffix
        return new File(getContentTypeDir(context, profile.type), profile.verName);
    }

    public static File getContentDir(Context context) {
        return new File(context.getFilesDir(), ContentDirName.CONTENT_MAIN_DIR_NAME.toString());
    }

    public static File getContentTypeDir(Context context, ContentProfile.ContentType type) {
        // Wine/Proton install to imagefs_shared/proton; symlinked into each variant's opt
        if (type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            return ImageFs.getSharedProtonDir(context);
        }
        return new File(getContentDir(context), type.toString());
    }

    public static File getTmpDir(Context context) {
        return new File(context.getFilesDir(), "tmp/" + ContentDirName.CONTENT_MAIN_DIR_NAME);
    }

    public static void cleanTmpDir(Context context) {
        File file = getTmpDir(context);
        FileUtils.delete(file);
        file.mkdirs();
    }

    /**
     * Normalizes Wine/Proton library structure to ensure consistent layout.
     * All Wine/Proton installations should have lib/wine/ subdirectory structure
     * regardless of what the profile.json specifies.
     *
     * Expected structure after normalization:
     * - bin/ (executables)
     * - lib/ (shared libraries)
     *   └── wine/ (Wine DLLs by architecture)
     *       ├── i386-windows/
     *       ├── x86_64-windows/
     *       └── aarch64-windows/ (for arm64ec)
     */
    private void normalizeWineLibraryStructure(File installPath, ContentProfile profile) {
        String libPath = profile.wineLibPath != null ? profile.wineLibPath : "lib";
        File actualLibDir = new File(installPath, libPath);
        File expectedLibDir = new File(installPath, "lib");
        File expectedWineLibDir = new File(expectedLibDir, "wine");

        // If libPath is already "lib/wine", we need to restructure
        if (libPath.equals("lib/wine")) {
            // Use timestamp to avoid conflicts with existing temp directories
            File tempWineDir = new File(installPath, "wine_temp_" + System.currentTimeMillis());
            if (actualLibDir.exists()) {
                // Check if temp dir already exists (shouldn't happen with timestamp)
                if (tempWineDir.exists()) {
                    FileUtils.delete(tempWineDir);
                }

                // Move lib/wine -> wine_temp
                if (!actualLibDir.renameTo(tempWineDir)) {
                    return;
                }

                // Recreate lib/wine and move contents back
                if (!expectedWineLibDir.mkdirs()) {
                    // Try to restore original state
                    if (!tempWineDir.renameTo(actualLibDir)) {
                    }
                    return;
                }

                File[] wineContents = tempWineDir.listFiles();
                if (wineContents != null) {
                    boolean allMoved = true;
                    for (File item : wineContents) {
                        File dest = new File(expectedWineLibDir, item.getName());
                        if (!item.renameTo(dest)) {
                            allMoved = false;
                        }
                    }
                    if (!allMoved) {
                    }
                }
                if (tempWineDir.exists()) {
                    FileUtils.delete(tempWineDir);
                }
            }
        }
        // If libPath is "lib" and wine subdirs exist directly in lib/, move them
        else if (libPath.equals("lib")) {
            if (!actualLibDir.exists()) {
                return;
            }

            // Check if wine architecture directories exist directly in lib/
            File[] archDirs = actualLibDir.listFiles(file ->
                file.isDirectory() && (
                    file.getName().equals("i386-windows") ||
                    file.getName().equals("x86_64-windows") ||
                    file.getName().equals("aarch64-windows") ||
                    file.getName().equals("i386-unix") ||
                    file.getName().equals("x86_64-unix") ||
                    file.getName().equals("aarch64-unix")
                )
            );

            if (archDirs != null && archDirs.length > 0) {

                // Create lib/wine subdirectory
                if (!expectedWineLibDir.exists() && !expectedWineLibDir.mkdirs()) {
                    return;
                }

                // Move each architecture directory into lib/wine/
                for (File archDir : archDirs) {
                    File dest = new File(expectedWineLibDir, archDir.getName());
                    if (archDir.renameTo(dest)) {
                    } else {
                    }
                }
            } else {
            }
        }

    }

    /**
     * Recursively sets executable permissions (0755) on all files in a
     * directory. This is needed for Wine/Proton binaries to run on Android.
     */
    private void setExecutablePermissionsRecursive(File dir) {
        if (!dir.exists()) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                setExecutablePermissionsRecursive(file);
            } else if (file.isFile()) {
                // Set executable permissions on all files in bin/ directory
                FileUtils.chmod(file, 0755);
            }
        }
    }

    public List<ContentProfile.ContentFile> getUnTrustedContentFiles(ContentProfile profile) {
        createTrustedFilesMap();
        List<ContentProfile.ContentFile> files = new ArrayList<>();
        for (ContentProfile.ContentFile contentFile : profile.fileList) {
            if (!trustedFilesMap.get(profile.type).contains(
                    Paths.get(getPathFromTemplate(contentFile.target)).toAbsolutePath().normalize().toString()))
                files.add(contentFile);
        }
        return files;
    }

    private boolean isSubPath(String parent, String child) {
        return Paths.get(child).toAbsolutePath().normalize().startsWith(Paths.get(parent).toAbsolutePath().normalize());
    }

    private void createDirTemplateMap() {
        if (dirTemplateMap == null) {
            dirTemplateMap = new HashMap<>();
            String imagefsPath = context.getFilesDir().getAbsolutePath() + "/imagefs";
            String drivecPath = imagefsPath + "/home/xuser/.wine/drive_c";
            dirTemplateMap.put("${libdir}", imagefsPath + "/usr/lib");
            dirTemplateMap.put("${system32}", drivecPath + "/windows/system32");
            dirTemplateMap.put("${syswow64}", drivecPath + "/windows/syswow64");
            dirTemplateMap.put("${localbin}", imagefsPath + "/usr/local/bin");
            dirTemplateMap.put("${bindir}", imagefsPath + "/usr/bin");
            dirTemplateMap.put("${sharedir}", imagefsPath + "/usr/share");
        }
    }

    private void createTrustedFilesMap() {
        if (trustedFilesMap == null) {
            trustedFilesMap = new HashMap<>();
            for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
                List<String> pathList = new ArrayList<>();
                trustedFilesMap.put(type, pathList);

                String[] paths = switch (type) {
                    case CONTENT_TYPE_TURNIP -> TURNIP_TRUST_FILES;
                    case CONTENT_TYPE_WRAPPER -> WRAPPER_TRUST_FILES;
                    case CONTENT_TYPE_DXVK -> DXVK_TRUST_FILES;
                    case CONTENT_TYPE_VKD3D -> VKD3D_TRUST_FILES;
                    default -> new String[0];
                };
                for (String path : paths)
                    pathList.add(Paths.get(getPathFromTemplate(path)).toAbsolutePath().normalize().toString());
            }
        }
    }

    private String getPathFromTemplate(String path) {
        createDirTemplateMap();
        String realPath = path;
        for (String key : dirTemplateMap.keySet()) {
            realPath = realPath.replace(key, dirTemplateMap.get(key));
        }
        return realPath;
    }

    public void removeContent(ContentProfile profile) {
        File installDir = getInstallDir(context, profile);
        List<ContentProfile> profiles = profilesMap.get(profile.type);
        // Find matching profile by verName and verCode (not by reference equality)
        ContentProfile matchingProfile = null;
        if (profiles != null) {
            for (ContentProfile p : profiles) {
                if (p.verName.equals(profile.verName) && p.verCode == profile.verCode) {
                    matchingProfile = p;
                    break;
                }
            }
        }

        if (matchingProfile != null) {
            try { ComponentRemoval.remove(installDir); }
            catch (java.io.IOException e) { throw new IllegalStateException("Unable to remove component", e); }
            profiles.remove(matchingProfile);
            syncContents();
        }
    }

    public static String getEntryName(ContentProfile profile) {
        return profile.type.toString() + '-' + profile.verName + '-' + profile.verCode;
    }

    public ContentProfile getProfileByEntryName(String entryName) {

        // Initialize profilesMap if needed (first call before syncContents)
        if (profilesMap == null) {
            syncContents();
        }

        // Try to determine type from the entry name (supports prefixed builds like "GE-Proton")
        ContentProfile.ContentType type = null;
        String lowerVersionName = entryName.toLowerCase();
        if (lowerVersionName.contains("proton")) {
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON;
        } else if (lowerVersionName.contains("wine")) {
            type = ContentProfile.ContentType.CONTENT_TYPE_WINE;
        } else {
            // Try other types
            int firstDash = entryName.indexOf('-');
            if (firstDash > 0) {
                String possibleType = entryName.substring(0, firstDash);
                type = ContentProfile.ContentType.getTypeByName(possibleType);
            }
        }

        if (type != null && profilesMap.get(type) != null) {
            List<ContentProfile> profiles = profilesMap.get(type);

            String keyLower = lowerVersionName;

            for (ContentProfile profile : profiles) {
                String verName = (profile.verName != null) ? profile.verName : "";
                String verLower = verName.toLowerCase();

                String typeAndVer = profile.type.toString() + "-" + verName;
                String typeAndVerLower = typeAndVer.toLowerCase();

                String fullEntry = ContentsManager.getEntryName(profile);
                String fullEntryLower = fullEntry.toLowerCase();

                if (keyLower.equals(verLower) ||
                    keyLower.equals(typeAndVerLower) ||
                    keyLower.equals(fullEntryLower)) {
                    return profile;
                }
            }

        } else {
        }

        // Fallback: Try Wine and Proton lists if entry name doesn't have type prefix
        // This handles legacy identifiers like "proton-10-arm64ec-0"
        try {
            int lastDash = entryName.lastIndexOf('-');
            if (lastDash > 0) {
                String verName = entryName.substring(0, lastDash);
                String verCodeStr = entryName.substring(lastDash + 1);
                int verCode = Integer.parseInt(verCodeStr);

                // Check Wine list
                if (profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_WINE) != null) {
                    for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
                        if (verName.equals(profile.verName) && verCode == profile.verCode) {
                            return profile;
                        }
                    }
                }

                // Check Proton list
                if (profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_PROTON) != null) {
                    for (ContentProfile profile : profilesMap.get(ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
                        if (verName.equals(profile.verName) && verCode == profile.verCode) {
                            return profile;
                        }
                    }
                }
            }
        } catch (Exception e) {
        }

        return null;

    }

    public boolean applyContent(ContentProfile profile) {

        if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_WINE && profile.type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
            for (ContentProfile.ContentFile contentFile : profile.fileList) {
                File targetFile = new File(getPathFromTemplate(contentFile.target));
                File sourceFile = new File(getInstallDir(context, profile), contentFile.source);

                targetFile.delete();
                FileUtils.copy(sourceFile, targetFile);
            }
        } else {
            // TODO: do nothing?
        }
        return true;

    }
}
