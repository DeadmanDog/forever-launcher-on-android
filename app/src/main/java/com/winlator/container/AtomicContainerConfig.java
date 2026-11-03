package com.winlator.container;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Same-directory atomic publication; unchanged fields never overwrite a newer writer. */
final class AtomicContainerConfig {
    private AtomicContainerConfig() {}

    static synchronized void save(File config, JSONObject baseline, JSONObject desired) throws IOException, JSONException {
        JSONObject latest = config.isFile()
            ? new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        JSONObject merged = merge(config.isFile() ? baseline : null, desired, latest);
        byte[] bytes = merged.toString().getBytes(StandardCharsets.UTF_8);
        if (config.isFile() && equal(latest, merged)) return;
        File temp = File.createTempFile(".container-", ".pending", config.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            // Never fall back to truncating/copying the live file if atomic rename is unavailable.
            Files.move(temp.toPath(), config.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp.toPath()); }
    }

    private static JSONObject merge(JSONObject baseline, JSONObject desired, JSONObject latest) throws JSONException {
        JSONObject result = new JSONObject(latest.toString());
        Set<String> keys = new LinkedHashSet<>();
        desired.keys().forEachRemaining(keys::add);
        if (baseline != null) baseline.keys().forEachRemaining(keys::add);
        for (String key : keys) {
            Object old = baseline == null ? null : baseline.opt(key);
            Object next = desired.opt(key);
            if (baseline != null && equal(old, next)) continue;
            if (!desired.has(key)) result.remove(key);
            else if (old instanceof JSONObject && next instanceof JSONObject && latest.opt(key) instanceof JSONObject) {
                result.put(key, merge((JSONObject) old, (JSONObject) next, latest.getJSONObject(key)));
            } else result.put(key, next);
        }
        return result;
    }

    private static boolean equal(Object a, Object b) throws JSONException {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x = (JSONObject) a, y = (JSONObject) b;
            if (x.length() != y.length()) return false;
            for (Iterator<String> it = x.keys(); it.hasNext();) {
                String key = it.next();
                if (!y.has(key) || !equal(x.get(key), y.get(key))) return false;
            }
            return true;
        }
        if (a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x = (JSONArray) a, y = (JSONArray) b;
            if (x.length() != y.length()) return false;
            for (int i = 0; i < x.length(); i++) if (!equal(x.get(i), y.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }
}
