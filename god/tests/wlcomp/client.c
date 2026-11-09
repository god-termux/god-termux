// Tiny Wayland test client: xdg toplevel with a shm buffer (buffer scale 2); prints input events; clicking changes the colour.
#define _GNU_SOURCE
#include <wayland-client.h>
#include "xdg-shell-client-protocol.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>
#include <sys/select.h>
static struct wl_compositor* comp; static struct wl_shm* shm; static struct xdg_wm_base* wm; static struct wl_seat* seat;
static struct wl_surface* surf; static struct xdg_surface* xs; static struct xdg_toplevel* top;
static int W = 300, H = 300, SC = 2, color = 0, configured = 0; static const char* dir;
static void draw(void) {
  int bw = W * SC, bh = H * SC, size = bw * bh * 4; char path[300]; snprintf(path, sizeof path, "%s/cli-XXXXXX", dir);
  int fd = mkstemp(path); unlink(path); ftruncate(fd, size); uint32_t* p = mmap(0, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  uint32_t base[3] = {0xff2f6bff, 0xff1e9e5a, 0xffd98200};
  for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) {
    uint32_t c = base[color % 3]; int g = (x * 255 / bw), cell = ((x / 40) + (y / 40)) & 1;
    p[y * bw + x] = cell ? c : (0xff000000 | (g << 16) | (g << 8) | 0x40);
    if (x < 6 || y < 6 || x >= bw - 6 || y >= bh - 6) p[y * bw + x] = 0xffffffff; }
  struct wl_shm_pool* pool = wl_shm_create_pool(shm, fd, size); struct wl_buffer* b = wl_shm_pool_create_buffer(pool, 0, bw, bh, bw * 4, WL_SHM_FORMAT_ARGB8888);
  wl_shm_pool_destroy(pool); close(fd); wl_surface_set_buffer_scale(surf, SC); wl_surface_attach(surf, b, 0, 0); wl_surface_damage(surf, 0, 0, W, H); wl_surface_commit(surf);
}
static void xs_cfg(void* d, struct xdg_surface* x, uint32_t s) { xdg_surface_ack_configure(x, s); configured = 1; draw(); }
static const struct xdg_surface_listener xsl = {xs_cfg};
static void top_cfg(void* d, struct xdg_toplevel* t, int32_t w, int32_t h, struct wl_array* st) { if (w > 0 && h > 0) { W = w; H = h; } printf("toplevel configure %dx%d\n", w, h); fflush(stdout); }
static void top_close(void* d, struct xdg_toplevel* t) { exit(0); }
static const struct xdg_toplevel_listener tl = {top_cfg, top_close};
static void wm_ping(void* d, struct xdg_wm_base* w, uint32_t s) { xdg_wm_base_pong(w, s); }
static const struct xdg_wm_base_listener wml = {wm_ping};
static void p_enter(void* d, struct wl_pointer* p, uint32_t s, struct wl_surface* sf, wl_fixed_t x, wl_fixed_t y) { printf("pointer enter %.0f,%.0f\n", wl_fixed_to_double(x), wl_fixed_to_double(y)); fflush(stdout); }
static void p_leave(void* d, struct wl_pointer* p, uint32_t s, struct wl_surface* sf) {}
static void p_motion(void* d, struct wl_pointer* p, uint32_t t, wl_fixed_t x, wl_fixed_t y) { printf("pointer motion %.0f,%.0f\n", wl_fixed_to_double(x), wl_fixed_to_double(y)); fflush(stdout); }
static void p_button(void* d, struct wl_pointer* p, uint32_t s, uint32_t t, uint32_t b, uint32_t st) { printf("pointer button %x state %u\n", b, st); fflush(stdout); if (st == 1) { color++; draw(); } }
static void p_axis(void* d, struct wl_pointer* p, uint32_t t, uint32_t a, wl_fixed_t v) { printf("pointer axis %.1f\n", wl_fixed_to_double(v)); fflush(stdout); }
static void p_frame(void* d, struct wl_pointer* p) {}
static const struct wl_pointer_listener pl = {p_enter, p_leave, p_motion, p_button, p_axis, p_frame};
static void k_keymap(void* d, struct wl_keyboard* k, uint32_t f, int fd, uint32_t sz) { printf("keymap fmt %u size %u\n", f, sz); fflush(stdout); close(fd); }
static void k_enter(void* d, struct wl_keyboard* k, uint32_t s, struct wl_surface* sf, struct wl_array* keys) { printf("keyboard enter\n"); fflush(stdout); }
static void k_leave(void* d, struct wl_keyboard* k, uint32_t s, struct wl_surface* sf) {}
static void k_key(void* d, struct wl_keyboard* k, uint32_t s, uint32_t t, uint32_t key, uint32_t st) { printf("key %u state %u\n", key, st); fflush(stdout); }
static void k_mod(void* d, struct wl_keyboard* k, uint32_t s, uint32_t a, uint32_t b, uint32_t c, uint32_t g) { if (a) { printf("mods %u\n", a); fflush(stdout); } }
static void k_rep(void* d, struct wl_keyboard* k, int32_t r, int32_t dl) {}
static const struct wl_keyboard_listener kl = {k_keymap, k_enter, k_leave, k_key, k_mod, k_rep};
static void seat_caps(void* d, struct wl_seat* s, uint32_t c) {
  if (c & WL_SEAT_CAPABILITY_POINTER) wl_pointer_add_listener(wl_seat_get_pointer(s), &pl, 0);
  if (c & WL_SEAT_CAPABILITY_KEYBOARD) wl_keyboard_add_listener(wl_seat_get_keyboard(s), &kl, 0); }
