// Android backend: EGL/GLES2 composition of the Wayland windows into a SurfaceView, JNI bridge for wlcomp.WlScreen.
#include "wlc.h"
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <jni.h>
#include <atomic>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <time.h>
#include <dlfcn.h>
#include <stdio.h>
#include <unistd.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "wlcomp", __VA_ARGS__)

extern "C" {  // minimal GLES2 declarations (the Termux sysroot has no GLES2 headers); libGLESv2 is linked from /system
typedef unsigned int GLenum; typedef unsigned int GLuint; typedef int GLint; typedef int GLsizei; typedef float GLfloat; typedef unsigned char GLboolean; typedef char GLchar; typedef unsigned int GLbitfield;
GLuint glCreateShader(GLenum); void glShaderSource(GLuint, GLsizei, const GLchar* const*, const GLint*); void glCompileShader(GLuint); GLuint glCreateProgram(void);
void glAttachShader(GLuint, GLuint); void glLinkProgram(GLuint); void glUseProgram(GLuint); GLint glGetAttribLocation(GLuint, const GLchar*); GLint glGetUniformLocation(GLuint, const GLchar*);
void glEnableVertexAttribArray(GLuint); void glVertexAttribPointer(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*); void glUniform2f(GLint, GLfloat, GLfloat); void glUniform1i(GLint, GLint);
void glGenTextures(GLsizei, GLuint*); void glBindTexture(GLenum, GLuint); void glTexParameteri(GLenum, GLenum, GLint); void glTexImage2D(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*);
void glActiveTexture(GLenum); void glDrawArrays(GLenum, GLint, GLsizei); void glViewport(GLint, GLint, GLsizei, GLsizei); void glClearColor(GLfloat, GLfloat, GLfloat, GLfloat); void glClear(GLbitfield);
void glEnable(GLenum); void glDisable(GLenum); void glBlendFunc(GLenum, GLenum); void glDeleteTextures(GLsizei, const GLuint*); void glPixelStorei(GLenum, GLint); void glScissor(GLint, GLint, GLsizei, GLsizei);
}
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE0 0x84C0
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_TEXTURE_WRAP_S 0x2802
#define GL_TEXTURE_WRAP_T 0x2803
#define GL_LINEAR 0x2601
#define GL_CLAMP_TO_EDGE 0x812F
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401
#define GL_FLOAT 0x1406
#define GL_TRIANGLE_STRIP 0x0005
#define GL_BLEND 0x0BE2
#define GL_ONE 1
#define GL_ONE_MINUS_SRC_ALPHA 0x0303
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_UNPACK_ALIGNMENT 0x0CF5
#define GL_FALSE 0

