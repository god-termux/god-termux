// JNI-прослойка мода cosmic: загружает libcosmic_comp.so (cosmic-comp под bionic) и пробрасывает окно и ввод.
// Сборка: modbuild (clang++ -shared), без STL.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "cosmic-mod", __VA_ARGS__)

namespace {
typedef int (*start_t)(void*, unsigned);
typedef void (*void_t)(void);
typedef void (*window_t)(void*);
typedef void (*motion_t)(double, double);
typedef void (*button_t)(unsigned, bool);
typedef void (*scroll_t)(double);
typedef void (*touch_t)(unsigned, unsigned long long, double, double);
typedef void (*key_t)(unsigned, bool);

start_t f_start; void_t f_stop, f_lost; window_t f_set_window; motion_t f_motion; button_t f_button; scroll_t f_scroll; touch_t f_touch; key_t f_key;
bool loaded = false;
volatile int running = 0;
ANativeWindow *win = nullptr, *prev = nullptr;
unsigned mhz = 60000;
char err[512] = "";
pthread_t th;

void* thread_main(void*) {
  int rc = f_start(win, mhz);
  LOG("cosmic_start вернул %d", rc);
  if (rc != 0) snprintf(err, sizeof err, "cosmic-comp завершился с кодом %d (подробности: usr/tmp/cosmic-comp.log и logcat, тег cosmic-comp)", rc);
  running = 0;
  return nullptr;
}

bool load(const char* dir) {
  if (loaded) return true;
  char path[600]; snprintf(path, sizeof path, "%s/libcosmic_comp.so", dir);
  void* h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
  if (!h) { snprintf(err, sizeof err, "dlopen %s: %s", path, dlerror()); return false; }
#define SYM(v, t, n) v = (t)dlsym(h, n); if (!v) { snprintf(err, sizeof err, "нет символа %s в %s", n, path); return false; }
  SYM(f_start, start_t, "cosmic_start") SYM(f_stop, void_t, "cosmic_stop") SYM(f_lost, void_t, "cosmic_window_lost")
  SYM(f_set_window, window_t, "cosmic_set_window") SYM(f_motion, motion_t, "cosmic_pointer_motion") SYM(f_button, button_t, "cosmic_pointer_button")
  SYM(f_scroll, scroll_t, "cosmic_scroll") SYM(f_touch, touch_t, "cosmic_touch") SYM(f_key, key_t, "cosmic_key")
  loaded = true;
  return true;
}
void setenv_s(JNIEnv* e, const char* k, jstring v) { const char* c = e->GetStringUTFChars(v, nullptr); setenv(k, c, 1); e->ReleaseStringUTFChars(v, c); }
}  // namespace

extern "C" {
// 0 — запущен; иначе см. nativeError()
JNIEXPORT jint JNICALL Java_cosmic_CosmicScreen_nativeStart(JNIEnv* e, jclass, jobject surface, jstring rt, jstring home, jstring libdir, jint refreshMhz, jfloat scale) {
  if (running) return 0;
  const char* ld = e->GetStringUTFChars(libdir, nullptr); bool ok = load(ld); e->ReleaseStringUTFChars(libdir, ld);
  if (!ok) return 1;
  err[0] = 0;
  setenv_s(e, "XDG_RUNTIME_DIR", rt); setenv_s(e, "HOME", home);
  const char* h = getenv("HOME"); char b[600];
  snprintf(b, sizeof b, "%s/.config", h); setenv("XDG_CONFIG_HOME", b, 1);
  snprintf(b, sizeof b, "%s/.local/share", h); setenv("XDG_DATA_HOME", b, 1);
  setenv("COSMIC_BACKEND", "winit", 1); setenv("COSMIC_DISABLE_XWAYLAND", "1", 1);
  if (!getenv("RUST_LOG")) setenv("RUST_LOG", "warn", 1);
  if (scale >= 0.5f && scale <= 4.0f) { char sb[32]; snprintf(sb, sizeof sb, "%.3f", scale); setenv("COSMIC_SCALE", sb, 1); }
  win = ANativeWindow_fromSurface(e, surface);
  if (!win) { snprintf(err, sizeof err, "ANativeWindow_fromSurface вернул null"); return 2; }
  if (refreshMhz > 0) mhz = (unsigned)refreshMhz;
  running = 1;
  if (pthread_create(&th, nullptr, thread_main, nullptr) != 0) { running = 0; snprintf(err, sizeof err, "pthread_create не удался"); return 3; }
  pthread_detach(th);
  return 0;
}
JNIEXPORT jstring JNICALL Java_cosmic_CosmicScreen_nativeError(JNIEnv* e, jclass) { return e->NewStringUTF(err); }
JNIEXPORT jboolean JNICALL Java_cosmic_CosmicScreen_nativeRunning(JNIEnv*, jclass) { return running ? JNI_TRUE : JNI_FALSE; }
// Surface пересоздан (повторное открытие экрана): передать новое окно; предыдущее освобождаем с запасом — EGL может ещё им пользоваться
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeSetWindow(JNIEnv* e, jclass, jobject surface) {
  if (!running) return;
  ANativeWindow* nw = ANativeWindow_fromSurface(e, surface);
  if (!nw) return;
  f_set_window(nw);
  if (prev) ANativeWindow_release(prev);
  prev = win; win = nw;
}
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeWindowLost(JNIEnv*, jclass) { if (running) f_lost(); }
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeStop(JNIEnv*, jclass) { if (running) f_stop(); }
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeMotion(JNIEnv*, jclass, jfloat x, jfloat y) { if (running) f_motion(x, y); }
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeButton(JNIEnv*, jclass, jint b, jint p) { if (running) f_button(b, p != 0); }
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeScroll(JNIEnv*, jclass, jfloat dy) { if (running) f_scroll(dy); }
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeTouch(JNIEnv*, jclass, jint phase, jint id, jfloat x, jfloat y) {
  if (running) f_touch(phase < 0 ? 0 : phase > 3 ? 3 : phase, (unsigned long long)id, x, y);
}
JNIEXPORT void JNICALL Java_cosmic_CosmicScreen_nativeKey(JNIEnv*, jclass, jint evdev, jint pressed) { if (running) f_key(evdev, pressed != 0); }
}
