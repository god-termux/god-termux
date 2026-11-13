// Operations that must be translated by the fhs-run supervisor. Raw system calls; argv[1] is a scratch directory given as /tmp/NAME
// (an FHS path that only exists through the supervisor). Prints one line per check; the output must be identical for every supervisor version.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <unistd.h>
static char dir[256];
static const char* P(const char* name) { static char b[4][512]; static int i; char* r = b[i++ & 3]; snprintf(r, 512, "%s/%s", dir, name); return r; }
static void say(const char* what, long r) { printf("%s: %ld%s%s\n", what, r < 0 ? -1L : r, r < 0 ? " " : "", r < 0 ? strerror(errno) : ""); }
int main(int argc, char** argv) {
  struct stat st; char buf[512]; snprintf(dir, sizeof dir, "%s", argv[1]);
  say("mkdir", syscall(SYS_mkdirat, AT_FDCWD, P("d"), 0700));
  say("mkdir again", syscall(SYS_mkdirat, AT_FDCWD, P("d"), 0700));
  int fd = syscall(SYS_openat, AT_FDCWD, P("d/file"), O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
  say("create", fd >= 0); say("write", write(fd, "hello", 5)); say("cloexec", fcntl(fd, F_GETFD)); close(fd);
  say("stat", syscall(SYS_newfstatat, AT_FDCWD, P("d/file"), &st, 0)); say("size", st.st_size); say("mode", st.st_mode & 07777);
  struct statx sx; say("statx", syscall(SYS_statx, AT_FDCWD, P("d/file"), 0, STATX_SIZE, &sx)); say("statx size", sx.stx_size);
  say("access", syscall(SYS_faccessat, AT_FDCWD, P("d/file"), R_OK)); say("access missing", syscall(SYS_faccessat, AT_FDCWD, P("d/none"), R_OK));
  say("chmod", syscall(SYS_fchmodat, AT_FDCWD, P("d/file"), 0640));
  syscall(SYS_newfstatat, AT_FDCWD, P("d/file"), &st, 0); say("mode after chmod", st.st_mode & 07777);
  struct timespec ts[2] = {{1000, 0}, {2000, 0}}; say("utimens", syscall(SYS_utimensat, AT_FDCWD, P("d/file"), ts, 0));
  syscall(SYS_newfstatat, AT_FDCWD, P("d/file"), &st, 0); say("mtime", st.st_mtim.tv_sec);
  say("symlink", syscall(SYS_symlinkat, "file", AT_FDCWD, P("d/link")));
  long n = syscall(SYS_readlinkat, AT_FDCWD, P("d/link"), buf, sizeof buf); say("readlink", n); if (n > 0) printf("target: %.*s\n", (int)n, buf);
  say("lstat link", syscall(SYS_newfstatat, AT_FDCWD, P("d/link"), &st, AT_SYMLINK_NOFOLLOW)); say("is link", S_ISLNK(st.st_mode));
  say("rename", syscall(SYS_renameat2, AT_FDCWD, P("d/file"), AT_FDCWD, P("d/file2"), 0));
  say("old gone", syscall(SYS_newfstatat, AT_FDCWD, P("d/file"), &st, 0));
  say("chdir", chdir("/data/data/com.termux/files/usr/tmp")); const char* base = strrchr(dir, '/') + 1;
  snprintf(buf, sizeof buf, "%s/d/file2", base);   // relative source, translated destination
  say("rename relative->fhs", syscall(SYS_renameat2, AT_FDCWD, buf, AT_FDCWD, P("d/file3"), 0));
  say("link", syscall(SYS_linkat, AT_FDCWD, P("d/file3"), AT_FDCWD, P("d/hard"), 0));
  fd = syscall(SYS_openat, AT_FDCWD, P("d/file3"), O_RDONLY, 0); n = read(fd, buf, sizeof buf); say("read back", n); say("no cloexec", fcntl(fd, F_GETFD)); close(fd);
  // A path that ends exactly at the end of a page whose successor is unmapped, and one that crosses a page boundary.
  char* m = mmap(0, 8192, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0); const char* f3 = P("d/file3"); size_t l = strlen(f3) + 1;
  memcpy(m + 4096 - 7, f3, l); say("stat across pages", syscall(SYS_newfstatat, AT_FDCWD, m + 4096 - 7, &st, 0));
  munmap(m + 4096, 4096); memcpy(m + 4096 - l, f3, l); say("stat at page end", syscall(SYS_newfstatat, AT_FDCWD, m + 4096 - l, &st, 0));
  memset(m, 'a', 4096); m[0] = '/'; say("unterminated", syscall(SYS_newfstatat, AT_FDCWD, m + 4000, &st, 0));
  char lng[5000]; memset(lng, 'b', sizeof lng); memcpy(lng, "/usr/", 5); lng[4999] = 0; say("too long", syscall(SYS_newfstatat, AT_FDCWD, lng, &st, 0));
  say("bad pointer", syscall(SYS_newfstatat, AT_FDCWD, (char*)16, &st, 0));
  say("stat into bad buffer", syscall(SYS_newfstatat, AT_FDCWD, P("d/file3"), (void*)16, 0));
  // AF_UNIX bind/connect through an FHS path
  struct sockaddr_un sa = {0}; sa.sun_family = AF_UNIX; snprintf(sa.sun_path, sizeof sa.sun_path, "%s", P("d/sock"));
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); say("bind", bind(s, (struct sockaddr*)&sa, sizeof sa)); say("listen", listen(s, 1));
  int c = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); say("connect", connect(c, (struct sockaddr*)&sa, sizeof sa));
  say("socket file", syscall(SYS_newfstatat, AT_FDCWD, P("d/sock"), &st, 0)); say("is socket", S_ISSOCK(st.st_mode));
  say("unlink sock", syscall(SYS_unlinkat, AT_FDCWD, P("d/sock"), 0)); say("unlink link", syscall(SYS_unlinkat, AT_FDCWD, P("d/link"), 0));
  say("unlink hard", syscall(SYS_unlinkat, AT_FDCWD, P("d/hard"), 0)); say("unlink file3", syscall(SYS_unlinkat, AT_FDCWD, P("d/file3"), 0));
  say("rmdir", syscall(SYS_unlinkat, AT_FDCWD, P("d"), AT_REMOVEDIR)); say("dir gone", syscall(SYS_newfstatat, AT_FDCWD, P("d"), &st, 0));
  // bionic's own account lookups (O_NOFOLLOW) are left to the kernel; an ordinary open of the same name is still translated.
  // Lines starting with "new:" describe behaviour the baseline supervisor does not have.
  struct stat sys, got; int have = syscall(SYS_newfstatat, AT_FDCWD, "/system/etc/group", &sys, 0) == 0;
  fd = syscall(SYS_openat, AT_FDCWD, "/etc/group", O_RDONLY | O_NOFOLLOW | O_CLOEXEC, 0);
  int same = fd >= 0 ? (have && fstat(fd, &got) == 0 && got.st_ino == sys.st_ino && got.st_dev == sys.st_dev) : !have;
  printf("new: account lookup open not redirected: %d\n", same); if (fd >= 0) close(fd);
  fd = syscall(SYS_openat, AT_FDCWD, "/etc/group", O_RDONLY | O_CLOEXEC, 0);
  same = fd >= 0 && have && fstat(fd, &got) == 0 && got.st_ino == sys.st_ino && got.st_dev == sys.st_dev;
  printf("new: ordinary open still redirected: %d\n", !same); if (fd >= 0) close(fd);
  // O_PATH opens of a translated name (the kernel cannot pass such a descriptor on; the supervisor substitutes a read-only one)
  fd = syscall(SYS_openat, AT_FDCWD, "/usr/bin", O_PATH | O_DIRECTORY | O_CLOEXEC, 0);
  printf("new: O_PATH open: %d usable as directory: %d\n", fd >= 0, fd >= 0 && syscall(SYS_newfstatat, fd, "sh", &got, 0) == 0); if (fd >= 0) close(fd);
  return 0;
}
