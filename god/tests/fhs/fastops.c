// Read-side libc calls (the ones the bionic shim answers itself under fhs-run) plus create/remove through the supervisor in between.
// argv[1]: scratch directory given by its FHS name /tmp/NAME. The output must be identical with and without the fast path.
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
// statx is not declared for the API level Termux builds against; take whatever the process resolves (the shim's adapter when it is loaded)
static int (*statx_fn)(int, const char*, int, unsigned, struct statx*);
static void one(const char* p) {
  struct stat a, b, c; char link[512]; struct statx sx;
  int rs = stat(p, &a), es = rs ? errno : 0, rl = lstat(p, &b), el = rl ? errno : 0, rf = fstatat(AT_FDCWD, p, &c, 0), ef = rf ? errno : 0;
  int fd = open(p, O_RDONLY | O_CLOEXEC), eo = fd < 0 ? errno : 0; struct stat f; int samefd = fd >= 0 && fstat(fd, &f) == 0 && !rs && f.st_ino == a.st_ino && f.st_dev == a.st_dev;
  if (fd >= 0) close(fd);
  int fd2 = openat(AT_FDCWD, p, O_RDONLY | O_NOFOLLOW | O_CLOEXEC), eo2 = fd2 < 0 ? errno : 0; if (fd2 >= 0) close(fd2);
  ssize_t n = readlink(p, link, sizeof link - 1); int er = n < 0 ? errno : 0; if (n >= 0) link[n] = 0;
  int rx = statx_fn(AT_FDCWD, p, 0, STATX_SIZE | STATX_MODE, &sx), ex = rx ? errno : 0;
  printf("%s\n  stat %d/%d mode %o size %lld | lstat %d/%d mode %o | fstatat %d/%d same %d | access r%d x%d w%d f%d | faccessat %d\n"
         "  open %d same-file %d | open nofollow %d | readlink %d [%s] | statx %d/%d mode %o size %lld\n", p,
         rs, es, rs ? 0 : a.st_mode, rs ? 0LL : (long long)a.st_size, rl, el, rl ? 0 : b.st_mode, rf, ef, !rs && !rf && a.st_ino == c.st_ino,
         access(p, R_OK), access(p, X_OK), access(p, W_OK), access(p, F_OK), faccessat(AT_FDCWD, p, R_OK, 0),
         eo, samefd, eo2, er, n >= 0 ? (strncmp(link, "/data/", 6) ? link : "<absolute host path>") : "", rx, ex, rx ? 0 : sx.stx_mode, rx ? 0LL : (long long)sx.stx_size);
  // Calls that libc makes itself, straight after tagged ones: they must still reach the supervisor (a tag left in x5 would let them
  // through untranslated).
  stat(p, &a); FILE* fp = fopen(p, "r"); int ef2 = fp ? 0 : errno; if (fp) fclose(fp);
  access(p, F_OK); DIR* dp = opendir(p); int ed = dp ? 0 : errno; if (dp) closedir(dp);
  lstat(p, &b); char* rp = realpath(p, NULL); int erp = rp ? 0 : errno;
  printf("  libc-internal: fopen %d opendir %d realpath %d [%s]\n", ef2, ed, erp, rp ? (strncmp(rp, "/data/", 6) ? rp : "<absolute host path>") : ""); free(rp);
}
int main(int argc, char** argv) {
  static const char* fixed[] = {"/usr/bin/sh", "/bin/sh", "/usr/bin", "/usr/lib", "/lib", "/etc", "/etc/passwd", "/etc/fhs-missing", "/usr/share", "/usr/share/fhs-missing/x",
    "/usr/bin/env", "/usr/sbin/fhs-missing", "/var", "/opt", "/tmp", "/data/data/com.termux/files/usr/bin/sh", "/system/bin/sh", "/proc/self/exe", "/dev/null", ".", "..",
    "fhs-relative-missing", "", "/", NULL};
  statx_fn = dlsym(RTLD_DEFAULT, "statx"); if (!statx_fn) return 2;
  for (int i = 0; fixed[i]; i++) one(fixed[i]);
  char d[256], p[512], q[512]; snprintf(d, sizeof d, "%s", argv[1]);
  snprintf(p, sizeof p, "%s/file", d); snprintf(q, sizeof q, "%s/link", d);
  one(p);                                                                   // missing: the answer may be cached
  int fd = open(p, O_WRONLY | O_CREAT | O_CLOEXEC, 0640); printf("create %d\n", fd >= 0); if (write(fd, "hello", 5) != 5) return 1; close(fd);
  one(p);                                                                   // must be visible at once
  printf("symlink %d\n", symlink("file", q)); one(q);
  printf("chmod %d\n", chmod(p, 0600)); one(p);
  snprintf(q, sizeof q, "%s/moved", d); printf("rename %d\n", rename(p, q)); one(p); one(q);
  printf("chdir %d\n", chdir("/data/data/com.termux/files/usr/tmp")); snprintf(p, sizeof p, "%s/moved", strrchr(d, '/') + 1); one(p);   // relative name
  fd = open(d, O_RDONLY | O_DIRECTORY | O_CLOEXEC); struct stat st; printf("openat dirfd %d fstatat %d\n", fd >= 0, fstatat(fd, "moved", &st, 0)); 
  int f2 = openat(fd, "moved", O_RDONLY | O_CLOEXEC); char buf[8] = ""; printf("openat relative read %zd [%s]\n", read(f2, buf, 5), buf); close(f2); close(fd);
  printf("unlink %d\n", unlink(q)); one(q);
  snprintf(q, sizeof q, "%s/link", d); printf("unlink link %d\n", unlink(q));
  // more creating and modifying calls, addressed by the FHS name again
#define E(call) (errno = 0, (call) < 0 ? errno : 0)
  char sub[512], f[512], g[512]; snprintf(sub, sizeof sub, "%s/sub", d); snprintf(f, sizeof f, "%s/sub/a", d); snprintf(g, sizeof g, "%s/sub/b", d);
  printf("mkdir %d again %d\n", E(mkdir(sub, 0700)), E(mkdir(sub, 0700))); one(sub);
  fd = open(f, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600); printf("create excl %d", fd >= 0); if (write(fd, "0123456789", 10) != 10) return 1; close(fd);
  printf(" again %d\n", E(open(f, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600)));
  fd = open(f, O_WRONLY | O_TRUNC | O_CLOEXEC); printf("truncate %d\n", fd >= 0); close(fd); one(f);
  struct timeval tv[2] = {{1000, 0}, {2000, 500000}}; struct stat st2;
  printf("utimes %d", E(utimes(f, tv))); stat(f, &st2); printf(" mtime %lld.%09ld\n", (long long)st2.st_mtim.tv_sec, (long)st2.st_mtim.tv_nsec);
  struct timeval bad[2] = {{1, 2000000}, {1, 0}}; printf("utimes bad %d\n", E(utimes(f, bad)));
  struct timespec ts[2] = {{3000, 1}, {4000, 2}};
  printf("utimensat %d", E(utimensat(AT_FDCWD, f, ts, 0))); stat(f, &st2); printf(" mtime %lld.%09ld\n", (long long)st2.st_mtim.tv_sec, (long)st2.st_mtim.tv_nsec);
  printf("chown self %d lchown %d fchownat %d\n", E(chown(f, getuid(), getgid())), E(lchown(f, getuid(), -1)), E(fchownat(AT_FDCWD, f, -1, getgid(), 0)));
  printf("fchmodat %d nofollow %d", E(fchmodat(AT_FDCWD, f, 0644, 0)), E(fchmodat(AT_FDCWD, f, 0604, AT_SYMLINK_NOFOLLOW))); stat(f, &st2); printf(" mode %o\n", st2.st_mode & 07777);
  printf("faccessat eaccess %d\n", E(faccessat(AT_FDCWD, f, R_OK, AT_EACCESS)));
  printf("symlink %d again %d\n", E(symlink("a", g)), E(symlink("a", g))); one(g);
  printf("link %d\n", E(link(f, q)) != 0);                                  // hard links are refused for app data; only "refused or not" is stable
  unlink(q);
  fd = open(sub, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  printf("renameat %d", E(renameat(fd, "a", AT_FDCWD, q))); printf(" back %d\n", E(rename(q, f))); one(f);
  printf("rmdir non-empty %d unlinkat %d %d rmdir %d\n", E(rmdir(sub)), E(unlinkat(fd, "a", 0)), E(unlinkat(fd, "b", 0)), E(rmdir(sub))); close(fd); one(sub);
  // bionic's own account lookup is never translated; an ordinary open of the same name is
  struct stat sys, got; int have = stat("/system/etc/group", &sys) == 0;
  fd = open("/etc/group", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
  printf("account lookup open untranslated %d\n", fd >= 0 ? (have && fstat(fd, &got) == 0 && got.st_ino == sys.st_ino && got.st_dev == sys.st_dev) : !have); if (fd >= 0) close(fd);
  return 0;
}
