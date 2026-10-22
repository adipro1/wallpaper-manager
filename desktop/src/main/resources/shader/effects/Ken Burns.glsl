// Ken Burns: a slow zoom and pan across your picture.
// @image photo Picture
// @slider speed 0.1 3 1 Speed
// @slider zoom 1 1.6 1.25 Zoom

// The picture fills the screen without stretching (cropping what doesn't fit).
vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float t = iTime * 0.02 * speed;
    float z = mix(1.0, zoom, 0.5 - 0.5 * cos(t * 6.2831853));
    vec2 pan = vec2(sin(t * 3.7), cos(t * 2.9)) * 0.5 * (1.0 - 1.0 / z);
    fragColor = texture(photo, cover((uv - 0.5) / z + 0.5 + pan));
}
