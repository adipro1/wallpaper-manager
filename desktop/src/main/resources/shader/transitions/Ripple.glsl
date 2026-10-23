// Ripple: a wave spreads from the middle and leaves the new picture behind.
// @image from Old picture
// @image to New picture

vec2 cover(vec2 uv, vec2 image) {
    vec2 screen = iResolution.xy;
    image = max(image, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float p = clamp(iTime / DURATION, 0.0, 1.0);
    float aspect = iResolution.x / iResolution.y;
    vec2 d = (uv - 0.5) * vec2(aspect, 1.0);
    float r = length(d);
    // The front starts just before the middle (the first frame is exactly the old picture) and ends past the corners.
    float front = p * (0.5 * length(vec2(aspect, 1.0)) + 0.15) - 0.06;
    float ring = r - front;
    // Rings of waves follow the front and calm down behind it, like a stone dropped in water.
    float behind = max(-ring, 0.0);
    float waves = sin(ring * 90.0 - iTime * 12.0) * exp(-behind * 7.0) * smoothstep(0.03, -0.01, ring);
    float wave = waves * (1.0 - p) * smoothstep(0.0, 0.08, p) * 0.012;
    vec2 bent = uv + normalize(d + 1e-5) * wave / vec2(aspect, 1.0);
    vec3 a = texture(from, cover(bent, iChannelResolution[1].xy)).rgb;
    vec3 b = texture(to, cover(bent, iChannelResolution[2].xy)).rgb;
    vec3 color = mix(b, a, smoothstep(-0.015, 0.015, ring));
    color *= 1.0 + waves * 0.12 * (1.0 - p);     // light and shade on the waves
    fragColor = vec4(color, 1.0);
}
