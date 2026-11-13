// libfhs: FHS path emulation + transparent glibc exec for Termux. Build twice: -DGLIBC_BUILD (glibc world) / bionic.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <elf.h>
#include <limits.h>
#include <spawn.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <dirent.h>
#include <signal.h>

#define LDSO GLIB "/lib/ld-linux-aarch64.so.1"
#define SHIM_G GLIB "/shim/libfhs-glibc.so"
#define SHIM_B GLIB "/shim/libfhs-bionic.so"
extern char** environ;

#define FHS_TLS __thread     // fhs_create is set around single lookups by adapters that run on any thread
#define FHS_BIONIC_FAST      // fhsmap.h: path probes use the fast path too (both builds, despite the name)
#include "fhs-fast.h"
__attribute__((constructor)) static void fhs_shim_init(void) { fhs_fast_init(); }
#include "fhsmap.h"
// ------------------------------------------------------------------ exec logic
// At most four shebang expansions (four allocations each), then argv/env.
// Borrow caller strings; release only buffers owned by this preparation.
struct prep_mem { void* owned[24]; unsigned count; char path[PATH_MAX]; };
static void* prep_alloc(struct prep_mem* m, size_t size) {
  if (m->count == sizeof m->owned / sizeof m->owned[0]) { errno = ENOMEM; return NULL; }
  void* p = malloc(size); if (p) m->owned[m->count++] = p;
  return p;
}
static char* prep_dup(struct prep_mem* m, const char* s) {
  size_t n = strlen(s) + 1; char* p = prep_alloc(m, n); if (p) memcpy(p, s, n); return p;
}
static void prep_free(struct prep_mem* m) {
  int e = errno; while (m->count) free(m->owned[--m->count]); errno = e;
}
static char** envcopy(struct prep_mem* m, char* const* envp, const char* shim, int drop_ldlp, int keep_others) {
  size_t n = 0; const char* oldpre = NULL;
  if (envp) while (envp[n]) { if (!strncmp(envp[n], "LD_PRELOAD=", 11)) oldpre = envp[n] + 11; n++; }
  size_t extra = shim && *shim ? 12 + strlen(shim) + (keep_others && oldpre ? strlen(oldpre) + 1 : 0) : 0;
  char** r = prep_alloc(m, sizeof(char*) * (n + 2) + extra); if (!r) return NULL;
  size_t k = 0;
  for (size_t i = 0; i < n; i++) {
    if (!strncmp(envp[i], "LD_PRELOAD=", 11)) continue;
    if (drop_ldlp && !strncmp(envp[i], "LD_LIBRARY_PATH=", 16)) continue;
    r[k++] = envp[i];
  }
  if (extra) {
    char* v = (char*)(r + n + 2); char* w = stpcpy(stpcpy(v, "LD_PRELOAD="), shim);
    if (keep_others && oldpre) for (const char* t = oldpre; *t;) {
      t += strspn(t, ": "); size_t len = strcspn(t, ": ");
      if (len && !memmem(t, len, "libfhs-", 7)) { *w++ = ':'; memcpy(w, t, len); w += len; }
      t += len;
    }
    *w = 0; r[k++] = v;
  }
  r[k] = NULL; return r;
}

static int envhas(char* const* e, const char* k) { if (e) for (; *e; e++) if (!strncmp(*e, k, strlen(k))) return 1; return 0; }

