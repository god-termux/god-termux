#pragma once
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
// Fast path past the fhs-run supervisor, ABI 2. Private protocol, not a security boundary: the underlying Android filter still applies.
//
// Under fhs-run the shim translates a path itself and then makes the system call from one fixed place: a three-instruction
// trampoline it maps at FHS_FAST_TRAMP. The supervisor's seccomp filter lets through every call whose instruction pointer is
// that trampoline and intercepts all others as before.
//
// ABI 1 marked such calls with a constant in the unused sixth argument (register x5). That was unsound: the kernel leaves x5
// alone and libc's own system call stubs do not load it, so a marker left behind rode along with the next call made inside
// libc (fopen, opendir, the open inside realpath...), which then skipped the supervisor untranslated; clearing x5 after the
// call still left a signal handler that starts during a marked call with the marker in x5. An address cannot leak that way:
// code in libc or in a signal handler never executes its system calls from the trampoline.
#define FHS_FAST_ABI_VALUE "2"
#define FHS_FAST_TRAMP UINT64_C(0x46480000)        // free in every 64-bit position-independent process; if it is not, the fast path stays off
#define FHS_FAST_IP (FHS_FAST_TRAMP + 8)           // what the kernel reports for the svc below: the address after it
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

// -1: not decided (the shim's constructor has not run yet; libc opens files before that), 0: off, 1: trampoline mapped.
// Decided once per process: a filter is only installed by fhs-run right before exec, and fork() copies both this and the page.
static int fhs_fast_state = -1;
static int fhs_fast_active(void) { return fhs_fast_state > 0; }
static void fhs_fast_init(void) {
  if (fhs_fast_state >= 0) return;
  fhs_fast_state = 0;
  const char* abi = getenv("FHS_FAST_ABI");
  if (!abi || strcmp(abi, FHS_FAST_ABI_VALUE) || !getenv("FHS_SUP") || getenv("FHS_FAST_OFF")) return;
  size_t len = (size_t)sysconf(_SC_PAGESIZE);
  uint32_t* t = mmap((void*)(uintptr_t)FHS_FAST_TRAMP, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0);
  if (t == MAP_FAILED) return;
  if ((uintptr_t)t != FHS_FAST_TRAMP) { munmap(t, len); return; }   // a kernel without MAP_FIXED_NOREPLACE treated the address as a hint
  t[0] = 0xaa0503e8;   // mov x8, x5     system call number, passed as the sixth C argument
  t[1] = 0xd4000001;   // svc #0
  t[2] = 0xd65f03c0;   // ret
  if (mprotect(t, len, PROT_READ | PROT_EXEC)) { munmap(t, len); return; }
  __builtin___clear_cache((char*)t, (char*)(t + 3));
  fhs_fast_state = 1;
}
// A system call with at most five arguments, made from the trampoline. Only valid when fhs_fast_active().
static long fhs_tagged(long nr, long a0, long a1, long a2, long a3, long a4) {
  long r = ((long (*)(long, long, long, long, long, long))(uintptr_t)FHS_FAST_TRAMP)(a0, a1, a2, a3, a4, nr);
  if ((unsigned long)r > -4096UL) { errno = (int)-r; return -1; }
  return r;
}
#define FHS_TAGGED(nr, a, b, c, d, e) fhs_tagged(nr, (long)(a), (long)(b), (long)(c), (long)(d), (long)(e))
// the shim's own system calls: from the trampoline under a supervisor, ordinary otherwise
#define FHS_SYS5(nr, a, b, c, d, e) (fhs_fast_active() ? FHS_TAGGED(nr, a, b, c, d, e) : syscall(nr, a, b, c, d, e))
