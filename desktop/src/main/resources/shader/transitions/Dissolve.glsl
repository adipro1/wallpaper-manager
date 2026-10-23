// Dissolve: the old picture burns away like paper, with glowing edges.
// @image from Old picture
// @image to New picture

vec2 cover(vec2 uv, vec2 image) {
    vec2 screen = iResolution.xy;
    image = max(image, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

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

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float p = clamp(iTime / DURATION, 0.0, 1.0);
    vec3 a = texture(from, cover(uv, iChannelResolution[1].xy)).rgb;
    vec3 b = texture(to, cover(uv, iChannelResolution[2].xy)).rgb;
    vec2 q = uv * vec2(iResolution.x / iResolution.y, 1.0);
    float n = noise(q * 4.0) * 0.6 + noise(q * 11.0) * 0.3 + noise(q * 29.0) * 0.1;
    float edge = p * 1.3 - 0.15;                 // where the burning edge is now
    float d = n - edge;                           // < 0: burnt away (new picture), > 0: old paper
    vec3 color = mix(b, a, smoothstep(-0.004, 0.004, d));
    // The paper browns just before it burns, and a thin line glows where it does.
    color *= 1.0 - 0.55 * smoothstep(0.05, 0.0, d) * step(0.0, d);
    float glow = smoothstep(0.012, 0.0, abs(d)) * step(0.001, p) * step(p, 0.999);
    color += vec3(1.0, 0.6, 0.25) * glow * 1.4;
    fragColor = vec4(color, 1.0);
}
