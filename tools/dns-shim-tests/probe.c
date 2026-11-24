/* Host probe for gamenative_dns_v4mapped.c (loaded with LD_PRELOAD). Exit 0 = every check passed. */
#define _GNU_SOURCE
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static int fails = 0;
static void check(int ok, const char *what) { if (!ok) { fprintf(stderr, "FAIL %s\n", what); fails++; } }
int main(void) {
    const char *want = getenv("GAMENATIVE_HOSTNAME");
    char name[256] = {0};
    check(gethostname(name, sizeof name) == 0, "gethostname returns 0");
    if (want) {
        check(strcmp(name, want) == 0, "gethostname reports GAMENATIVE_HOSTNAME");
        struct hostent *h = gethostbyname(want);
        check(h != NULL, "gethostbyname resolves the configured name");
        if (h) check(strcmp(h->h_name, want) == 0, "gethostbyname h_name is the configured name");
        struct hostent he, *res = NULL; char buf[4096]; int err = 0;
        check(gethostbyname_r(want, &he, buf, sizeof buf, &res, &err) == 0 && res, "gethostbyname_r resolves it");
        if (res) check(strcmp(res->h_name, want) == 0, "gethostbyname_r h_name is the configured name");
        struct addrinfo hints = {0}, *ai = NULL; hints.ai_flags = AI_CANONNAME; hints.ai_family = AF_INET;
        check(getaddrinfo(want, NULL, &hints, &ai) == 0 && ai, "getaddrinfo resolves the configured name");
        if (ai) { check(ai->ai_canonname && strcmp(ai->ai_canonname, want) == 0, "getaddrinfo canonical name"); freeaddrinfo(ai); }
    } else {
        check(strlen(name) > 0, "without GAMENATIVE_HOSTNAME a name is still reported");
    }
    struct hostent *local = gethostbyname("localhost");
    check(local != NULL, "other lookups pass straight through");
    printf("hostname=%s fails=%d\n", name, fails);
    return fails ? 1 : 0;
}
