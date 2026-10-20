// Ripples - click (or drag) on the empty desktop to make waves. Needs "React to the mouse".
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.y;
    vec2 center = (iMouse.z != 0.0 ? abs(iMouse.zw) : 0.5 * iResolution.xy) / iResolution.y;
    float d = length(uv - center);
    // Waves spread from the last click; the press time isn't known, so they keep rolling outwards.
    float wave = sin(d * 40.0 - iTime * 4.0) * exp(-d * 3.0);
    vec2 drag = iMouse.xy / iResolution.y;
    float spot = exp(-length(uv - drag) * 12.0) * step(0.0, iMouse.z);
    vec3 water = mix(vec3(0.02, 0.10, 0.20), vec3(0.05, 0.35, 0.55), 0.5 + 0.5 * wave);
    water += vec3(0.6, 0.9, 1.0) * spot * 0.6;
    fragColor = vec4(water, 1.0);
}
