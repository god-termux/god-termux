// god-termux Wayland compositor core: single-threaded (all calls on the compositor thread) except the wlc_in_* input functions,
// which may be called from any thread. Backends (software test harness, Android EGL) only compose what wlc_windows() reports.
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
struct WlcWin {
  int id;
  int x, y;          // logical position (output logical units = physical / scale)
  int w, h;          // logical size  (buffer size / buffer scale)
  int bw, bh;        // buffer size in pixels
  int popup;         // 1 = popup/menu
  int dirty;         // pixels changed since wlc_clean(id)
  const uint32_t* px;// ARGB8888 premultiplied, bw*bh (valid until the next wlc_dispatch)
};
int  wlc_init(const char* runtime_dir, const char* socket_name, int px_w, int px_h, int scale);
void wlc_dispatch(int timeout_ms);        // runs queued input + protocol events
int  wlc_windows(struct WlcWin* out, int max);   // bottom -> top
void wlc_clean(int id);
void wlc_frame_done(void);                // call after every presented frame
void wlc_resize(int px_w, int px_h);
int  wlc_scale(void);
void wlc_stop(void);
// input (thread-safe, coordinates in physical pixels)
void wlc_in_motion(float x, float y);
void wlc_in_button(int btn, int pressed);       // btn: 0 left, 1 right, 2 middle
void wlc_in_axis(float dy);                     // wheel, positive = scroll down
void wlc_in_key(int evdev, int pressed);
void wlc_in_char(int unicode);                  // US layout ASCII typed as key presses
void wlc_in_cycle(void);                       // bring the oldest toplevel to the front (window switcher)
int  wlc_window_count(void);                    // mapped toplevels (thread-safe)
int  wlc_title(int id, char* out, int n);       // toplevel title (for a window switcher)
int  wlc_toplevels(int* ids, int max);          // ids of mapped toplevels, oldest first
void wlc_raise(int id);                         // make this toplevel the active one
#ifdef __cplusplus
}
#endif