// ---- shared ELF classification cache (mmap'd file, shared between all processes, validated by file fingerprint) ----
#include <sys/mman.h>
#include <stdint.h>
enum { K_NONE = 0, K_STD = 2, K_CUSTOM = 3, K_BIONIC = 4, K_STATIC = 5 };
struct fslot { volatile uint32_t seq; uint32_t kind; uint64_t dev, ino, size; int64_t ms; uint32_t mns, pad; };
#define FSLOTS 4096
static struct fslot* fcache; static int fc_state;   // 0 = not initialised, 1 = ready, -1 = disabled
static long fc_hits, fc_miss; static int fc_dbg;
static void fc_init(void) {
  fc_state = -1; if (getenv("FHS_NOCACHE")) return; fc_dbg = getenv("FHS_DEBUG") != 0;
  int fd = syscall(SYS_openat, AT_FDCWD, PFX "/tmp/fhs-elf.v1.cache", O_RDWR | O_CREAT | O_CLOEXEC, 0600); if (fd < 0) return;
  if (syscall(SYS_ftruncate, fd, (long)(FSLOTS * sizeof(struct fslot)))) { close(fd); return; }
  void* m = mmap(0, FSLOTS * sizeof(struct fslot), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0); close(fd);
  if (m == MAP_FAILED) return; fcache = m; fc_state = 1;
}
static unsigned fc_idx(const struct stat* st) { uint64_t h = st->st_ino * 0x9E3779B97F4A7C15ull ^ (uint64_t)st->st_dev * 0xC2B2AE3D27D4EB4Full; return (unsigned)(h >> 40) % FSLOTS; }
static int fc_get(const struct stat* st) {
  if (!fc_state) fc_init(); if (fc_state < 0) return 0;
  unsigned i = fc_idx(st);
  for (int p = 0; p < 4; p++) { struct fslot* s = &fcache[(i + p) % FSLOTS];
    uint32_t a = __atomic_load_n(&s->seq, __ATOMIC_ACQUIRE); if (a & 1) continue;
    uint32_t kind = s->kind; uint64_t dev = s->dev, ino = s->ino, size = s->size; int64_t ms = s->ms; uint32_t mns = s->mns;
    __atomic_thread_fence(__ATOMIC_ACQUIRE); if (a != __atomic_load_n(&s->seq, __ATOMIC_RELAXED)) continue;
    if (kind && dev == (uint64_t)st->st_dev && ino == (uint64_t)st->st_ino && size == (uint64_t)st->st_size && ms == st->st_mtim.tv_sec && mns == (uint32_t)st->st_mtim.tv_nsec) { fc_hits++; return kind; } }
  fc_miss++; return 0;
}
static void fc_put(const struct stat* st, int kind) {
  if (fc_state <= 0) return; unsigned i = fc_idx(st); struct fslot* t = 0;
  for (int p = 0; p < 4; p++) { struct fslot* s = &fcache[(i + p) % FSLOTS];
    if (!s->kind || (s->ino == (uint64_t)st->st_ino && s->dev == (uint64_t)st->st_dev)) { t = s; break; } }
  if (!t) t = &fcache[i];
  uint32_t a = __atomic_load_n(&t->seq, __ATOMIC_RELAXED); if (a & 1) return;
  if (!__atomic_compare_exchange_n(&t->seq, &a, a + 1, 0, __ATOMIC_ACQUIRE, __ATOMIC_RELAXED)) return;
  t->kind = kind; t->dev = st->st_dev; t->ino = st->st_ino; t->size = st->st_size; t->ms = st->st_mtim.tv_sec; t->mns = st->st_mtim.tv_nsec;
  __atomic_store_n(&t->seq, a + 2, __ATOMIC_RELEASE);
}
// A binary started as "ld.so --library-path ... binary" has the loader as /proc/self/exe, but programs like Chromium locate their data (and re-exec
// themselves) through it. FHS_EXE=<real binary> is put into the environment of such processes (and removed for all others); the glibc shim answers
// readlink("/proc/self/exe") with it and execve("/proc/self/exe") is redirected to it.
static char** envexe(struct prep_mem* m, char** e, const char* real) {
  if (!e) return NULL;
  size_t n = 0; while (e[n]) n++;
  char** r = prep_alloc(m, sizeof(char*) * (n + 2) + (real ? strlen(real) + 9 : 0));
  if (!r) return NULL;
  size_t k = 0;
  for (size_t i = 0; i < n; i++) if (strncmp(e[i], "FHS_EXE=", 8)) r[k++] = e[i];
  if (real) { char* v = (char*)(r + n + 2); stpcpy(stpcpy(v, "FHS_EXE="), real); r[k++] = v; }
  r[k] = NULL; return r;
}
// returns 0 ok (outputs filled) or -1/errno
static int prepare(struct prep_mem* mem, const char* path, char* const argv[], char* const envp[], const char** op, char*** oa, char*** oe) {
  char* buf = mem->path;
  char** av = (char**)argv; int depth = 0;
  { const char* fe = path && !strcmp(path, "/proc/self/exe") ? getenv("FHS_EXE") : 0; if (fe && fe[0]) path = fe; }
  for (;;) {
    const char* real = fmap(path, buf);
    struct stat st; int haveSt = FHS_SYS5(SYS_newfstatat, AT_FDCWD, real, &st, 0, 0) == 0 && S_ISREG(st.st_mode);
    int kind = haveSt ? fc_get(&st) : 0;
    if (kind) goto act;
    int fd = FHS_SYS5(SYS_openat, AT_FDCWD, real, O_RDONLY | O_CLOEXEC, 0, 0);
    unsigned char h[4096]; ssize_t n = 0;
    if (fd >= 0) { n = read(fd, h, sizeof h); close(fd); }
    if (fd < 0 || n < 4) { *op = real; *oa = av; *oe = (char**)envp; return 0; }
    if (h[0] == '#' && h[1] == '!' && depth++ < 4) {
      char line[512]; size_t m = n < 511 ? n : 511; memcpy(line, h, m); line[m] = 0; char* nl = strchr(line, '\n'); if (nl) *nl = 0;
      char* ip = line + 2; while (*ip == ' ' || *ip == '\t') ip++;
      char* sp = ip; while (*sp && *sp != ' ' && *sp != '\t') sp++;
      char* arg = 0; if (*sp) { *sp++ = 0; while (*sp == ' ' || *sp == '\t') sp++; if (*sp) arg = sp; }
      int ac = 0; while (av[ac]) ac++;
      char** nv = prep_alloc(mem, sizeof(char*) * (ac + 4)); if (!nv) return -1; int k = 0;
      nv[k] = prep_dup(mem, ip); if (!nv[k++]) return -1;
      if (arg) { nv[k] = prep_dup(mem, arg); if (!nv[k++]) return -1; }
      nv[k] = prep_dup(mem, real); if (!nv[k++]) return -1;
      for (int i = 1; i < ac; i++) nv[k++] = av[i]; nv[k] = 0;
      av = nv; path = nv[0];
      continue;
    }
    if (n > 64 && !memcmp(h, ELFMAG, 4) && h[EI_CLASS] == ELFCLASS64) {
      Elf64_Ehdr* eh = (Elf64_Ehdr*)h; const char* interp = 0; char ibuf[256];
      for (int i = 0; i < eh->e_phnum; i++) { size_t off = eh->e_phoff + (size_t)i * eh->e_phentsize;
        if (off + sizeof(Elf64_Phdr) > (size_t)n) break; Elf64_Phdr* ph = (Elf64_Phdr*)(h + off);
        if (ph->p_type == PT_INTERP) {
          if (ph->p_offset + ph->p_filesz <= (size_t)n) interp = (const char*)h + ph->p_offset;
          else { int f2 = FHS_SYS5(SYS_openat, AT_FDCWD, real, O_RDONLY | O_CLOEXEC, 0, 0);
                 if (f2 >= 0) { ssize_t k = pread(f2, ibuf, sizeof ibuf - 1, ph->p_offset); close(f2); if (k > 0) { ibuf[k] = 0; interp = ibuf; } } }
          break; } }
      kind = !interp ? K_STATIC : strstr(interp, "ld-linux") ? ((!strncmp(interp, "/lib/", 5) || !strncmp(interp, "/lib64/", 7)) ? K_STD : K_CUSTOM) : K_BIONIC;
      if (haveSt) fc_put(&st, kind);
    } else { *op = real; *oa = av; *oe = (char**)envp; return 0; }
  act: {
      int ac = 0; while (av[ac]) ac++;
      if (kind == K_STD || kind == K_CUSTOM) {
        if (kind == K_STD) {
          char** nv = prep_alloc(mem, sizeof(char*) * (ac + 7)); if (!nv) return -1; int k = 0;
          nv[k++] = (char*)LDSO; nv[k++] = "--library-path"; nv[k++] = GLIB "/god-lib:" GLIB "/lib:" DEB "/usr/lib/aarch64-linux-gnu:" DEB "/lib/aarch64-linux-gnu:" DEB "/usr/lib:" DEB "/lib";
          nv[k++] = "--argv0"; nv[k++] = ac ? av[0] : (char*)real; nv[k++] = (char*)real;
          for (int i = 1; i < ac; i++) nv[k++] = av[i]; nv[k] = 0;
          *op = LDSO; *oa = nv;
        } else { *op = real; *oa = av; }
        *oe = envexe(mem, envcopy(mem, envp, SHIM_G, envhas(envp, "FHS_KEEP_LDLP=") ? 0 : 1, 0), kind == K_STD ? real : 0); return *oe ? 0 : -1;   // FHS_KEEP_LDLP=1: the program needs its own LD_LIBRARY_PATH (e.g. chromen)
      }
      if (kind == K_STATIC && !envhas(envp, "FHS_SUP=") && !envhas(envp, "FHS_STATIC=0") && rexists(GLIB "/shim/fhs-run")) {
        char** nv = prep_alloc(mem, sizeof(char*) * (ac + 3)); if (!nv) return -1; int k = 0;
        nv[k++] = GLIB "/shim/fhs-run"; nv[k++] = (char*)real; for (int i = 1; i < ac; i++) nv[k++] = av[i]; nv[k] = 0;
        *op = GLIB "/shim/fhs-run"; *oa = nv; *oe = (char**)envp; return 0;
      }
      // bionic (or unsupervised static) target: make sure no glibc shim is inherited
      // Also do NOT inject libfhs into Android /system or /apex binaries
      if (!strncmp(real, "/system/", 8) || !strncmp(real, "/apex/", 6) || !strncmp(real, "/vendor/", 8)) {
        *op = real; *oa = av; *oe = envcopy(mem, envp, "", 0, 0); return *oe ? 0 : -1;
      }
      *op = real; *oa = av; *oe = envcopy(mem, envp, SHIM_B, 0, 1); return *oe ? 0 : -1;
    }
  }
}

