/* Test-only Linux harness for the same fd-based native entry point as Android.
 * This is not an Android binary and does not alter the product library. */
#include <stdlib.h>
#include <stdio.h>

extern int hev_socks5_tunnel_main_from_file(const char *, int);

int main(int argc, char **argv) {
    char *end = NULL;
    long fd;
    if (argc != 3) {
        fprintf(stderr, "usage: hev_fd_probe CONFIG FD\n");
        return 2;
    }
    fd = strtol(argv[2], &end, 10);
    if (!*argv[2] || *end || fd < 0 || fd > 1048576) return 2;
    return hev_socks5_tunnel_main_from_file(argv[1], (int)fd) == 0 ? 0 : 1;
}
