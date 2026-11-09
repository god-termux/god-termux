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

#include "fhsmap.h"
// ------------------------------------------------------------------ exec logic
static char** envcopy(char* const* envp, const char* shim, int drop_ldlp, int keep_others) {
  int n = 0; if (envp) while (envp[n]) n++;
  char** r = malloc(sizeof(char*) * (n + 2)); int k = 0; char* oldpre = 0;
  for (int i = 0; i < n; i++) {
    if (!strncmp(envp[i], "LD_PRELOAD=", 11)) { oldpre = envp[i] + 11; continue; }
    if (drop_ldlp && !strncmp(envp[i], "LD_LIBRARY_PATH=", 16)) continue;
    r[k++] = envp[i];
  }
  char* v = malloc(PATH_MAX * 4); strcpy(v, "LD_PRELOAD="); strcat(v, shim);
  if (keep_others && oldpre) { char* t = strdup(oldpre); for (char* s = strtok(t, ": "); s; s = strtok(0, ": "))
      if (!strstr(s, "libfhs-")) { strcat(v, ":"); strcat(v, s); } free(t); }
  r[k++] = v; r[k] = 0; return r;
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
// returns 0 ok (outputs filled) or -1/errno
static int prepare(const char* path, char* const argv[], char* const envp[], const char** op, char*** oa, char*** oe) {
  static __thread char buf[PATH_MAX]; char* argc_hold = 0; (void)argc_hold;
  char** av = (char**)argv; int depth = 0;
  for (;;) {
    const char* real = fmap(path, buf);
    struct stat st; int haveSt = syscall(SYS_newfstatat, AT_FDCWD, real, &st, 0) == 0 && S_ISREG(st.st_mode);
    int kind = haveSt ? fc_get(&st) : 0;
    if (kind) goto act;
    int fd = syscall(SYS_openat, AT_FDCWD, real, O_RDONLY | O_CLOEXEC, 0);
    unsigned char h[4096]; ssize_t n = 0;
    if (fd >= 0) { n = read(fd, h, sizeof h); close(fd); }
    if (fd < 0 || n < 4) { *op = real; *oa = av; *oe = (char**)envp; return 0; }
    if (h[0] == '#' && h[1] == '!' && depth++ < 4) {
      char line[512]; size_t m = n < 511 ? n : 511; memcpy(line, h, m); line[m] = 0; char* nl = strchr(line, '\n'); if (nl) *nl = 0;
      char* ip = line + 2; while (*ip == ' ' || *ip == '\t') ip++;
      char* sp = ip; while (*sp && *sp != ' ' && *sp != '\t') sp++;
      char* arg = 0; if (*sp) { *sp++ = 0; while (*sp == ' ' || *sp == '\t') sp++; if (*sp) arg = sp; }
      int ac = 0; while (av[ac]) ac++;
      char** nv = malloc(sizeof(char*) * (ac + 4)); int k = 0;
      nv[k++] = strdup(ip); if (arg) nv[k++] = strdup(arg); nv[k++] = (char*)real;
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
          else { int f2 = syscall(SYS_openat, AT_FDCWD, real, O_RDONLY | O_CLOEXEC, 0);
                 if (f2 >= 0) { ssize_t k = pread(f2, ibuf, sizeof ibuf - 1, ph->p_offset); close(f2); if (k > 0) { ibuf[k] = 0; interp = ibuf; } } }
          break; } }
      kind = !interp ? K_STATIC : strstr(interp, "ld-linux") ? ((!strncmp(interp, "/lib/", 5) || !strncmp(interp, "/lib64/", 7)) ? K_STD : K_CUSTOM) : K_BIONIC;
      if (haveSt) fc_put(&st, kind);
    } else { *op = real; *oa = av; *oe = (char**)envp; return 0; }
  act: {
      int ac = 0; while (av[ac]) ac++;
      if (kind == K_STD || kind == K_CUSTOM) {
        if (kind == K_STD) {
          char** nv = malloc(sizeof(char*) * (ac + 6)); int k = 0;
          nv[k++] = (char*)LDSO; nv[k++] = "--library-path"; nv[k++] = GLIB "/lib:" DEB "/usr/lib/aarch64-linux-gnu:" DEB "/lib/aarch64-linux-gnu:" DEB "/usr/lib:" DEB "/lib";
          nv[k++] = "--argv0"; nv[k++] = ac ? av[0] : (char*)real; nv[k++] = (char*)real;
          for (int i = 1; i < ac; i++) nv[k++] = av[i]; nv[k] = 0;
          *op = LDSO; *oa = nv;
        } else { *op = real; *oa = av; }
        *oe = envcopy(envp, SHIM_G, 1, 0); return 0;
      }
      if (kind == K_STATIC && !envhas(envp, "FHS_SUP=") && !envhas(envp, "FHS_STATIC=0") && rexists(GLIB "/shim/fhs-run")) {
        char** nv = malloc(sizeof(char*) * (ac + 3)); int k = 0;
        nv[k++] = GLIB "/shim/fhs-run"; nv[k++] = (char*)real; for (int i = 1; i < ac; i++) nv[k++] = av[i]; nv[k] = 0;
        *op = GLIB "/shim/fhs-run"; *oa = nv; *oe = (char**)envp; return 0;
      }
      // bionic (or unsupervised static) target: make sure no glibc shim is inherited
      *op = real; *oa = av; *oe = envcopy(envp, SHIM_B, 0, 1); return 0;
    }
  }
}

