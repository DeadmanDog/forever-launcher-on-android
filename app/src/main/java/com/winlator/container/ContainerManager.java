package com.winlator.container;

import android.content.Context;

import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.OnExtractFileListener;
import com.winlator.core.SharedComponents;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private final File homeDir;
    private final Context context;

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    private void loadContainers() {

        containers.clear();

        File[] files = homeDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (file.getName().startsWith(ImageFs.USER+"-")) {
                        String containerId = file.getName().replace(ImageFs.USER+"-", "");
                        Container container = new Container(containerId);
                        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
                        try {
                            RuntimePrefixTransaction.recover(container.getRootDir());
                            File configFile = container.getConfigFile();
                            String configContent = FileUtils.readString(configFile);

                            if (configContent == null || configContent.trim().isEmpty()) {
                                continue;
                            }

                            JSONObject data = new JSONObject(configContent);
                            container.loadData(data);
                            containers.add(container);
                        } catch (Exception e) {
                            // Catch ALL exceptions (NullPointerException, JSONException, etc.)
                            // Continue loading other containers
                        }
                    }
                }
            }
        }

    }

    public void activateContainer(Container container) {

        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());

    }

    public Container createContainer(String containerId, JSONObject data) {

        File containerDir = new File(homeDir, ImageFs.USER+"-"+containerId);
        try {
            data.put("id", containerId);

            // A folder without a config is what an interrupted creation leaves behind; it would otherwise block
            // this container id for good (mkdirs() fails on it).
            if (containerDir.isDirectory()) {
                Container leftover = new Container(containerId);
                leftover.setRootDir(containerDir);
                String config = FileUtils.readString(leftover.getConfigFile());
                if (config == null || config.trim().isEmpty()) FileUtils.delete(containerDir);
            }
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(containerId);
            container.setRootDir(containerDir);
            container.loadData(data);
            ContentsManager contentsManager = new ContentsManager(context);

            boolean isMainWineVersion = !data.has("wineVersion") || WineInfo.isMainWineVersion(data.getString("wineVersion"));
            if (!isMainWineVersion) container.setWineVersion(data.getString("wineVersion"));

            boolean thin = ContainerOverlay.isEligible(container) && createThinPrefix(container, contentsManager);
            if (!thin && !extractContainerPatternFile(container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

            container.saveData();
            containers.add(container);
            return container;
        }
        catch (JSONException e) {
            FileUtils.delete(containerDir);
        }
        return null;

    }

    private boolean createThinPrefix(Container container, ContentsManager contentsManager) {
        contentsManager.syncContents();
        File baseWine = BasePrefix.ensure(context, contentsManager, container.getWineVersion());
        File wineDir = new File(container.getRootDir(), ".wine");
        if (baseWine != null && ContainerOverlay.createThinPrefix(baseWine, wineDir)) {
            container.setBasePrefix(ContainerOverlay.canonicalHostPath(baseWine));
            return true;
        }
        FileUtils.delete(wineDir);
        return false;
    }

    public boolean hasContainer(String id) {
        for (Container container : containers) if (container.id.equals(id)) return true;
        return false;
    }

    public Container getContainerById(String id) {
        for (Container container : containers) if (container.id.equals(id)) return container;
        return null;
    }

    /**
     * Extracts the Wine prefix pack from a custom Wine installation.
     * Checks for prefixPack.tzst or prefixPack.txz and uses the appropriate decompression algorithm.
     *
     * @param wineInstallPath Path to the Wine installation root directory
     * @param destinationDir Directory where the prefix should be extracted
     * @return true if extraction succeeded, false otherwise
     */
    private static boolean extractPrefixPack(String wineInstallPath, File destinationDir) {
        if (wineInstallPath == null || wineInstallPath.isEmpty()) {
            return false;
        }

        File tzstFile = new File(wineInstallPath, "prefixPack.tzst");
        if (tzstFile.exists()) {
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, tzstFile, destinationDir);
        }

        File txzFile = new File(wineInstallPath, "prefixPack.txz");
        if (txzFile.exists()) {
            return TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, txzFile, destinationDir);
        }

        return false;
    }

    private void deleteCommonDlls(String dstName,
                                  JSONObject commonDlls,
                                  File containerDir) throws JSONException {
        // Get the list of DLL names for the given destination folder
        JSONArray dlnames = commonDlls.getJSONArray(dstName);

        for (int i = 0; i < dlnames.length(); i++) {
            String dlname = dlnames.getString(i);

            // Compose full path to the target DLL inside the Wine prefix
            File targetFile = new File(containerDir,
                    ".wine/drive_c/windows/" + dstName + "/" + dlname);

            // Delete if present
            if (targetFile.exists()) {
                //noinspection ResultOfMethodCallIgnored  // intentional, we don't care about the boolean
                targetFile.delete();
            }
        }
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());
        // A runtime may ship without the 32-bit (i386) payload: nothing to place in syswow64 then.
        if (srcfiles == null) return;

        for (File file : srcfiles) {
            String dllName = file.getName();
            File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
            if (dstFile.exists()) continue;
            if (onExtractFileListener != null ) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(file, dstFile);
        }
    }

    public boolean extractContainerPatternCommon(File containerDir, OnExtractFileListener onExtractFileListener) {
        return extractContainerPatternCommonArchive(containerDir, onExtractFileListener);
    }

    private boolean extractContainerPatternCommonArchive(File containerDir, OnExtractFileListener onExtractFileListener) {
        // The Windows fonts and shared files ship inside the APK (assets/container_pattern_common.txz).
        return SharedComponents.extractAndLink(context, "container_pattern_common", TarCompressorUtils.Type.XZ, "container_pattern_common.txz", containerDir, onExtractFileListener);
    }

    public boolean extractContainerPatternFile(String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (WineInfo.isMainWineVersion(wineVersion)) return false; // the built-in Wine 9 container pattern is not bundled

        try {
            JSONObject commonDlls = new JSONObject(FileUtils.readString(context, "common_dlls.json"));
            deleteCommonDlls("system32", commonDlls, containerDir);
            deleteCommonDlls("syswow64", commonDlls, containerDir);
        }
        catch (JSONException e) {
            return false;
        }

        // Every other runtime (the bundled Proton) carries its own prefix pack.
        boolean result = extractPrefixPack(wineInfo.path, containerDir);
        if (result) {
            try {
                if (wineInfo.isArm64EC())
                    extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener); // arm64ec only
                else
                    extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);

                extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            }
            catch (JSONException e) {
                return false;
            }
        }

        return result;
    }
}
