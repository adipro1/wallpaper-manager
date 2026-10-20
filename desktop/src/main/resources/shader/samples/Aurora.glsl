// Aurora - northern lights over a dark sky with twinkling stars.
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * noise(p); p *= 2.0; a *= 0.5; }
    return v;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 sky = mix(vec3(0.01, 0.02, 0.06), vec3(0.02, 0.05, 0.12), uv.y);

    // Stars
    vec2 grid = fragCoord / 3.0;
    float star = step(0.998, hash(floor(grid)));
    star *= 0.5 + 0.5 * sin(iTime * 2.0 + hash(floor(grid)) * 40.0);
    sky += star * 0.8 * smoothstep(0.3, 1.0, uv.y);

    // Curtains of light
    float t = iTime * 0.05;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float band = fbm(vec2(uv.x * 3.0 + t * (1.0 + fi * 0.3), fi * 4.0 + t));
        float y = 0.55 + 0.2 * band + fi * 0.06;
        float glow = exp(-pow((uv.y - y) * (9.0 + fi * 4.0), 2.0)) * smoothstep(0.0, 0.4, uv.y);
        vec3 tint = mix(vec3(0.1, 0.9, 0.5), vec3(0.4, 0.3, 0.9), fi / 2.0);
        sky += tint * glow * (0.6 + 0.4 * fbm(vec2(uv.x * 10.0, iTime * 0.2 + fi)));
    }
    fragColor = vec4(sky, 1.0);
}
