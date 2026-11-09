// LD_PRELOAD helper for dapt: Android app uid may not create hard links; fall back to copying the file.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
static int copy(int dirfd_old, const char* o, int dirfd_new, const char* n) {
  int in = openat(dirfd_old, o, O_RDONLY); if (in < 0) return -1;
  struct stat st; fstat(in, &st);
  int out = openat(dirfd_new, n, O_WRONLY | O_CREAT | O_EXCL, st.st_mode & 07777); if (out < 0) { close(in); return -1; }
  char buf[65536]; ssize_t r;
  while ((r = read(in, buf, sizeof buf)) > 0) { ssize_t w = 0; while (w < r) { ssize_t k = write(out, buf + w, r - w); if (k < 0) { close(in); close(out); return -1; } w += k; } }
  close(in); close(out); return r < 0 ? -1 : 0;
}
int linkat(int od, const char* o, int nd, const char* n, int flags) {
  static int (*real)(int, const char*, int, const char*, int); if (!real) real = dlsym(RTLD_NEXT, "linkat");
  int rc = real(od, o, nd, n, flags); if (rc == 0 || (errno != EACCES && errno != EPERM)) return rc;
  return copy(od, o, nd, n);
}
int link(const char* o, const char* n) { return linkat(AT_FDCWD, o, AT_FDCWD, n, 0); }
