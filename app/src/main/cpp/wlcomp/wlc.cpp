#include "wlc.h"
#include <wayland-server.h>
#include "xdg-shell-server-protocol.h"
#include "keymap_us.inc"
#include <algorithm>
#include <atomic>
#include <deque>
#include <mutex>
#include <set>
#include <string>
#include <map>
#include <vector>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

namespace {
struct Pos { int w = 0, h = 0, ax = 0, ay = 0, aw = 0, ah = 0, anchor = 0, gravity = 0, ox = 0, oy = 0; };
struct XSurf;
struct Surf {
  wl_resource* res = nullptr; wl_client* client = nullptr; int id = 0;
  wl_resource* pend_buf = nullptr; bool has_pend = false; int pend_scale = 1, scale = 1;
  std::vector<wl_resource*> pend_frames, frames;
  std::vector<uint32_t> px; int bw = 0, bh = 0, x = 0, y = 0; bool mapped = false, dirty = false;
  XSurf* xs = nullptr; bool popup = false; std::string title;
};
struct XSurf {
  wl_resource* res = nullptr; Surf* s = nullptr; wl_resource* top = nullptr; wl_resource* pop = nullptr;
  bool acked = false, cfg_sent = false; int gx = 0, gy = 0; XSurf* parent = nullptr; Pos pos; int rx = 0, ry = 0;
};
struct Ev { int t; float a, b; int i; };
enum { E_MOTION, E_BUTTON, E_AXIS, E_KEY, E_CHAR, E_CYCLE, E_TDOWN, E_TMOVE, E_TUP, E_TCANCEL };
std::atomic<int> g_count{0};

wl_display* dpy; wl_event_loop* loop;
int OW = 1080, OH = 2392, SCALE = 2, NEXT_ID = 1; uint32_t serial_ = 1;
std::vector<Surf*> order; std::set<Surf*> alive;
std::vector<wl_resource*> outputs, pointers, keyboards;
std::vector<wl_resource*> touches; std::map<int, Surf*> touch_focus;   // wl_touch resources; touch id -> surface that received "down"
Surf *ptr_focus = nullptr, *kbd_focus = nullptr; float cur_x = 0, cur_y = 0;
std::deque<Ev> queue; std::mutex qm;
int keymap_fd = -1; uint32_t keymap_size = 0; uint32_t mods_dep = 0;   // modifier mask: Shift=1 Control=4 Alt(Mod1)=8 Super(Mod4)=64
std::string rt_dir;

uint32_t now_ms() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint32_t)(t.tv_sec * 1000 + t.tv_nsec / 1000000); }
int LW() { return OW / SCALE; } int LH() { return OH / SCALE; }
int lw(const Surf* s) { return s->bw / (s->scale > 0 ? s->scale : 1); } int lh(const Surf* s) { return s->bh / (s->scale > 0 ? s->scale : 1); }

Surf* root_of(Surf* s) { while (s && s->popup && s->xs && s->xs->parent && s->xs->parent->s) s = s->xs->parent->s; return s; }

// ---------------------------------------------------------------------------------------------------- layout
void layout() {
  for (Surf* s : order) {
    if (!s->xs) continue;
    if (!s->popup) { s->x = -s->xs->gx; s->y = -s->xs->gy; continue; }
    XSurf* p = s->xs->parent; Surf* ps = p ? p->s : nullptr; if (!ps) continue;
    s->x = ps->x + p->gx + s->xs->rx - s->xs->gx; s->y = ps->y + p->gy + s->xs->ry - s->xs->gy;
  }
}

void place_popup(XSurf* xs) {
  const Pos& p = xs->pos; int px, py;
  switch (p.anchor) {
    case 1: px = p.ax + p.aw / 2; py = p.ay; break;             case 2: px = p.ax + p.aw / 2; py = p.ay + p.ah; break;
    case 3: px = p.ax; py = p.ay + p.ah / 2; break;             case 4: px = p.ax + p.aw; py = p.ay + p.ah / 2; break;
    case 5: px = p.ax; py = p.ay; break;                        case 6: px = p.ax; py = p.ay + p.ah; break;
    case 7: px = p.ax + p.aw; py = p.ay; break;                 case 8: px = p.ax + p.aw; py = p.ay + p.ah; break;
    default: px = p.ax + p.aw / 2; py = p.ay + p.ah / 2;
  }
  int x, y;
  switch (p.gravity) {
    case 1: x = px - p.w / 2; y = py - p.h; break;              case 2: x = px - p.w / 2; y = py; break;
    case 3: x = px - p.w; y = py - p.h / 2; break;              case 4: x = px; y = py - p.h / 2; break;
    case 5: x = px - p.w; y = py - p.h; break;                  case 6: x = px - p.w; y = py; break;
    case 7: x = px; y = py - p.h; break;                        case 8: x = px; y = py; break;
    default: x = px - p.w / 2; y = py - p.h / 2;
  }
  x += p.ox; y += p.oy;
  // keep the menu inside the screen (slide)
  Surf* ps = xs->parent ? xs->parent->s : nullptr;
  if (ps) {
    int ox = ps->x + xs->parent->gx, oy = ps->y + xs->parent->gy;
    if (ox + x + p.w > LW()) x = LW() - p.w - ox; if (ox + x < 0) x = -ox;
    if (oy + y + p.h > LH()) y = LH() - p.h - oy; if (oy + y < 0) y = -oy;
  }
  xs->rx = x; xs->ry = y;
}

