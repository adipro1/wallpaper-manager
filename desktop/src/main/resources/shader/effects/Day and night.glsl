// Day and night: your picture follows the real sun - golden at sunset, blue at dusk, dark with stars at night.
// @image photo Picture
// @checkbox real on Follow the real sun
// @slider hour 0 24 20 Time of day (when not following the sun)
// @slider strength 0 1 0.9 Strength
// @checkbox stars on Stars at night

// The picture fills the screen without stretching (cropping what doesn't fit).
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

// The sun's height in degrees: the real one, or one for the chosen hour (6 sunrise, 12 noon, 18 sunset).
float sunHeight() {
    if (real) return iSun.x;
    return degrees(asin(0.7071 * cos((hour - 12.0) / 24.0 * 6.2831853)));
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 at = cover(uv);
    vec3 c = texture(photo, at).rgb;
    vec3 blurred = textureLod(photo, at, 5.0).rgb;
    float lum = luma(c);
    float el = sunHeight();

    // How much each part of the day shows (they overlap, so changes are gradual).
    float golden = smoothstep(-3.0, 1.5, el) * (1.0 - smoothstep(6.0, 14.0, el));
    float dusk = smoothstep(-11.0, -4.0, el) * (1.0 - smoothstep(-3.0, 1.0, el));
    float night = 1.0 - smoothstep(-13.0, -6.0, el);

    // Sky: the upper part of the picture where it is bright around (clouds count as sky). The blurred
    // picture decides, so edges of clouds and hills don't get outlines.
    float sky = smoothstep(0.15, 0.55, uv.y) * smoothstep(0.45, 0.7, luma(blurred));
    // Lamps and windows: small spots much brighter than the area around them.
    float lamp = smoothstep(0.12, 0.3, lum - luma(textureLod(photo, at, 7.0).rgb)) * smoothstep(0.55, 0.8, lum) * (1.0 - sky);

    vec3 graded = c;

    // Golden hour: warm light; the sky glows orange at the horizon, pink higher up, soft blue at the top.
    vec3 warm = c * vec3(1.25, 0.95, 0.66) + vec3(0.08, 0.03, 0.0);
    float up = smoothstep(0.3, 1.0, uv.y);
    vec3 glow = mix(mix(vec3(1.0, 0.6, 0.22), vec3(0.97, 0.52, 0.42), smoothstep(0.1, 0.7, up)), vec3(0.55, 0.55, 0.82), smoothstep(0.75, 1.0, up));
    warm = mix(warm, glow * (0.65 + 0.45 * lum), sky * 0.9);
    graded = mix(graded, warm, golden);

    // Blue hour: cooler and darker; the sky turns violet above a pink band.
    vec3 cool = mix(vec3(lum), c, 0.45) * vec3(0.58, 0.62, 0.95) * 0.6;
    vec3 duskSky = mix(vec3(0.82, 0.48, 0.58), vec3(0.3, 0.32, 0.62), smoothstep(0.3, 0.85, uv.y)) * (0.4 + 0.35 * lum);
    cool = mix(cool, duskSky, sky * 0.75);
    graded = mix(graded, cool, dusk);

    // Night: moonlit and dark; the sky stays a little lighter so the skyline shows.
    vec3 moon = vec3(lum) * vec3(0.16, 0.2, 0.36);
    vec3 nightSky = mix(vec3(0.07, 0.09, 0.2), vec3(0.025, 0.035, 0.09), smoothstep(0.3, 1.0, uv.y)) * (0.7 + 0.5 * lum);
    moon = mix(moon, nightSky, sky);
    graded = mix(graded, moon, night);

    // Lamps glow warm from dusk on.
    graded = mix(graded, c * vec3(1.15, 0.9, 0.6), lamp * max(dusk * 0.7, night));

    // Stars in the clear parts of the sky (not on clouds), twinkling.
    if (stars && night > 0.01) {
        vec2 cell = floor(fragCoord / 2.0);
        float h = hash(cell);
        float twinkle = 0.6 + 0.4 * sin(iTime * (0.8 + fract(h * 91.7) * 2.5) + h * 50.0);
        float star = smoothstep(0.996, 1.0, h) * twinkle;
        float clear = 1.0 - smoothstep(0.03, 0.1, length(blurred - textureLod(photo, at, 7.0).rgb)) * smoothstep(0.7, 0.85, lum);
        graded += vec3(0.85, 0.9, 1.0) * star * sky * clear * night * 2.5;
    }

    fragColor = vec4(mix(c, graded, strength), 1.0);
}
