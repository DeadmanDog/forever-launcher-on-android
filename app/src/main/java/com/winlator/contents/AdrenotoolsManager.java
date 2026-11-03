package com.winlator.contents;

import android.net.Uri;

import android.content.Context;
import com.winlator.core.FileUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {

    private File adrenotoolsContentDir;
    private Context mContext;

    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }

    public String getLibraryName(String adrenoToolsDriverId) {

        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (JSONException e) {
        }
        return libraryName;

    }

    public String getDriverVersion(String adrenoToolsDriverId) {

        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (JSONException e) {
        }
        return driverVersion;

    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();

        for (File f : adrenotoolsContentDir.listFiles()) {
            if (!f.getName().startsWith(".removed-") && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    /** Developer import: same storage/ZIP format, with validation before publication. */
    public String installValidatedDriver(Uri uri) throws IOException {
        File stage = new File(adrenotoolsContentDir, ".developer-staging");
        FileUtils.delete(stage);
        if (!stage.mkdirs()) throw new IOException("Unable to create driver staging.");
        java.util.Set<String> entries = new java.util.HashSet<>();
        try (InputStream input = mContext.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(stage, entry.getName());
                if (new File(entry.getName()).isAbsolute()
                        || !target.getCanonicalFile().toPath().startsWith(stage.getCanonicalFile().toPath()))
                    throw new IOException("Unsafe driver archive path.");
                if (!entry.isDirectory() && !entries.add(target.getCanonicalPath())) throw new IOException("Duplicate driver archive entry.");
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Unable to create driver directory.");
                } else {
                    if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("Unable to create driver directory.");
                    Files.copy(zip, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String id = validateDriverDirectory(stage);
            File target = new File(adrenotoolsContentDir, id);
            if (target.exists()) throw new IOException("Driver already installed; it was not overwritten.");
            if (!stage.renameTo(target)) throw new IOException("Unable to install driver.");
            return id;
        } finally {
            FileUtils.delete(stage);
        }
    }

    public static String validateDriverDirectory(File directory) throws IOException {
        try {
            JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(directory, "meta.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
            String name = meta.getString("name");
            String library = meta.getString("libraryName");
            if (!name.toLowerCase(java.util.Locale.ROOT).contains("turnip")
                    && !library.toLowerCase(java.util.Locale.ROOT).contains("freedreno")) throw new IOException("Only Turnip driver packages are supported here.");
            if (name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains(",") || name.contains("="))
                throw new IOException("Invalid driver name.");
            if (library.isEmpty() || library.contains("/") || library.contains("\\") || !library.endsWith(".so"))
                throw new IOException("Invalid driver libraryName.");
            File binary = new File(directory, library);
            if (!binary.isFile() || !binary.getCanonicalFile().toPath().startsWith(directory.getCanonicalFile().toPath())) throw new IOException("Driver library missing.");
            try (java.io.RandomAccessFile elf = new java.io.RandomAccessFile(binary, "r")) {
                if (elf.length() < 64 || elf.readInt() != 0x7f454c46 || elf.readUnsignedByte() != 2 || elf.readUnsignedByte() != 1)
                    throw new IOException("Driver must be a 64-bit ELF library.");
                elf.seek(16);
                if (elf.readUnsignedByte() != 3 || elf.readUnsignedByte() != 0 || elf.readUnsignedByte() != 183 || elf.readUnsignedByte() != 0)
                    throw new IOException("Driver must be an ARM64 shared library.");
            }
            return name;
        } catch (JSONException e) { throw new IOException("Invalid driver meta.json.", e); }
    }

    /** Caller must validate dependencies; unlike removeDriver, this never rewrites containers. */
    public boolean deleteDriverWithoutReassigning(String id) throws IOException {
        File target = new File(adrenotoolsContentDir, id);
        if (!target.getCanonicalFile().getParentFile().equals(adrenotoolsContentDir.getCanonicalFile())) throw new IOException("Invalid driver path.");
        ComponentRemoval.remove(target);
        return true;
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));
            }
        }
    }
}
