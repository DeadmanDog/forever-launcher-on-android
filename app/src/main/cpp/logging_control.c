#include <jni.h>
#include <dlfcn.h>

/* Covers prebuilt libraries loaded into the Android app process on API 30+.
 * Source-owned native calls are additionally compiled out by logging_disabled.h.
 * Avoid redirecting stdio: Developer shell output uses separate child pipes. */
static void discard_log(const void *message) { (void)message; }
JNIEXPORT void JNICALL
Java_app_gamenative_utils_AppLogging_disableNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    void (*set_logger)(void (*)(const void *)) = dlsym(RTLD_DEFAULT, "__android_log_set_logger");
    if (set_logger) set_logger(discard_log);
    int (*set_priority)(int) = dlsym(RTLD_DEFAULT, "__android_log_set_minimum_priority");
    if (set_priority) set_priority(8); /* ANDROID_LOG_SILENT */
}

JNIEXPORT void JNICALL
Java_app_gamenative_utils_AppLogging_configureNative(JNIEnv *env, jclass cls, jboolean enabled, jboolean detailed) {
    (void)env; (void)cls;
    void (*set_logger)(void (*)(const void *)) = dlsym(RTLD_DEFAULT, "__android_log_set_logger");
    void (*logd_logger)(const void *) = dlsym(RTLD_DEFAULT, "__android_log_logd_logger");
    int (*set_priority)(int) = dlsym(RTLD_DEFAULT, "__android_log_set_minimum_priority");
    if (set_logger && (!enabled || logd_logger)) set_logger(enabled ? logd_logger : discard_log);
    if (set_priority) set_priority(enabled ? (detailed ? 3 : 5) : 8);
}