int execve(const char* path, char* const argv[], char* const envp[]) {
  struct prep_mem mem; mem.count = 0; const char* p; char **a, **e;
  int r = prepare(&mem, path, argv, envp, &p, &a, &e);
  if (!r) r = syscall(SYS_execve, p, a, e);
  prep_free(&mem); return r;
}
int execv(const char* path, char* const argv[]) { return execve(path, argv, environ); }
int execvpe(const char* file, char* const argv[], char* const envp[]) {
  if (strchr(file, '/')) return execve(file, argv, envp);
  const char* pa = getenv("PATH"); if (!pa) pa = "/usr/bin:/bin"; char* t = strdup(pa); if (!t) return -1; char* save; int err = ENOENT;
  for (char* d = strtok_r(t, ":", &save); d; d = strtok_r(NULL, ":", &save)) { char f[PATH_MAX]; snprintf(f, sizeof f, "%s/%s", d, file);
    execve(f, argv, envp); if (errno == EACCES) err = EACCES; else if (errno != ENOENT && errno != ENOTDIR) { err = errno; break; } }
  free(t); errno = err; return -1;
}
int execvp(const char* file, char* const argv[]) { return execvpe(file, argv, environ); }
int execl(const char* path, const char* arg, ...) { va_list ap; int n = 1; va_start(ap, arg); while (va_arg(ap, char*)) n++; va_end(ap);
  char** av = malloc(sizeof(char*) * (n + 1)); if (!av) return -1; va_start(ap, arg); av[0] = (char*)arg; for (int i = 1; i <= n; i++) av[i] = va_arg(ap, char*); va_end(ap); int r = execv(path, av); int e = errno; free(av); errno = e; return r; }
