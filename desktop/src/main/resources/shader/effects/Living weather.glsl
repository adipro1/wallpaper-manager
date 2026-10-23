// Living weather: when it rains, snows or storms outside, it does on your picture too.
// @image photo Picture
// @checkbox live on Follow the real weather
// @slider rain 0 1 0.7 Rain (when not following)
// @slider snow 0 1 0 Snow (when not following)
// @slider fog 0 1 0 Fog (when not following)
// @checkbox storm off Thunderstorm (when not following)
// @checkbox night on Darker at night

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

// Falling rain: thin slanted streaks in three depths.
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
        float line = smoothstep(0.06, 0.0, abs(f.x - x)) * smoothstep(0.0, 0.3, f.y) * smoothstep(1.0, 0.6, f.y);
        total += line * (0.5 - layer * 0.12);
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
    vec4 w = live ? iWeather : vec4(rain, snow, fog, storm ? 1.0 : 0.0);
    float clouds = live ? iClouds : clamp(max(max(w.z, w.y * 0.8), w.x * 0.9 + w.w), 0.0, 1.0);

    // Wet and rainy: a little softer.
    vec3 c = textureLod(photo, cover(uv), w.x * 1.2 + w.z * 1.5).rgb;
    float lum = dot(c, vec3(0.299, 0.587, 0.114));

    // Overcast: flatter, greyer, cooler light.
    float grey = clamp(clouds * 0.45 + w.x * 0.2 + w.w * 0.25, 0.0, 0.8);
    c = mix(c, vec3(lum) * vec3(0.94, 0.97, 1.03), grey * 0.6) * (1.0 - grey * 0.3);

    // Night: much darker and bluer (after sunset, before sunrise).
    float dark = night ? 1.0 - smoothstep(-12.0, 0.0, iSun.x) : 0.0;
    c = mix(c, c * vec3(0.22, 0.26, 0.42), dark * 0.85);

    // Fog: thicker low down, drifting slowly.
    float drift = noise(uv * vec2(3.0, 6.0) + vec2(iTime * 0.03, 0.0)) * 0.6 + noise(uv * 9.0 - iTime * 0.05) * 0.4;
    float haze = w.z * clamp(0.55 + 0.45 * (1.0 - uv.y) + (drift - 0.5) * 0.5, 0.0, 1.0);
    c = mix(c, mix(vec3(0.78, 0.8, 0.83), vec3(0.12, 0.13, 0.17), dark), haze * 0.8);

    // Rain and snow.
    float lightness = mix(0.75, 0.3, dark);
    c += vec3(0.8, 0.85, 0.95) * rainStreaks(uv, w.x) * lightness;
    c = mix(c, vec3(mix(1.0, 0.55, dark)), snowFlakes(uv, w.y));

    // Lightning: a bright flash every few seconds during a storm.
    float slot = floor(iTime * 3.0);
    float flash = step(0.965, hash(vec2(slot, 7.0))) * exp(-fract(iTime * 3.0) * 4.0) * w.w;
    c += vec3(0.75, 0.8, 1.0) * flash * (0.35 + 0.65 * lum);

    fragColor = vec4(c, 1.0);
}
