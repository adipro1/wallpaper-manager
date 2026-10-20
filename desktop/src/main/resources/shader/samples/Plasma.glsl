// Plasma - soft flowing colours. Slow and cheap to run.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = (fragCoord - 0.5 * iResolution.xy) / iResolution.y;
    float t = iTime * 0.25;
    float v = sin(uv.x * 3.0 + t)
            + sin(uv.y * 4.0 - t * 1.3)
            + sin((uv.x + uv.y) * 3.5 + t * 0.7)
            + sin(length(uv * 4.0) - t * 2.0);
    vec3 color = 0.5 + 0.5 * cos(vec3(0.0, 2.1, 4.2) + v * 1.2 + t);
    fragColor = vec4(color * 0.85, 1.0);
}
