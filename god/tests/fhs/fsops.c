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
#include <dirent.h>
#include <ftw.h>
#include <glob.h>
#include <limits.h>
#define PFX "/data/data/com.termux/files/usr"
#include <stdlib.h>
#define T "/tmp/fhs-fsops-test"
#define R PFX "/tmp/fhs-fsops-test"
static int exists(const char* p) { struct stat st; return lstat(p, &st) == 0; }
static int ftw_seen;
static int fcb(const char* p, const struct stat* s, int t) { (void)p; (void)s; (void)t; ftw_seen++; return 0; }
static int fcb64(const char* p, const struct stat64* s, int t) { (void)p; (void)s; (void)t; ftw_seen++; return 0; }
static int ncb(const char* p, const struct stat* s, int t, struct FTW* f) { (void)p; (void)s; (void)t; (void)f; ftw_seen++; return 0; }
static int ncb64(const char* p, const struct stat64* s, int t, struct FTW* f) { (void)p; (void)s; (void)t; (void)f; ftw_seen++; return 0; }
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
  // higher-level libc entry points (realpath, scandir*, ftw*/nftw*, glob*, creat*, freopen*, remove)
  char rbuf[PATH_MAX]; assert(realpath(T "/d", rbuf) == rbuf && !strcmp(rbuf, R "/d"));
  struct dirent** nl = 0; int sc = scandir(T, &nl, 0, alphasort); assert(sc > 0); while (sc--) free(nl[sc]); free(nl);
  struct dirent64** nl64 = 0; sc = scandir64(T, &nl64, 0, alphasort64); assert(sc > 0); while (sc--) free(nl64[sc]); free(nl64);
  sc = scandirat(AT_FDCWD, T, &nl, 0, alphasort); assert(sc > 0); while (sc--) free(nl[sc]); free(nl);
  sc = scandirat64(AT_FDCWD, T, &nl64, 0, alphasort64); assert(sc > 0); while (sc--) free(nl64[sc]); free(nl64);
  ftw_seen = 0; OK(ftw(T, fcb, 4)); assert(ftw_seen > 0);
  ftw_seen = 0; OK(ftw64(T, fcb64, 4)); assert(ftw_seen > 0);
  ftw_seen = 0; OK(nftw(T, ncb, 4, FTW_PHYS)); assert(ftw_seen > 0);
  ftw_seen = 0; OK(nftw64(T, ncb64, 4, FTW_PHYS)); assert(ftw_seen > 0);
  glob_t gl = {0}; OK(glob(T "/d*", 0, 0, &gl)); assert(gl.gl_pathc == 1 && !strcmp(gl.gl_pathv[0], T "/d")); globfree(&gl);
  glob64_t gl64 = {0}; OK(glob64(T "/d*", 0, 0, &gl64)); assert(gl64.gl_pathc == 1 && !strcmp(gl64.gl_pathv[0], T "/d")); globfree64(&gl64);
  int cfd = creat(T "/cr", 0600); assert(cfd >= 0); close(cfd); assert(exists(R "/cr"));
  cfd = creat64(T "/cr2", 0600); assert(cfd >= 0); close(cfd); assert(exists(R "/cr2"));
  FILE* fp = fopen(T "/cr", "r"); assert(fp); fp = freopen(T "/cr2", "w", fp); assert(fp); fp = freopen64(T "/cr", "r", fp); assert(fp); fclose(fp);
  OK(remove(T "/cr")); OK(remove(T "/cr2")); assert(!exists(R "/cr") && !exists(R "/cr2"));
  OK(mkdir(T "/rmd", 0700)); assert(exists(R "/rmd")); OK(remove(T "/rmd")); assert(!exists(R "/rmd"));
  // a lookup cached before a rename must not outlive it
  assert(stat(T "/d", &st) == 0); OK(rename(T "/d", T "/e")); assert(stat(T "/d", &st) == -1 && errno == ENOENT); assert(stat(T "/e", &st) == 0);
  OK(unlinkat(AT_FDCWD, T "/e", 0)); OK(unlinkat(AT_FDCWD, T "/sub", AT_REMOVEDIR)); assert(!exists(R "/e") && !exists(R "/sub"));
  system("rm -rf " R "*");
  // read-only lookups across the trees: names stay in FHS form, content comes from whichever tree has the file
  { glob_t g = {0}; OK(glob("/usr/bin/l[s]", 0, 0, &g)); assert(g.gl_pathc == 1 && !strcmp(g.gl_pathv[0], "/usr/bin/ls")); globfree(&g);          // Termux tree
    OK(glob("/usr/lib/libc.so.[6]", 0, 0, &g)); assert(g.gl_pathc == 1 && !strcmp(g.gl_pathv[0], "/usr/lib/libc.so.6")); globfree(&g);          // glibc tree
    OK(glob("/etc/prof*", 0, 0, &g)); assert(g.gl_pathc >= 1 && !strncmp(g.gl_pathv[0], "/etc/prof", 9)); globfree(&g);
    assert(glob("/usr/bin/no-such-file-*", 0, 0, &g) == GLOB_NOMATCH); globfree(&g);
    OK(glob("/usr/bin/l[s]", GLOB_MARK, 0, &g)); globfree(&g);   // flags other than ALTDIRFUNC pass through
    OK(glob("/usr/b[i]n", GLOB_ONLYDIR, 0, &g)); assert(g.gl_pathc == 1); globfree(&g); }
  { struct dirent** l = 0; int c = scandir("/usr/bin", &l, 0, alphasort); assert(c > 10); int hit = 0; while (c--) { if (!strcmp(l[c]->d_name, "ls")) hit = 1; free(l[c]); } free(l); assert(hit); }
  { int c = scandir("/usr/lib", &(struct dirent**){0}, 0, 0); assert(c >= 0); }
  { char rb[PATH_MAX]; assert(realpath("/usr/bin/ls", rb) && !strncmp(rb, PFX "/", sizeof PFX));
    errno = 0; assert(!realpath("/usr/bin/definitely-missing", rb) && errno == ENOENT);
    assert(realpath("/usr/lib/libc.so.6", rb) && strstr(rb, "libc")); }
  { ftw_seen = 0; OK(nftw("/etc", ncb, 8, FTW_PHYS)); assert(ftw_seen > 3); }
  errno = 0; assert(nftw("/usr/bin/definitely-missing", ncb, 4, 0) == -1 && errno == ENOENT);
  // creating inside a mapped directory that already exists in the Termux tree
  { FILE* f = fopen("/etc/fhs-fsops-probe", "w"); if (f) { fclose(f); assert(exists(PFX "/etc/fhs-fsops-probe")); OK(remove("/etc/fhs-fsops-probe")); } }
  puts("OK"); return 0;
}
