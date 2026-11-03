/*
 * WoW Forever exec shim (GPL-3.0, part of this app). Preloaded into the Wine processes; replaces GameNative's closed
 * libredirect-bionic-wx-minimal.so.
 *
 * An app that targets API 29 or later may not execve() a file in its own data directory: SELinux denies execute_no_trans
 * on app_data_file. The system linker can still load such a program, so a denied exec of a dynamic ELF is retried as
 * "/system/bin/linker64 <program> <args>". The same policy denies execmod: a private file page that was written (Wine
 * relocates every PE image it maps) cannot become executable; such pages move to anonymous memory first. Under the
 * linker /proc/self/exe names the linker, so it is answered with the program instead.
 *
 * Written for this app after the approach documented by Bliss-Bass/GameNative-x64 (exec_redirect.c) and Droidtop
 * (exec-redirect.c), both GPL-3.0. Exec paths allocate nothing: they may run in a vfork()ed child.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "WowExec", __VA_ARGS__)
#else
#define LOG(...) do { fprintf(stderr, "WowExec: " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#define SELF_ENV "WOW_EXEC_SELF"
#define MAX_INTERPRETERS 4

extern char **environ;

typedef int (*execve_t)(const char *, char *const[], char *const[]);
typedef int (*spawn_t)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *, char *const[],
                       char *const[]);
typedef ssize_t (*readlink_t)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_t)(int, const char *, char *, size_t);
typedef char *(*realpath_t)(const char *, char *);
typedef int (*mprotect_t)(void *, size_t, int);
typedef void *(*mmap_t)(void *, size_t, int, int, int, off_t);

static void *next(const char *name) { return dlsym(RTLD_NEXT, name); }
#define REAL(type, name) static type real_##name; if (!real_##name) real_##name = (type)next(#name)

/* The linker that starts programs. Host tests point it at a stand-in. */
static const char *linker_path(bool elf32)
{
    const char *test = getenv("WOW_EXEC_LINKER");
    if (test && *test) return test;
    return elf32 ? "/system/bin/linker" : "/system/bin/linker64";
}

static bool is_linker(const char *path)
{
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    if (!strcmp(base, "linker64") || !strcmp(base, "linker")) return true;
    const char *test = getenv("WOW_EXEC_LINKER");
    return test && *test && !strcmp(path, test);
}

static bool system_image(const char *path)
{
    static const char *const roots[] = { "/system/", "/apex/", "/vendor/", "/product/", "/system_ext/", "/odm/" };
    for (size_t i = 0; i < sizeof(roots) / sizeof(roots[0]); i++)
        if (!strncmp(path, roots[i], strlen(roots[i]))) return true;
    return false;
}

enum kind { OTHER, DYNAMIC_ELF, STATIC_ELF, SCRIPT };

struct image {
    enum kind kind;
    bool elf32;
    char interpreter[PATH_MAX];
    char interpreter_arg[PATH_MAX];
};

static bool has_interp(int fd, bool elf32, const unsigned char *head, ssize_t n)
{
    if (elf32) {
        if (n < (ssize_t)sizeof(Elf32_Ehdr)) return false;
        Elf32_Ehdr eh; memcpy(&eh, head, sizeof(eh));
        for (unsigned i = 0; i < eh.e_phnum; i++) {
            Elf32_Phdr ph;
            if (pread(fd, &ph, sizeof(ph), (off_t)eh.e_phoff + (off_t)i * eh.e_phentsize) != (ssize_t)sizeof(ph)) return false;
            if (ph.p_type == PT_INTERP) return true;
        }
        return false;
    }
    if (n < (ssize_t)sizeof(Elf64_Ehdr)) return false;
    Elf64_Ehdr eh; memcpy(&eh, head, sizeof(eh));
    for (unsigned i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr ph;
        if (pread(fd, &ph, sizeof(ph), (off_t)eh.e_phoff + (off_t)i * eh.e_phentsize) != (ssize_t)sizeof(ph)) return false;
        if (ph.p_type == PT_INTERP) return true;
    }
    return false;
}

