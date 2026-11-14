#include <errno.h>
#pragma once
#include <limits.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <time.h>
#define PFX  "/data/data/com.termux/files/usr"
#define GLIB PFX "/glibc"
#define DEB  PFX "/debian"
static int rexists(const char* p) {
#ifdef FHS_BIONIC_FAST
  if (fhs_fast_active()) return FHS_TAGGED(SYS_faccessat, AT_FDCWD, p, F_OK, 0, 0) == 0;
#endif
  return syscall(SYS_faccessat, AT_FDCWD, p, F_OK, 0) == 0;
}
static int pre(const char* p, const char* pf) { size_t n = strlen(pf); return !strncmp(p, pf, n) && (p[n] == '/' || p[n] == 0); }

// Only these namespaces can be remapped. Dispatch before hashing or reading time.
static int fhs_scope(const char* p) {
  switch (p[1]) {
    case 'u': return pre(p, "/usr");
    case 'b': return pre(p, "/bin");
    case 's': return pre(p, "/sbin");
    case 'l': return pre(p, "/lib") || pre(p, "/lib64");
    case 'e': return pre(p, "/etc");
    case 't': return pre(p, "/tmp");
    case 'v': return pre(p, "/var");
    case 'o': return pre(p, "/opt");
    default: return 0;
  }
}


#define FHS_CN 128
#define FHS_CL 384
#define FHS_TTL_MS 1000
struct fhs_ce { unsigned h; unsigned gen; long t; char in[FHS_CL]; char out[FHS_CL]; unsigned out_len; };
static __thread struct fhs_ce fhs_ctab[FHS_CN];
// One-entry MRU avoids hashing repeated paths; the full string is still checked.
static __thread struct fhs_ce* fhs_last;
static volatile unsigned fhs_gen = 1;
static void fhs_cache_clear(void) { fhs_gen++; }
// Can creating/removing this path change a cached mapping? Mappings depend only on what exists below the FHS namespaces
// and the Termux/glibc/Debian trees; /tmp always resolves to one place. Relative paths may point anywhere: clear.
static int fhs_dirty(const char* p) {
  if (!p || p[0] != '/') return 1;
  if (fhs_scope(p)) return !pre(p, "/tmp");
  return (pre(p, PFX) && !pre(p, PFX "/tmp")) || pre(p, GLIB) || pre(p, DEB);   // $TMPDIR (PFX/tmp) is the only target of /tmp as well
}
static long fhs_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC_COARSE, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000; }
static unsigned fhs_hash(const char* p) { unsigned h = 2166136261u; while (*p) h = (h ^ (unsigned char)*p++) * 16777619u; return h; }