static void seat_name(void* d, struct wl_seat* s, const char* n) {}
static const struct wl_seat_listener sl = {seat_caps, seat_name};
static void reg(void* d, struct wl_registry* r, uint32_t id, const char* i, uint32_t v) {
  if (!strcmp(i, "wl_compositor")) comp = wl_registry_bind(r, id, &wl_compositor_interface, 4);
  else if (!strcmp(i, "wl_shm")) shm = wl_registry_bind(r, id, &wl_shm_interface, 1);
  else if (!strcmp(i, "xdg_wm_base")) { wm = wl_registry_bind(r, id, &xdg_wm_base_interface, 1); xdg_wm_base_add_listener(wm, &wml, 0); }
  else if (!strcmp(i, "wl_seat")) { seat = wl_registry_bind(r, id, &wl_seat_interface, 5); wl_seat_add_listener(seat, &sl, 0); } }
static void reg_rm(void* d, struct wl_registry* r, uint32_t id) {}
static const struct wl_registry_listener rl = {reg, reg_rm};
int main(int argc, char** argv) {
  dir = getenv("XDG_RUNTIME_DIR"); struct wl_display* d = wl_display_connect("wayland-0"); if (!d) { printf("connect failed\n"); return 1; }
  struct wl_registry* r = wl_display_get_registry(d); wl_registry_add_listener(r, &rl, 0); wl_display_roundtrip(d);
  surf = wl_compositor_create_surface(comp); xs = xdg_wm_base_get_xdg_surface(wm, surf); xdg_surface_add_listener(xs, &xsl, 0);
  top = xdg_surface_get_toplevel(xs); xdg_toplevel_add_listener(top, &tl, 0); xdg_toplevel_set_title(top, "wlc test client"); wl_surface_commit(surf);
  int secs = argc > 1 ? atoi(argv[1]) : 5; time_t end = time(0) + secs;
  while (time(0) < end && wl_display_dispatch_pending(d) >= 0) { wl_display_flush(d); struct pollfd; fd_set fs; FD_ZERO(&fs); FD_SET(wl_display_get_fd(d), &fs); struct timeval tv = {0, 100000};
    if (select(wl_display_get_fd(d) + 1, &fs, 0, 0, &tv) > 0) wl_display_dispatch(d); }
  return 0; }
