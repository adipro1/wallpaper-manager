// Layers: several effects on one picture, each switched on and tuned in its settings. AI depth
// makes them 3D: the picture moves like a scene, fog sits in the distance, fireflies fly
// between the trees and light only shines past what is far away.
// @image photo Picture
// @depth depth photo
// @slider amount 0 0.08 0.03 3D depth (0 = flat)
// @checkbox move on 3D: follow the mouse
// @checkbox fireflies on Fireflies
// @slider flies 0 1 0.5 Fireflies: how many
// @color flyColor #ffd27a Fireflies: color
// @checkbox rays on Light rays
// @slider rayStrength 0 1 0.45 Light rays: strength
// @slider raySource 0 1 0.3 Light rays: from (left to right)
// @checkbox fog off Fog in the distance
// @slider fogAmount 0 1 0.5 Fog: thickness
// @checkbox weather on Rain and snow when it does outside
// @checkbox bars off Music bars
// @slider barHeight 0 1 0.4 Music bars: height
// @checkbox cursor on Soft light at the mouse

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

// A random number per cell (no sin(), which is imprecise on some GPUs).
float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// How near the picture is at q (1 near, 0 far).
float nearness(vec2 q) { return texture(depth, q).r; }

const float FOCUS = 0.35;

// Where the view ray through p meets the picture when the camera is shifted (see 3D photo).
vec2 look(vec2 p, vec2 shift) {
    const int STEPS = 24;
    float layer = 1.0, previous = 1.0;
    for (int i = 0; i < STEPS; i++) {
        if (nearness(p - shift * (layer - FOCUS)) >= layer) break;
        previous = layer;
        layer -= 1.0 / float(STEPS);
    }
    float lo = layer, hi = previous;
    for (int i = 0; i < 4; i++) {
        float mid = 0.5 * (lo + hi);
        if (nearness(p - shift * (mid - FOCUS)) >= mid) lo = mid; else hi = mid;
    }
    return clamp(p - shift * (0.5 * (lo + hi) - FOCUS), 0.0, 1.0);
}

float rainStreaks(vec2 uv, float amount) {
    float aspect = iResolution.x / iResolution.y;
    float total = 0.0;
    for (int i = 0; i < 3; i++) {
        float layer = float(i);
        float scale = 28.0 + layer * 22.0;
        vec2 p = vec2((uv.x + uv.y * 0.12) * aspect * scale, uv.y * scale * 0.18 + iTime * (2.6 - layer * 0.5));
        vec2 id = floor(p);
        if (hash(id + layer * 7.0) > amount * 0.8) continue;
        vec2 f = fract(p);
        float x = hash(id + 3.0) * 0.8 + 0.1;
        total += smoothstep(0.06, 0.0, abs(f.x - x)) * smoothstep(0.0, 0.3, f.y) * smoothstep(1.0, 0.6, f.y) * (0.5 - layer * 0.12);
    }
    return total;
}

