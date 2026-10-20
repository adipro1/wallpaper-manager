// Equalizer - bars that dance to whatever is playing on your PC. Needs "React to audio".
// iChannel0 row 0 holds the spectrum (bass on the left), like on Shadertoy.
void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    float bars = 48.0;
    float index = floor(uv.x * bars);
    float level = texture(iChannel0, vec2((index + 0.5) / bars, 0.25)).r;
    float inBar = step(0.12, fract(uv.x * bars)) * step(uv.y, level * 0.85 + 0.01);
    vec3 color = mix(vec3(0.1, 0.6, 1.0), vec3(1.0, 0.25, 0.6), uv.y);
    vec3 background = vec3(0.02, 0.02, 0.05) + 0.05 * vec3(0.2, 0.1, 0.4) * (1.0 - uv.y);
    fragColor = vec4(mix(background, color, inBar), 1.0);
}
