// sigsys.c: тест шима (SIGSYS -> ENOSYS для openat2/437 под Android seccomp). Сборка и запуск:
//   PATH=$PREFIX/glibc/bin:$PATH aarch64-linux-gnu-gcc -O1 -o t_glibc god/tests/fhs/sigsys.c
//   (cd god/fhs && PATH=$PREFIX/glibc/bin:$PATH aarch64-linux-gnu-gcc -shared -fPIC -O2 -DGLIBC_BUILD -fuse-ld=bfd -o /tmp/libfhs-glibc.so fhs.c -ldl)
//   linker64 $PREFIX/bin/env LD_PRELOAD=/tmp/libfhs-glibc.so FHS_SIGSYS_LOG=1 ./t_glibc raw|crash   (без LD_PRELOAD: убит сигналом 31)
//   Запускать через linker64 env: env с шимом внутри подменил бы LD_PRELOAD установленным шимом.
// openat2-test: как Android seccomp реагирует на syscall 437 (openat2) и помогает ли обработчик SIGSYS -> ENOSYS.
// usage: t raw | t handler      (каждый режим выполняется в дочернем процессе, родитель печатает, чем тот кончился)
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <ucontext.h>
#include <unistd.h>
#ifndef __NR_openat2
#define __NR_openat2 437
#endif
struct how { uint64_t flags, mode, resolve; };
static volatile int hits;
static void on_sigsys(int sig, siginfo_t* si, void* uc) {
  (void)sig; ucontext_t* c = uc; hits++;
  // si_syscall (nr) лежит в si_call_addr+sizeof(void*)+sizeof(int) -> используем поля через syscall номер в x8
  c->uc_mcontext.regs[0] = (uint64_t)(-ENOSYS);   // результат syscall: -ENOSYS, выполнение продолжается после svc
}
static void app_crash_handler(int sig){ const char m[]="  APP-обработчик SIGSYS вызван (как Crashpad)\n"; if(write(1,m,sizeof m-1)<0){} _exit(99); }
static int child(const char* mode) {
  if (!strcmp(mode, "crash")) { struct sigaction a; memset(&a,0,sizeof a); a.sa_handler=app_crash_handler; sigaction(SIGSYS,&a,0); signal(SIGSYS, app_crash_handler); }
  if (!strcmp(mode, "handler")) {
    struct sigaction sa; memset(&sa, 0, sizeof sa); sa.sa_sigaction = on_sigsys; sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask); if (sigaction(SIGSYS, &sa, 0)) { perror("sigaction"); return 2; }
  }
  struct how h = {O_RDONLY, 0, 0};
  long r = syscall(__NR_openat2, AT_FDCWD, "/proc/self/exe", &h, sizeof h);
  printf("  openat2 -> %ld errno=%d (%s) handler_hits=%d\n", r, r < 0 ? errno : 0, r < 0 ? strerror(errno) : "ok", hits);
  fflush(stdout);
  // после ENOSYS приложение должно суметь продолжить обычным openat
  int fd = openat(AT_FDCWD, "/proc/self/exe", O_RDONLY);
  printf("  openat fallback -> %d\n", fd); fflush(stdout); return 0;
}
int main(int argc, char** argv) {
  const char* mode = argc > 1 ? argv[1] : "raw";
  printf("mode=%s pid=%d\n", mode, getpid()); fflush(stdout);
  pid_t p = fork(); if (p == 0) _exit(child(mode));
  int st; waitpid(p, &st, 0);
  if (WIFSIGNALED(st)) printf("  РЕЗУЛЬТАТ: дочерний процесс убит сигналом %d (%s)\n", WTERMSIG(st), strsignal(WTERMSIG(st)));
  else printf("  РЕЗУЛЬТАТ: дочерний процесс завершился сам, код %d\n", WEXITSTATUS(st));
  return 0;
}
