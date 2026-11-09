#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <pthread.h>
#include <math.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO,"gldemo",__VA_ARGS__)
extern "C" { void glClearColor(float,float,float,float); void glClear(unsigned); const unsigned char* glGetString(unsigned); }
static ANativeWindow* win; static pthread_t th; static volatile bool run;
static void* loop(void*) {
  EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d,0,0);
  EGLint ca[]={EGL_RENDERABLE_TYPE,4,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
  EGLConfig c; EGLint n; eglChooseConfig(d,ca,&c,1,&n);
  EGLint xa[]={0x3098,2,EGL_NONE};
  EGLContext x=eglCreateContext(d,c,EGL_NO_CONTEXT,xa);
  EGLSurface s=eglCreateWindowSurface(d,c,(EGLNativeWindowType)win,0);
  eglMakeCurrent(d,s,s,x);
  LOG("GL_RENDERER=%s GL_VERSION=%s",glGetString(0x1F01),glGetString(0x1F02));
  float t=0;
  while(run){ t+=0.02f; glClearColor(.5f+.5f*sinf(t),.5f+.5f*sinf(t*1.3f),.5f+.5f*sinf(t*1.7f),1); glClear(0x4000); eglSwapBuffers(d,s); }
  eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
  eglDestroySurface(d,s); eglDestroyContext(d,x); eglTerminate(d); return 0;
}
extern "C" JNIEXPORT void JNICALL Java_gldemo_GlScreen_nStart(JNIEnv* e,jclass,jobject surf){
  win=ANativeWindow_fromSurface(e,surf); run=true; pthread_create(&th,0,loop,0); }
extern "C" JNIEXPORT void JNICALL Java_gldemo_GlScreen_nStop(JNIEnv*,jclass){
  run=false; pthread_join(th,0); ANativeWindow_release(win); }
