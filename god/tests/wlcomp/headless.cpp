// Headless test backend: runs the compositor, optionally injects scripted input, writes the composed frame as PPM.
//   wlc-headless RUNTIME_DIR SECONDS OUT.ppm [W H SCALE] [events...]      event = MS:m:X:Y | MS:d | MS:u | MS:c:CHAR | MS:k:EVDEV:PRESSED | MS:w:DY
#include "wlc.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <string>
#include <vector>
struct Script { long ms; std::string cmd; };
static long now() { timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec * 1000L + t.tv_nsec / 1000000; }
int main(int argc, char** argv) {
  if (argc < 4) { fprintf(stderr, "usage\n"); return 2; }
  int W = 1080, H = 2392, S = 2, ai = 4;
  if (argc >= 7 && isdigit(argv[4][0])) { W = atoi(argv[4]); H = atoi(argv[5]); S = atoi(argv[6]); ai = 7; }
  std::vector<Script> sc;
  for (int i = ai; i < argc; i++) { char* c = strchr(argv[i], ':'); if (c) sc.push_back({atol(argv[i]), std::string(c + 1)}); }
  int r = wlc_init(argv[1], "wayland-0", W, H, S); if (r) { fprintf(stderr, "wlc_init failed %d\n", r); return 1; }
  long t0 = now(), end = t0 + atol(argv[2]) * 1000, lastf = 0; size_t si = 0;
  while (now() < end) {
    wlc_dispatch(4);
    long t = now() - t0;
    while (si < sc.size() && sc[si].ms <= t) {
      const std::string& c = sc[si].cmd; float a, b; int i1, i2;
      if (sscanf(c.c_str(), "m:%f:%f", &a, &b) == 2) wlc_in_motion(a, b);
      else if (c == "d") wlc_in_button(0, 1); else if (c == "u") wlc_in_button(0, 0);
      else if (c.size() >= 3 && c[0] == 'c' && c[1] == ':') wlc_in_char((unsigned char)c[2]);
      else if (sscanf(c.c_str(), "k:%d:%d", &i1, &i2) == 2) wlc_in_key(i1, i2);
      else if (sscanf(c.c_str(), "w:%f", &a) == 1) wlc_in_axis(a);
      si++;
    }
    if (t - lastf >= 16) { wlc_frame_done(); lastf = t; }
  }
  // compose
  std::vector<uint32_t> fb((size_t)W * H, 0xff101010u); WlcWin ws[64]; int n = wlc_windows(ws, 64);
  fprintf(stderr, "windows: %d\n", n);
  for (int i = 0; i < n; i++) {
    WlcWin& w = ws[i]; fprintf(stderr, "  win %d: pos %d,%d logical %dx%d buffer %dx%d popup=%d\n", w.id, w.x, w.y, w.w, w.h, w.bw, w.bh, w.popup);
    int dw = w.w * S, dh = w.h * S, dx0 = w.x * S, dy0 = w.y * S;
    for (int y = 0; y < dh; y++) { int dy = dy0 + y; if (dy < 0 || dy >= H) continue; int sy = (long)y * w.bh / dh;
      for (int x = 0; x < dw; x++) { int dx = dx0 + x; if (dx < 0 || dx >= W) continue; uint32_t s = w.px[(size_t)sy * w.bw + (long)x * w.bw / dw], d = fb[(size_t)dy * W + dx];
        uint32_t a = s >> 24, ia = 255 - a;
        uint32_t rr = ((s >> 16) & 255) + (((d >> 16) & 255) * ia) / 255, gg = ((s >> 8) & 255) + (((d >> 8) & 255) * ia) / 255, bb = (s & 255) + ((d & 255) * ia) / 255;
        fb[(size_t)dy * W + dx] = 0xff000000u | (std::min(rr, 255u) << 16) | (std::min(gg, 255u) << 8) | std::min(bb, 255u); } }
  }
  FILE* f = fopen(argv[3], "wb"); fprintf(f, "P6\n%d %d\n255\n", W, H);
  for (size_t i = 0; i < fb.size(); i++) { unsigned char p[3] = {(unsigned char)(fb[i] >> 16), (unsigned char)(fb[i] >> 8), (unsigned char)fb[i]}; fwrite(p, 1, 3, f); }
  fclose(f); wlc_stop(); return 0;
}