int execlp(const char* file, const char* arg, ...) { va_list ap; int n = 1; va_start(ap, arg); while (va_arg(ap, char*)) n++; va_end(ap);
  char** av = malloc(sizeof(char*) * (n + 1)); if (!av) return -1; va_start(ap, arg); av[0] = (char*)arg; for (int i = 1; i <= n; i++) av[i] = va_arg(ap, char*); va_end(ap); int r = execvp(file, av); int e = errno; free(av); errno = e; return r; }

static int (*real_spawn)(pid_t*, const char*, const posix_spawn_file_actions_t*, const posix_spawnattr_t*, char* const[], char* const[]);
int posix_spawn(pid_t* pid, const char* path, const posix_spawn_file_actions_t* fa, const posix_spawnattr_t* at, char* const argv[], char* const envp[]) {
  if (!real_spawn) real_spawn = dlsym(RTLD_NEXT, "posix_spawn");
  struct prep_mem mem; mem.count = 0; const char* p; char **a, **e;
  int r = prepare(&mem, path, argv, envp, &p, &a, &e);
  r = r ? errno : real_spawn(pid, p, fa, at, a, e);
  prep_free(&mem); return r;
}
int posix_spawnp(pid_t* pid, const char* file, const posix_spawn_file_actions_t* fa, const posix_spawnattr_t* at, char* const argv[], char* const envp[]) {
  if (strchr(file, '/')) return posix_spawn(pid, file, fa, at, argv, envp);
  const char* pa = getenv("PATH"); if (!pa) pa = "/usr/bin:/bin"; char* t = strdup(pa); if (!t) return ENOMEM; char* save; int r = ENOENT;
  for (char* d = strtok_r(t, ":", &save); d; d = strtok_r(NULL, ":", &save)) { char f[PATH_MAX]; snprintf(f, sizeof f, "%s/%s", d, file);
    if (access(f, X_OK) == 0) { r = posix_spawn(pid, f, fa, at, argv, envp); break; } }
  free(t); return r;
}
int system(const char* cmd) {
  if (!cmd) return 1; pid_t pid; char* av[] = {"sh", "-c", (char*)cmd, 0};
  int r = posix_spawn(&pid, "/bin/sh", 0, 0, av, environ); if (r) return -1;
  int st; while (waitpid(pid, &st, 0) < 0 && errno == EINTR); return st;
}

