#define _GNU_SOURCE
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include "fhsmap.h"
static double seconds(void) {
  struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9;
}
int main(void) {
  char paths[16][320], out[PATH_MAX]; volatile unsigned checksum = 0;
  const char* names[] = {"repeat", "round_robin", "long_repeat", "passthrough"};
  for (int mode = 0; mode < 4; mode++) {
    for (int i = 0; i < 16; i++) {
      if (mode == 0) strcpy(paths[i], "/usr/bin/sh");
      if (mode == 1) snprintf(paths[i], sizeof paths[i], "/usr/share/fhs-cache-benchmark-%d", i);
      if (mode == 2) { strcpy(paths[i], "/usr/share/"); memset(paths[i] + 11, 'a', 250); paths[i][261] = 0; }
      if (mode == 3) snprintf(paths[i], sizeof paths[i], "/data/data/com.termux/files/home/test-%d", i);
    }
    for (int j = 0; j < 5; j++) {
      fhs_cache_clear();
      for (int i = 0; i < 16; i++) fmap(paths[i], out);
      double start = seconds();
      for (int i = 0; i < 100000; i++) { const char* p = fmap(paths[i & 15], out); checksum += (unsigned char)p[0]; }
      printf("%s %.9f\n", names[mode], seconds() - start);
    }
  }
  // Misses: every call probes the filesystem. An existing name (first candidate) and names found in no tree.
  for (int mode = 0; mode < 2; mode++) for (int j = 0; j < 5; j++) {
    double start = seconds();
    for (int i = 0; i < 100000; i++) {
      char q[64]; const char* in = "/usr/bin/sh"; if (mode) { memcpy(q, "/usr/share/fhs-miss-00000", 26); for (int k = 24, v = i; v; v /= 10) q[k--] = '0' + v % 10; in = q; }
      fhs_cache_clear(); const char* p = fmap(in, out); checksum += (unsigned char)p[0];
    }
    printf("%s %.9f\n", mode ? "miss_absent" : "miss_present", seconds() - start);
  }
  return checksum == 0;
}
