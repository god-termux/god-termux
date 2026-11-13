// Workload for the fhs-run supervisor: raw system calls only, so no LD_PRELOAD hook is involved.
#define _GNU_SOURCE
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
static double seconds(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
int main(int argc, char** argv) {
  int n = argc > 1 ? atoi(argv[1]) : 20000; struct stat st; long ok = 0; char many[512][64];
  for (int i = 0; i < 512; i++) snprintf(many[i], sizeof many[i], "/usr/share/fhs-sup-benchmark-%d", i);
  for (int round = 0; round < 3; round++) {
    double t = seconds();
    for (int i = 0; i < n; i++) ok += syscall(SYS_newfstatat, AT_FDCWD, "/usr/bin/sh", &st, 0) == 0;
    printf("stat_mapped %.9f\n", seconds() - t); t = seconds();
    for (int i = 0; i < n; i++) ok += syscall(SYS_newfstatat, AT_FDCWD, "/data/data/com.termux/files/usr/bin/sh", &st, 0) == 0;
    printf("stat_passthrough %.9f\n", seconds() - t); t = seconds();
    for (int i = 0; i < n; i++) ok += syscall(SYS_newfstatat, AT_FDCWD, ".", &st, 0) == 0;
    printf("stat_relative %.9f\n", seconds() - t); t = seconds();
    for (int i = 0; i < n; i++) { int fd = syscall(SYS_openat, AT_FDCWD, "/usr/bin/sh", O_RDONLY | O_CLOEXEC, 0); if (fd >= 0) { ok++; close(fd); } }
    printf("open_mapped %.9f\n", seconds() - t); t = seconds();
    for (int i = 0; i < n; i++) ok += syscall(SYS_newfstatat, AT_FDCWD, many[i & 511], &st, 0) != 0;
    printf("stat_many_missing %.9f\n", seconds() - t);
  }
  printf("ok %ld\n", ok);
  return ok != 15L * n;
}