#ifdef GLIBC_BUILD
// ------------------------------------------------------------------ path hooks (glibc world only)
#define REAL(ret, name, args) static ret (*r_##name) args; 
#define LOAD(name) if (!r_##name) r_##name = dlsym(RTLD_NEXT, #name)
#define P(x) char _b[PATH_MAX]; x = fmap(x, _b)
// Under fhs-run (ABI 1) the translated path goes to the kernel directly, tagged, instead of through libc and the supervisor,
// which would only pass it on. FHS_FAST_OFF=1 or no supervisor: libc as before.
#define FAST fhs_fast_active()
#define OMODE(fl, m) ((((fl) & O_CREAT) || ((fl) & O_TMPFILE) == O_TMPFILE) ? (m) : 0)

int open(const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "open"); P(p); if (fl & O_CREAT) fhs_cache_clear();
  if (FAST) return FHS_TAGGED(SYS_openat, AT_FDCWD, p, fl, OMODE(fl, m), 0); return r(p, fl, m); }
int open64(const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "open64"); P(p); if (fl & O_CREAT) fhs_cache_clear();
  if (FAST) return FHS_TAGGED(SYS_openat, AT_FDCWD, p, fl, OMODE(fl, m), 0); return r(p, fl, m); }
int openat(int d, const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(int, const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "openat"); P(p); if (fl & O_CREAT) fhs_cache_clear();
  if (FAST) return FHS_TAGGED(SYS_openat, d, p, fl, OMODE(fl, m), 0); return r(d, p, fl, m); }
int openat64(int d, const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(int, const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "openat64"); P(p); if (fl & O_CREAT) fhs_cache_clear();
  if (FAST) return FHS_TAGGED(SYS_openat, d, p, fl, OMODE(fl, m), 0); return r(d, p, fl, m); }
