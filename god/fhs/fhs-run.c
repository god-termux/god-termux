// fhs-run TARGET ARGS...: runs a (static) program under a seccomp user-notif supervisor that rewrites FHS paths.
#define _GNU_SOURCE
#include <linux/seccomp.h>
#include <linux/filter.h>
#include <linux/audit.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <poll.h>
#include <errno.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdint.h>
#include "fhsmap.h"
extern char** environ;
#ifndef __NR_faccessat2
#define __NR_faccessat2 439
#endif
#ifndef __NR_renameat
#define __NR_renameat 38
#endif
static const int NRS[] = {__NR_openat, __NR_newfstatat, __NR_faccessat, __NR_faccessat2, __NR_readlinkat, __NR_statx,
  __NR_mkdirat, __NR_unlinkat, __NR_symlinkat, __NR_linkat, __NR_renameat, __NR_renameat2, __NR_fchmodat, __NR_fchownat, __NR_utimensat, __NR_bind, __NR_connect};
#define NN (sizeof NRS / sizeof NRS[0])

static int send_fd(int s, int fd) { char c = 1; struct iovec io = {&c, 1}; char cb[CMSG_SPACE(sizeof(int))] = {0};
  struct msghdr m = {0}; m.msg_iov = &io; m.msg_iovlen = 1; m.msg_control = cb; m.msg_controllen = sizeof cb;
  struct cmsghdr* h = CMSG_FIRSTHDR(&m); h->cmsg_level = SOL_SOCKET; h->cmsg_type = SCM_RIGHTS; h->cmsg_len = CMSG_LEN(sizeof(int)); memcpy(CMSG_DATA(h), &fd, sizeof(int));
  return sendmsg(s, &m, 0); }
static int recv_fd(int s) { char c; struct iovec io = {&c, 1}; char cb[CMSG_SPACE(sizeof(int))]; struct msghdr m = {0};
  m.msg_iov = &io; m.msg_iovlen = 1; m.msg_control = cb; m.msg_controllen = sizeof cb;
  if (recvmsg(s, &m, 0) <= 0) return -1; struct cmsghdr* h = CMSG_FIRSTHDR(&m); if (!h) return -1; int fd; memcpy(&fd, CMSG_DATA(h), sizeof fd); return fd; }

static int rdstr(int mem, uint64_t addr, char* buf, size_t n) {
  addr &= 0x00ffffffffffffffULL;   // strip the ARM top-byte (heap tag) from user pointers
  ssize_t k = pread(mem, buf, n - 1, addr); if (k <= 0) return -1; buf[k] = 0; return memchr(buf, 0, k + 1) ? 0 : -1; }
static int wr(int mem, uint64_t addr, const void* b, size_t n) { addr &= 0x00ffffffffffffffULL; return pwrite(mem, b, n, addr) == (ssize_t)n ? 0 : -1; }

static void reply(int fd, struct seccomp_notif* rq, long val, int err, int cont) {
  struct seccomp_notif_resp r; memset(&r, 0, sizeof r); r.id = rq->id; r.val = val; r.error = err ? -err : 0; r.flags = cont ? SECCOMP_USER_NOTIF_FLAG_CONTINUE : 0;
  ioctl(fd, SECCOMP_IOCTL_NOTIF_SEND, &r); }