// ---------------------------------------------------------------------------------------------------- seat helpers
template <class F> void each_res(std::vector<wl_resource*>& v, wl_client* c, F f) { for (wl_resource* r : std::vector<wl_resource*>(v)) if (wl_resource_get_client(r) == c) f(r); }

void kbd_set_focus(Surf* s) {
  if (kbd_focus == s) return;
  if (kbd_focus && alive.count(kbd_focus)) each_res(keyboards, kbd_focus->client, [&](wl_resource* r) { wl_keyboard_send_leave(r, serial_++, kbd_focus->res); });
  kbd_focus = s;
  if (s) each_res(keyboards, s->client, [&](wl_resource* r) {
    wl_array keys; wl_array_init(&keys);
    wl_keyboard_send_enter(r, serial_++, s->res, &keys); wl_array_release(&keys);
    wl_keyboard_send_modifiers(r, serial_++, mods_dep, 0, 0, 0);
  });
}

void send_toplevel_configure(XSurf* xs) {
  wl_array st; wl_array_init(&st);
  *(uint32_t*)wl_array_add(&st, 4) = XDG_TOPLEVEL_STATE_MAXIMIZED; *(uint32_t*)wl_array_add(&st, 4) = XDG_TOPLEVEL_STATE_ACTIVATED;
  xdg_toplevel_send_configure(xs->top, LW(), LH(), &st); wl_array_release(&st);
  xdg_surface_send_configure(xs->res, serial_++); xs->cfg_sent = true;
}

void send_popup_configure(XSurf* xs) {
  place_popup(xs);
  xdg_popup_send_configure(xs->pop, xs->rx, xs->ry, xs->pos.w, xs->pos.h);
  xdg_surface_send_configure(xs->res, serial_++); xs->cfg_sent = true;
}

void unmap(Surf* s) {
  s->mapped = false; s->px.clear(); s->bw = s->bh = 0;
  order.erase(std::remove(order.begin(), order.end(), s), order.end());
  if (ptr_focus == s) ptr_focus = nullptr;
  if (kbd_focus == s) { kbd_focus = nullptr; for (int i = (int)order.size() - 1; i >= 0; i--) if (!order[i]->popup) { kbd_set_focus(order[i]); break; } }
}

void map(Surf* s) {
  s->mapped = true; order.push_back(s);
  each_res(outputs, s->client, [&](wl_resource* o) { wl_surface_send_enter(s->res, o); });
  if (!s->popup) kbd_set_focus(s);
}

Surf* hit(float lx, float ly, float* sx, float* sy) {
  layout();
  for (int i = (int)order.size() - 1; i >= 0; i--) {
    Surf* s = order[i];
    if (lx >= s->x && ly >= s->y && lx < s->x + lw(s) && ly < s->y + lh(s)) { *sx = lx - s->x; *sy = ly - s->y; return s; }
  }
  return nullptr;
}