// ---- graphics backend chosen at run time: the platform driver (libEGL.so/libGLESv2.so) or ANGLE (copies of libEGL_angle.so/libGLESv2_angle.so shipped in
// the app's native lib dir by build-local.sh). Flags: ~/.termux/wl-angle (use ANGLE), ~/.termux/wl-bench (continuous redraw, vsync off, stats in logcat).
extern "C" const unsigned char* glGetString(unsigned);
static __typeof__(&eglGetDisplay) p_eglGetDisplay;
static __typeof__(&eglInitialize) p_eglInitialize;
static __typeof__(&eglChooseConfig) p_eglChooseConfig;
static __typeof__(&eglCreateContext) p_eglCreateContext;
static __typeof__(&eglCreateWindowSurface) p_eglCreateWindowSurface;
static __typeof__(&eglMakeCurrent) p_eglMakeCurrent;
static __typeof__(&eglSwapInterval) p_eglSwapInterval;
static __typeof__(&eglSwapBuffers) p_eglSwapBuffers;
static __typeof__(&eglQuerySurface) p_eglQuerySurface;
static __typeof__(&eglDestroySurface) p_eglDestroySurface;
static __typeof__(&eglDestroyContext) p_eglDestroyContext;
static __typeof__(&eglTerminate) p_eglTerminate;
static __typeof__(&glCreateShader) p_glCreateShader;
static __typeof__(&glShaderSource) p_glShaderSource;
static __typeof__(&glCompileShader) p_glCompileShader;
static __typeof__(&glCreateProgram) p_glCreateProgram;
static __typeof__(&glAttachShader) p_glAttachShader;
static __typeof__(&glLinkProgram) p_glLinkProgram;
static __typeof__(&glUseProgram) p_glUseProgram;
static __typeof__(&glGetAttribLocation) p_glGetAttribLocation;
static __typeof__(&glGetUniformLocation) p_glGetUniformLocation;
static __typeof__(&glEnableVertexAttribArray) p_glEnableVertexAttribArray;
static __typeof__(&glVertexAttribPointer) p_glVertexAttribPointer;
static __typeof__(&glUniform2f) p_glUniform2f;
static __typeof__(&glUniform1i) p_glUniform1i;
static __typeof__(&glGenTextures) p_glGenTextures;
static __typeof__(&glBindTexture) p_glBindTexture;
static __typeof__(&glTexParameteri) p_glTexParameteri;
static __typeof__(&glTexImage2D) p_glTexImage2D;
static __typeof__(&glActiveTexture) p_glActiveTexture;
static __typeof__(&glDrawArrays) p_glDrawArrays;
static __typeof__(&glViewport) p_glViewport;
static __typeof__(&glClearColor) p_glClearColor;
static __typeof__(&glClear) p_glClear;
static __typeof__(&glEnable) p_glEnable;
static __typeof__(&glDisable) p_glDisable;
static __typeof__(&glBlendFunc) p_glBlendFunc;
static __typeof__(&glDeleteTextures) p_glDeleteTextures;
static __typeof__(&glPixelStorei) p_glPixelStorei;
static __typeof__(&glScissor) p_glScissor;
static __typeof__(&glGetString) p_glGetString;
#define eglGetDisplay p_eglGetDisplay
#define eglInitialize p_eglInitialize
#define eglChooseConfig p_eglChooseConfig
#define eglCreateContext p_eglCreateContext
#define eglCreateWindowSurface p_eglCreateWindowSurface
#define eglMakeCurrent p_eglMakeCurrent
#define eglSwapInterval p_eglSwapInterval
#define eglSwapBuffers p_eglSwapBuffers
#define eglQuerySurface p_eglQuerySurface
#define eglDestroySurface p_eglDestroySurface
#define eglDestroyContext p_eglDestroyContext
#define eglTerminate p_eglTerminate
#define glCreateShader p_glCreateShader
#define glShaderSource p_glShaderSource
#define glCompileShader p_glCompileShader
#define glCreateProgram p_glCreateProgram
#define glAttachShader p_glAttachShader
#define glLinkProgram p_glLinkProgram
#define glUseProgram p_glUseProgram
#define glGetAttribLocation p_glGetAttribLocation
#define glGetUniformLocation p_glGetUniformLocation
#define glEnableVertexAttribArray p_glEnableVertexAttribArray
#define glVertexAttribPointer p_glVertexAttribPointer
#define glUniform2f p_glUniform2f
#define glUniform1i p_glUniform1i
#define glGenTextures p_glGenTextures
#define glBindTexture p_glBindTexture
#define glTexParameteri p_glTexParameteri
#define glTexImage2D p_glTexImage2D
#define glActiveTexture p_glActiveTexture
#define glDrawArrays p_glDrawArrays
#define glViewport p_glViewport
#define glClearColor p_glClearColor
#define glClear p_glClear
#define glEnable p_glEnable
#define glDisable p_glDisable
#define glBlendFunc p_glBlendFunc
#define glDeleteTextures p_glDeleteTextures
#define glPixelStorei p_glPixelStorei
#define glScissor p_glScissor
#define glGetString p_glGetString
static bool loadGraphics(bool angle) {
  const char* el = angle ? "libEGL_angle.so" : "libEGL.so"; const char* gl = angle ? "libGLESv2_angle.so" : "libGLESv2.so";
  void* e = dlopen(el, RTLD_NOW | RTLD_LOCAL); if (!e) { LOG("dlopen %s failed: %s", el, dlerror()); return false; }
  void* g = dlopen(gl, RTLD_NOW | RTLD_LOCAL); if (!g) { LOG("dlopen %s failed: %s", gl, dlerror()); return false; }
  bool ok = true;
  p_eglGetDisplay = (decltype(p_eglGetDisplay))dlsym(e, "eglGetDisplay"); if (!p_eglGetDisplay) { LOG("missing eglGetDisplay"); ok = false; }
  p_eglInitialize = (decltype(p_eglInitialize))dlsym(e, "eglInitialize"); if (!p_eglInitialize) { LOG("missing eglInitialize"); ok = false; }
  p_eglChooseConfig = (decltype(p_eglChooseConfig))dlsym(e, "eglChooseConfig"); if (!p_eglChooseConfig) { LOG("missing eglChooseConfig"); ok = false; }
  p_eglCreateContext = (decltype(p_eglCreateContext))dlsym(e, "eglCreateContext"); if (!p_eglCreateContext) { LOG("missing eglCreateContext"); ok = false; }
  p_eglCreateWindowSurface = (decltype(p_eglCreateWindowSurface))dlsym(e, "eglCreateWindowSurface"); if (!p_eglCreateWindowSurface) { LOG("missing eglCreateWindowSurface"); ok = false; }
  p_eglMakeCurrent = (decltype(p_eglMakeCurrent))dlsym(e, "eglMakeCurrent"); if (!p_eglMakeCurrent) { LOG("missing eglMakeCurrent"); ok = false; }
  p_eglSwapInterval = (decltype(p_eglSwapInterval))dlsym(e, "eglSwapInterval"); if (!p_eglSwapInterval) { LOG("missing eglSwapInterval"); ok = false; }
  p_eglSwapBuffers = (decltype(p_eglSwapBuffers))dlsym(e, "eglSwapBuffers"); if (!p_eglSwapBuffers) { LOG("missing eglSwapBuffers"); ok = false; }
  p_eglQuerySurface = (decltype(p_eglQuerySurface))dlsym(e, "eglQuerySurface"); if (!p_eglQuerySurface) { LOG("missing eglQuerySurface"); ok = false; }
  p_eglDestroySurface = (decltype(p_eglDestroySurface))dlsym(e, "eglDestroySurface"); if (!p_eglDestroySurface) { LOG("missing eglDestroySurface"); ok = false; }
  p_eglDestroyContext = (decltype(p_eglDestroyContext))dlsym(e, "eglDestroyContext"); if (!p_eglDestroyContext) { LOG("missing eglDestroyContext"); ok = false; }
  p_eglTerminate = (decltype(p_eglTerminate))dlsym(e, "eglTerminate"); if (!p_eglTerminate) { LOG("missing eglTerminate"); ok = false; }
  p_glCreateShader = (decltype(p_glCreateShader))dlsym(g, "glCreateShader"); if (!p_glCreateShader) { LOG("missing glCreateShader"); ok = false; }
  p_glShaderSource = (decltype(p_glShaderSource))dlsym(g, "glShaderSource"); if (!p_glShaderSource) { LOG("missing glShaderSource"); ok = false; }
  p_glCompileShader = (decltype(p_glCompileShader))dlsym(g, "glCompileShader"); if (!p_glCompileShader) { LOG("missing glCompileShader"); ok = false; }
  p_glCreateProgram = (decltype(p_glCreateProgram))dlsym(g, "glCreateProgram"); if (!p_glCreateProgram) { LOG("missing glCreateProgram"); ok = false; }
  p_glAttachShader = (decltype(p_glAttachShader))dlsym(g, "glAttachShader"); if (!p_glAttachShader) { LOG("missing glAttachShader"); ok = false; }
  p_glLinkProgram = (decltype(p_glLinkProgram))dlsym(g, "glLinkProgram"); if (!p_glLinkProgram) { LOG("missing glLinkProgram"); ok = false; }
  p_glUseProgram = (decltype(p_glUseProgram))dlsym(g, "glUseProgram"); if (!p_glUseProgram) { LOG("missing glUseProgram"); ok = false; }
  p_glGetAttribLocation = (decltype(p_glGetAttribLocation))dlsym(g, "glGetAttribLocation"); if (!p_glGetAttribLocation) { LOG("missing glGetAttribLocation"); ok = false; }
  p_glGetUniformLocation = (decltype(p_glGetUniformLocation))dlsym(g, "glGetUniformLocation"); if (!p_glGetUniformLocation) { LOG("missing glGetUniformLocation"); ok = false; }
  p_glEnableVertexAttribArray = (decltype(p_glEnableVertexAttribArray))dlsym(g, "glEnableVertexAttribArray"); if (!p_glEnableVertexAttribArray) { LOG("missing glEnableVertexAttribArray"); ok = false; }
  p_glVertexAttribPointer = (decltype(p_glVertexAttribPointer))dlsym(g, "glVertexAttribPointer"); if (!p_glVertexAttribPointer) { LOG("missing glVertexAttribPointer"); ok = false; }
  p_glUniform2f = (decltype(p_glUniform2f))dlsym(g, "glUniform2f"); if (!p_glUniform2f) { LOG("missing glUniform2f"); ok = false; }
  p_glUniform1i = (decltype(p_glUniform1i))dlsym(g, "glUniform1i"); if (!p_glUniform1i) { LOG("missing glUniform1i"); ok = false; }
  p_glGenTextures = (decltype(p_glGenTextures))dlsym(g, "glGenTextures"); if (!p_glGenTextures) { LOG("missing glGenTextures"); ok = false; }
  p_glBindTexture = (decltype(p_glBindTexture))dlsym(g, "glBindTexture"); if (!p_glBindTexture) { LOG("missing glBindTexture"); ok = false; }
  p_glTexParameteri = (decltype(p_glTexParameteri))dlsym(g, "glTexParameteri"); if (!p_glTexParameteri) { LOG("missing glTexParameteri"); ok = false; }
  p_glTexImage2D = (decltype(p_glTexImage2D))dlsym(g, "glTexImage2D"); if (!p_glTexImage2D) { LOG("missing glTexImage2D"); ok = false; }
  p_glActiveTexture = (decltype(p_glActiveTexture))dlsym(g, "glActiveTexture"); if (!p_glActiveTexture) { LOG("missing glActiveTexture"); ok = false; }
  p_glDrawArrays = (decltype(p_glDrawArrays))dlsym(g, "glDrawArrays"); if (!p_glDrawArrays) { LOG("missing glDrawArrays"); ok = false; }
  p_glViewport = (decltype(p_glViewport))dlsym(g, "glViewport"); if (!p_glViewport) { LOG("missing glViewport"); ok = false; }
  p_glClearColor = (decltype(p_glClearColor))dlsym(g, "glClearColor"); if (!p_glClearColor) { LOG("missing glClearColor"); ok = false; }
  p_glClear = (decltype(p_glClear))dlsym(g, "glClear"); if (!p_glClear) { LOG("missing glClear"); ok = false; }
  p_glEnable = (decltype(p_glEnable))dlsym(g, "glEnable"); if (!p_glEnable) { LOG("missing glEnable"); ok = false; }
  p_glDisable = (decltype(p_glDisable))dlsym(g, "glDisable"); if (!p_glDisable) { LOG("missing glDisable"); ok = false; }
  p_glBlendFunc = (decltype(p_glBlendFunc))dlsym(g, "glBlendFunc"); if (!p_glBlendFunc) { LOG("missing glBlendFunc"); ok = false; }
  p_glDeleteTextures = (decltype(p_glDeleteTextures))dlsym(g, "glDeleteTextures"); if (!p_glDeleteTextures) { LOG("missing glDeleteTextures"); ok = false; }
  p_glPixelStorei = (decltype(p_glPixelStorei))dlsym(g, "glPixelStorei"); if (!p_glPixelStorei) { LOG("missing glPixelStorei"); ok = false; }
  p_glScissor = (decltype(p_glScissor))dlsym(g, "glScissor"); if (!p_glScissor) { LOG("missing glScissor"); ok = false; }
  p_glGetString = (decltype(p_glGetString))dlsym(g, "glGetString"); if (!p_glGetString) { LOG("missing glGetString"); ok = false; }
  return ok;
}
long now_us() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000000L + t.tv_nsec / 1000; }