/* "#!interpreter [one argument]" on the first line. */
static void read_shebang(char *text, struct image *img)
{
    char *p = text + 2, *eol = strchr(p, '\n');
    if (!eol) return;
    *eol = 0;
    while (*p == ' ' || *p == '\t') p++;
    size_t len = strcspn(p, " \t\r");
    if (!len || len >= sizeof(img->interpreter)) return;
    memcpy(img->interpreter, p, len); img->interpreter[len] = 0;
    p += len;
    while (*p == ' ' || *p == '\t') p++;
    size_t arg = strlen(p);
    while (arg && (p[arg - 1] == ' ' || p[arg - 1] == '\t' || p[arg - 1] == '\r')) arg--;
    if (arg < sizeof(img->interpreter_arg)) { memcpy(img->interpreter_arg, p, arg); img->interpreter_arg[arg] = 0; }
    img->kind = SCRIPT;
}

static void inspect(const char *path, struct image *img)
{
    memset(img, 0, sizeof(*img));
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;
    unsigned char head[512];
    ssize_t n = pread(fd, head, sizeof(head) - 1, 0);
    if (n >= SELFMAG && !memcmp(head, ELFMAG, SELFMAG)) {
        img->elf32 = head[EI_CLASS] == ELFCLASS32;
        img->kind = has_interp(fd, img->elf32, head, n) ? DYNAMIC_ELF : STATIC_ELF;
    } else if (n >= 2 && head[0] == '#' && head[1] == '!') {
        head[n] = 0;
        read_shebang((char *)head, img);
    }
    close(fd);
}

static size_t count(char *const list[]) { size_t n = 0; if (list) while (list[n]) n++; return n; }

/* envp without an older SELF_ENV, plus SELF_ENV=<absolute program>; storage comes from the caller (no allocation). */
static void with_self(char *const envp[], const char *program, char **out, char *entry, size_t size)
{
    size_t j = 0, prefix = strlen(SELF_ENV "=");
    if (envp) for (size_t i = 0; envp[i]; i++) if (strncmp(envp[i], SELF_ENV "=", prefix)) out[j++] = envp[i];
    if (program[0] == '/') snprintf(entry, size, SELF_ENV "=%s", program);
    else {
        char cwd[PATH_MAX];
        if (getcwd(cwd, sizeof(cwd))) snprintf(entry, size, SELF_ENV "=%s/%s", cwd, program);
        else snprintf(entry, size, SELF_ENV "=%s", program);
    }
    out[j++] = entry;
    out[j] = NULL;
}

static int exec_image(const char *path, char *const argv[], char *const envp[], int depth);

static int exec_through_linker(const char *path, const struct image *img, char *const argv[], char *const envp[])
{
    REAL(execve_t, execve);
    const char *linker = linker_path(img->elf32);
    size_t argc = count(argv), envc = count(envp);
    char *args[argc + 3], *env[envc + 2], entry[PATH_MAX + 32];
    args[0] = (char *)linker;
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[(argc ? argc : 1) + 1] = NULL;
    with_self(envp, path, env, entry, sizeof(entry));
    LOG("exec %s through %s", path, linker);
    return real_execve(linker, args, env);
}

static int exec_through_interpreter(const char *path, const struct image *img, char *const argv[], char *const envp[], int depth)
{
    size_t argc = count(argv), j = 0;
    char *args[argc + 4];
    args[j++] = (char *)img->interpreter;
    if (img->interpreter_arg[0]) args[j++] = (char *)img->interpreter_arg;
    args[j++] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[j++] = argv[i];
    args[j] = NULL;
    return exec_image(img->interpreter, args, envp, depth + 1);
}

static int exec_image(const char *path, char *const argv[], char *const envp[], int depth)
{
    REAL(execve_t, execve);
    if (!real_execve) { errno = ENOSYS; return -1; }
    int result = real_execve(path, argv, envp);
    if (errno != EACCES || system_image(path) || depth > MAX_INTERPRETERS) return result;
    struct image img;
    inspect(path, &img);
    if (img.kind == DYNAMIC_ELF) exec_through_linker(path, &img, argv, envp);
    else if (img.kind == SCRIPT) exec_through_interpreter(path, &img, argv, envp, depth);
    /* A static program cannot run under the linker: keep the denial, so a caller can try its next candidate. */
    errno = EACCES;
    return -1;
}

