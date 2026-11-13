#define _GNU_SOURCE
#include <stdlib.h>
#include <assert.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <time.h>
static unsigned long formats, probes;
#define snprintf(...) (formats++, snprintf(__VA_ARGS__))
#define syscall(...) (probes++, syscall(__VA_ARGS__))
#include "fhsmap.h"
#undef snprintf
#undef syscall
static double seconds(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9;
}
int main(void) {
  // Names present in only one tree on the test phone are included on purpose (glibc-only, Termux-only, Debian-only), together with
  // prefixes that merely look like a mapped directory. A name missing on another device still compares equal: both versions miss.
  static const char* fixed[] = {"relative", "", "/", "/proc/self/exe", "/system/bin/sh", "/data/a", "/unknown/a",
    "/usr/bin/sh", "/bin/sh", "/usr/lib/libc.so.6", "/lib64/libc.so.6", "/usr/share/mime", "/etc/passwd",
    "/tmp", "/var/lib/dpkg/status", "/opt/missing-fhs-test", "/usr/missing-fhs-test",
    "/usr/bin/aarch64-linux-gnu-c++", "/bin/aarch64-linux-gnu-c++", "/usr/sbin/aarch64-linux-gnu-c++", "/sbin/sh", "/usr/sbin/missing-fhs-test",
    "/usr/lib/GraphicsMagick-1.3.49", "/lib/GraphicsMagick-1.3.49", "/usr/lib64/GraphicsMagick-1.3.49", "/lib64/missing-fhs-test",
    "/usr/bin/X11", "/usr/lib/aarch64-linux-gnu/Thunar", "/lib/aarch64-linux-gnu/Thunar", "/etc/X11", "/usr/share/gcc-14.2.1",
    "/usr", "/usr/", "/lib", "/lib/", "/lib64", "/bin", "/bin/", "/sbin", "/var", "/opt", "/etc", "/usr/lib", "/usr/lib/", "/usr/bin/",
    "/usr/libexec", "/usr/libexec/missing-fhs-test", "/usr/local/bin/missing-fhs-test", "/usr/binx/sh", "/usr/lib64x", "/usr/libx/a",
    "/libx", "/binx/sh", "/sbinx", "/etcx", "/tmpx", "/varx", "/optx", "/usrx/bin/sh", "/tmp/missing-fhs-test",
    "/etc/missing-fhs-dir/file", "/etc/X11/missing-fhs-file", "/usr/bin/missing-fhs-file", "/usr/lib/missing-fhs-dir/file", NULL};
  // Lengths around the PATH_MAX truncation point of every candidate prefix, and the longest possible input.
  static char longs[40][PATH_MAX]; int nl = 0; char out[PATH_MAX];
  static const char* heads[] = {"/usr/", "/usr/bin/", "/usr/lib64/", "/lib/", "/etc/", "/tmp/"};
  static const int sizes[] = {505, 3990, 4020, 4040, 4060, 4095};
  for (int a = 0; a < 6; a++) for (int b = 0; b < 6; b++) {
    char* q = longs[nl++]; size_t hl = strlen(heads[a]); memcpy(q, heads[a], hl); memset(q + hl, 'x', sizes[b] - hl); q[sizes[b]] = 0; }
  const char* paths[128]; int np = 0;
  for (int i = 0; fixed[i]; i++) paths[np++] = fixed[i];
  for (int i = 0; i < nl; i++) paths[np++] = longs[i];
  for (int deb = 0; deb <= 1; deb++) for (int create = 0; create <= 1; create++) {
    fhs_deb = deb; fhs_create = create; fhs_cache_clear();
    for (int i = 0; i < np; i++) {
      const char* p = paths[i];
      for (int mode = 0; mode < 4; mode++) {
        if (mode == 2) fhs_cache_clear();
        if (mode == 3) for (int j = 0; j < FHS_CN; j++) fhs_ctab[j].t -= FHS_TTL_MS + 1;
        const char* m = fmap(p, out);
        printf("%d %d %d %d %d %s\n", deb, create, i, mode, m == p, m);
      }
    }
  }
  fhs_deb = 0; fhs_create = 0; fhs_cache_clear();
  // Reuse one input buffer for different strings and overflow the direct-mapped
  // table. This catches pointer-identity shortcuts and stale MRU slot contents.
  char changing[128];
  for (int pass = 0; pass < 2; pass++) for (int i = 0; i < 256; i++) {
    snprintf(changing, sizeof changing, "/usr/share/fhs-cache-test-%d", pass ? 255 - i : i);
    printf("CHURN %s\n", fmap(changing, out));
  }
  fmap("/usr/bin/sh", out);
  fhs_cache_clear(); probes = 0; fmap("/usr/bin/sh", out); assert(probes > 0);
  for (int i = 0; i < FHS_CN; i++) fhs_ctab[i].t -= FHS_TTL_MS + 1;
  probes = 0; fmap("/usr/bin/sh", out); assert(probes > 0);
  fhs_create = 1; probes = 0; fmap("/usr/bin/sh", out); assert(probes > 0); fhs_create = 0;
  fmap("/usr/bin/sh", out); formats = probes = 0;
  fmap("/usr/bin/sh", out);
  printf("COUNTERS %lu %lu\n", formats, probes);
  // Several samples; the driver compares medians, not a single fastest run.
  for (int j = 0; j < 5; j++) {
    double start = seconds();
    for (int i = 0; i < 200000; i++) fmap("/usr/bin/sh", out);
    printf("BENCH %.9f\n", seconds() - start);
  }
}