FILE* fopen(const char* p, const char* m) { static FILE* (*r)(const char*, const char*); if (!r) r = dlsym(RTLD_NEXT, "fopen"); P(p); return r(p, m); }
FILE* fopen64(const char* p, const char* m) { static FILE* (*r)(const char*, const char*); if (!r) r = dlsym(RTLD_NEXT, "fopen64"); P(p); return r(p, m); }
int stat(const char* p, struct stat* s) { static int (*r)(const char*, struct stat*); if (!r) r = dlsym(RTLD_NEXT, "stat"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, AT_FDCWD, p, s, 0, 0); return r(p, s); }
int lstat(const char* p, struct stat* s) { static int (*r)(const char*, struct stat*); if (!r) r = dlsym(RTLD_NEXT, "lstat"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW, 0); return r(p, s); }
int stat64(const char* p, struct stat64* s) { static int (*r)(const char*, struct stat64*); if (!r) r = dlsym(RTLD_NEXT, "stat64"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, AT_FDCWD, p, s, 0, 0); return r(p, s); }
int lstat64(const char* p, struct stat64* s) { static int (*r)(const char*, struct stat64*); if (!r) r = dlsym(RTLD_NEXT, "lstat64"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW, 0); return r(p, s); }
int fstatat(int d, const char* p, struct stat* s, int f) { static int (*r)(int, const char*, struct stat*, int); if (!r) r = dlsym(RTLD_NEXT, "fstatat"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, d, p, s, f, 0); return r(d, p, s, f); }
int fstatat64(int d, const char* p, struct stat64* s, int f) { static int (*r)(int, const char*, struct stat64*, int); if (!r) r = dlsym(RTLD_NEXT, "fstatat64"); P(p); if (FAST) return FHS_TAGGED(SYS_newfstatat, d, p, s, f, 0); return r(d, p, s, f); }
int statx(int d, const char* p, int f, unsigned m, struct statx* s) { static int (*r)(int, const char*, int, unsigned, struct statx*); if (!r) r = dlsym(RTLD_NEXT, "statx"); P(p); if (FAST) return FHS_TAGGED(SYS_statx, d, p, f, m, s); return r(d, p, f, m, s); }
int access(const char* p, int m) { static int (*r)(const char*, int); if (!r) r = dlsym(RTLD_NEXT, "access"); P(p); if (FAST) return FHS_TAGGED(SYS_faccessat, AT_FDCWD, p, m, 0, 0); return r(p, m); }
int faccessat(int d, const char* p, int m, int f) { static int (*r)(int, const char*, int, int); if (!r) r = dlsym(RTLD_NEXT, "faccessat"); P(p); if (FAST && !f) return FHS_TAGGED(SYS_faccessat, d, p, m, 0, 0); return r(d, p, m, f); }
static ssize_t fhs_selfexe(const char* p, char* b, size_t n) {   // readlink("/proc/self/exe") for processes started through the loader
  if (!p || strcmp(p, "/proc/self/exe")) return -2;
  const char* fe = getenv("FHS_EXE"); if (!fe || !fe[0]) return -2;
  size_t l = strlen(fe); if (l > n) l = n; memcpy(b, fe, l); return (ssize_t)l;
}
ssize_t readlink(const char* p, char* b, size_t n) { static ssize_t (*r)(const char*, char*, size_t); if (!r) r = dlsym(RTLD_NEXT, "readlink"); ssize_t x = fhs_selfexe(p, b, n); if (x != -2) return x; P(p); if (FAST) return FHS_TAGGED(SYS_readlinkat, AT_FDCWD, p, b, n, 0); return r(p, b, n); }
ssize_t readlinkat(int d, const char* p, char* b, size_t n) { static ssize_t (*r)(int, const char*, char*, size_t); if (!r) r = dlsym(RTLD_NEXT, "readlinkat"); ssize_t x = fhs_selfexe(p, b, n); if (x != -2) return x; P(p); if (FAST) return FHS_TAGGED(SYS_readlinkat, d, p, b, n, 0); return r(d, p, b, n); }

// popen/pclose: glibc's internal spawn bypasses our hooks, so reimplement via posix_spawn
static struct { FILE* f; pid_t pid; } pops[256];
FILE* popen(const char* cmd, const char* mode) {
  int rd = (mode[0] == 'r'); int p[2]; if (pipe2(p, O_CLOEXEC)) return NULL;
  posix_spawn_file_actions_t fa; posix_spawn_file_actions_init(&fa);
  if (rd) posix_spawn_file_actions_adddup2(&fa, p[1], 1); else posix_spawn_file_actions_adddup2(&fa, p[0], 0);
  char* av[] = {"sh", "-c", (char*)cmd, 0}; pid_t pid;
  int r = posix_spawn(&pid, "/bin/sh", &fa, 0, av, environ); posix_spawn_file_actions_destroy(&fa);
  if (r) { close(p[0]); close(p[1]); errno = r; return NULL; }
  int mine = rd ? p[0] : p[1]; close(rd ? p[1] : p[0]);
  FILE* f = fdopen(mine, rd ? "r" : "w"); if (!f) { close(mine); return NULL; }
  for (int i = 0; i < 256; i++) if (!pops[i].f) { pops[i].f = f; pops[i].pid = pid; break; }
  return f;
}
int pclose(FILE* f) {
  pid_t pid = -1; for (int i = 0; i < 256; i++) if (pops[i].f == f) { pid = pops[i].pid; pops[i].f = 0; }
  fclose(f); if (pid < 0) return -1; int st; while (waitpid(pid, &st, 0) < 0 && errno == EINTR); return st;
}