int execve(const char *path, char *const argv[], char *const envp[]) { return exec_image(path, argv, envp, 0); }
int execv(const char *path, char *const argv[]) { return exec_image(path, argv, environ, 0); }

static int exec_search(const char *file, char *const argv[], char *const envp[])
{
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return exec_image(file, argv, envp, 0);
    const char *dirs = getenv("PATH");
    if (!dirs || !*dirs) dirs = "/system/bin";
    bool denied = false;
    for (const char *p = dirs;; ) {
        size_t len = strcspn(p, ":"), flen = strlen(file);
        char candidate[PATH_MAX];
        if (len && len + flen + 2 <= sizeof(candidate)) {
            memcpy(candidate, p, len); candidate[len] = '/'; memcpy(candidate + len + 1, file, flen + 1);
            exec_image(candidate, argv, envp, 0);
            if (errno == EACCES) denied = true;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!p[len]) break;
        p += len + 1;
    }
    errno = denied ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[]) { return exec_search(file, argv, environ); }
int execvpe(const char *file, char *const argv[], char *const envp[]) { return exec_search(file, argv, envp); }

/* The variadic exec forms gather their arguments on the stack. */
#define GATHER(first, list)                                                   \
    va_list ap; size_t n = 1;                                                 \
    va_start(ap, first); while (va_arg(ap, char *)) n++; va_end(ap);          \
    char *list[n + 1]; list[0] = (char *)(first);                             \
    va_start(ap, first); for (size_t i = 1; i <= n; i++) list[i] = va_arg(ap, char *)

int execl(const char *path, const char *arg0, ...) { GATHER(arg0, args); va_end(ap); return exec_image(path, args, environ, 0); }
int execlp(const char *file, const char *arg0, ...) { GATHER(arg0, args); va_end(ap); return exec_search(file, args, environ); }
int execle(const char *path, const char *arg0, ...)
{
    GATHER(arg0, args);
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    return exec_image(path, args, envp, 0);
}

/* posix_spawn reports a failed exec only through the child's exit, so programs outside the system start through the linker. */
static int spawn_image(spawn_t real, pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                       const posix_spawnattr_t *attr, char *const argv[], char *const envp[], int depth)
{
    if (!envp) envp = environ;
    if (system_image(path) || depth > MAX_INTERPRETERS) return real(pid, path, actions, attr, argv, envp);
    struct image img;
    inspect(path, &img);
    size_t argc = count(argv);
    if (img.kind == SCRIPT) {
        size_t j = 0;
        char *args[argc + 4];
        args[j++] = img.interpreter;
        if (img.interpreter_arg[0]) args[j++] = img.interpreter_arg;
        args[j++] = (char *)path;
        for (size_t i = 1; i < argc; i++) args[j++] = argv[i];
        args[j] = NULL;
        return spawn_image(real, pid, img.interpreter, actions, attr, args, envp, depth + 1);
    }
    if (img.kind != DYNAMIC_ELF) return real(pid, path, actions, attr, argv, envp);
    const char *linker = linker_path(img.elf32);
    size_t envc = count(envp);
    char *args[argc + 3], *env[envc + 2], entry[PATH_MAX + 32];
    args[0] = (char *)linker;
    args[1] = (char *)path;
    for (size_t i = 1; i < argc; i++) args[i + 1] = argv[i];
    args[(argc ? argc : 1) + 1] = NULL;
    with_self(envp, path, env, entry, sizeof(entry));
    LOG("spawn %s through %s", path, linker);
    return real(pid, linker, actions, attr, args, env);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                char *const argv[], char *const envp[])
{
    REAL(spawn_t, posix_spawn);
    if (!real_posix_spawn) return ENOSYS;
    return spawn_image(real_posix_spawn, pid, path, actions, attr, argv, envp, 0);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                 char *const argv[], char *const envp[])
{
    REAL(spawn_t, posix_spawn);
    if (!real_posix_spawn) return ENOSYS;
    if (!*file) return ENOENT;
    if (strchr(file, '/')) return spawn_image(real_posix_spawn, pid, file, actions, attr, argv, envp, 0);
    const char *dirs = getenv("PATH");
    if (!dirs || !*dirs) dirs = "/system/bin";
    for (const char *p = dirs;; ) {
        size_t len = strcspn(p, ":"), flen = strlen(file);
        char candidate[PATH_MAX];
        if (len && len + flen + 2 <= sizeof(candidate)) {
            memcpy(candidate, p, len); candidate[len] = '/'; memcpy(candidate + len + 1, file, flen + 1);
            if (!access(candidate, X_OK) || errno == EACCES)
                if (!access(candidate, F_OK)) return spawn_image(real_posix_spawn, pid, candidate, actions, attr, argv, envp, 0);
        }
        if (!p[len]) break;
        p += len + 1;
    }
    return ENOENT;
}

