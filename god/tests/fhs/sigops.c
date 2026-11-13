// A signal handler that starts while the main program is inside a fast-path call must still get its own libc-internal path
// calls translated by the supervisor. (With the marker of ABI 1 in register x5 the handler inherited the marker.)
#define _GNU_SOURCE
#include <dirent.h>
#include <signal.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <time.h>
static volatile long runs, untranslated;
static void handler(int sig) { runs++; DIR* d = opendir("/usr/bin"); if (d) closedir(d); else untranslated++; }   // /usr/bin exists only through translation
int main(void) {
  struct sigaction sa = {0}; sa.sa_handler = handler; sigaction(SIGALRM, &sa, NULL);
  struct itimerval it = {{0, 150}, {0, 150}}; setitimer(ITIMER_REAL, &it, NULL);
  struct timespec start, now; clock_gettime(CLOCK_MONOTONIC, &start); struct stat st; long calls = 0, bad = 0;
  do { for (int i = 0; i < 200; i++) { calls++; bad += stat("/usr/bin/sh", &st) != 0; } clock_gettime(CLOCK_MONOTONIC, &now); }
  while ((now.tv_sec - start.tv_sec) * 1000 + (now.tv_nsec - start.tv_nsec) / 1000000 < 1500);
  struct itimerval off = {{0, 0}, {0, 0}}; setitimer(ITIMER_REAL, &off, NULL);
  printf("%ld %ld %ld %ld\n", calls, bad, (long)runs, (long)untranslated);
  return 0;
}