namespace {
std::thread th; std::atomic<bool> running{false}, resizeReq{false}; std::atomic<int> reqW{0}, reqH{0}, scaleReq{0}, refreshReq{0};
std::mutex wm; ANativeWindow* newWin = nullptr; bool winChanged = false;   // handed over from the UI thread
std::string rtDir; int startW = 0, startH = 0, startScale = 2;

struct Tex { GLuint id = 0; int w = 0, h = 0; };
const char* VS = "attribute vec2 a_pos; attribute vec2 a_uv; uniform vec2 u_res; varying vec2 v_uv;\n"
                 "void main() { vec2 p = a_pos / u_res * 2.0 - 1.0; gl_Position = vec4(p.x, -p.y, 0.0, 1.0); v_uv = a_uv; }\n";
const char* FS = "precision mediump float; varying vec2 v_uv; uniform sampler2D u_tex;\n"
                 "void main() { gl_FragColor = texture2D(u_tex, v_uv).bgra; }\n";   // ARGB8888 little endian = bytes B,G,R,A

GLuint compile(GLenum t, const char* src) { GLuint s = glCreateShader(t); glShaderSource(s, 1, &src, nullptr); glCompileShader(s); return s; }
long now_ms() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000; }

void renderLoop() {
  std::string home = rtDir + "/../../home";
  bool bench = access((home + "/.termux/wl-bench").c_str(), F_OK) == 0, angle = access((home + "/.termux/wl-angle").c_str(), F_OK) == 0,
       pipeline = access((home + "/.termux/wl-pipeline").c_str(), F_OK) == 0;   // wl-pipeline: send frame callbacks right after the commit was copied, not after the vsync-blocked swap (client draws frame N+1 while we present N)
  if (angle && !loadGraphics(true)) { LOG("ANGLE недоступен, использую системный драйвер"); angle = false; }
  if (!angle && !loadGraphics(false)) { LOG("не удалось загрузить EGL/GLES"); running = false; return; }
  std::string logPath = rtDir + "/wlcomp-render.log"; bool reported = false; long statT = 0, statSum = 0, statMax = 0; int statN = 0;
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d, 0, 0);
  const EGLint ca[] = {EGL_RENDERABLE_TYPE, 4, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0, EGL_NONE};
  EGLConfig cfg; EGLint n; eglChooseConfig(d, ca, &cfg, 1, &n);
  const EGLint xa[] = {0x3098, 2, EGL_NONE}; EGLContext ctx = eglCreateContext(d, cfg, EGL_NO_CONTEXT, xa);
  EGLSurface surf = EGL_NO_SURFACE; ANativeWindow* win = nullptr; bool ready = false;
  GLuint prog = 0; GLint aPos = 0, aUv = 0, uRes = 0, uTex = 0; std::map<int, Tex> texs;
  int r = wlc_init(rtDir.c_str(), "wayland-0", startW, startH, startScale); LOG("wlc_init -> %d (%dx%d scale %d)", r, startW, startH, startScale);
  if (r) { running = false; return; }
  int W = startW, H = startH; long lastDone = 0; bool force = true;
  while (running) {
    wlc_dispatch(4);
    if (pipeline) wlc_frame_done();
    { std::lock_guard<std::mutex> g(wm);
      if (winChanged) {
        winChanged = false;
        if (surf != EGL_NO_SURFACE) { eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroySurface(d, surf); surf = EGL_NO_SURFACE; }
        if (win) { ANativeWindow_release(win); win = nullptr; } win = newWin; newWin = nullptr;
        if (win) { surf = eglCreateWindowSurface(d, cfg, (EGLNativeWindowType)win, 0); eglMakeCurrent(d, surf, surf, ctx); eglSwapInterval(d, bench ? 0 : 1); force = true;
          if (!ready) { prog = glCreateProgram(); glAttachShader(prog, compile(GL_VERTEX_SHADER, VS)); glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS)); glLinkProgram(prog);
            aPos = glGetAttribLocation(prog, "a_pos"); aUv = glGetAttribLocation(prog, "a_uv"); uRes = glGetUniformLocation(prog, "u_res"); uTex = glGetUniformLocation(prog, "u_tex"); ready = true; }
          else texs.clear();   // textures belong to the old context state after a surface change: recreate lazily
        }
      } }
    { int rr = refreshReq.exchange(0); if (rr) wlc_set_refresh(rr); }
    { int sr = scaleReq.exchange(0); if (sr) { wlc_set_scale(sr); force = true; } }
    if (resizeReq.exchange(false)) { W = reqW; H = reqH; wlc_resize(W, H); force = true; }
    if (bench && surf != EGL_NO_SURFACE && ready) force = true;   // bench: redraw (and re-upload every window) on each pass
    WlcWin ws[64]; int cnt = wlc_windows(ws, 64); bool need = force;
    if (surf != EGL_NO_SURFACE && ready) {
      for (int i = 0; i < cnt; i++) { Tex& t = texs[ws[i].id]; if (ws[i].dirty || !t.id || t.w != ws[i].bw || t.h != ws[i].bh) need = true; }
      if (need) {
        long tf0 = now_us(); eglMakeCurrent(d, surf, surf, ctx); EGLint sw = 0, sh = 0;
        if (!reported) { reported = true; char b[512]; snprintf(b, sizeof b, "backend=%s bench=%d renderer=[%s] version=[%s]", angle ? "ANGLE" : "platform", (int)bench, (const char*)glGetString(0x1F01), (const char*)glGetString(0x1F02));
          LOG("%s", b); if (FILE* f = fopen(logPath.c_str(), "a")) { fprintf(f, "%s\n", b); fclose(f); } } eglQuerySurface(d, surf, EGL_WIDTH, &sw); eglQuerySurface(d, surf, EGL_HEIGHT, &sh);
        glViewport(0, 0, sw, sh); glClearColor(0.055f, 0.058f, 0.07f, 1); glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(prog); glUniform2f(uRes, (float)sw, (float)sh); glUniform1i(uTex, 0); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        int S = wlc_scale();
        for (int i = 0; i < cnt; i++) {
          WlcWin& w = ws[i]; Tex& t = texs[w.id]; glActiveTexture(GL_TEXTURE0);
          if (!t.id) { glGenTextures(1, &t.id); glBindTexture(GL_TEXTURE_2D, t.id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); t.w = t.h = 0; }
          glBindTexture(GL_TEXTURE_2D, t.id);
          if (w.dirty || bench || t.w != w.bw || t.h != w.bh) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w.bw, w.bh, 0, GL_RGBA, GL_UNSIGNED_BYTE, w.px); t.w = w.bw; t.h = w.bh; wlc_clean(w.id); }
          float x0 = (float)w.x * S, y0 = (float)w.y * S, x1 = x0 + (float)w.w * S, y1 = y0 + (float)w.h * S;
          const GLfloat q[] = {x0, y0, 0, 0, x1, y0, 1, 0, x0, y1, 0, 1, x1, y1, 1, 1};
          glEnableVertexAttribArray(aPos); glEnableVertexAttribArray(aUv);
          glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 16, q); glVertexAttribPointer(aUv, 2, GL_FLOAT, GL_FALSE, 16, q + 2);
          glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        eglSwapBuffers(d, surf); force = false; wlc_frame_done(); lastDone = now_ms();
        { long dt = now_us() - tf0; statSum += dt; if (dt > statMax) statMax = dt; statN++;
          if (statT == 0) statT = now_ms();
          if (now_ms() - statT >= 2000) { char b[256]; snprintf(b, sizeof b, "%s: %d кадров за %.1f с, в среднем %.2f мс, максимум %.2f мс (%.1f кадров/с), окон %d", angle ? "ANGLE" : "platform", statN, (now_ms() - statT) / 1000.0, statSum / 1000.0 / statN, statMax / 1000.0, statN * 1000.0 / (now_ms() - statT), cnt);
            LOG("%s", b); if (FILE* f = fopen(logPath.c_str(), "a")) { fprintf(f, "%s\n", b); fclose(f); } statT = now_ms(); statN = 0; statSum = 0; statMax = 0; } }
        std::map<int, Tex> keep; for (int i = 0; i < cnt; i++) keep[ws[i].id] = texs[ws[i].id];   // drop textures of closed windows
        for (auto& kv : texs) if (!keep.count(kv.first) && kv.second.id) glDeleteTextures(1, &kv.second.id);
        texs.swap(keep);
      }
    }
    if (now_ms() - lastDone >= 16) { wlc_frame_done(); lastDone = now_ms(); }
  }
  if (surf != EGL_NO_SURFACE) { eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroySurface(d, surf); }
  if (win) ANativeWindow_release(win);
  eglDestroyContext(d, ctx); eglTerminate(d); wlc_stop();
}
}  // namespace

