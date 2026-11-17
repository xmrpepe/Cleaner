#version 450
// Moderate-ALU fragment shader for blended fill-rate measurement.
layout(location = 0) out vec4 outColor;
layout(push_constant) uniform PC { float layer; } pc;
void main() {
    vec2 uv = gl_FragCoord.xy * 0.001;
    float v = sin(uv.x * 3.0 + pc.layer) * cos(uv.y * 2.0 - pc.layer);
    outColor = vec4(0.5 + 0.5 * v, uv.x, uv.y, 0.05);
}
