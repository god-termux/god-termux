// supnew.c: fhs-run supervisor behaviour that supops.c does not cover: concurrency, umask of the traced program,
// size of the structures copied back (stat, statx), and many threads at once. Run by supnew.py under fhs-run.
//   supnew TEST SCRATCH     TEST: concurrent | umask | statsize | stress ; SCRATCH: directory name under /tmp
// All paths go through the FHS name /tmp/SCRATCH, which only the supervisor can resolve.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <linux/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

static char scratch[256];
static double now_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1e3 + t.tv_nsec / 1e6; }
static void path(char* out, size_t n, const char* name) { snprintf(out, n, "/tmp/%s/%s", scratch, name); }

// ---- concurrent: one thread blocks the supervisor inside open() of a FIFO; another thread's stat must not wait for it.
// The harness (supnew.py) opens the FIFO for writing after 3 s, which releases a blocked supervisor.
static void* open_fifo(void* unused) { (void)unused; char p[512]; path(p, sizeof p, "fifo"); int f = open(p, O_RDONLY); if (f >= 0) close(f); return NULL; }
static int t_concurrent(void) {
  pthread_t th; pthread_create(&th, NULL, open_fifo, NULL);
  usleep(300000);   // let the thread reach the supervisor and block there
  char p[512]; path(p, sizeof p, ""); struct stat st; double t0 = now_ms();
  int rc = stat(p, &st); double ms = now_ms() - t0;
  printf("concurrent_stat_rc %d\nconcurrent_stat_ms %.0f\n", rc, ms);
  pthread_join(th, NULL); printf("concurrent_done 1\n"); return 0;
}

// ---- umask: the traced program sets its own umask; creation through the supervisor must obey it, not the supervisor's.
static void mode_of(const char* label, const char* name) {
  char p[512]; path(p, sizeof p, name); struct stat st;
  if (stat(p, &st)) printf("%s errno=%d\n", label, errno); else printf("%s %04o\n", label, st.st_mode & 07777);
}
static int t_umask(void) {
  char p[512];
  umask(0);
  path(p, sizeof p, "file_u0"); int f = open(p, O_CREAT | O_WRONLY, 0666); if (f >= 0) close(f);
  path(p, sizeof p, "dir_u0"); mkdir(p, 0777);
  umask(077);
  path(p, sizeof p, "file_u077"); f = open(p, O_CREAT | O_WRONLY, 0666); if (f >= 0) close(f);
  path(p, sizeof p, "dir_u077"); mkdir(p, 0777);
  umask(022);
  path(p, sizeof p, "file_u022"); f = open(p, O_CREAT | O_WRONLY, 0664); if (f >= 0) close(f);
  mode_of("umask0_file", "file_u0"); mode_of("umask0_dir", "dir_u0");
  mode_of("umask077_file", "file_u077"); mode_of("umask077_dir", "dir_u077");
  mode_of("umask022_file664", "file_u022");
  return 0;
}

// ---- statsize: the supervisor writes the kernel's structure into the program's buffer; nothing past its size may be touched,
// and every field must arrive. The file "data" (12345 bytes) is created by the harness.
static int t_statsize(void) {
  char p[512]; path(p, sizeof p, "data");
  struct { struct stat st; unsigned char guard[64]; } b; memset(&b, 0, sizeof b); memset(b.guard, 0xAA, sizeof b.guard);
  int rc = stat(p, &b.st); int intact = 1; for (size_t i = 0; i < sizeof b.guard; i++) if (b.guard[i] != 0xAA) intact = 0;
  printf("stat_rc %d\nstat_size %lld\nstat_regular %d\nstat_nlink %lu\nstat_guard_intact %d\n", rc, (long long)b.st.st_size, S_ISREG(b.st.st_mode), (unsigned long)b.st.st_nlink, intact);
  struct stat direct; char real[512]; const char* rp = getenv("SUPNEW_REAL"); snprintf(real, sizeof real, "%s", rp ? rp : ""); real[sizeof real - 1] = 0;
  if (real[0]) { strncat(real, "/data", sizeof real - strlen(real) - 1);
    int r2 = syscall(__NR_newfstatat, AT_FDCWD, real, &direct, 0);   // not translated: the path is already the real one
    printf("stat_equals_direct %d\n", r2 == 0 && !memcmp(&direct, &b.st, sizeof direct)); }
  struct { struct statx sx; unsigned char guard[64]; } x; memset(&x, 0, sizeof x); memset(x.guard, 0xAA, sizeof x.guard);
  rc = syscall(__NR_statx, AT_FDCWD, p, 0, STATX_BASIC_STATS, &x.sx); intact = 1; for (size_t i = 0; i < sizeof x.guard; i++) if (x.guard[i] != 0xAA) intact = 0;
  printf("statx_rc %d\nstatx_size %llu\nstatx_guard_intact %d\n", rc, (unsigned long long)x.sx.stx_size, intact);
  return 0;
}

// ---- stress: several threads create, stat, check and remove their own files through the supervisor at the same time.
#define THREADS 8
#define ROUNDS 300
static volatile int errors;
static void* worker(void* arg) {
  long id = (long)arg; char name[64], p[512];
  for (int n = 0; n < ROUNDS; n++) {
    snprintf(name, sizeof name, "t%ld_%d", id, n); path(p, sizeof p, name); struct stat st;
    int f = open(p, O_CREAT | O_WRONLY | O_EXCL, 0600); if (f < 0) { __sync_fetch_and_add(&errors, 1); continue; }
    if (write(f, "x", 1) != 1) __sync_fetch_and_add(&errors, 1);
    close(f);
    if (stat(p, &st) || st.st_size != 1) __sync_fetch_and_add(&errors, 1);
    if (access(p, R_OK)) __sync_fetch_and_add(&errors, 1);
    path(p, sizeof p, "nonexistent_name"); if (!stat(p, &st) || errno != ENOENT) __sync_fetch_and_add(&errors, 1);
    path(p, sizeof p, name); if (unlink(p)) __sync_fetch_and_add(&errors, 1);
    if (!stat(p, &st)) __sync_fetch_and_add(&errors, 1);
  }
  return NULL;
}
static int t_stress(void) {
  pthread_t th[THREADS]; double t0 = now_ms();
  for (long i = 0; i < THREADS; i++) pthread_create(&th[i], NULL, worker, (void*)i);
  for (int i = 0; i < THREADS; i++) pthread_join(th[i], NULL);
  printf("stress_ops %d\nstress_errors %d\nstress_ms %.0f\n", THREADS * ROUNDS * 7, errors, now_ms() - t0);
  return 0;
}

// The scenarios must exercise the supervisor, not the in-process shim: report whether the shim is mapped into this program.
static int shim_mapped(void) { FILE* f = fopen("/proc/self/maps", "r"); if (!f) return -1; char l[1024]; int r = 0; while (fgets(l, sizeof l, f)) if (strstr(l, "libfhs-")) { r = 1; break; } fclose(f); return r; }

int main(int argc, char** argv) {
  if (argc < 3) { fprintf(stderr, "usage: supnew TEST SCRATCH\n"); return 2; }
  snprintf(scratch, sizeof scratch, "%s", argv[2]); setvbuf(stdout, NULL, _IOLBF, 0); printf("shim_loaded %d\n", shim_mapped());
  if (!strcmp(argv[1], "concurrent")) return t_concurrent();
  if (!strcmp(argv[1], "umask")) return t_umask();
  if (!strcmp(argv[1], "statsize")) return t_statsize();
  if (!strcmp(argv[1], "stress")) return t_stress();
  fprintf(stderr, "unknown test %s\n", argv[1]); return 2;
}
