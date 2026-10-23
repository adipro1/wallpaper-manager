// Fold: the old picture folds away in strips, like paper, and shows the new one.
// @image from Old picture
// @image to New picture

// A picture filling the screen without stretching (as Windows' "Fill" does).
vec2 cover(vec2 uv, vec2 image) {
    vec2 screen = iResolution.xy;
    image = max(image, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float p = clamp(iTime / DURATION, 0.0, 1.0);
    vec3 color = texture(to, cover(uv, iChannelResolution[2].xy)).rgb;

    const float strips = 8.0;
    float i = floor(uv.x * strips);
    float local = fract(uv.x * strips);
    // The strips fold one after the other, from left to right.
    float t = smoothstep(0.0, 1.0, clamp(p * 1.7 - i / strips * 0.7, 0.0, 1.0));
    float angle = t * 1.5707963;
    float width = cos(angle);
    // An accordion: even strips fold towards their left edge, odd ones towards their right edge.
    bool even = mod(i, 2.0) < 1.0;
    float x = even ? local : 1.0 - local;

    if (x < width) {
        float along = x / max(width, 1e-4);
        // The edge that lifts away gets a little shorter (perspective).
        float lift = sin(angle) * 0.1 * along;
        vec2 at = vec2((i + (even ? along : 1.0 - along)) / strips, (uv.y - 0.5) / (1.0 - lift) + 0.5);
        if (at.y >= 0.0 && at.y <= 1.0) {
            color = texture(from, cover(at, iChannelResolution[1].xy)).rgb;
            // Mountain folds catch the light, valley folds turn into the shade.
            float shade = even ? 1.0 - 0.6 * sin(angle) * along : 1.0 + 0.15 * sin(angle) * (1.0 - along) - 0.35 * sin(angle) * along;
            color *= shade;
        }
    } else {
        // The folding strip throws a soft shadow on the new picture.
        float gap = (x - width) * 4.0;
        color *= 1.0 - 0.4 * (1.0 - smoothstep(0.0, 1.0, gap)) * sin(t * 3.1415927);
    }
    fragColor = vec4(color, 1.0);
}
