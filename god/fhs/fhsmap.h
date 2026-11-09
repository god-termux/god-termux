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
static int rexists(const char* p) { return syscall(SYS_faccessat, AT_FDCWD, p, F_OK, 0) == 0; }
static int pre(const char* p, const char* pf) { size_t n = strlen(pf); return !strncmp(p, pf, n) && (p[n] == '/' || p[n] == 0); }

static const char* skip[] = {"/proc","/sys","/dev","/system","/vendor","/data","/sdcard","/storage","/apex","/mnt","/odm","/product","/system_ext","/linkerconfig",0};

#define FHS_CN 128
#define FHS_CL 384
#define FHS_TTL_MS 1000
struct fhs_ce { unsigned h; unsigned gen; long t; char in[FHS_CL]; char out[FHS_CL]; };
static __thread struct fhs_ce fhs_ctab[FHS_CN];
static volatile unsigned fhs_gen = 1;
static void fhs_cache_clear(void) { fhs_gen++; }
static long fhs_ms(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC_COARSE, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000; }
static unsigned fhs_hash(const char* p) { unsigned h = 2166136261u; while (*p) h = (h ^ (unsigned char)*p++) * 16777619u; return h; }

// map a "standard Linux" absolute path to a Termux one. out must be PATH_MAX.
static int fhs_trace = -1;
static int fhs_deb = -1;
static void fhs_tr(const char* a, const char* b) { char m[2 * PATH_MAX + 32]; int n = snprintf(m, sizeof m, "fhs: %s -> %s\n", a, b ? b : "(unchanged)"); syscall(SYS_write, 2, m, n); }
static const char* fmap_i(const char* in, char* out);
static const char* fmap(const char* in, char* out) {
  const char* r = fmap_i(in, out);
  if (fhs_trace < 0) fhs_trace = getenv("FHS_TRACE") != 0;
  if (fhs_trace && in && in[0] == '/') fhs_tr(in, r == in ? 0 : r);
  return r;
}
static const char* fmap_i(const char* in, char* out) {
  if (!in || in[0] != '/') return in;
  for (int i = 0; skip[i]; i++) if (pre(in, skip[i])) return in;
  const char *c1 = 0, *c2 = 0, *c3 = 0; char a[PATH_MAX], b[PATH_MAX], cc[PATH_MAX]; const char* rest;
  static const char* libs[] = {"/usr/lib64","/usr/lib","/lib64","/lib",0};
  static const char* bins[] = {"/usr/sbin","/usr/bin","/sbin","/bin",0};
  int done = 0;
  for (int i = 0; libs[i] && !done; i++) if (pre(in, libs[i])) { rest = in + strlen(libs[i]);
    snprintf(a, sizeof a, GLIB "/lib%s", rest); snprintf(b, sizeof b, PFX "/lib%s", rest); c1 = a; c2 = b; done = 1; }
  for (int i = 0; bins[i] && !done; i++) if (pre(in, bins[i])) { rest = in + strlen(bins[i]);
    snprintf(a, sizeof a, PFX "/bin%s", rest); snprintf(b, sizeof b, GLIB "/bin%s", rest); c1 = a; c2 = b; done = 1; }
  if (!done && pre(in, "/usr")) { rest = in + 4; snprintf(a, sizeof a, PFX "%s", rest); snprintf(b, sizeof b, GLIB "%s", rest); c1 = a; c2 = b; done = 1; }
  if (!done && (pre(in, "/etc") || pre(in, "/tmp") || pre(in, "/var") || pre(in, "/opt"))) { snprintf(a, sizeof a, PFX "%s", in); c1 = a; done = 1; }
  if (!done) return in;
  snprintf(cc, sizeof cc, DEB "%s", in); c3 = cc;
  size_t il = strlen(in); unsigned h = fhs_hash(in); struct fhs_ce* ce = &fhs_ctab[h % FHS_CN]; long now = fhs_ms();
  if (ce->h == h && ce->gen == fhs_gen && now - ce->t < FHS_TTL_MS && il < FHS_CL && !strcmp(ce->in, in)) {
    strcpy(out, ce->out); return out; }
  const char* pick = c1;
  if (fhs_deb < 0) fhs_deb = getenv("FHS_DEBIAN") != 0;
  if (fhs_deb && !pre(in, "/tmp")) {   // Debian mode (maintainer scripts): the Debian tree wins, also for creating files
    pick = c3; if (!rexists(c3)) { if (rexists(c1)) pick = c1; else if (c2 && rexists(c2)) pick = c2; }
  } else
  if (!rexists(c1)) { if (c2 && rexists(c2)) pick = c2; else if (c3 && rexists(c3)) pick = c3; }
  strncpy(out, pick, PATH_MAX - 1); out[PATH_MAX - 1] = 0;
  if (il < FHS_CL && strlen(out) < FHS_CL) { ce->h = h; ce->gen = fhs_gen; ce->t = now; strcpy(ce->in, in); strcpy(ce->out, out); }
  return out;
}