float snowFlakes(vec2 uv, float amount) {
    float aspect = iResolution.x / iResolution.y;
    float total = 0.0;
    for (int i = 0; i < 5; i++) {
        float layer = float(i);
        float scale = 6.0 + layer * 5.0;
        float t = iTime * (0.6 - layer * 0.07);
        vec2 p = vec2(uv.x * aspect, uv.y) * scale + vec2(-0.4 * t, t * 3.0);
        p.x += sin(p.y * 0.7 + layer) * 0.3;
        vec2 id = floor(p);
        if (hash(id + layer * 13.0) > amount) continue;
        vec2 center = vec2(hash(id + 1.0), hash(id + 2.0)) * 0.8 + 0.1;
        float size = 0.05 + 0.06 * hash(id + 4.0) - layer * 0.006;
        total += smoothstep(size, size * 0.3, length(fract(p) - center)) * (1.0 - layer * 0.12);
    }
    return clamp(total, 0.0, 1.0);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float aspect = iResolution.x / iResolution.y;
    float t = iTime;

    // 3D: the camera follows the mouse and drifts slowly.
    vec2 drift = vec2(sin(t * 0.23), sin(t * 0.31) * 0.6) * (move ? 0.2 : 0.4);
    vec2 shift = ((move ? iPointer - 0.5 : vec2(0.0)) + drift) * amount;
    float zoom = 1.0 + amount * 2.2;
    vec2 p = cover((uv - 0.5) / zoom + 0.5);
    vec2 q = amount > 0.0 ? look(p, shift) : p;
    vec3 color = texture(photo, q).rgb;
    float here = nearness(q);

    // Fog: far parts fade into the picture's own sky color, drifting slowly.
    if (fog) {
        vec3 haze = mix(textureLod(photo, vec2(0.5, 0.85), 9.0).rgb, vec3(0.82, 0.84, 0.88), 0.35);
        float thick = fogAmount * (1.0 - smoothstep(0.05, 0.75, here));
        thick *= 0.7 + 0.3 * noise(uv * vec2(3.0, 5.0) + vec2(t * 0.04, 0.0));
        color = mix(color, haze, clamp(thick, 0.0, 0.92));
    }

    // Light rays: bright, far parts of the picture shine towards the viewer from above.
    if (rays) {
        vec2 source = vec2(raySource, 1.15);
        vec2 dir = (source - uv) / 28.0;
        vec2 s = uv;
        float light = 0.0, weight = 1.0;
        for (int i = 0; i < 28; i++) {
            s += dir;
            vec2 sq = cover((s - 0.5) / zoom + 0.5);
            float bright = smoothstep(0.6, 0.95, luma(textureLod(photo, sq, 2.0).rgb));
            light += bright * (1.0 - nearness(sq) * 0.85) * weight;
            weight *= 0.94;
        }
        float shimmer = 0.85 + 0.15 * sin(t * 0.35 + uv.x * 7.0);
        color += vec3(1.0, 0.9, 0.72) * light / 10.0 * rayStrength * shimmer;
    }

    // The real weather (from Living weather): rain streaks and snowflakes.
    if (weather) {
        color += vec3(0.8, 0.85, 0.95) * rainStreaks(uv, iWeather.x) * 0.7;
        color = mix(color, vec3(1.0), snowFlakes(uv, iWeather.y));
    }

    // Fireflies: each flies at its own depth, so nearer parts of the picture hide it.
    if (fireflies) {
        int count = int(4.0 + flies * 40.0);
        for (int i = 0; i < 44; i++) {
            if (i >= count) break;
            float n = float(i);
            vec2 seed = vec2(n * 7.13, n * 3.71);
            float z = 0.15 + 0.75 * hash(seed + 1.0);
            vec2 pos = vec2(hash(seed), 0.12 + 0.6 * hash(seed + 2.0));
            pos += 0.07 * vec2(sin(t * (0.15 + 0.25 * hash(seed + 3.0)) + n), cos(t * (0.12 + 0.2 * hash(seed + 4.0)) + n * 1.7));
            pos += shift * (z - FOCUS) * zoom;
            vec2 d = (uv - pos) * vec2(aspect, 1.0);
            float size = 0.0015 + 0.004 * z;
            float glow = exp(-dot(d, d) / (size * size)) + 0.22 * exp(-dot(d, d) / (16.0 * size * size));
            float blink = 0.35 + 0.65 * smoothstep(0.2, 1.0, 0.5 + 0.5 * sin(t * (0.7 + 1.6 * hash(seed + 5.0)) + n * 2.3));
            float visible = smoothstep(0.04, -0.04, here - z);
            color += flyColor * glow * blink * visible * 1.4;
        }
    }

    // Music bars along the bottom, in the album's color while music plays.
    if (bars) {
        const float BARS = 56.0;
        float index = floor(uv.x * BARS);
        float level = texture(iChannel0, vec2((index + 0.5) / BARS * 0.45, 0.25)).r;
        float top = level * barHeight * 0.3;
        float inside = step(fract(uv.x * BARS), 0.72);
        vec3 tint = mix(vec3(1.0, 0.93, 0.85), iMusic.rgb * 1.2 + 0.1, iMusic.a);
        color = mix(color, tint, inside * step(uv.y, top) * 0.65);
        color += tint * inside * smoothstep(top + 0.04, top, uv.y) * step(top, uv.y) * 0.18 * step(0.01, level);
    }

    // A soft warm light where the mouse is.
    if (cursor) {
        vec2 d = (uv - iPointer) * vec2(aspect, 1.0);
        color += vec3(1.0, 0.86, 0.62) * 0.1 * exp(-dot(d, d) / 0.015);
    }

    fragColor = vec4(color, 1.0);
}
