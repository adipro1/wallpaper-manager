// Rain on glass: drops run down a window in front of your picture.
// @image photo Picture
// @slider amount 0 1 0.6 Rain
// @slider blur 0 1 0.35 Mist

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }

// One layer of drops: xy = how much a drop bends the light here, z = 1 inside a drop.
vec3 drops(vec2 uv, float scale, float t) {
    vec2 grid = vec2(scale * iResolution.x / iResolution.y, scale);
    vec2 id = floor(uv * grid);
    vec2 st = fract(uv * grid) - 0.5;
    float h = hash(id);
    if (h > amount) return vec3(0.0);
    float fall = fract(t * (0.04 + 0.08 * hash(id + 7.0)) + h);
    vec2 center = vec2((hash(id + 3.0) - 0.5) * 0.5, 0.32 - fall * 0.64); // stays inside its cell
    vec2 d = (st - center) * vec2(1.0, 0.8);
    float inside = smoothstep(0.13, 0.09, length(d));
    float trail = smoothstep(0.03, 0.0, abs(st.x - center.x)) * step(center.y, st.y) * (1.0 - fall) * 0.35;
    return vec3(d * inside * 4.0, max(inside, trail));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 a = drops(uv, 9.0, iTime);
    vec3 b = drops(uv + 0.37, 15.0, iTime * 1.3);
    vec2 bend = (a.xy + b.xy * 0.6) * 0.05;
    float wet = clamp(a.z + b.z, 0.0, 1.0);
    vec3 color = textureLod(photo, cover(uv - bend), mix(blur * 5.0, 0.0, wet)).rgb;
    fragColor = vec4(color * mix(0.85, 1.05, wet), 1.0);
}
