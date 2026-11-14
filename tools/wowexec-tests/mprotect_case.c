/* Asks for PROT_READ|PROT_EXEC on two private pages that were read-write and prints what happened:
 *   result=<mprotect rc> errno=<errno> prot=<rwx of the pages afterwards, from /proc/self/maps> data=<ok|changed> write=<ok>
 * The write probe runs last: on pages left read-only it crashes (the caller sees the signal). */
#define _GNU_SOURCE
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

static void prot_of(uintptr_t addr, char out[4])
{
    strcpy(out, "???");
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return;
    char line[512];
    while (fgets(line, sizeof line, f)) {
        unsigned long lo, hi; char perms[8];
        if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) == 3 && addr >= lo && addr < hi) { memcpy(out, perms, 3); out[3] = 0; break; }
    }
    fclose(f);
}

int main(void)
{
    size_t page = (size_t)sysconf(_SC_PAGESIZE), len = 2 * page;
    unsigned char *mem = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) return 3;
    for (size_t i = 0; i < len; i++) mem[i] = (unsigned char)(i * 7);
    errno = 0;
    int rc = mprotect(mem, len, PROT_READ | PROT_EXEC);
    int err = rc ? errno : 0;
    char prot[4]; prot_of((uintptr_t)mem, prot);
    int same = 1;
    for (size_t i = 0; i < len; i++) if (mem[i] != (unsigned char)(i * 7)) { same = 0; break; }
    printf("result=%d errno=%d prot=%s data=%s\n", rc, err, prot, same ? "ok" : "changed");
    fflush(stdout);
    if (prot[1] == 'w') { mem[0] = 1; mem[len - 1] = 2; printf("write=ok\n"); }
    return 0;
}
