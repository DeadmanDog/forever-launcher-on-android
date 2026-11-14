/* Stand-in for the system's memory calls, preloaded AFTER the shim: the shim's RTLD_NEXT lookups land here.
 * WOW_TEST_DENY_EXEC=1  : mprotect with PROT_EXEC fails with EACCES (what SELinux execmod does to file pages on Android)
 * WOW_TEST_FAIL_FIXED=1 : a MAP_FIXED anonymous mmap fails (the remap step of the shim's fallback)
 * WOW_TEST_FAIL_ALL_EXEC=1 : every PROT_EXEC mprotect fails, also on the anonymous copy (the last step of the fallback) */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <sys/mman.h>

typedef int (*mprotect_fn)(void *, size_t, int);
typedef void *(*mmap_fn)(void *, size_t, int, int, int, off_t);
static int flag(const char *name) { const char *v = getenv(name); return v && *v == '1'; }
static int exec_calls;

int mprotect(void *addr, size_t len, int prot)
{
    static mprotect_fn real;
    if (!real) real = (mprotect_fn)dlsym(RTLD_NEXT, "mprotect");
    if ((prot & PROT_EXEC) && flag("WOW_TEST_DENY_EXEC")) {
        /* The first exec request is the caller's own (denied like execmod); a later one is the shim's on its anonymous copy. */
        if (exec_calls++ == 0 || flag("WOW_TEST_FAIL_ALL_EXEC")) { errno = EACCES; return -1; }
    }
    return real(addr, len, prot);
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t offset)
{
    static mmap_fn real;
    if (!real) real = (mmap_fn)dlsym(RTLD_NEXT, "mmap");
    if ((flags & MAP_FIXED) && (flags & MAP_ANONYMOUS) && flag("WOW_TEST_FAIL_FIXED")) { errno = ENOMEM; return MAP_FAILED; }
    return real(addr, len, prot, flags, fd, offset);
}
