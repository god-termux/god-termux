#version 450
precision mediump float;
layout(push_constant) uniform PC { vec2 r; float t; } pc;
layout(location = 0) out vec4 o;
void main() {
  vec2 p = gl_FragCoord.xy / pc.r; float s = 0.0;
  for (int i = 0; i < 48; i++) { float k = float(i) * 0.37 + pc.t; s += sin(p.x * 11.0 + k) * cos(p.y * 9.0 - k * 0.7); p = p.yx + vec2(0.01, 0.02) * s; }
  o = vec4(0.5 + 0.5 * sin(s), 0.5 + 0.5 * cos(s * 1.3), 0.5 + 0.5 * sin(s * 0.7), 1.0);
}