int execve(const char* path, char* const argv[], char* const envp[]) {
  const char* p; char **a, **e; if (prepare(path, argv, envp, &p, &a, &e)) return -1;
  return syscall(SYS_execve, p, a, e);
}
int execv(const char* path, char* const argv[]) { return execve(path, argv, environ); }
int execvpe(const char* file, char* const argv[], char* const envp[]) {
  if (strchr(file, '/')) return execve(file, argv, envp);
  const char* pa = getenv("PATH"); if (!pa) pa = "/usr/bin:/bin"; char* t = strdup(pa); int err = ENOENT;
  for (char* d = strtok(t, ":"); d; d = strtok(0, ":")) { char f[PATH_MAX]; snprintf(f, sizeof f, "%s/%s", d, file);
    execve(f, argv, envp); if (errno == EACCES) err = EACCES; else if (errno != ENOENT && errno != ENOTDIR) { err = errno; break; } }
  free(t); errno = err; return -1;
}
int execvp(const char* file, char* const argv[]) { return execvpe(file, argv, environ); }
int execl(const char* path, const char* arg, ...) { va_list ap; int n = 1; va_start(ap, arg); while (va_arg(ap, char*)) n++; va_end(ap);
  char** av = malloc(sizeof(char*) * (n + 1)); va_start(ap, arg); av[0] = (char*)arg; for (int i = 1; i <= n; i++) av[i] = va_arg(ap, char*); va_end(ap); return execv(path, av); }
int execlp(const char* file, const char* arg, ...) { va_list ap; int n = 1; va_start(ap, arg); while (va_arg(ap, char*)) n++; va_end(ap);
  char** av = malloc(sizeof(char*) * (n + 1)); va_start(ap, arg); av[0] = (char*)arg; for (int i = 1; i <= n; i++) av[i] = va_arg(ap, char*); va_end(ap); return execvp(file, av); }

static int (*real_spawn)(pid_t*, const char*, const posix_spawn_file_actions_t*, const posix_spawnattr_t*, char* const[], char* const[]);
int posix_spawn(pid_t* pid, const char* path, const posix_spawn_file_actions_t* fa, const posix_spawnattr_t* at, char* const argv[], char* const envp[]) {
  if (!real_spawn) real_spawn = dlsym(RTLD_NEXT, "posix_spawn");
  const char* p; char **a, **e; if (prepare(path, argv, envp, &p, &a, &e)) return errno;
  return real_spawn(pid, p, fa, at, a, e);
}
int posix_spawnp(pid_t* pid, const char* file, const posix_spawn_file_actions_t* fa, const posix_spawnattr_t* at, char* const argv[], char* const envp[]) {
  if (strchr(file, '/')) return posix_spawn(pid, file, fa, at, argv, envp);
  const char* pa = getenv("PATH"); if (!pa) pa = "/usr/bin:/bin"; char* t = strdup(pa); int r = ENOENT;
  for (char* d = strtok(t, ":"); d; d = strtok(0, ":")) { char f[PATH_MAX]; snprintf(f, sizeof f, "%s/%s", d, file);
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

int open(const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "open"); P(p); if (fl & O_CREAT) fhs_cache_clear(); return r(p, fl, m); }
int open64(const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "open64"); P(p); if (fl & O_CREAT) fhs_cache_clear(); return r(p, fl, m); }
int openat(int d, const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(int, const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "openat"); P(p); if (fl & O_CREAT) fhs_cache_clear(); return r(d, p, fl, m); }
int openat64(int d, const char* p, int fl, ...) { va_list ap; va_start(ap, fl); mode_t m = va_arg(ap, mode_t); va_end(ap);
  static int (*r)(int, const char*, int, ...); if (!r) r = dlsym(RTLD_NEXT, "openat64"); P(p); if (fl & O_CREAT) fhs_cache_clear(); return r(d, p, fl, m); }