extern "C" {
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeSurface(JNIEnv* e, jclass, jobject surface, jstring rt, jint w, jint h, jint scale) {
  ANativeWindow* nw = surface ? ANativeWindow_fromSurface(e, surface) : nullptr;
  { std::lock_guard<std::mutex> g(wm); if (newWin) ANativeWindow_release(newWin); newWin = nw; winChanged = true; }
  if (nw && !running) {
    const char* c = e->GetStringUTFChars(rt, 0); rtDir = c; e->ReleaseStringUTFChars(rt, c); startW = w; startH = h; startScale = scale;
    if (th.joinable()) th.join(); running = true; th = std::thread(renderLoop);
  } else if (nw) { reqW = w; reqH = h; resizeReq = true; }
}
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeRefresh(JNIEnv*, jclass, jint mhz) { refreshReq = mhz; }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeResize(JNIEnv*, jclass, jint w, jint h) { reqW = w; reqH = h; resizeReq = true; }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeScale(JNIEnv*, jclass, jint s) { scaleReq = s; }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeStop(JNIEnv*, jclass) { running = false; if (th.joinable()) th.join(); }
JNIEXPORT jboolean JNICALL Java_wlcomp_WlScreen_nativeRunning(JNIEnv*, jclass) { return running ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL Java_wlcomp_WlScreen_nativeWindows(JNIEnv*, jclass) { return running ? wlc_window_count() : 0; }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeMotion(JNIEnv*, jclass, jfloat x, jfloat y) { wlc_in_motion(x, y); }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeButton(JNIEnv*, jclass, jint b, jint p) { wlc_in_button(b, p); }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeAxis(JNIEnv*, jclass, jfloat dy) { wlc_in_axis(dy); }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeKey(JNIEnv*, jclass, jint k, jint p) { wlc_in_key(k, p); }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeChar(JNIEnv*, jclass, jint c) { wlc_in_char(c); }
JNIEXPORT void JNICALL Java_wlcomp_WlScreen_nativeCycle(JNIEnv*, jclass) { wlc_in_cycle(); }
}
