// Music colors: while music plays, the picture takes on the colors of the song's album art
// and glows from the edges with the bass. Without music it is just the picture.
// @image photo Picture
// @slider tint 0 1 0.4 Album color
// @slider pulse 0 1 0.6 Bass glow

vec2 cover(vec2 uv) {
    vec2 screen = iResolution.xy;
    vec2 image = max(iChannelResolution[1].xy, vec2(1.0));
    vec2 size = image * max(screen.x / image.x, screen.y / image.y) / screen;
    return (uv - 0.5) / size + 0.5;
}

vec3 softLight(vec3 base, vec3 blend) {
    return mix(2.0 * base * blend + base * base * (1.0 - 2.0 * blend),
               sqrt(base) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend),
               step(0.5, blend));
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec3 color = texture(photo, cover(uv)).rgb;
    float playing = iMusic.a;
    vec3 album = iMusic.rgb;

    // Row 0 of iChannel0 is the spectrum (bass first).
    float bass = 0.0;
    for (int i = 0; i < 6; i++) bass += texture(iChannel0, vec2((float(i) + 0.5) / 512.0, 0.25)).r;
    bass = clamp(bass / 6.0, 0.0, 1.0);

    color = mix(color, softLight(color, album), tint * playing);
    float edge = smoothstep(0.35, 0.95, length((uv - 0.5) * vec2(1.25, 1.0)));
    color += album * edge * bass * pulse * playing * 0.8;
    fragColor = vec4(color, 1.0);
}