/*
 * /proc/self/exe under the linker names the linker. A child started here carries SELF_ENV; the first Wine process, which the
 * app itself starts as "linker64 <wine> ...", has its program as the second word of /proc/self/cmdline.
 */
static const char *self_program(char *buf, size_t size)
{
    char actual[PATH_MAX];
    ssize_t n = syscall(__NR_readlinkat, AT_FDCWD, "/proc/self/exe", actual, sizeof(actual) - 1);
    if (n <= 0) return NULL;
    actual[n] = 0;
    if (!is_linker(actual)) return NULL;
    const char *env = getenv(SELF_ENV);
    if (env && *env) return env;
    int fd = syscall(__NR_openat, AT_FDCWD, "/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return NULL;
    ssize_t got = read(fd, buf, size - 1);
    close(fd);
    if (got <= 0) return NULL;
    buf[got] = 0;
    size_t first = strlen(buf);
    if ((ssize_t)first + 1 >= got || !is_linker(buf)) return NULL;
    const char *program = buf + first + 1;
    return *program == '/' ? program : NULL;
}

static bool is_self_exe(const char *path) { return path && (!strcmp(path, "/proc/self/exe") || !strcmp(path, "/proc/thread-self/exe")); }

static ssize_t answer(const char *value, char *buf, size_t size)
{
    size_t len = strlen(value);
    if (len > size) len = size;
    memcpy(buf, value, len);
    return (ssize_t)len;
}

ssize_t readlink(const char *path, char *buf, size_t size)
{
    char scratch[PATH_MAX];
    if (is_self_exe(path)) { const char *p = self_program(scratch, sizeof(scratch)); if (p) return answer(p, buf, size); }
    REAL(readlink_t, readlink);
    return real_readlink ? real_readlink(path, buf, size) : (errno = ENOSYS, -1);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size)
{
    char scratch[PATH_MAX];
    if (is_self_exe(path)) { const char *p = self_program(scratch, sizeof(scratch)); if (p) return answer(p, buf, size); }
    REAL(readlinkat_t, readlinkat);
    return real_readlinkat ? real_readlinkat(dirfd, path, buf, size) : (errno = ENOSYS, -1);
}

char *realpath(const char *path, char *resolved)
{
    REAL(realpath_t, realpath);
    if (!real_realpath) { errno = ENOSYS; return NULL; }
    char scratch[PATH_MAX];
    if (is_self_exe(path)) { const char *p = self_program(scratch, sizeof(scratch)); if (p) return real_realpath(p, resolved); }
    return real_realpath(path, resolved);
}

/* True when no mapping overlapping [start, end) is shared: only private pages may be replaced by anonymous copies. */
static bool all_private(uintptr_t start, uintptr_t end)
{
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[8192];
    size_t have = 0;
    bool shared = false, more = true;
    while (more) {
        ssize_t n = read(fd, buf + have, sizeof(buf) - 1 - have);
        if (n <= 0) more = false; else have += (size_t)n;
        buf[have] = 0;
        char *line = buf, *eol;
        while ((eol = memchr(line, '\n', (size_t)(buf + have - line))) || (!more && line < buf + have)) {
            if (eol) *eol = 0;
            char *p;
            uintptr_t lo = strtoull(line, &p, 16), hi = *p == '-' ? strtoull(p + 1, &p, 16) : 0;
            while (*p == ' ') p++;
            if (lo < end && hi > start && strlen(p) >= 4 && p[3] != 'p') shared = true;
            if (!eol) { line = buf + have; break; }
            line = eol + 1;
        }
        size_t rest = (size_t)(buf + have - line);
        if (rest == sizeof(buf) - 1) rest = 0;
        memmove(buf, line, rest);
        have = rest;
    }
    close(fd);
    return !shared;
}

static int remap_count;

int mprotect(void *addr, size_t len, int prot)
{
    REAL(mprotect_t, mprotect);
    if (!real_mprotect) { errno = ENOSYS; return -1; }
    int result = real_mprotect(addr, len, prot);
    if (!result || errno != EACCES || !(prot & PROT_EXEC) || !len) return result;
    /* execmod denied: copy the pages into anonymous memory at the same place, then make that executable. */
    uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)addr & ~(page - 1), end = ((uintptr_t)addr + len + page - 1) & ~(page - 1);
    size_t span = end - start;
    if (!all_private(start, end) || real_mprotect((void *)start, span, PROT_READ)) { errno = EACCES; return -1; }
    void *copy = mmap(NULL, span, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) { errno = EACCES; return -1; }
    memcpy(copy, (void *)start, span);
    void *fresh = mmap((void *)start, span, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0);
    if (fresh == MAP_FAILED) { LOG("remap %p+%zx failed: %d", (void *)start, span, errno); munmap(copy, span); errno = EACCES; return -1; }
    memcpy(fresh, copy, span);
    munmap(copy, span);
    if (remap_count++ < 16) LOG("execmod: %p+%zx moved to anonymous memory", (void *)start, span);
    return real_mprotect((void *)start, span, prot);
}

