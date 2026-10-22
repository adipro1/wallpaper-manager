// Snowfall: flakes drift down over your picture.
// @image photo Picture
// @slider amount 0 1 0.6 Snow
// @slider wind -1 1 0.2 Wind
// @slider speed 0.2 2 1 Speed

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 color = texture(photo, cover(uv)).rgb * 0.92;
    float aspect = iResolution.x / iResolution.y;
    float snow = 0.0;
    for (int i = 0; i < 5; i++) {
        float layer = float(i);
        float scale = 6.0 + layer * 5.0;
        float t = iTime * speed * (0.6 - layer * 0.07);
        vec2 p = vec2(uv.x * aspect, uv.y) * scale + vec2(-wind * t * 2.0, t * 3.0);
        p.x += sin(p.y * 0.7 + layer) * 0.3;
        vec2 id = floor(p);
        if (hash(id + layer * 13.0) > amount) continue;
        vec2 center = vec2(hash(id + 1.0), hash(id + 2.0)) * 0.8 + 0.1;
        float size = 0.05 + 0.06 * hash(id + 4.0) - layer * 0.006;
        snow += smoothstep(size, size * 0.3, length(fract(p) - center)) * (1.0 - layer * 0.12);
    }
    fragColor = vec4(mix(color, vec3(1.0), clamp(snow, 0.0, 1.0)), 1.0);
}