// legacy glibc (<2.33) stat wrappers and _FORTIFY variants used by Debian binaries
int __xstat(int v, const char* p, struct stat* s) { P(p); return FHS_SYS5(SYS_newfstatat, AT_FDCWD, p, s, 0, 0); }
int __xstat64(int v, const char* p, struct stat64* s) { P(p); return FHS_SYS5(SYS_newfstatat, AT_FDCWD, p, s, 0, 0); }
int __lxstat(int v, const char* p, struct stat* s) { P(p); return FHS_SYS5(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW, 0); }
int __lxstat64(int v, const char* p, struct stat64* s) { P(p); return FHS_SYS5(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW, 0); }
int __fxstatat(int v, int d, const char* p, struct stat* s, int fl) { P(p); return FHS_SYS5(SYS_newfstatat, d, p, s, fl, 0); }
int __fxstatat64(int v, int d, const char* p, struct stat64* s, int fl) { P(p); return FHS_SYS5(SYS_newfstatat, d, p, s, fl, 0); }
int __open_2(const char* p, int fl) { return open(p, fl); }
int __open64_2(const char* p, int fl) { return open64(p, fl); }
int __openat_2(int d, const char* p, int fl) { return openat(d, p, fl); }
int __openat64_2(int d, const char* p, int fl) { return openat64(d, p, fl); }
ssize_t __readlink_chk(const char* p, char* b, size_t n, size_t bl) { return readlink(p, b, n); }
ssize_t __readlinkat_chk(int d, const char* p, char* b, size_t n, size_t bl) { return readlinkat(d, p, b, n); }
DIR* opendir(const char* p) { static DIR* (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "opendir"); P(p); return r(p); }
int chdir(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "chdir"); P(p); return r(p); }
int mkdir(const char* p, mode_t m) { static int (*r)(const char*, mode_t); if (!r) r = dlsym(RTLD_NEXT, "mkdir"); P(p); fhs_cache_clear(); if (FAST) return FHS_TAGGED(SYS_mkdirat, AT_FDCWD, p, m, 0, 0); return r(p, m); }
int unlink(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "unlink"); P(p); fhs_cache_clear(); if (FAST) return FHS_TAGGED(SYS_unlinkat, AT_FDCWD, p, 0, 0, 0); return r(p); }
int rmdir(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "rmdir"); P(p); fhs_cache_clear(); if (FAST) return FHS_TAGGED(SYS_unlinkat, AT_FDCWD, p, AT_REMOVEDIR, 0, 0); return r(p); }
void* dlopen(const char* p, int fl) { static void* (*r)(const char*, int); if (!r) r = dlsym(RTLD_NEXT, "dlopen"); if (!p) return r(p, fl); P(p); return r(p, fl); }
#endif

#ifndef GLIBC_BUILD
// Debian mode (dpkg maintainer scripts run by Termux's bionic sh): the fhs-run supervisor translates the paths of open/stat/... but cannot change
// another process's working directory, so chdir is mapped here. Only with FHS_DEBIAN set; ordinary Termux processes are left alone.
int chdir(const char* p) {
  static int (*r)(const char*);
  if (!r) r = dlsym(RTLD_NEXT, "chdir");
  if (p && p[0] == '/' && getenv("FHS_DEBIAN")) { char b[PATH_MAX]; const char* m = fmap(p, b); return r(m); }
  return r(p);
}
#endif

#ifndef GLIBC_BUILD
#include "fhs-bionic-io.h"
#endif
