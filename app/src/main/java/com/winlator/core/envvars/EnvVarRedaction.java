package com.winlator.core.envvars;

import androidx.annotation.NonNull;

/**
 * Launch logs get shared for support, so the values of these variables are masked wherever an
 * environment is logged. Add any new credential or account identifier here.
 */
public final class EnvVarRedaction {
    private static final java.util.regex.Pattern SECRET_NAME = java.util.regex.Pattern.compile(
        "token|password|passwd|secret|credential|api.?key|cookie|authorization", java.util.regex.Pattern.CASE_INSENSITIVE);

    private EnvVarRedaction() {}

    public static boolean isRedacted(String name) {
        return SECRET_NAME.matcher(name).find();
    }

    @NonNull
    public static String redact(EnvVars envVars) {
        StringBuilder sb = new StringBuilder();
        for (String key : envVars) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(key).append('=');
            sb.append(isRedacted(key) ? "<redacted>" : envVars.get(key));
        }
        return sb.toString();
    }

    @NonNull
    public static String redact(String envVars) {
        return redact(new EnvVars(envVars));
    }
}
