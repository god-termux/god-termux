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

namespace {
std::thread th; std::atomic<bool> running{false}, resizeReq{false}; std::atomic<int> reqW{0}, reqH{0}, scaleReq{0};
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
    { std::lock_guard<std::mutex> g(wm);
      if (winChanged) {
        winChanged = false;
        if (surf != EGL_NO_SURFACE) { eglMakeCurrent(d, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); eglDestroySurface(d, surf); surf = EGL_NO_SURFACE; }
        if (win) { ANativeWindow_release(win); win = nullptr; } win = newWin; newWin = nullptr;
        if (win) { surf = eglCreateWindowSurface(d, cfg, (EGLNativeWindowType)win, 0); eglMakeCurrent(d, surf, surf, ctx); eglSwapInterval(d, 1); force = true;
          if (!ready) { prog = glCreateProgram(); glAttachShader(prog, compile(GL_VERTEX_SHADER, VS)); glAttachShader(prog, compile(GL_FRAGMENT_SHADER, FS)); glLinkProgram(prog);
            aPos = glGetAttribLocation(prog, "a_pos"); aUv = glGetAttribLocation(prog, "a_uv"); uRes = glGetUniformLocation(prog, "u_res"); uTex = glGetUniformLocation(prog, "u_tex"); ready = true; }
          else texs.clear();   // textures belong to the old context state after a surface change: recreate lazily
        }
      } }
    { int sr = scaleReq.exchange(0); if (sr) { wlc_set_scale(sr); force = true; } }
    if (resizeReq.exchange(false)) { W = reqW; H = reqH; wlc_resize(W, H); force = true; }
    WlcWin ws[64]; int cnt = wlc_windows(ws, 64); bool need = force;
    if (surf != EGL_NO_SURFACE && ready) {
      for (int i = 0; i < cnt; i++) { Tex& t = texs[ws[i].id]; if (ws[i].dirty || !t.id || t.w != ws[i].bw || t.h != ws[i].bh) need = true; }
      if (need) {
        eglMakeCurrent(d, surf, surf, ctx); EGLint sw = 0, sh = 0; eglQuerySurface(d, surf, EGL_WIDTH, &sw); eglQuerySurface(d, surf, EGL_HEIGHT, &sh);
        glViewport(0, 0, sw, sh); glClearColor(0.055f, 0.058f, 0.07f, 1); glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(prog); glUniform2f(uRes, (float)sw, (float)sh); glUniform1i(uTex, 0); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA); glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        int S = wlc_scale();
        for (int i = 0; i < cnt; i++) {
          WlcWin& w = ws[i]; Tex& t = texs[w.id]; glActiveTexture(GL_TEXTURE0);
          if (!t.id) { glGenTextures(1, &t.id); glBindTexture(GL_TEXTURE_2D, t.id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE); t.w = t.h = 0; }
          glBindTexture(GL_TEXTURE_2D, t.id);
          if (w.dirty || t.w != w.bw || t.h != w.bh) { glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w.bw, w.bh, 0, GL_RGBA, GL_UNSIGNED_BYTE, w.px); t.w = w.bw; t.h = w.bh; wlc_clean(w.id); }
          float x0 = (float)w.x * S, y0 = (float)w.y * S, x1 = x0 + (float)w.w * S, y1 = y0 + (float)w.h * S;
          const GLfloat q[] = {x0, y0, 0, 0, x1, y0, 1, 0, x0, y1, 0, 1, x1, y1, 1, 1};
          glEnableVertexAttribArray(aPos); glEnableVertexAttribArray(aUv);
          glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 16, q); glVertexAttribPointer(aUv, 2, GL_FLOAT, GL_FALSE, 16, q + 2);
          glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        eglSwapBuffers(d, surf); force = false; wlc_frame_done(); lastDone = now_ms();
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
