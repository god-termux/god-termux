#version 450
precision mediump float;
layout(push_constant) uniform PC { vec2 r; float t; } pc;
layout(location = 0) out vec4 o;
void main() { o = vec4(pc.t, 0.2, 0.4, 1.0); }
