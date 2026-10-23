// Fade: the new picture slowly appears over the old one.
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
    float p = smoothstep(0.0, 1.0, clamp(iTime / DURATION, 0.0, 1.0));
    vec3 a = texture(from, cover(uv, iChannelResolution[1].xy)).rgb;
    vec3 b = texture(to, cover(uv, iChannelResolution[2].xy)).rgb;
    fragColor = vec4(mix(a, b, p), 1.0);
}