/* An executable mapping of a file the policy will not map executable: read the bytes into anonymous memory instead. */
static void *exec_mapping_fallback(mmap_t real, void *addr, size_t len, int prot, int flags, int fd, off_t offset)
{
    void *result = real(addr, len, prot, flags, fd, offset);
    if (result != MAP_FAILED || errno != EACCES || !(prot & PROT_EXEC) || fd < 0 || (flags & MAP_SHARED)) return result;
    int anon = (flags & ~(MAP_SHARED | MAP_PRIVATE)) | MAP_PRIVATE | MAP_ANONYMOUS;
    void *mem = real(addr, len, PROT_READ | PROT_WRITE, anon, -1, 0);
    if (mem == MAP_FAILED) { errno = EACCES; return MAP_FAILED; }
    size_t done = 0;
    while (done < len) {
        ssize_t n = pread(fd, (char *)mem + done, len - done, offset + (off_t)done);
        if (n <= 0) break;   /* past the end of the file the pages stay zero, as a file mapping would read */
        done += (size_t)n;
    }
    REAL(mprotect_t, mprotect);
    if ((real_mprotect ? real_mprotect : mprotect)(mem, len, prot)) { munmap(mem, len); errno = EACCES; return MAP_FAILED; }
    LOG("exec mapping of fd %d read into anonymous memory (%zu bytes)", fd, len);
    return mem;
}

void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t offset)
{
    REAL(mmap_t, mmap);
    if (!real_mmap) { errno = ENOSYS; return MAP_FAILED; }
    return exec_mapping_fallback(real_mmap, addr, len, prot, flags, fd, offset);
}

#ifdef __ANDROID__
void *mmap64(void *addr, size_t len, int prot, int flags, int fd, off64_t offset)
{
    REAL(mmap_t, mmap64);
    if (!real_mmap64) { errno = ENOSYS; return MAP_FAILED; }
    return exec_mapping_fallback(real_mmap64, addr, len, prot, flags, fd, (off_t)offset);
}
#endif
