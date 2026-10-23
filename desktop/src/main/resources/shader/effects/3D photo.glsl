// 3D photo: AI works out what is near and far in your picture, so it moves like a real 3D scene
// when the mouse moves, and a slow camera drift keeps it alive when it doesn't.
// @image photo Picture
// @depth depth photo
// @slider amount 0 0.08 0.035 Depth
// @slider drift 0 1 0.4 Camera drift
// @slider focus 0 1 0.35 Still point (0 far, 1 near)
// @slider mouse 0 1 1 Follow the mouse

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

float nearness(vec2 q) {
    return texture(depth, q).r;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float t = iTime;
    // Where the camera looks from: the mouse plus a slow figure-of-eight.
    vec2 lean = (iPointer - 0.5) * mouse + vec2(sin(t * 0.23), sin(t * 0.31) * 0.6) * 0.45 * drift;
    vec2 shift = lean * amount;
    // Zoom in a little so the picture's edges never come into view, and breathe slowly.
    float zoom = 1.0 + amount * 2.2 + 0.012 * drift * (1.0 + sin(t * 0.13));
    vec2 p = cover((uv - 0.5) / zoom + 0.5);

    // March from the nearest layer to the farthest: the first surface the view ray meets wins,
    // so near things cover what is behind them.
    const int STEPS = 32;
    float layer = 1.0;
    float dz = 1.0 / float(STEPS);
    vec2 q = p - shift * (layer - focus);
    float previous = layer;
    for (int i = 0; i < STEPS; i++) {
        q = p - shift * (layer - focus);
        if (nearness(q) >= layer) break;
        previous = layer;
        layer -= dz;
    }
    // Refine between the last two layers.
    float lo = layer, hi = previous;
    for (int i = 0; i < 5; i++) {
        float mid = 0.5 * (lo + hi);
        if (nearness(p - shift * (mid - focus)) >= mid) lo = mid; else hi = mid;
    }
    q = clamp(p - shift * (0.5 * (lo + hi) - focus), 0.0, 1.0);
    fragColor = vec4(texture(photo, q).rgb, 1.0);
}
