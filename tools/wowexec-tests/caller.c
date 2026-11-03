/* Starts argv[2] through the exec form named by argv[1]; prints the errno when the call returns. */
#include <errno.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
extern char **environ;
int main(int argc, char **argv)
{
    char *args[] = { argv[2], "first", "second", NULL };
    if (!strcmp(argv[1], "spawn")) {
        pid_t pid; int rc = posix_spawn(&pid, argv[2], NULL, NULL, args, environ);
        if (rc) { printf("spawn_error=%d\n", rc); return 1; }
        int status; waitpid(pid, &status, 0);
        printf("spawn_exit=%d\n", WEXITSTATUS(status));
        return 0;
    }
    execve(argv[2], args, environ);
    printf("execve_errno=%d\n", errno);
    return 1;
}
