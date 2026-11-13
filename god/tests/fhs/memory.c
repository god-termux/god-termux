// Compile with clang memory.c -ldl; includes the shim to exercise its ownership paths.
#define _GNU_SOURCE
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <spawn.h>
static void* live[128];
static size_t sizes[128];
static int alive, fail_after = -1;
static void* tracked_malloc(size_t n) {
  if (fail_after == 0) { errno = ENOMEM; return NULL; }
  if (fail_after > 0) fail_after--;
  unsigned char* raw = malloc(n + 32); assert(raw); memset(raw, 0xa5, n + 32);
  void* p = raw + 16; assert(alive < 128); sizes[alive] = n; live[alive++] = p; return p;
}
static void tracked_free(void* p) {
  if (!p) return;
  int i; for (i = 0; i < alive && live[i] != p; i++);
  assert(i < alive); unsigned char* raw = (unsigned char*)p - 16;
  for (int j = 0; j < 16; j++) { assert(raw[j] == 0xa5); assert(raw[16 + sizes[i] + j] == 0xa5); }
  --alive; live[i] = live[alive]; sizes[i] = sizes[alive]; free(raw);
}
static char* tracked_strdup(const char* s) {
  char* p = tracked_malloc(strlen(s) + 1); if (p) strcpy(p, s); return p;
}
#define malloc tracked_malloc
#define free tracked_free
#define strdup tracked_strdup
#include "../../fhs/fhs.c"
#undef malloc
#undef free
#undef strdup
static int fake_spawn(pid_t* pid, const char* path, const posix_spawn_file_actions_t* fa,
                      const posix_spawnattr_t* attr, char* const av[], char* const env[]) {
  (void)pid; (void)fa; (void)attr;
  assert(path && av && env); return EAGAIN;
}
int main(int argc, char** argv) {
  assert(argc == 3); setenv("FHS_NOCACHE", "1", 1);
  char* av[] = {argv[1], "argument", NULL}; char* env[] = {"A=B", "FHS_STATIC=0", NULL};
  // Failure cleanup after several shebang allocations, preserving errno.
  for (int f = 0; f < 16; f++) {
    fail_after = f; assert(execve(argv[1], av, env) == -1);
    assert(errno == ENOMEM || errno == ENOENT); assert(alive == 0);
  }
  // Exercise the glibc loader argv/environment and every allocation failure.
  for (int f = 0; f < 8; f++) {
    struct prep_mem m = {0}; const char* path; char **args, **e;
    fail_after = f;
    int rc = prepare(&m, argv[2], av, env, &path, &args, &e);
    if (!rc) { assert(!strcmp(path, LDSO)); assert(!strcmp(args[5], argv[2])); assert(!strcmp(args[6], "argument")); assert(args[7] == NULL); }
    else assert(errno == ENOMEM);
    prep_free(&m); assert(alive == 0);
  }
  fail_after = -1; real_spawn = fake_spawn;
  for (int i = 0; i < 10000; i++) {
    assert(posix_spawn(NULL, argv[1], NULL, NULL, av, env) == EAGAIN);
    assert(alive == 0);
    assert(posix_spawn(NULL, argv[2], NULL, NULL, av, env) == EAGAIN); assert(alive == 0);
  }
  // A preload larger than the old 16 KiB limit, including filtered shim entries.
  char* old = malloc(40000); strcpy(old, "LD_PRELOAD=libfhs-old.so:");
  memset(old + strlen(old), 'x', 32768); old[23 + 32768] = 0;
  char* longenv[] = {old, "LD_LIBRARY_PATH=/keep", "FHS_EXE=/old", NULL};
  struct prep_mem m = {0};
  char** e = envcopy(&m, longenv, SHIM_B, 0, 1); assert(e);
  assert(strstr(e[2], "LD_PRELOAD=" SHIM_B ":")); assert(!strstr(e[2], "libfhs-old"));
  e = envexe(&m, e, "/new"); assert(e); assert(!strcmp(e[2], "FHS_EXE=/new"));
  prep_free(&m); assert(alive == 0); free(old);
  puts("PASS: 20000 spawn preparations, guarded allocations, allocation failures, failed exec, glibc argv, long preload");
}