FILE* fopen(const char* p, const char* m) { static FILE* (*r)(const char*, const char*); if (!r) r = dlsym(RTLD_NEXT, "fopen"); P(p); return r(p, m); }
FILE* fopen64(const char* p, const char* m) { static FILE* (*r)(const char*, const char*); if (!r) r = dlsym(RTLD_NEXT, "fopen64"); P(p); return r(p, m); }
int stat(const char* p, struct stat* s) { static int (*r)(const char*, struct stat*); if (!r) r = dlsym(RTLD_NEXT, "stat"); P(p); return r(p, s); }
int lstat(const char* p, struct stat* s) { static int (*r)(const char*, struct stat*); if (!r) r = dlsym(RTLD_NEXT, "lstat"); P(p); return r(p, s); }
int stat64(const char* p, struct stat64* s) { static int (*r)(const char*, struct stat64*); if (!r) r = dlsym(RTLD_NEXT, "stat64"); P(p); return r(p, s); }
int lstat64(const char* p, struct stat64* s) { static int (*r)(const char*, struct stat64*); if (!r) r = dlsym(RTLD_NEXT, "lstat64"); P(p); return r(p, s); }
int fstatat(int d, const char* p, struct stat* s, int f) { static int (*r)(int, const char*, struct stat*, int); if (!r) r = dlsym(RTLD_NEXT, "fstatat"); P(p); return r(d, p, s, f); }
int fstatat64(int d, const char* p, struct stat64* s, int f) { static int (*r)(int, const char*, struct stat64*, int); if (!r) r = dlsym(RTLD_NEXT, "fstatat64"); P(p); return r(d, p, s, f); }
int statx(int d, const char* p, int f, unsigned m, struct statx* s) { static int (*r)(int, const char*, int, unsigned, struct statx*); if (!r) r = dlsym(RTLD_NEXT, "statx"); P(p); return r(d, p, f, m, s); }
int access(const char* p, int m) { static int (*r)(const char*, int); if (!r) r = dlsym(RTLD_NEXT, "access"); P(p); return r(p, m); }
int faccessat(int d, const char* p, int m, int f) { static int (*r)(int, const char*, int, int); if (!r) r = dlsym(RTLD_NEXT, "faccessat"); P(p); return r(d, p, m, f); }
ssize_t readlink(const char* p, char* b, size_t n) { static ssize_t (*r)(const char*, char*, size_t); if (!r) r = dlsym(RTLD_NEXT, "readlink"); P(p); return r(p, b, n); }
ssize_t readlinkat(int d, const char* p, char* b, size_t n) { static ssize_t (*r)(int, const char*, char*, size_t); if (!r) r = dlsym(RTLD_NEXT, "readlinkat"); P(p); return r(d, p, b, n); }

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
int __xstat(int v, const char* p, struct stat* s) { P(p); return syscall(SYS_newfstatat, AT_FDCWD, p, s, 0); }
int __xstat64(int v, const char* p, struct stat64* s) { P(p); return syscall(SYS_newfstatat, AT_FDCWD, p, s, 0); }
int __lxstat(int v, const char* p, struct stat* s) { P(p); return syscall(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW); }
int __lxstat64(int v, const char* p, struct stat64* s) { P(p); return syscall(SYS_newfstatat, AT_FDCWD, p, s, AT_SYMLINK_NOFOLLOW); }
int __fxstatat(int v, int d, const char* p, struct stat* s, int fl) { P(p); return syscall(SYS_newfstatat, d, p, s, fl); }
int __fxstatat64(int v, int d, const char* p, struct stat64* s, int fl) { P(p); return syscall(SYS_newfstatat, d, p, s, fl); }
int __open_2(const char* p, int fl) { return open(p, fl); }
int __open64_2(const char* p, int fl) { return open64(p, fl); }
int __openat_2(int d, const char* p, int fl) { return openat(d, p, fl); }
int __openat64_2(int d, const char* p, int fl) { return openat64(d, p, fl); }
ssize_t __readlink_chk(const char* p, char* b, size_t n, size_t bl) { return readlink(p, b, n); }
ssize_t __readlinkat_chk(int d, const char* p, char* b, size_t n, size_t bl) { return readlinkat(d, p, b, n); }
DIR* opendir(const char* p) { static DIR* (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "opendir"); P(p); return r(p); }
int chdir(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "chdir"); P(p); return r(p); }
int mkdir(const char* p, mode_t m) { static int (*r)(const char*, mode_t); if (!r) r = dlsym(RTLD_NEXT, "mkdir"); P(p); fhs_cache_clear(); return r(p, m); }
int unlink(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "unlink"); P(p); fhs_cache_clear(); return r(p); }
int rmdir(const char* p) { static int (*r)(const char*); if (!r) r = dlsym(RTLD_NEXT, "rmdir"); P(p); fhs_cache_clear(); return r(p); }
void* dlopen(const char* p, int fl) { static void* (*r)(const char*, int); if (!r) r = dlsym(RTLD_NEXT, "dlopen"); if (!p) return r(p, fl); P(p); return r(p, fl); }
#endif