// map path argument at tracee address; returns mapped string, or NULL if unchanged/relative/unreadable (buffers owned by caller)
static const char* mapat(int mem, uint64_t addr, char* orig, char* mp) {
  if (rdstr(mem, addr, orig, PATH_MAX) || orig[0] != '/') return NULL;
  const char* m = fmap(orig, mp); return m == orig ? NULL : m;
}
// relative path of the traced process (dirfd or its cwd) -> absolute path, so that a rename/link with one translated and one relative name can be done here
static const char* absat(int pid, int dirfd, const char* rel, char* out) {
  char lp[64], base[PATH_MAX];
  if (dirfd == AT_FDCWD) snprintf(lp, sizeof lp, "/proc/%d/cwd", pid); else snprintf(lp, sizeof lp, "/proc/%d/fd/%d", pid, dirfd);
  ssize_t n = readlink(lp, base, sizeof base - 1); if (n <= 0) return NULL; base[n] = 0;
  snprintf(out, PATH_MAX, "%s/%s", base, rel); return out;
}
static int handle_write_i(int fd, struct seccomp_notif* rq, int mem);
static int handle_write(int fd, struct seccomp_notif* rq, int mem) { fhs_create = 1; int r = handle_write_i(fd, rq, mem); fhs_create = 0; return r; }
static int handle_write_i(int fd, struct seccomp_notif* rq, int mem) {
  int nr = rq->data.nr; const uint64_t* a = rq->data.args; char o1[PATH_MAX], m1[PATH_MAX], o2[PATH_MAX], m2[PATH_MAX]; long res = 0;
  if (nr == __NR_mkdirat || nr == __NR_unlinkat || nr == __NR_fchmodat || nr == __NR_fchownat || nr == __NR_utimensat) {
    const char* m = mapat(mem, a[1], o1, m1); if (!m) return 0;
    if (nr == __NR_mkdirat) res = syscall(__NR_mkdirat, AT_FDCWD, m, (mode_t)a[2]);
    else if (nr == __NR_unlinkat) res = syscall(__NR_unlinkat, AT_FDCWD, m, (int)a[2]);
    else if (nr == __NR_fchmodat) res = syscall(__NR_fchmodat, AT_FDCWD, m, (mode_t)a[2]);
    else if (nr == __NR_fchownat) res = syscall(__NR_fchownat, AT_FDCWD, m, (uid_t)a[2], (gid_t)a[3], (int)a[4]);
    else { char ts[32]; int has = a[2] != 0; if (has && pread(mem, ts, 32, a[2] & 0x00ffffffffffffffULL) != 32) { reply(fd, rq, 0, EFAULT, 0); return 1; }
           res = syscall(__NR_utimensat, AT_FDCWD, m, has ? ts : NULL, (int)a[3]); }
  } else if (nr == __NR_symlinkat) {
    char tgt[PATH_MAX]; const char* m = mapat(mem, a[2], o1, m1); if (!m) return 0;
    if (rdstr(mem, a[0], tgt, sizeof tgt)) { reply(fd, rq, 0, EFAULT, 0); return 1; }
    res = syscall(__NR_symlinkat, tgt, AT_FDCWD, m);
  } else if (nr == __NR_linkat || nr == __NR_renameat || nr == __NR_renameat2) {
    const char* p1 = mapat(mem, a[1], o1, m1); const char* p2 = mapat(mem, a[3], o2, m2);
    if (!p1 && !p2) return 0;
    if (!p1) { if (rdstr(mem, a[1], o1, sizeof o1)) { reply(fd, rq, 0, EFAULT, 0); return 1; } p1 = o1; if (o1[0] != '/') { if (!absat(rq->pid, (int)a[0], o1, m1)) return 0; p1 = m1; } }
    if (!p2) { if (rdstr(mem, a[3], o2, sizeof o2)) { reply(fd, rq, 0, EFAULT, 0); return 1; } p2 = o2; if (o2[0] != '/') { if (!absat(rq->pid, (int)a[2], o2, m2)) return 0; p2 = m2; } }
    if (nr == __NR_linkat) res = syscall(__NR_linkat, AT_FDCWD, p1, AT_FDCWD, p2, (int)a[4]);
    else if (nr == __NR_renameat2) res = syscall(__NR_renameat2, AT_FDCWD, p1, AT_FDCWD, p2, (unsigned)a[4]);
    else res = syscall(__NR_renameat2, AT_FDCWD, p1, AT_FDCWD, p2, 0u);
  } else return 0;
  if (ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &rq->id) < 0) return 1;
  reply(fd, rq, res < 0 ? 0 : res, res < 0 ? errno : 0, 0); return 1;
}
// AF_UNIX bind/connect with FHS paths (e.g. /tmp/.X11-unix/X0): the socket belongs to the tracee, so operate on a duplicate via pidfd_getfd
static int handle_sock(int fd, struct seccomp_notif* rq, int mem) {
  const uint64_t* a = rq->data.args; socklen_t len = (socklen_t)a[2]; if (len < 3 || len > sizeof(struct sockaddr_un)) return 0;
  struct sockaddr_un sa; memset(&sa, 0, sizeof sa);
  if (pread(mem, &sa, len, a[1] & 0x00ffffffffffffffULL) != (ssize_t)len || sa.sun_family != AF_UNIX || sa.sun_path[0] != '/') return 0;
  char orig[PATH_MAX], mp[PATH_MAX]; snprintf(orig, sizeof orig, "%.*s", (int)(len - offsetof(struct sockaddr_un, sun_path)), sa.sun_path);
  fhs_create = (rq->data.nr == __NR_bind); const char* m = fmap(orig, mp); fhs_create = 0;
  if (m == orig || strlen(m) >= sizeof sa.sun_path) return 0;
  struct sockaddr_un na; memset(&na, 0, sizeof na); na.sun_family = AF_UNIX; strcpy(na.sun_path, m);
  socklen_t nl = offsetof(struct sockaddr_un, sun_path) + strlen(m) + 1;
  int pfd = syscall(434 /*pidfd_open*/, rq->pid, 0); if (pfd < 0) return 0;
  int tfd = syscall(438 /*pidfd_getfd*/, pfd, (int)a[0], 0); close(pfd); if (tfd < 0) return 0;
  if (ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &rq->id) < 0) { close(tfd); return 1; }
  long res = rq->data.nr == __NR_bind ? bind(tfd, (struct sockaddr*)&na, nl) : connect(tfd, (struct sockaddr*)&na, nl); int e = res < 0 ? errno : 0; close(tfd);
  reply(fd, rq, e ? 0 : res, e, 0); return 1;
}
static void handle(int fd, struct seccomp_notif* rq) {
  int nr = rq->data.nr; const uint64_t* a = rq->data.args; int pi = (nr == __NR_openat || nr == __NR_newfstatat || nr == __NR_faccessat || nr == __NR_faccessat2 || nr == __NR_readlinkat || nr == __NR_statx) ? 1 : -1;
  char pp[64]; snprintf(pp, sizeof pp, "/proc/%d/mem", rq->pid); int mem = open(pp, O_RDWR);
  if (mem >= 0 && (nr == __NR_bind || nr == __NR_connect)) { int r = handle_sock(fd, rq, mem); if (!r) reply(fd, rq, 0, 0, 1); close(mem); return; }
  if (mem >= 0 && handle_write(fd, rq, mem)) { close(mem); return; }
  char path[PATH_MAX], mp[PATH_MAX];
  if (mem < 0 || pi < 0 || rdstr(mem, a[pi], path, sizeof path) || path[0] != '/') { if (mem >= 0) close(mem); reply(fd, rq, 0, 0, 1); return; }
  fhs_create = (nr == __NR_openat && ((int)a[2] & O_CREAT)) ? 1 : 0;
  const char* m = fmap(path, mp); fhs_create = 0;
  if (m == path) { close(mem); reply(fd, rq, 0, 0, 1); return; }
  if (ioctl(fd, SECCOMP_IOCTL_NOTIF_ID_VALID, &rq->id) < 0) { close(mem); return; }
  long res = 0; int e = 0;
  if (nr == __NR_openat) {
    int nf = open(m, (int)a[2], (mode_t)a[3]);
    if (nf < 0) { e = errno; }
    else { struct seccomp_notif_addfd af; memset(&af, 0, sizeof af); af.id = rq->id; af.flags = SECCOMP_ADDFD_FLAG_SEND; af.srcfd = nf; af.newfd_flags = ((int)a[2] & O_CLOEXEC) ? O_CLOEXEC : 0;
      int r = ioctl(fd, SECCOMP_IOCTL_NOTIF_ADDFD, &af); close(nf); close(mem); if (r >= 0) return; reply(fd, rq, 0, errno, 0); return; }
  } else if (nr == __NR_newfstatat) {
    char kst[256]; res = syscall(__NR_newfstatat, AT_FDCWD, m, kst, (int)a[3]); if (res < 0) e = errno; else if (wr(mem, a[2], kst, 128)) e = EFAULT;
  } else if (nr == __NR_statx) {
    char kst[256]; res = syscall(__NR_statx, AT_FDCWD, m, (int)a[2], (unsigned)a[3], kst); if (res < 0) e = errno; else if (wr(mem, a[4], kst, 256)) e = EFAULT;
  } else if (nr == __NR_faccessat || nr == __NR_faccessat2) {
    res = syscall(__NR_faccessat, AT_FDCWD, m, (int)a[2], 0); if (res < 0) e = errno;
  } else if (nr == __NR_readlinkat) {
    char lb[PATH_MAX]; size_t sz = a[3] < sizeof lb ? a[3] : sizeof lb; res = readlink(m, lb, sz); if (res < 0) e = errno; else if (wr(mem, a[2], lb, res)) e = EFAULT;
  }
  close(mem); reply(fd, rq, e ? 0 : res, e, 0);
}

