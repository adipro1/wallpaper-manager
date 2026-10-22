// Parallax: your picture leans towards the mouse, the lower part (closer) more than the top.
// @image photo Picture
// @slider depth 0 0.08 0.03 Depth
// @slider drift 0 1 0.3 Drift when the mouse is still

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 lean = (iPointer - 0.5) + vec2(sin(iTime * 0.21), cos(iTime * 0.17)) * 0.5 * drift;
    float near = mix(0.4, 1.0, 1.0 - uv.y);
    vec2 p = (uv - 0.5) / (1.0 + depth * 2.5) + 0.5 - lean * depth * near;
    fragColor = texture(photo, cover(p));
}
