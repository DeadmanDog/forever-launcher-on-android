/* Stands in for /system/bin/linker64 in host tests: records how it was started and what /proc/self/exe reads. */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv)
{
    FILE *out = fopen(getenv("WOW_TEST_OUT"), "a");
    for (int i = 0; i < argc; i++) fprintf(out, "arg%d=%s\n", i, argv[i]);
    const char *self = getenv("WOW_EXEC_SELF");
    fprintf(out, "self_env=%s\n", self ? self : "");
    char exe[4096]; ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    exe[n > 0 ? n : 0] = 0;
    fprintf(out, "proc_self_exe=%s\n", exe);
    fclose(out);
    return 0;
}