// map a "standard Linux" absolute path to a Termux one. out must be PATH_MAX.
static int fhs_trace = -1;
static int fhs_deb = -1;
#ifndef FHS_TLS
#define FHS_TLS   // the shim sets fhs_create around single lookups on any thread and defines this as __thread; the supervisor has one thread
#endif
static FHS_TLS int fhs_create = 0;   // set by the supervisor around creating operations: the Debian tree wins whenever the parent directory exists there
static int fhs_parent_exists(const char* p, size_t n) { char t[PATH_MAX]; while (n && p[n - 1] != '/') n--; if (n < 2) return 0; memcpy(t, p, n - 1); t[n - 1] = 0; return rexists(t); }
// prefix + rest into out (PATH_MAX), truncated like snprintf; returns the length written
static size_t fhs_join(char* out, const char* pf, size_t pl, const char* rest, size_t rl) {
  if (pl + rl > PATH_MAX - 1) rl = PATH_MAX - 1 - pl;
  memcpy(out, pf, pl); memcpy(out + pl, rest, rl); out[pl + rl] = 0; return pl + rl;
}
static void fhs_tr(const char* a, const char* b) { char m[2 * PATH_MAX + 32]; int n = snprintf(m, sizeof m, "fhs: %s -> %s\n", a, b ? b : "(unchanged)"); syscall(SYS_write, 2, m, n); }
// bionic's getpwnam/getgrnam/getpwuid open exactly these two files with O_NOFOLLOW for every name that is not a built-in Android id.
// It cannot use the Termux or Debian files: every line is rejected ("without required prefix") and logged to logd, two more
// intercepted calls per line, on every lookup (measured: 240 intercepts per file dpkg unpacks). Such an open is not translated.
// FHS_SUP_ACCT=1 restores the translation.
static int fhs_acct_open(const char* path, int flags) {
  return (flags & (O_NOFOLLOW | O_ACCMODE | O_CREAT)) == O_NOFOLLOW && (!strcmp(path, "/etc/passwd") || !strcmp(path, "/etc/group")) && !getenv("FHS_SUP_ACCT");
}
static const char* fmap_i(const char* in, char* out);
static const char* fmap(const char* in, char* out) {
  int se = errno;   // the candidate probes (and the trace write) fail with ENOENT/anything: the caller's errno must survive a successful call
  const char* r = fmap_i(in, out);
  if (fhs_trace < 0) fhs_trace = getenv("FHS_TRACE") != 0;
  if (fhs_trace && in && in[0] == '/') fhs_tr(in, r == in ? 0 : r);
  errno = se; return r;
}
static const char* fmap_i(const char* in, char* out) {
  if (!in || in[0] != '/') return in;
  if (!fhs_scope(in)) return in;
  if (!fhs_create && fhs_last && fhs_last->gen == fhs_gen && !strcmp(fhs_last->in, in)
      && fhs_ms() - fhs_last->t < FHS_TTL_MS) {
    memcpy(out, fhs_last->out, fhs_last->out_len + 1); return out;
  }
  // A cache hit needs neither candidate paths nor filesystem probes.
  size_t il = strlen(in); unsigned h = fhs_hash(in); struct fhs_ce* ce = &fhs_ctab[h % FHS_CN]; long now = fhs_ms();
  if (!fhs_create && ce->h == h && ce->gen == fhs_gen && now - ce->t < FHS_TTL_MS && il < FHS_CL && !strcmp(ce->in, in)) {
    fhs_last = ce; memcpy(out, ce->out, ce->out_len + 1); return out; }
  // Miss: build one candidate at a time straight into out and probe it; most paths are settled by the first probe.
  const char *p1, *p2 = 0, *rest = in; size_t l1, l2 = 0; const char* u = in[1] == 'u' ? in + 4 : in;   // u: path below /usr, if any
#define FHS_SET(a, b, skip) (p1 = a, l1 = sizeof(a) - 1, p2 = b, l2 = sizeof(b) - 1, rest = u + (skip))
  if (pre(u, "/lib64")) FHS_SET(GLIB "/lib", PFX "/lib", 6);
  else if (pre(u, "/lib")) FHS_SET(GLIB "/lib", PFX "/lib", 4);
  else if (pre(u, "/sbin")) FHS_SET(PFX "/bin", GLIB "/bin", 5);
  else if (pre(u, "/bin")) FHS_SET(PFX "/bin", GLIB "/bin", 4);
  else if (u != in) FHS_SET(PFX, GLIB, 0);
  else { p1 = PFX; l1 = sizeof(PFX) - 1; }   // /etc /tmp /var /opt
#undef FHS_SET
  size_t rl = il - (size_t)(rest - in), ol;
#define FHS_C1 (ol = fhs_join(out, p1, l1, rest, rl))
#define FHS_C2 (ol = fhs_join(out, p2, l2, rest, rl))
#define FHS_C3 (ol = fhs_join(out, DEB, sizeof(DEB) - 1, in, il))
  if (fhs_deb < 0) fhs_deb = getenv("FHS_DEBIAN") != 0;
  if (fhs_deb && !pre(in, "/tmp")) {   // Debian mode (maintainer scripts): the Debian tree wins, also for creating files
    FHS_C3; if (!rexists(out) && !(fhs_create && fhs_parent_exists(out, ol))) { FHS_C1; if (!rexists(out)) { if (!p2 || (FHS_C2, !rexists(out))) FHS_C3; } }
  } else {
    FHS_C1; if (!rexists(out)) { if (!p2 || (FHS_C2, !rexists(out))) { FHS_C3; if (!rexists(out)) FHS_C1; } }
  }
#undef FHS_C1
#undef FHS_C2
#undef FHS_C3
  if (!fhs_create && il < FHS_CL && ol < FHS_CL) {
    ce->h = h; ce->gen = fhs_gen; ce->t = now; ce->out_len = ol;
    memcpy(ce->in, in, il + 1); memcpy(ce->out, out, ol + 1); fhs_last = ce;
  }
  return out;
}

