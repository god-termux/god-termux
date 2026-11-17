LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
# god-termux: C-only flag — NDK r29 rejects -std=c11 when compiling C++ (libgldemo)
LOCAL_CONLYFLAGS := -std=c11
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libgldemo
LOCAL_SRC_FILES := gldemo/gl.cpp
LOCAL_LDLIBS := -lEGL -lGLESv2 -landroid -llog
include $(BUILD_SHARED_LIBRARY)
