// Bionic adapters for fhs-run ABI 1. Under the supervisor the shim translates the path itself and makes the system call from
// the fast-path trampoline (fhs-fast.h), so the call is not intercepted. The rules are the supervisor's (fhs-run.c): the same fmap(), "create"
// lookups for the same calls, the same exceptions. Everything not covered here (calls made inside libc, flags that libc
// emulates) still reaches the supervisor. Outside a supervisor each adapter is one flag test and a jump to libc.
// Do not intercept syscall(): it must remain usable for raw calls, including pointer validation and future syscall interfaces.
#include <sys/time.h>

// libc's own functions. Resolved when the library is loaded, so that an adapter called from a signal handler (unlink, open,
// stat are all legal there) does not have to enter the dynamic linker; the lazy lookup only covers calls made before that.
#define FAST_REALS(X) X(open) X(open64) X(openat) X(openat64) X(stat) X(lstat) X(fstatat) X(stat64) X(lstat64) X(fstatat64) \
  X(statx) X(faccessat) X(readlinkat) X(mkdirat) X(unlinkat) X(rename) X(renameat) X(renameat2) X(symlink) X(symlinkat) \
  X(chmod) X(fchmodat) X(chown) X(lchown) X(fchownat) X(utimensat) X(utimes) X(link) X(linkat)
#define X(name) static void* real_##name;
FAST_REALS(X)
#undef X
__attribute__((constructor)) static void fast_resolve(void) {
#define X(name) if (!real_##name) real_##name = dlsym(RTLD_NEXT, #name);
  FAST_REALS(X)
#undef X
}
#define REAL(type, name) ((type)(real_##name ? real_##name : (real_##name = dlsym(RTLD_NEXT, #name))))
#define TAGGED FHS_TAGGED

// create != 0: the lookup of a creating/modifying call, exactly where the supervisor sets fhs_create
static const char* fast_map(const char* p, char* b, int create) {
  if (!create) return fmap(p, b);
  fhs_create = 1; p = fmap(p, b); fhs_create = 0; return p;
}
static int fast_open(int d, const char* p, int flags, mode_t mode) {
  char b[PATH_MAX]; int w = (flags & O_CREAT) && fhs_dirty(p);
  if (!(p && p[0] == '/' && fhs_acct_open(p, flags))) p = fast_map(p, b, flags & O_CREAT);
  int fd = TAGGED(SYS_openat, d, p, flags, mode, 0);
  if (w) fhs_cache_clear();
  return fd;
}
#define OPEN_ADAPTER(name, decl, call, dir) \
int name decl { \
  mode_t mode = 0; \
  if ((flags & O_CREAT) || (flags & O_TMPFILE) == O_TMPFILE) { va_list ap; va_start(ap, flags); mode = va_arg(ap, mode_t); va_end(ap); } \
  if (fhs_fast_active()) return fast_open(dir, p, flags, mode); \
  return REAL(int (*) decl, name) call; \
}
OPEN_ADAPTER(open, (const char* p, int flags, ...), (p, flags, mode), AT_FDCWD)
OPEN_ADAPTER(open64, (const char* p, int flags, ...), (p, flags, mode), AT_FDCWD)
OPEN_ADAPTER(openat, (int d, const char* p, int flags, ...), (d, p, flags, mode), d)
OPEN_ADAPTER(openat64, (int d, const char* p, int flags, ...), (d, p, flags, mode), d)
#undef OPEN_ADAPTER
#define STAT_ADAPTER(name, type, decl, call, dir, fl) \
int name decl { \
  if (fhs_fast_active()) { char b[PATH_MAX]; p = fmap(p, b); return TAGGED(SYS_newfstatat, dir, p, st, fl, 0); } \
  return REAL(int (*) decl, name) call; \
}
STAT_ADAPTER(stat, stat, (const char* p, struct stat* st), (p, st), AT_FDCWD, 0)
STAT_ADAPTER(lstat, stat, (const char* p, struct stat* st), (p, st), AT_FDCWD, AT_SYMLINK_NOFOLLOW)
STAT_ADAPTER(fstatat, stat, (int d, const char* p, struct stat* st, int flags), (d, p, st, flags), d, flags)
STAT_ADAPTER(stat64, stat64, (const char* p, struct stat64* st), (p, st), AT_FDCWD, 0)
STAT_ADAPTER(lstat64, stat64, (const char* p, struct stat64* st), (p, st), AT_FDCWD, AT_SYMLINK_NOFOLLOW)
STAT_ADAPTER(fstatat64, stat64, (int d, const char* p, struct stat64* st, int flags), (d, p, st, flags), d, flags)
#undef STAT_ADAPTER
int statx(int d, const char* p, int flags, unsigned mask, struct statx* st) {
  if (fhs_fast_active()) { char b[PATH_MAX]; p = fmap(p, b); return TAGGED(SYS_statx, d, p, flags, mask, st); }
  int (*real)(int, const char*, int, unsigned, struct statx*) = REAL(int (*)(int, const char*, int, unsigned, struct statx*), statx);
  if (!real) { errno = ENOSYS; return -1; }
  return real(d, p, flags, mask, st);
}
int faccessat(int d, const char* p, int mode, int flags) {
  // Preserve libc's flag handling when it needs faccessat2 or emulation.
  if (fhs_fast_active() && !flags) { char b[PATH_MAX]; p = fmap(p, b); return TAGGED(SYS_faccessat, d, p, mode, 0, 0); }
  return REAL(int (*)(int, const char*, int, int), faccessat)(d, p, mode, flags);
}
int access(const char* p, int mode) { return faccessat(AT_FDCWD, p, mode, 0); }
ssize_t readlinkat(int d, const char* p, char* out, size_t size) {
  if (fhs_fast_active()) { char b[PATH_MAX]; p = fmap(p, b); return TAGGED(SYS_readlinkat, d, p, out, size, 0); }
  return REAL(ssize_t (*)(int, const char*, char*, size_t), readlinkat)(d, p, out, size);
}
ssize_t readlink(const char* p, char* out, size_t size) { return readlinkat(AT_FDCWD, p, out, size); }