void pointer_frame(wl_resource* r) { if (wl_resource_get_version(r) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(r); }

void pointer_update() {
  float sx = 0, sy = 0; Surf* t = hit(cur_x, cur_y, &sx, &sy);
  if (t != ptr_focus) {
    if (ptr_focus && alive.count(ptr_focus)) each_res(pointers, ptr_focus->client, [&](wl_resource* r) { wl_pointer_send_leave(r, serial_++, ptr_focus->res); pointer_frame(r); });
    ptr_focus = t;
    if (t) each_res(pointers, t->client, [&](wl_resource* r) { wl_pointer_send_enter(r, serial_++, t->res, wl_fixed_from_double(sx), wl_fixed_from_double(sy)); pointer_frame(r); });
  }
  if (t) each_res(pointers, t->client, [&](wl_resource* r) { wl_pointer_send_motion(r, now_ms(), wl_fixed_from_double(sx), wl_fixed_from_double(sy)); pointer_frame(r); });
}

void dismiss_popups_if_outside() {
  // xdg_popup grab: a press outside every popup closes the top-most one
  for (int i = (int)order.size() - 1; i >= 0; i--) {
    Surf* p = order[i]; if (!p->popup) continue;
    if (ptr_focus && ptr_focus->popup) return;
    if (p->xs && p->xs->pop) { xdg_popup_send_popup_done(p->xs->pop); unmap(p); }
    return;
  }
}

void on_button(int btn, int pressed) {
  static const int codes[3] = {0x110, 0x111, 0x112};
  if (btn < 0 || btn > 2) return;
  if (pressed) dismiss_popups_if_outside();
  if (!ptr_focus) return;
  if (pressed && !ptr_focus->popup) kbd_set_focus(root_of(ptr_focus));
  each_res(pointers, ptr_focus->client, [&](wl_resource* r) {
    wl_pointer_send_button(r, serial_++, now_ms(), codes[btn], pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED); pointer_frame(r); });
}

void on_key(int code, int pressed) {
  uint32_t bit = (code == 42 || code == 54) ? 1 : (code == 29 || code == 97) ? 4 : (code == 56 || code == 100) ? 8 : (code == 125 || code == 126) ? 64 : 0;
  if (bit) { if (pressed) mods_dep |= bit; else mods_dep &= ~bit; }
  if (!kbd_focus || !alive.count(kbd_focus)) return;
  uint32_t dep = mods_dep;
  each_res(keyboards, kbd_focus->client, [&](wl_resource* r) {
    wl_keyboard_send_key(r, serial_++, now_ms(), code, pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
    wl_keyboard_send_modifiers(r, serial_++, dep, 0, 0, 0); });
}

bool char_key(int ch, int* code, bool* shift) {
  static const char* r1 = "qwertyuiop"; static const char* r2 = "asdfghjkl"; static const char* r3 = "zxcvbnm";
  *shift = false;
  if (ch >= 'A' && ch <= 'Z') { *shift = true; ch += 32; }
  if (ch >= 'a' && ch <= 'z') {
    const char* p;
    if ((p = strchr(r1, ch))) { *code = 16 + (int)(p - r1); return true; }
    if ((p = strchr(r2, ch))) { *code = 30 + (int)(p - r2); return true; }
    if ((p = strchr(r3, ch))) { *code = 44 + (int)(p - r3); return true; }
  }
  if (ch >= '1' && ch <= '9') { *code = 2 + (ch - '1'); return true; }
  if (ch == '0') { *code = 11; return true; }
  static const char* sh = "!@#$%^&*()";
  const char* p = ch ? strchr(sh, ch) : nullptr; if (p) { *shift = true; *code = 2 + (int)(p - sh); return true; }
  struct M { char c; int k; bool s; };
  static const M m[] = {{' ',57,0},{'\n',28,0},{'\r',28,0},{'\t',15,0},{8,14,0},{27,1,0},{'-',12,0},{'_',12,1},{'=',13,0},{'+',13,1},{'[',26,0},{'{',26,1},{']',27,0},{'}',27,1},
    {';',39,0},{':',39,1},{'\'',40,0},{'"',40,1},{'`',41,0},{'~',41,1},{'\\',43,0},{'|',43,1},{',',51,0},{'<',51,1},{'.',52,0},{'>',52,1},{'/',53,0},{'?',53,1}};
  for (const M& e : m) if (e.c == ch) { *code = e.k; *shift = e.s; return true; }
  return false;
}

// ---------------------------------------------------------------------------------------------------- wl_region
void region_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void region_op(wl_client*, wl_resource*, int, int, int, int) {}
const struct wl_region_interface region_impl = {region_destroy, region_op, region_op};

// ---------------------------------------------------------------------------------------------------- wl_surface
void surf_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void surf_attach(wl_client*, wl_resource* r, wl_resource* buf, int32_t, int32_t) { Surf* s = (Surf*)wl_resource_get_user_data(r); s->pend_buf = buf; s->has_pend = true; }
void surf_damage(wl_client*, wl_resource*, int, int, int, int) {}
void surf_frame(wl_client* c, wl_resource* r, uint32_t id) {
  Surf* s = (Surf*)wl_resource_get_user_data(r);
  wl_resource* cb = wl_resource_create(c, &wl_callback_interface, 1, id);
  wl_resource_set_implementation(cb, nullptr, s, [](wl_resource* x) {
    Surf* o = (Surf*)wl_resource_get_user_data(x); if (!alive.count(o)) return;
    o->pend_frames.erase(std::remove(o->pend_frames.begin(), o->pend_frames.end(), x), o->pend_frames.end());
    o->frames.erase(std::remove(o->frames.begin(), o->frames.end(), x), o->frames.end()); });
  s->pend_frames.push_back(cb);
}
void surf_region(wl_client*, wl_resource*, wl_resource*) {}
void surf_commit(wl_client*, wl_resource* r) {
  Surf* s = (Surf*)wl_resource_get_user_data(r);
  for (auto f : s->pend_frames) s->frames.push_back(f); s->pend_frames.clear();
  if (s->has_pend) {
    s->has_pend = false; s->scale = s->pend_scale;
    if (!s->pend_buf) { if (s->mapped) unmap(s); }
    else if (wl_shm_buffer* sb = wl_shm_buffer_get(s->pend_buf)) {
      int w = wl_shm_buffer_get_width(sb), h = wl_shm_buffer_get_height(sb), st = wl_shm_buffer_get_stride(sb); uint32_t fmt = wl_shm_buffer_get_format(sb);
      s->px.resize((size_t)w * h); s->bw = w; s->bh = h;
      wl_shm_buffer_begin_access(sb); const uint8_t* src = (const uint8_t*)wl_shm_buffer_get_data(sb);
      for (int y = 0; y < h; y++) {
        uint32_t* dst = &s->px[(size_t)y * w]; memcpy(dst, src + (size_t)y * st, (size_t)w * 4);
        if (fmt == WL_SHM_FORMAT_XRGB8888) for (int x = 0; x < w; x++) dst[x] |= 0xff000000u;
      }
      wl_shm_buffer_end_access(sb); wl_buffer_send_release(s->pend_buf); s->dirty = true;
      if (!s->mapped && s->xs && s->xs->acked) map(s);
    }
    s->pend_buf = nullptr;
  }
  // first commit of an xdg surface asks for its configure
  if (s->xs && !s->xs->cfg_sent) { if (s->xs->top) send_toplevel_configure(s->xs); else if (s->xs->pop) send_popup_configure(s->xs); }
}
void surf_noop_i(wl_client*, wl_resource*, int32_t) {}
void surf_scale(wl_client*, wl_resource* r, int32_t sc) { Surf* s = (Surf*)wl_resource_get_user_data(r); s->pend_scale = sc > 0 ? sc : 1; }
void surf_offset(wl_client*, wl_resource*, int32_t, int32_t) {}
void surf_get_release(wl_client*, wl_resource*, uint32_t) {}
const struct wl_surface_interface surf_impl = {surf_destroy, surf_attach, surf_damage, surf_frame, surf_region, surf_region, surf_commit,
                                               surf_noop_i, surf_scale, surf_damage, surf_offset, surf_get_release};
void surf_res_destroy(wl_resource* r) {
  Surf* s = (Surf*)wl_resource_get_user_data(r);
  if (s->mapped) unmap(s);
  alive.erase(s); if (ptr_focus == s) ptr_focus = nullptr; if (kbd_focus == s) kbd_focus = nullptr;
  if (s->xs) s->xs->s = nullptr;
  delete s;
}

void comp_create_surface(wl_client* c, wl_resource* r, uint32_t id) {
  Surf* s = new Surf; s->client = c; s->id = NEXT_ID++; alive.insert(s);
  s->res = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
  wl_resource_set_implementation(s->res, &surf_impl, s, surf_res_destroy);
}
void comp_create_region(wl_client* c, wl_resource*, uint32_t id) { wl_resource* x = wl_resource_create(c, &wl_region_interface, 1, id); wl_resource_set_implementation(x, &region_impl, nullptr, nullptr); }
void comp_release(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
const struct wl_compositor_interface comp_impl = {comp_create_surface, comp_create_region, comp_release};
void comp_bind(wl_client* c, void*, uint32_t v, uint32_t id) { wl_resource* r = wl_resource_create(c, &wl_compositor_interface, v > 4 ? 4 : v, id); wl_resource_set_implementation(r, &comp_impl, nullptr, nullptr); }

// ---------------------------------------------------------------------------------------------------- wl_subcompositor (accepted so toolkits that probe for it are happy; subsurfaces are composed as separate windows)
void sub_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void sub_pos(wl_client*, wl_resource*, int32_t, int32_t) {}
void sub_above(wl_client*, wl_resource*, wl_resource*) {}
void sub_mode(wl_client*, wl_resource*) {}
const struct wl_subsurface_interface sub_impl = {sub_destroy, sub_pos, sub_above, sub_above, sub_mode, sub_mode};
void subc_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void subc_get(wl_client* c, wl_resource* r, uint32_t id, wl_resource*, wl_resource*) { wl_resource* x = wl_resource_create(c, &wl_subsurface_interface, 1, id); wl_resource_set_implementation(x, &sub_impl, nullptr, nullptr); }
const struct wl_subcompositor_interface subc_impl = {subc_destroy, subc_get};
void subc_bind(wl_client* c, void*, uint32_t, uint32_t id) { wl_resource* r = wl_resource_create(c, &wl_subcompositor_interface, 1, id); wl_resource_set_implementation(r, &subc_impl, nullptr, nullptr); }

// ---------------------------------------------------------------------------------------------------- xdg_shell
void pos_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void pos_size(wl_client*, wl_resource* r, int32_t w, int32_t h) { Pos* p = (Pos*)wl_resource_get_user_data(r); p->w = w; p->h = h; }
void pos_arect(wl_client*, wl_resource* r, int32_t x, int32_t y, int32_t w, int32_t h) { Pos* p = (Pos*)wl_resource_get_user_data(r); p->ax = x; p->ay = y; p->aw = w; p->ah = h; }
void pos_anchor(wl_client*, wl_resource* r, uint32_t a) { ((Pos*)wl_resource_get_user_data(r))->anchor = a; }
void pos_gravity(wl_client*, wl_resource* r, uint32_t g) { ((Pos*)wl_resource_get_user_data(r))->gravity = g; }
void pos_adjust(wl_client*, wl_resource*, uint32_t) {}
void pos_offset(wl_client*, wl_resource* r, int32_t x, int32_t y) { Pos* p = (Pos*)wl_resource_get_user_data(r); p->ox = x; p->oy = y; }
void pos_reactive(wl_client*, wl_resource*) {}
void pos_psize(wl_client*, wl_resource*, int32_t, int32_t) {}
void pos_pcfg(wl_client*, wl_resource*, uint32_t) {}
const struct xdg_positioner_interface pos_impl = {pos_destroy, pos_size, pos_arect, pos_anchor, pos_gravity, pos_adjust, pos_offset, pos_reactive, pos_psize, pos_pcfg};

void top_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void top_parent(wl_client*, wl_resource*, wl_resource*) {}
void top_title(wl_client*, wl_resource* r, const char* t) { XSurf* x = (XSurf*)wl_resource_get_user_data(r); if (x->s) x->s->title = t ? t : ""; }
void top_appid(wl_client*, wl_resource*, const char*) {}
void top_menu(wl_client*, wl_resource*, wl_resource*, uint32_t, int32_t, int32_t) {}
void top_move(wl_client*, wl_resource*, wl_resource*, uint32_t) {}
void top_resize(wl_client*, wl_resource*, wl_resource*, uint32_t, uint32_t) {}
void top_size(wl_client*, wl_resource*, int32_t, int32_t) {}
void top_noop(wl_client*, wl_resource*) {}
void top_full(wl_client*, wl_resource*, wl_resource*) {}
const struct xdg_toplevel_interface top_impl = {top_destroy, top_parent, top_title, top_appid, top_menu, top_move, top_resize, top_size, top_size, top_noop, top_noop, top_full, top_noop, top_noop};
void top_res_destroy(wl_resource* r) { XSurf* x = (XSurf*)wl_resource_get_user_data(r); x->top = nullptr; if (x->s && x->s->mapped) unmap(x->s); }

void pop_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void pop_grab(wl_client*, wl_resource*, wl_resource*, uint32_t) {}
void pop_reposition(wl_client*, wl_resource* r, wl_resource* positioner, uint32_t token) {
  XSurf* x = (XSurf*)wl_resource_get_user_data(r); x->pos = *(Pos*)wl_resource_get_user_data(positioner);
  xdg_popup_send_repositioned(r, token); send_popup_configure(x);
}
const struct xdg_popup_interface pop_impl = {pop_destroy, pop_grab, pop_reposition};
void pop_res_destroy(wl_resource* r) { XSurf* x = (XSurf*)wl_resource_get_user_data(r); x->pop = nullptr; if (x->s && x->s->mapped) unmap(x->s); }

void xs_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void xs_toplevel(wl_client* c, wl_resource* r, uint32_t id) {
  XSurf* x = (XSurf*)wl_resource_get_user_data(r);
  x->top = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(x->top, &top_impl, x, top_res_destroy);
}
void xs_popup(wl_client* c, wl_resource* r, uint32_t id, wl_resource* parent, wl_resource* positioner) {
  XSurf* x = (XSurf*)wl_resource_get_user_data(r);
  x->parent = parent ? (XSurf*)wl_resource_get_user_data(parent) : nullptr; x->pos = *(Pos*)wl_resource_get_user_data(positioner); if (x->s) x->s->popup = true;
  x->pop = wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(x->pop, &pop_impl, x, pop_res_destroy);
}
void xs_geometry(wl_client*, wl_resource* r, int32_t x, int32_t y, int32_t, int32_t) { XSurf* xs = (XSurf*)wl_resource_get_user_data(r); xs->gx = x; xs->gy = y; }
void xs_ack(wl_client*, wl_resource* r, uint32_t) { XSurf* x = (XSurf*)wl_resource_get_user_data(r); x->acked = true; }
const struct xdg_surface_interface xs_impl = {xs_destroy, xs_toplevel, xs_popup, xs_geometry, xs_ack};
void xs_res_destroy(wl_resource* r) { XSurf* x = (XSurf*)wl_resource_get_user_data(r); if (x->s) x->s->xs = nullptr; delete x; }

void wm_destroy(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
void wm_positioner(wl_client* c, wl_resource* r, uint32_t id) {
  wl_resource* p = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
  wl_resource_set_implementation(p, &pos_impl, new Pos, [](wl_resource* x) { delete (Pos*)wl_resource_get_user_data(x); });
}
void wm_get_surface(wl_client* c, wl_resource* r, uint32_t id, wl_resource* surf) {
  Surf* s = (Surf*)wl_resource_get_user_data(surf); XSurf* x = new XSurf; x->s = s; s->xs = x;
  x->res = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(x->res, &xs_impl, x, xs_res_destroy);
}
void wm_pong(wl_client*, wl_resource*, uint32_t) {}
const struct xdg_wm_base_interface wm_impl = {wm_destroy, wm_positioner, wm_get_surface, wm_pong};
void wm_bind(wl_client* c, void*, uint32_t v, uint32_t id) { wl_resource* r = wl_resource_create(c, &xdg_wm_base_interface, v > 5 ? 5 : v, id); wl_resource_set_implementation(r, &wm_impl, nullptr, nullptr); }

// ---------------------------------------------------------------------------------------------------- wl_output / wl_seat
void out_release(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
const struct wl_output_interface out_impl = {out_release};
int REFRESH_MHZ = 60000;   // announced in wl_output.mode; Chromium paces its frames by it, so it must be the real display rate (set by wlc_set_refresh)
void send_output(wl_resource* r) {
  wl_output_send_geometry(r, 0, 0, 65, 145, WL_OUTPUT_SUBPIXEL_UNKNOWN, "god", "termux", WL_OUTPUT_TRANSFORM_NORMAL);
  wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED, OW, OH, REFRESH_MHZ);
  if (wl_resource_get_version(r) >= WL_OUTPUT_SCALE_SINCE_VERSION) wl_output_send_scale(r, SCALE);
  if (wl_resource_get_version(r) >= WL_OUTPUT_DONE_SINCE_VERSION) wl_output_send_done(r);
}
void out_bind(wl_client* c, void*, uint32_t v, uint32_t id) {
  wl_resource* r = wl_resource_create(c, &wl_output_interface, v > 3 ? 3 : v, id);
  wl_resource_set_implementation(r, &out_impl, nullptr, [](wl_resource* x) { outputs.erase(std::remove(outputs.begin(), outputs.end(), x), outputs.end()); });
  outputs.push_back(r); send_output(r);
}

void ptr_set_cursor(wl_client*, wl_resource*, uint32_t, wl_resource*, int32_t, int32_t) {}
void rel(wl_client*, wl_resource* r) { wl_resource_destroy(r); }
const struct wl_pointer_interface ptr_impl = {ptr_set_cursor, rel};
const struct wl_keyboard_interface kbd_impl = {rel};
const struct wl_touch_interface touch_impl = {rel};
void seat_pointer(wl_client* c, wl_resource* r, uint32_t id) {
  wl_resource* p = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
  wl_resource_set_implementation(p, &ptr_impl, nullptr, [](wl_resource* x) { pointers.erase(std::remove(pointers.begin(), pointers.end(), x), pointers.end()); });
  pointers.push_back(p);
}
void seat_keyboard(wl_client* c, wl_resource* r, uint32_t id) {
  wl_resource* k = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
  wl_resource_set_implementation(k, &kbd_impl, nullptr, [](wl_resource* x) { keyboards.erase(std::remove(keyboards.begin(), keyboards.end(), x), keyboards.end()); });
  keyboards.push_back(k);
  wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, keymap_fd, keymap_size);
  if (wl_resource_get_version(k) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION) wl_keyboard_send_repeat_info(k, 30, 400);
}
void seat_touch(wl_client* c, wl_resource* r, uint32_t id) { wl_resource* t = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(t, &touch_impl, nullptr, [](wl_resource* x) { touches.erase(std::remove(touches.begin(), touches.end(), x), touches.end()); }); touches.push_back(t); }
const struct wl_seat_interface seat_impl = {seat_pointer, seat_keyboard, seat_touch, rel};
void seat_bind(wl_client* c, void*, uint32_t v, uint32_t id) {
  wl_resource* r = wl_resource_create(c, &wl_seat_interface, v > 5 ? 5 : v, id); wl_resource_set_implementation(r, &seat_impl, nullptr, nullptr);
  wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_TOUCH);
  if (v >= WL_SEAT_NAME_SINCE_VERSION) wl_seat_send_name(r, "seat0");
}


// ---------------------------------------------------------------------------------------------------- wl_data_device_manager (clipboard / drag stubs: GTK needs the global to create its seat)
void ds_offer(wl_client*, wl_resource*, const char*) {}
void ds_actions(wl_client*, wl_resource*, uint32_t) {}
const struct wl_data_source_interface dsrc_impl = {ds_offer, rel, ds_actions};
void dd_start_drag(wl_client*, wl_resource*, wl_resource*, wl_resource*, wl_resource*, uint32_t) {}
void dd_set_selection(wl_client*, wl_resource*, wl_resource*, uint32_t) {}
const struct wl_data_device_interface dd_impl = {dd_start_drag, dd_set_selection, rel};
void ddm_source(wl_client* c, wl_resource* r, uint32_t id) { wl_resource* x = wl_resource_create(c, &wl_data_source_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(x, &dsrc_impl, nullptr, nullptr); }
void ddm_device(wl_client* c, wl_resource* r, uint32_t id, wl_resource*) { wl_resource* x = wl_resource_create(c, &wl_data_device_interface, wl_resource_get_version(r), id); wl_resource_set_implementation(x, &dd_impl, nullptr, nullptr); }
const struct wl_data_device_manager_interface ddm_impl = {ddm_source, ddm_device, rel};
void ddm_bind(wl_client* c, void*, uint32_t v, uint32_t id) { wl_resource* r = wl_resource_create(c, &wl_data_device_manager_interface, v > 3 ? 3 : v, id); wl_resource_set_implementation(r, &ddm_impl, nullptr, nullptr); }

void touch_frame_all(wl_client* c) { each_res(touches, c, [&](wl_resource* r) { wl_touch_send_frame(r); }); }
void on_touch(int type, int id, float lx, float ly) {
  if (type == E_TDOWN) {
    dismiss_popups_if_outside();
    float sx = 0, sy = 0; Surf* t = hit(lx, ly, &sx, &sy); if (!t) return;
    if (!t->popup) kbd_set_focus(root_of(t));
    touch_focus[id] = t;
    each_res(touches, t->client, [&](wl_resource* r) { wl_touch_send_down(r, serial_++, now_ms(), t->res, id, wl_fixed_from_double(sx), wl_fixed_from_double(sy)); });
    touch_frame_all(t->client); return;
  }
  auto it = touch_focus.find(id); if (it == touch_focus.end()) return;
  Surf* t = it->second;
  if (!alive.count(t)) { touch_focus.erase(it); return; }
  if (type == E_TMOVE) {
    float sx = lx - t->x, sy = ly - t->y;
    each_res(touches, t->client, [&](wl_resource* r) { wl_touch_send_motion(r, now_ms(), id, wl_fixed_from_double(sx), wl_fixed_from_double(sy)); });
  } else if (type == E_TUP) {
    each_res(touches, t->client, [&](wl_resource* r) { wl_touch_send_up(r, serial_++, now_ms(), id); });
    touch_focus.erase(it);
  } else {   // E_TCANCEL: cancel every active touch of that client
    wl_client* c = t->client; each_res(touches, c, [&](wl_resource* r) { wl_touch_send_cancel(r); });
    for (auto i = touch_focus.begin(); i != touch_focus.end();) { if (alive.count(i->second) && i->second->client == c) i = touch_focus.erase(i); else ++i; }
  }
  touch_frame_all(t->client);
}

void process(const Ev& e) {
  switch (e.t) {
    case E_MOTION: cur_x = e.a / SCALE; cur_y = e.b / SCALE; pointer_update(); break;
    case E_BUTTON: pointer_update(); on_button(e.i, e.a > 0); break;
    case E_AXIS: if (ptr_focus) each_res(pointers, ptr_focus->client, [&](wl_resource* r) { wl_pointer_send_axis(r, now_ms(), WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_double(e.a)); pointer_frame(r); }); break;
    case E_KEY: on_key(e.i, e.a > 0); break;
    case E_TDOWN: case E_TMOVE: case E_TUP: case E_TCANCEL: on_touch(e.t, e.i, e.a / SCALE, e.b / SCALE); break;
    case E_CYCLE: { Surf* first = nullptr; int n = 0; for (Surf* x : order) if (!x->popup) { if (!first) first = x; n++; } if (n > 1) { int id = first->id; wlc_raise(id); } break; }
    case E_CHAR: { int code; bool sh; if (char_key(e.i, &code, &sh)) { if (sh) on_key(42, 1); on_key(code, 1); on_key(code, 0); if (sh) on_key(42, 0); } break; }
  }
}
void push(Ev e) { std::lock_guard<std::mutex> g(qm); if (queue.size() < 4096) queue.push_back(e); }
}  // namespace

// ================================================================================================== public API
extern "C" {
int wlc_init(const char* runtime_dir, const char* sock, int w, int h, int scale) {
  OW = w; OH = h; SCALE = scale > 0 ? scale : 1; rt_dir = runtime_dir;
  setenv("XDG_RUNTIME_DIR", runtime_dir, 1);
  dpy = wl_display_create(); if (!dpy) return -1;
  loop = wl_display_get_event_loop(dpy);
  std::string km; for (const char* c : KEYMAP_US) km += c;
  keymap_size = (uint32_t)km.size() + 1;
  char path[512]; snprintf(path, sizeof path, "%s/wlc-keymap-XXXXXX", runtime_dir); keymap_fd = mkstemp(path); if (keymap_fd < 0) return -3;
  unlink(path); if (ftruncate(keymap_fd, keymap_size) || write(keymap_fd, km.c_str(), keymap_size) != (ssize_t)keymap_size) return -4;
  wl_display_init_shm(dpy);
  wl_global_create(dpy, &wl_compositor_interface, 4, nullptr, comp_bind);
  wl_global_create(dpy, &wl_subcompositor_interface, 1, nullptr, subc_bind);
  wl_global_create(dpy, &xdg_wm_base_interface, 5, nullptr, wm_bind);
  wl_global_create(dpy, &wl_output_interface, 3, nullptr, out_bind);
  wl_global_create(dpy, &wl_seat_interface, 5, nullptr, seat_bind);
  wl_global_create(dpy, &wl_data_device_manager_interface, 3, nullptr, ddm_bind);
  if (wl_display_add_socket(dpy, sock) != 0) return -5;
  return 0;
}

void wlc_dispatch(int timeout_ms) {
  std::deque<Ev> q; { std::lock_guard<std::mutex> g(qm); q.swap(queue); }
  for (const Ev& e : q) process(e);
  wl_event_loop_dispatch(loop, timeout_ms); wl_display_flush_clients(dpy);
  int c = 0; for (Surf* x : order) if (!x->popup) c++; g_count = c;
}

int wlc_windows(WlcWin* out, int max) {
  layout(); int n = 0;
  for (Surf* s : order) { if (n >= max) break; if (!s->mapped || s->px.empty()) continue;
    out[n++] = WlcWin{s->id, s->x, s->y, lw(s), lh(s), s->bw, s->bh, s->popup ? 1 : 0, s->dirty ? 1 : 0, s->px.data()}; }
  return n;
}
void wlc_clean(int id) { for (Surf* s : order) if (s->id == id) s->dirty = false; }
void wlc_frame_done(void) {
  uint32_t t = now_ms();
  for (Surf* s : std::vector<Surf*>(order)) { std::vector<wl_resource*> f; f.swap(s->frames); for (wl_resource* cb : f) { wl_callback_send_done(cb, t); wl_resource_destroy(cb); } }
}
void wlc_set_refresh(int mhz) {
  if (mhz < 20000 || mhz > 360000 || mhz == REFRESH_MHZ) return; REFRESH_MHZ = mhz;
  for (wl_resource* o : outputs) send_output(o);
}
void wlc_resize(int w, int h) {
  if (w == OW && h == OH) return; OW = w; OH = h;
  for (wl_resource* o : outputs) send_output(o);
  for (Surf* s : order) if (!s->popup && s->xs && s->xs->top) send_toplevel_configure(s->xs);
}
int wlc_scale(void) { return SCALE; }
void wlc_set_scale(int sc) {
  if (sc < 1) sc = 1; if (sc > 4) sc = 4; if (sc == SCALE) return; SCALE = sc;
  for (wl_resource* o : outputs) send_output(o);
  for (Surf* s : order) if (!s->popup && s->xs && s->xs->top) send_toplevel_configure(s->xs);
}
void wlc_stop(void) { if (dpy) { wl_display_destroy_clients(dpy); wl_display_destroy(dpy); dpy = nullptr; } order.clear(); }
void wlc_in_motion(float x, float y) { push(Ev{E_MOTION, x, y, 0}); }
void wlc_in_button(int b, int p) { push(Ev{E_BUTTON, (float)p, 0, b}); }
void wlc_in_axis(float dy) { push(Ev{E_AXIS, dy, 0, 0}); }
void wlc_in_key(int c, int p) { push(Ev{E_KEY, (float)p, 0, c}); }
void wlc_in_char(int u) { push(Ev{E_CHAR, 0, 0, u}); }
void wlc_in_touch(int phase, int id, float x, float y) { push(Ev{E_TDOWN + (phase < 0 ? 0 : phase > 3 ? 3 : phase), x, y, id}); }
void wlc_in_cycle(void) { push(Ev{E_CYCLE, 0, 0, 0}); }
int wlc_window_count(void) { return g_count; }
int wlc_title(int id, char* out, int n) { for (Surf* s : order) if (s->id == id) { snprintf(out, n, "%s", s->title.c_str()); return (int)s->title.size(); } return -1; }
int wlc_toplevels(int* ids, int max) { int n = 0; for (Surf* s : order) if (!s->popup && n < max) ids[n++] = s->id; return n; }
void wlc_raise(int id) {
  Surf* t = nullptr; for (Surf* s : order) if (s->id == id && !s->popup) t = s; if (!t) return;
  std::vector<Surf*> keep, move; for (Surf* s : order) (s == t || (s->popup && root_of(s) == t) ? move : keep).push_back(s);
  order = keep; order.insert(order.end(), move.begin(), move.end()); kbd_set_focus(t);
}
}
