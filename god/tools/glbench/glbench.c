/* glbench: tiny OpenGL ES 2 benchmark for Termux. Loads the EGL/GLES libraries by path, so the same test can be run
 * through the platform driver and through ANGLE (libEGL_angle.so / libGLESv2_angle.so from /system/lib64), without any app.
 *   glbench default            platform driver (/system/lib64/libEGL.so + libGLESv2.so)
 *   glbench angle [vulkan]     ANGLE from /system/lib64
 *   glbench EGLLIB GLESLIB     any pair of libraries
 * Offscreen (pbuffer) 1280x720; prints the renderer string and numbers; exit code 0 = benchmark ran. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#define W 1280
#define H 720
static void* egl; static void* gles;
#define FN(h, name) static __typeof__(&name) p_##name
#define LOADFN(h, name) do { p_##name = (void*)dlsym(h, #name); if (!p_##name) { fprintf(stderr, "glbench: нет функции " #name " (%s)\n", dlerror()); return 0; } } while (0)

FN(egl, eglGetDisplay); FN(egl, eglInitialize); FN(egl, eglChooseConfig); FN(egl, eglCreatePbufferSurface);
FN(egl, eglCreateContext); FN(egl, eglMakeCurrent); FN(egl, eglBindAPI); FN(egl, eglGetError); FN(egl, eglQueryString);
FN(egl, eglGetProcAddress); FN(egl, eglTerminate);
FN(gles, glCreateShader); FN(gles, glShaderSource); FN(gles, glCompileShader); FN(gles, glGetShaderiv); FN(gles, glGetShaderInfoLog);
FN(gles, glCreateProgram); FN(gles, glAttachShader); FN(gles, glLinkProgram); FN(gles, glGetProgramiv); FN(gles, glUseProgram);
FN(gles, glGetAttribLocation); FN(gles, glGetUniformLocation); FN(gles, glUniform1f); FN(gles, glUniform2f);
FN(gles, glVertexAttribPointer); FN(gles, glEnableVertexAttribArray); FN(gles, glDrawArrays); FN(gles, glViewport);
FN(gles, glClearColor); FN(gles, glClear); FN(gles, glFinish); FN(gles, glGetString); FN(gles, glGetError); FN(gles, glReadPixels);

static int load_all(void) {
  LOADFN(egl, eglGetDisplay); LOADFN(egl, eglInitialize); LOADFN(egl, eglChooseConfig); LOADFN(egl, eglCreatePbufferSurface);
  LOADFN(egl, eglCreateContext); LOADFN(egl, eglMakeCurrent); LOADFN(egl, eglBindAPI); LOADFN(egl, eglGetError);
  LOADFN(egl, eglQueryString); LOADFN(egl, eglGetProcAddress); LOADFN(egl, eglTerminate);
  LOADFN(gles, glCreateShader); LOADFN(gles, glShaderSource); LOADFN(gles, glCompileShader); LOADFN(gles, glGetShaderiv);
  LOADFN(gles, glGetShaderInfoLog); LOADFN(gles, glCreateProgram); LOADFN(gles, glAttachShader); LOADFN(gles, glLinkProgram);
  LOADFN(gles, glGetProgramiv); LOADFN(gles, glUseProgram); LOADFN(gles, glGetAttribLocation); LOADFN(gles, glGetUniformLocation);
  LOADFN(gles, glUniform1f); LOADFN(gles, glUniform2f); LOADFN(gles, glVertexAttribPointer); LOADFN(gles, glEnableVertexAttribArray);
  LOADFN(gles, glDrawArrays); LOADFN(gles, glViewport); LOADFN(gles, glClearColor); LOADFN(gles, glClear); LOADFN(gles, glFinish);
  LOADFN(gles, glGetString); LOADFN(gles, glGetError); LOADFN(gles, glReadPixels);
  return 1;
}

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

static GLuint build(const char* vs, const char* fs) {
  GLuint v = p_glCreateShader(GL_VERTEX_SHADER), f = p_glCreateShader(GL_FRAGMENT_SHADER), p = p_glCreateProgram();
  const char* src[2] = { vs, fs }; GLuint sh[2] = { v, f }; GLint ok; char log[1024];
  for (int i = 0; i < 2; i++) {
    p_glShaderSource(sh[i], 1, &src[i], 0); p_glCompileShader(sh[i]); p_glGetShaderiv(sh[i], GL_COMPILE_STATUS, &ok);
    if (!ok) { p_glGetShaderInfoLog(sh[i], sizeof log, 0, log); fprintf(stderr, "glbench: шейдер %d: %s\n", i, log); return 0; }
    p_glAttachShader(p, sh[i]);
  }
  p_glLinkProgram(p); p_glGetProgramiv(p, GL_LINK_STATUS, &ok);
  if (!ok) { fprintf(stderr, "glbench: линковка не удалась\n"); return 0; }
  return p;
}

static const char* VS = "attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }";
/* heavy fragment shader: 48 iterations of sin/cos mixing per pixel */
static const char* FS_HEAVY =
  "precision mediump float; uniform vec2 r; uniform float t;"
  "void main(){ vec2 p = gl_FragCoord.xy / r; float s = 0.0;"
  "for (int i = 0; i < 48; i++) { float k = float(i) * 0.37 + t; s += sin(p.x * 11.0 + k) * cos(p.y * 9.0 - k * 0.7); p = p.yx + vec2(0.01, 0.02) * s; }"
  "gl_FragColor = vec4(0.5 + 0.5 * sin(s), 0.5 + 0.5 * cos(s * 1.3), 0.5 + 0.5 * sin(s * 0.7), 1.0); }";