static void supervisor(int sock) {
  int fd = recv_fd(sock); close(sock); int dn = open("/dev/null", O_RDWR); dup2(dn, 0); dup2(dn, 1); dup2(dn, 2);
  if (fd < 0) _exit(1);
  struct seccomp_notif_sizes sz; syscall(__NR_seccomp, SECCOMP_GET_NOTIF_SIZES, 0, &sz);
  struct seccomp_notif* rq = malloc(sz.seccomp_notif);
  for (;;) { struct pollfd pf = {fd, POLLIN, 0}; if (poll(&pf, 1, -1) < 0 && errno != EINTR) break;
    if ((pf.revents & (POLLHUP | POLLERR)) && !(pf.revents & POLLIN)) break;
    memset(rq, 0, sz.seccomp_notif); if (ioctl(fd, SECCOMP_IOCTL_NOTIF_RECV, rq) < 0) { if (errno == EINTR || errno == ENOENT) continue; break; }
    handle(fd, rq); }
  _exit(0);
}

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: fhs-run PROG [ARGS]\n"); return 2; }
  int sp[2]; if (socketpair(AF_UNIX, SOCK_STREAM, 0, sp)) return 127;
  pid_t p = fork(); if (p == 0) { close(sp[0]); setsid(); supervisor(sp[1]); }
  close(sp[1]); prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
  struct sock_filter f[2 + NN * 2 + 2]; int k = 0;
  f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
  f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  f[k++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
  for (unsigned i = 0; i < NN; i++) { f[k++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NRS[i], 0, 1); f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_USER_NOTIF); }
  f[k++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
  struct sock_fprog prog = {k, f};
  int lfd = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_NEW_LISTENER, &prog);
  if (lfd < 0) { setenv("FHS_SUP", "1", 1); }   // fallback: run unsupervised
  else { send_fd(sp[0], lfd); close(lfd); setenv("FHS_SUP", "1", 1); }
  close(sp[0]);
  char* const* av = argv + 1; syscall(SYS_execve, argv[1], av, environ); perror("fhs-run exec"); return 127;
}