// ---- creating and modifying calls: "create" lookups, like handle_write() in the supervisor. The lookup cache of this process
// is dropped afterwards, because the call may have changed which tree a name is found in.
int mkdirat(int d, const char* p, mode_t mode) {
  if (fhs_fast_active()) { char b[PATH_MAX]; int w = fhs_dirty(p); p = fast_map(p, b, 1); int r = TAGGED(SYS_mkdirat, d, p, mode, 0, 0); if (w) fhs_cache_clear(); return r; }
  return REAL(int (*)(int, const char*, mode_t), mkdirat)(d, p, mode);
}
int mkdir(const char* p, mode_t mode) { return mkdirat(AT_FDCWD, p, mode); }
int unlinkat(int d, const char* p, int flags) {
  if (fhs_fast_active()) { char b[PATH_MAX]; int w = fhs_dirty(p); p = fast_map(p, b, 1); int r = TAGGED(SYS_unlinkat, d, p, flags, 0, 0); if (w) fhs_cache_clear(); return r; }
  return REAL(int (*)(int, const char*, int), unlinkat)(d, p, flags);
}
int unlink(const char* p) { return unlinkat(AT_FDCWD, p, 0); }
int rmdir(const char* p) { return unlinkat(AT_FDCWD, p, AT_REMOVEDIR); }
static int fast_rename(int od, const char* o, int nd, const char* n, unsigned flags) {
  char b1[PATH_MAX], b2[PATH_MAX]; int w = fhs_dirty(o) || fhs_dirty(n); o = fast_map(o, b1, 1); n = fast_map(n, b2, 1);
  int r = TAGGED(SYS_renameat2, od, o, nd, n, flags); if (w) fhs_cache_clear(); return r;
}
int rename(const char* o, const char* n) {
  if (fhs_fast_active()) return fast_rename(AT_FDCWD, o, AT_FDCWD, n, 0);
  return REAL(int (*)(const char*, const char*), rename)(o, n);
}
int renameat(int od, const char* o, int nd, const char* n) {
  if (fhs_fast_active()) return fast_rename(od, o, nd, n, 0);
  return REAL(int (*)(int, const char*, int, const char*), renameat)(od, o, nd, n);
}
int renameat2(int od, const char* o, int nd, const char* n, unsigned flags) {
  if (fhs_fast_active()) return fast_rename(od, o, nd, n, flags);
  int (*real)(int, const char*, int, const char*, unsigned) = REAL(int (*)(int, const char*, int, const char*, unsigned), renameat2);
  if (!real) { errno = ENOSYS; return -1; }
  return real(od, o, nd, n, flags);
}
static int fast_symlink(const char* target, int nd, const char* n) {
  char b[PATH_MAX]; int w = fhs_dirty(n); n = fast_map(n, b, 1); int r = TAGGED(SYS_symlinkat, target, nd, n, 0, 0); if (w) fhs_cache_clear(); return r;
}
int symlink(const char* target, const char* n) {
  if (fhs_fast_active()) return fast_symlink(target, AT_FDCWD, n);
  return REAL(int (*)(const char*, const char*), symlink)(target, n);
}
int symlinkat(const char* target, int nd, const char* n) {
  if (fhs_fast_active()) return fast_symlink(target, nd, n);
  return REAL(int (*)(const char*, int, const char*), symlinkat)(target, nd, n);
}
static int fast_link(int od, const char* o, int nd, const char* n, int flags) {
  char b1[PATH_MAX], b2[PATH_MAX]; int w = fhs_dirty(o) || fhs_dirty(n); o = fast_map(o, b1, 1); n = fast_map(n, b2, 1);
  int r = TAGGED(SYS_linkat, od, o, nd, n, flags); if (w) fhs_cache_clear(); return r;
}
int link(const char* o, const char* n) {
  if (fhs_fast_active()) return fast_link(AT_FDCWD, o, AT_FDCWD, n, 0);
  return REAL(int (*)(const char*, const char*), link)(o, n);
}
int linkat(int od, const char* o, int nd, const char* n, int flags) {
  if (fhs_fast_active()) return fast_link(od, o, nd, n, flags);
  return REAL(int (*)(int, const char*, int, const char*, int), linkat)(od, o, nd, n, flags);
}
static int fast_chmod(int d, const char* p, mode_t mode) { char b[PATH_MAX]; p = fast_map(p, b, 1); return TAGGED(SYS_fchmodat, d, p, mode, 0, 0); }
int chmod(const char* p, mode_t mode) {
  if (fhs_fast_active()) return fast_chmod(AT_FDCWD, p, mode);
  return REAL(int (*)(const char*, mode_t), chmod)(p, mode);
}
int fchmodat(int d, const char* p, mode_t mode, int flags) {
  if (fhs_fast_active() && !flags) return fast_chmod(d, p, mode);   // AT_SYMLINK_NOFOLLOW is emulated inside libc
  return REAL(int (*)(int, const char*, mode_t, int), fchmodat)(d, p, mode, flags);
}
static int fast_chown(int d, const char* p, uid_t u, gid_t g, int flags) { char b[PATH_MAX]; p = fast_map(p, b, 1); return TAGGED(SYS_fchownat, d, p, u, g, flags); }
int chown(const char* p, uid_t u, gid_t g) {
  if (fhs_fast_active()) return fast_chown(AT_FDCWD, p, u, g, 0);
  return REAL(int (*)(const char*, uid_t, gid_t), chown)(p, u, g);
}
int lchown(const char* p, uid_t u, gid_t g) {
  if (fhs_fast_active()) return fast_chown(AT_FDCWD, p, u, g, AT_SYMLINK_NOFOLLOW);
  return REAL(int (*)(const char*, uid_t, gid_t), lchown)(p, u, g);
}
int fchownat(int d, const char* p, uid_t u, gid_t g, int flags) {
  if (fhs_fast_active()) return fast_chown(d, p, u, g, flags);
  return REAL(int (*)(int, const char*, uid_t, gid_t, int), fchownat)(d, p, u, g, flags);
}
int utimensat(int d, const char* p, const struct timespec ts[2], int flags) {
  if (fhs_fast_active() && p) { char b[PATH_MAX]; p = fast_map(p, b, 1); return TAGGED(SYS_utimensat, d, p, ts, flags, 0); }   // p == NULL: futimens()
  return REAL(int (*)(int, const char*, const struct timespec[2], int), utimensat)(d, p, ts, flags);
}
int utimes(const char* p, const struct timeval tv[2]) {
  if (fhs_fast_active() && p) {
    struct timespec ts[2];
    if (tv) for (int i = 0; i < 2; i++) {
      if (tv[i].tv_usec < 0 || tv[i].tv_usec >= 1000000) { errno = EINVAL; return -1; }   // as libc does
      ts[i].tv_sec = tv[i].tv_sec; ts[i].tv_nsec = tv[i].tv_usec * 1000; }
    char b[PATH_MAX]; p = fast_map(p, b, 1); return TAGGED(SYS_utimensat, AT_FDCWD, p, tv ? ts : NULL, 0, 0);
  }
  return REAL(int (*)(const char*, const struct timeval[2]), utimes)(p, tv);
}
#undef TAGGED
#undef REAL
#undef FAST_REALS