static const char* FS_FLAT = "precision mediump float; uniform float t; void main(){ gl_FragColor = vec4(t, 0.2, 0.4, 1.0); }";

int main(int argc, char** argv) {
  const char* el = "/system/lib64/libEGL.so"; const char* gl = "/system/lib64/libGLESv2.so"; int angle = 0, vulkan = 0;
  if (argc < 2 || !strcmp(argv[1], "default")) { /* defaults */ }
  else if (!strcmp(argv[1], "angle")) { el = "/system/lib64/libEGL_angle.so"; gl = "/system/lib64/libGLESv2_angle.so"; angle = 1; vulkan = argc > 2 && !strcmp(argv[2], "vulkan"); }
  else if (argc >= 3) { el = argv[1]; gl = argv[2]; }
  else { fprintf(stderr, "usage: glbench [default | angle [vulkan] | EGLLIB GLESLIB]\n"); return 2; }
  printf("== glbench %s\n   EGL: %s\n   GLES: %s\n", argc < 2 ? "default" : argv[1], el, gl);
  egl = dlopen(el, RTLD_NOW | RTLD_LOCAL); if (!egl) { printf("   ОШИБКА: не удалось загрузить EGL: %s\n", dlerror()); return 3; }
  gles = dlopen(gl, RTLD_NOW | RTLD_LOCAL); if (!gles) { printf("   ОШИБКА: не удалось загрузить GLES: %s\n", dlerror()); return 3; }
  if (!load_all()) return 3;

  EGLDisplay d = EGL_NO_DISPLAY;
  if (angle && vulkan) {
    PFNEGLGETPLATFORMDISPLAYEXTPROC gpd = (PFNEGLGETPLATFORMDISPLAYEXTPROC)p_eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLint at[] = { 0x3203 /*EGL_PLATFORM_ANGLE_TYPE_ANGLE*/, 0x3450 /*VULKAN*/, EGL_NONE };
    if (gpd) d = gpd(0x3202 /*EGL_PLATFORM_ANGLE_ANGLE*/, EGL_DEFAULT_DISPLAY, at);
  }
  if (d == EGL_NO_DISPLAY) d = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
  EGLint maj, min;
  if (d == EGL_NO_DISPLAY || !p_eglInitialize(d, &maj, &min)) { printf("   ОШИБКА: eglInitialize (egl error 0x%x)\n", p_eglGetError()); return 4; }
  printf("   EGL %d.%d, vendor: %s\n", maj, min, p_eglQueryString(d, EGL_VENDOR));
  EGLint ca[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
  EGLConfig cfg; EGLint n = 0;
  if (!p_eglChooseConfig(d, ca, &cfg, 1, &n) || n < 1) { printf("   ОШИБКА: нет подходящей конфигурации (0x%x)\n", p_eglGetError()); return 4; }
  EGLint pa[] = { EGL_WIDTH, W, EGL_HEIGHT, H, EGL_NONE };
  EGLSurface s = p_eglCreatePbufferSurface(d, cfg, pa);
  if (s == EGL_NO_SURFACE) { printf("   ОШИБКА: pbuffer (0x%x)\n", p_eglGetError()); return 4; }
  p_eglBindAPI(EGL_OPENGL_ES_API);
  EGLint xa[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
  EGLContext c = p_eglCreateContext(d, cfg, EGL_NO_CONTEXT, xa);
  if (c == EGL_NO_CONTEXT || !p_eglMakeCurrent(d, s, s, c)) { printf("   ОШИБКА: контекст (0x%x)\n", p_eglGetError()); return 4; }
  printf("   GL_VENDOR:   %s\n   GL_RENDERER: %s\n   GL_VERSION:  %s\n", p_glGetString(GL_VENDOR), p_glGetString(GL_RENDERER), p_glGetString(GL_VERSION));

  p_glViewport(0, 0, W, H);
  static const GLfloat quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
  /* test 1: heavy fragment shader, fullscreen quad */
  GLuint pr = build(VS, FS_HEAVY); if (!pr) return 5;
  p_glUseProgram(pr); GLint a = p_glGetAttribLocation(pr, "a");
  p_glEnableVertexAttribArray(a); p_glVertexAttribPointer(a, 2, GL_FLOAT, 0, 0, quad);
  p_glUniform2f(p_glGetUniformLocation(pr, "r"), W, H);
  GLint tu = p_glGetUniformLocation(pr, "t");
  for (int i = 0; i < 5; i++) { p_glUniform1f(tu, i * 0.1f); p_glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); } p_glFinish();
  int frames = 0; double t0 = now(), t1;
  do { for (int i = 0; i < 4; i++) { p_glUniform1f(tu, frames * 0.01f); p_glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); frames++; } p_glFinish(); t1 = now(); } while (t1 - t0 < 3.0);
  unsigned char px[4]; p_glReadPixels(W / 2, H / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
  printf("   [тяжёлый шейдер 1280x720, 48 итераций] %.1f кадров/с, %.0f Мпикс/с (за %.1f с, %d кадров, пиксель %u,%u,%u)\n",
         frames / (t1 - t0), frames * (double)W * H / (t1 - t0) / 1e6, t1 - t0, frames, px[0], px[1], px[2]);

  /* test 2: many small draw calls (CPU/driver overhead) */
  GLuint pf = build(VS, FS_FLAT); if (!pf) return 5;
  p_glUseProgram(pf); a = p_glGetAttribLocation(pf, "a"); GLint tf = p_glGetUniformLocation(pf, "t");
  p_glEnableVertexAttribArray(a); p_glVertexAttribPointer(a, 2, GL_FLOAT, 0, 0, quad);
  p_glViewport(0, 0, 32, 32);   /* tiny viewport: the test measures call overhead, not fill rate */
  long calls = 0; t0 = now();
  do { for (int i = 0; i < 2000; i++) { p_glUniform1f(tf, (i & 255) / 255.0f); p_glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); calls++; } p_glFinish(); t1 = now(); } while (t1 - t0 < 2.0);
  printf("   [вызовы отрисовки, вид 32x32] %.0f вызовов/с (%.1f мкс на вызов)\n", calls / (t1 - t0), (t1 - t0) / calls * 1e6);
  GLenum e = p_glGetError(); if (e) printf("   glGetError: 0x%x\n", e);
  p_eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); p_eglTerminate(d);
  return 0;
}
