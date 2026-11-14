// Path-carrying creating calls of a glibc program with the shim linked in: /tmp names must land in $PREFIX/tmp (no supervisor).
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>
#include <utime.h>
#include <sys/xattr.h>
#define PFX "/data/data/com.termux/files/usr"
#include <stdlib.h>
#define T "/tmp/fhs-fsops-test"
#define R PFX "/tmp/fhs-fsops-test"
static int exists(const char* p) { struct stat st; return lstat(p, &st) == 0; }
#define OK(x) do { if ((x) != 0) { fprintf(stderr, "%s:%d: %s: %s\n", __FILE__, __LINE__, #x, strerror(errno)); return 1; } } while (0)
int bmain(int argc, char** argv) {
  (void)argc; (void)argv;
  system("rm -rf " R "*");
  OK(mkdir(T, 0700)); assert(exists(R));                                   // mkdir (already hooked)
  OK(mkdirat(AT_FDCWD, T "/sub", 0700)); assert(exists(R "/sub"));
  int fd = open(T "/a", O_CREAT | O_WRONLY, 0600); assert(fd >= 0); assert(write(fd, "hi", 2) == 2); close(fd); assert(exists(R "/a"));
  OK(rename(T "/a", T "/b")); assert(!exists(R "/a") && exists(R "/b"));
  OK(renameat(AT_FDCWD, T "/b", AT_FDCWD, T "/c")); assert(exists(R "/c"));
  OK(renameat2(AT_FDCWD, T "/c", AT_FDCWD, T "/d", 0)); assert(exists(R "/d"));
  OK(symlink("target-stays-verbatim", T "/ln")); char buf[64]; ssize_t n = readlink(R "/ln", buf, sizeof buf - 1); assert(n > 0); buf[n] = 0; assert(!strcmp(buf, "target-stays-verbatim"));
  OK(symlinkat("x", AT_FDCWD, T "/ln2")); assert(exists(R "/ln2"));
  // Android forbids hard links for apps (SELinux): then only the absence of ENOENT shows the path was mapped
  if (link(T "/d", T "/hard")) assert(errno == EACCES || errno == EPERM); else assert(exists(R "/hard"));
  if (linkat(AT_FDCWD, T "/d", AT_FDCWD, T "/hard2", 0)) assert(errno == EACCES || errno == EPERM); else assert(exists(R "/hard2"));
  OK(chmod(T "/d", 0640)); struct stat st; assert(stat(R "/d", &st) == 0 && (st.st_mode & 0777) == 0640);
  OK(fchmodat(AT_FDCWD, T "/d", 0600, 0));
  OK(chown(T "/d", getuid(), getgid())); OK(lchown(T "/ln", getuid(), getgid())); OK(fchownat(AT_FDCWD, T "/d", getuid(), getgid(), 0));
  struct timespec ts[2] = {{1000000000, 0}, {1000000000, 0}}; OK(utimensat(AT_FDCWD, T "/d", ts, 0)); assert(stat(R "/d", &st) == 0 && st.st_mtime == 1000000000);
  struct timeval tv[2] = {{1100000000, 5}, {1100000000, 5}}; OK(utimes(T "/d", tv)); assert(stat(R "/d", &st) == 0 && st.st_mtime == 1100000000);
  errno = 0; tv[0].tv_usec = 2000000; assert(utimes(T "/d", tv) == -1 && errno == EINVAL);
  // the remaining families
  struct utimbuf ub = {1200000000, 1200000000}; OK(utime(T "/d", &ub)); assert(stat(R "/d", &st) == 0 && st.st_mtime == 1200000000);
  tv[0].tv_usec = tv[1].tv_usec = 0; tv[0].tv_sec = tv[1].tv_sec = 1300000000; OK(lutimes(T "/ln2", tv)); OK(futimesat(AT_FDCWD, T "/d", tv)); assert(stat(R "/d", &st) == 0 && st.st_mtime == 1300000000);
  OK(truncate(T "/d", 0)); OK(truncate64(T "/d", 0)); assert(stat(R "/d", &st) == 0 && st.st_size == 0);
  OK(mkfifo(T "/ff", 0600)); assert(exists(R "/ff") && (lstat(R "/ff", &st), S_ISFIFO(st.st_mode)));
  OK(mkfifoat(AT_FDCWD, T "/ff2", 0600)); OK(mknod(T "/ff3", S_IFIFO | 0600, 0)); OK(mknodat(AT_FDCWD, T "/ff4", S_IFIFO | 0600, 0)); assert(exists(R "/ff2") && exists(R "/ff3") && exists(R "/ff4"));
  // xattrs may be refused for apps; what matters is that the path was found (ENOENT would mean it was not mapped)
  errno = 0; setxattr(T "/d", "user.t", "v", 1, 0); assert(errno != ENOENT);
  errno = 0; getxattr(T "/d", "user.t", buf, sizeof buf); assert(errno != ENOENT);
  errno = 0; listxattr(T "/d", buf, sizeof buf); assert(errno != ENOENT);
  errno = 0; removexattr(T "/d", "user.t"); assert(errno != ENOENT);
  errno = 0; lsetxattr(T "/ln2", "user.t", "v", 1, 0); assert(errno != ENOENT);
  // a lookup cached before a rename must not outlive it
  assert(stat(T "/d", &st) == 0); OK(rename(T "/d", T "/e")); assert(stat(T "/d", &st) == -1 && errno == ENOENT); assert(stat(T "/e", &st) == 0);
  OK(unlinkat(AT_FDCWD, T "/e", 0)); OK(unlinkat(AT_FDCWD, T "/sub", AT_REMOVEDIR)); assert(!exists(R "/e") && !exists(R "/sub"));
  system("rm -rf " R "*");
  puts("OK"); return 0;
}
