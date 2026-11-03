#ifndef WOW_LOGGING_DISABLED_H
#define WOW_LOGGING_DISABLED_H
/* Compile out diagnostic calls and their arguments. */
#define __android_log_print(...) (0)
#define __android_log_write(...) (0)
#define __android_log_buf_print(...) (0)
#define __android_log_buf_write(...) (0)
#define __android_log_vprint(...) (0)
#endif
