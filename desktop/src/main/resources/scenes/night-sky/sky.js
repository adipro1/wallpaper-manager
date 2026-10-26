"use strict";
// The real sky. Directions are unit vectors in the horizon frame: x = east, y = north, z = up.
// The picture is a rectilinear (camera lens) view looking at the horizon, which sits low on
// the screen. Drawn in three passes: the sky (with the Milky Way), the stars, constellation lines,
// planets and the moon, then the clouds and the hills in front.

const RAD = Math.PI / 180;
const glCanvas = document.getElementById("gl");
const textCanvas = document.getElementById("text");
const gl = glCanvas.getContext("webgl2", { alpha: false, antialias: true, preserveDrawingBuffer: true });
const ctx = textCanvas.getContext("2d");

// Settings (LivelyProperties.json), changed through livelyPropertyListener.
const settings = { view: 0, lines: true, labels: true, brightness: 1, milkyway: true, meteors: true, drift: 0.3, moonSize: 4, clouds: true, hills: true };
// What the app sends (wpm.setSky). Until then: a guess from the time zone, no moon or planets.
const sky = { lat: 45, lon: -new Date().getTimezoneOffset() / 4, moon: null, planets: [], clouds: 0, fog: 0, lang: "en" };

window.livelyPropertyListener = function (name, value) {
  if (name in settings) settings[name] = value;
  overlayDirty = true;
};
window.wpm = window.wpm || {};
window.wpm.setSky = function (data) {
  Object.assign(sky, data);
  overlayDirty = true;
};
// For tests: what is drawn right now.
window.wpm.debug = () => ({ frames, error, lat: sky.lat, sunAlt: Math.asin(sunDir()[2]) / RAD, moon: !!sky.moon, planets: sky.planets.length });

// -- astronomy (the sun's motion is worked out here so dusk and dawn change smoothly) -----------

/** The time shown: now, unless a test moves the clock (sky.offset in milliseconds). */
function now() { return Date.now() + (sky.offset || 0); }

function days2000(ms) { return ms / 86400000 + 2440587.5 - 2451545.0; }

function siderealDeg(ms) { return ((280.46061837 + 360.98564736629 * days2000(ms)) % 360 + 360) % 360; }

/** The sun's right ascension and declination (degrees), to about a hundredth of a degree. */
function sunRaDec(ms) {
  const n = days2000(ms);
  const g = (357.528 + 0.9856003 * n) * RAD;
  const lambda = (280.46 + 0.9856474 * n + 1.915 * Math.sin(g) + 0.02 * Math.sin(2 * g)) * RAD;
  const eps = (23.439 - 0.0000004 * n) * RAD;
  return [Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)) / RAD, Math.asin(Math.sin(eps) * Math.sin(lambda)) / RAD];
}

function equatorial(ra, dec) {
  const a = ra * RAD, d = dec * RAD;
  return [Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d)];
}

/** Rows of the matrix that turns equatorial directions into horizon ones, for now and here. */
let eqRows = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
function updateEarth(ms) {
  const theta = (siderealDeg(ms) + sky.lon) * RAD, phi = sky.lat * RAD;
  const st = Math.sin(theta), ct = Math.cos(theta), sp = Math.sin(phi), cp = Math.cos(phi);
  eqRows = [[-st, ct, 0], [-sp * ct, -sp * st, cp], [cp * ct, cp * st, sp]];
}
function toHorizon(e) { return eqRows.map(r => r[0] * e[0] + r[1] * e[1] + r[2] * e[2]); }
function sunDir() { const [ra, dec] = sunRaDec(now()); return toHorizon(equatorial(ra, dec)); }

/** The moon seen from the ground (not the Earth's center): it sits up to a degree lower. */
function moonDir() {
  if (!sky.moon) return null;
  const g = toHorizon(equatorial(sky.moon.ra, sky.moon.dec)).map(v => v * sky.moon.dist);
  g[2] -= 1;
  const l = Math.hypot(g[0], g[1], g[2]);
  return g.map(v => v / l);
}

// -- the view ------------------------------------------------------------------------------------

const camera = { R: [1, 0, 0], U: [0, 0, 1], F: [0, 1, 0], k: 1, scale: [1, 1], az: null, alt: null, at: 0 };
/**
 * Points the view: towards the moon while it is up ("automatic"), else towards the equator, or
 * the chosen direction. The view eases to a new direction and tilts up to keep a high moon in sight.
 */
function updateCamera(seconds, w, h, moon) {
  // About 90° across a 16:9 screen (k: pixels per unit of tan(angle)); the horizon straight ahead at 88% of the height.
  const fov = 90 * Math.min(1.25, Math.max(0.6, (w / h) / (16 / 9))) * RAD;
  const k = (w / 2) / Math.tan(fov / 2);
  let facing = settings.view > 0 ? (settings.view - 1) * 90 : (sky.lat >= 0 ? 180 : 0);
  let alt = Math.atan((0.38 * h) / k);
  const moonAlt = moon ? Math.asin(moon[2]) : -1;
  if (settings.view === 0 && moon && moonAlt > 3 * RAD) {
    facing = Math.atan2(moon[0], moon[1]) / RAD;
    alt = Math.max(alt, Math.min(moonAlt - Math.atan((0.3 * h) / k), 40 * RAD));
  }
  const dt = Math.min(1, Math.max(0, seconds - camera.at));
  camera.at = seconds;
  if (camera.az === null) { camera.az = facing; camera.alt = alt; }
  const turn = ((facing - camera.az) % 360 + 540) % 360 - 180;
  camera.az += turn * Math.min(1, dt * 0.4);
  camera.alt += (alt - camera.alt) * Math.min(1, dt * 0.4);
  const sway = settings.drift * 9 * Math.sin(seconds / 140) + settings.drift * 3 * Math.sin(seconds / 61);
  camera.look = camera.az + sway;
  const az = camera.look * RAD;
  const ca = Math.cos(camera.alt), sa = Math.sin(camera.alt);
  camera.F = [ca * Math.sin(az), ca * Math.cos(az), sa];
  camera.R = [Math.cos(az), -Math.sin(az), 0];
  camera.U = cross(camera.R, camera.F);
  camera.k = k;
  camera.scale = [k / (w / 2), k / (h / 2)];
}
function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
/** Screen position (CSS pixels) of a direction, or null when it is behind the viewer. */
function project(d, w, h) {
  const cz = dot(d, camera.F);
  if (cz < 0.1) return null;
  const s = camera.k / cz;
  return [w / 2 + dot(d, camera.R) * s, h / 2 - dot(d, camera.U) * s];
}

// -- WebGL -----------------------------------------------------------------------------------------

const COMMON = `#version 300 es
precision highp float;
uniform vec3 uR, uU, uF, uSun, uMoon, uNgp, uGc;
uniform vec2 uScale, uRes;
uniform mat3 uEq;
uniform float uTime, uNight, uDay, uSunAlt, uMoonLit, uClouds, uFog, uMilky, uHills, uPx, uBright, uWash;

vec2 toClip(vec3 d) {
  return vec2(dot(d, uR), dot(d, uU)) / max(dot(d, uF), 0.05) * uScale;
}
vec3 direction(vec2 clip) {
  vec2 p = clip / uScale;
  return normalize(p.x * uR + p.y * uU + uF);
}
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float noise(vec3 x) {
  vec3 i = floor(x), f = fract(x);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(mix(hash(i), hash(i + vec3(1,0,0)), f.x), mix(hash(i + vec3(0,1,0)), hash(i + vec3(1,1,0)), f.x), f.y),
             mix(mix(hash(i + vec3(0,0,1)), hash(i + vec3(1,0,1)), f.x), mix(hash(i + vec3(0,1,1)), hash(i + vec3(1,1,1)), f.x), f.y), f.z);
}
float fbm(vec3 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
  return v;
}
`;

const QUAD_VS = `#version 300 es
in vec2 aPos; out vec2 vClip;
void main() { vClip = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }`;

const SKY_FS = COMMON + `
in vec2 vClip; out vec4 color;
void main() {
  vec3 d = direction(vClip);
  float h = max(d.z, 0.0);
  float grad = pow(1.0 - h, 3.0);
  vec3 night = mix(vec3(0.004, 0.007, 0.022), vec3(0.03, 0.04, 0.075), grad) + vec3(0.012, 0.02, 0.045) * uWash;
  vec3 day = mix(vec3(0.16, 0.36, 0.78), vec3(0.62, 0.76, 0.92), grad);
  vec3 c = mix(night, day, uDay);
  float cs = dot(d, uSun);
  float twilight = exp(-pow((uSunAlt + 4.0) / 7.0, 2.0));
  c += twilight * (pow(max(cs, 0.0), 4.0) * 0.8 + 0.15) * pow(1.0 - h, 6.0) * vec3(1.0, 0.42, 0.13) * 1.1;
  c += twilight * pow(1.0 - h, 2.0) * vec3(0.22, 0.10, 0.28) * 0.35;
  // The sun: a small bright disc (half a degree) with a glow around it.
  float sunUp = smoothstep(-2.0, 1.0, uSunAlt);
  c += sunUp * (smoothstep(0.99995, 0.99997, cs) * vec3(1.0, 0.97, 0.9) * 3.0 + pow(max(cs, 0.0), 900.0) * vec3(1.0, 0.9, 0.7) * 0.7 +
       pow(max(cs, 0.0), 12.0) * vec3(1.0, 0.85, 0.6) * 0.25);
  if (uMoon.z > -0.1) c += vec3(0.55, 0.62, 0.8) * uMoonLit * pow(max(dot(d, uMoon), 0.0), 60.0) * 0.18 * (1.0 - uDay * 0.8);
  // The Milky Way: a soft band along the galactic equator, brightest towards the center.
  if (uMilky > 0.0 && uNight > 0.0) {
    vec3 e = d * uEq;
    float b = asin(clamp(dot(e, uNgp), -1.0, 1.0));
    float l = atan(dot(e, cross(uNgp, uGc)), dot(e, uGc));
    float core = exp(-pow(l / 0.7, 2.0));
    float band = exp(-pow(b / (0.16 + 0.08 * core), 2.0)) * (0.3 + 0.7 * core);
    float detail = fbm(e * 7.0 + fbm(e * 3.0));
    float clumps = fbm(e * 22.0);
    float lanes = smoothstep(0.42, 0.68, fbm(e * 11.0 + 3.0)) * exp(-pow(b / 0.06, 2.0));
    float glow = band * (0.25 + 1.2 * detail * detail) * (0.7 + 0.6 * clumps) * (1.0 - 0.85 * lanes);
    vec3 tint = mix(vec3(0.68, 0.74, 0.98), vec3(1.0, 0.86, 0.7), core * 0.6);
    // Countless faint stars, more of them in the band.
    vec3 cell = floor(e * 700.0);
    float dust = pow(hash(cell), 60.0) * (0.2 + 2.0 * band);
    // Moonlight washes the faint light out.
    float fade = uNight * smoothstep(0.0, 0.25, h) * (1.0 - 0.85 * uWash);
    c += (tint * glow * 0.2 * uMilky + vec3(0.8, 0.85, 1.0) * dust * 0.35) * fade;
  }
  c += (hash(vec3(gl_FragCoord.xy, uTime)) - 0.5) / 255.0;
  color = vec4(c, 1.0);
}`;

const STAR_VS = COMMON + `
in vec3 aPos; in vec2 aInfo; out vec3 vColor; out float vAlpha;
vec3 bvColor(float bv) {
  vec3 c = mix(vec3(0.65, 0.75, 1.0), vec3(1.0, 0.97, 0.92), smoothstep(-0.3, 0.4, bv));
  return mix(c, vec3(1.0, 0.72, 0.45), smoothstep(0.6, 1.8, bv));
}
void main() {
  vec3 d = uEq * aPos;
  float mag = aInfo.x, seed = fract(aPos.x * 91.7 + aPos.y * 37.3);
  gl_Position = vec4(toClip(d), 0.0, 1.0);
  if (dot(d, uF) < 0.1 || d.z < -0.03) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
  float flux = pow(10.0, -0.4 * (mag - 1.0));
  float low = smoothstep(-0.02, 0.35, d.z);
  float twinkle = 1.0 + (0.1 + 0.3 * (1.0 - low)) * sin(uTime * (2.0 + seed * 5.0) + seed * 40.0) * sin(uTime * (1.3 + seed * 3.0) + seed * 17.0);
  vAlpha = clamp(pow(flux, 0.33) * 1.4 * uBright, 0.0, 2.2) * twinkle * mix(0.3, 1.0, low) * uNight * (1.0 - 0.8 * uWash * smoothstep(2.0, 5.0, mag));
  gl_PointSize = (2.0 + 3.2 * pow(min(flux, 8.0), 0.35)) * max(uPx, 0.75);
  vColor = bvColor(aInfo.y);
}`;

const STAR_FS = `#version 300 es
precision highp float;
in vec3 vColor; in float vAlpha; out vec4 color;
void main() {
  float r = length(gl_PointCoord - 0.5) * 2.0;
  float a = exp(-r * r * 9.0) + 0.8 * exp(-r * r * 60.0);
  color = vec4(vColor * vAlpha * a, 1.0);
}`;

const LINE_VS = COMMON + `
in vec3 aPos;
void main() {
  vec3 d = uEq * aPos;
  gl_Position = vec4(toClip(d), 0.0, 1.0);
}`;

const LINE_FS = `#version 300 es
precision highp float;
uniform float uAlpha; out vec4 color;
void main() { color = vec4(vec3(0.55, 0.65, 0.95) * uAlpha, 1.0); }`;

// Planets and the moon: aPos is already a horizon direction; aInfo = (size px, kind 0/1, brightness).
const BODY_VS = COMMON + `
in vec3 aPos; in vec3 aInfo; in vec3 aColor;
out vec3 vColor; out float vAlpha; out float vKind; out vec3 vRight; out vec3 vUp; out vec3 vDir;
void main() {
  gl_Position = vec4(toClip(aPos), 0.0, 1.0);
  if (dot(aPos, uF) < 0.1 || aPos.z < -0.05) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
  gl_PointSize = aInfo.x * uPx;
  vKind = aInfo.y;
  vAlpha = aInfo.z * mix(0.4, 1.0, smoothstep(-0.02, 0.2, aPos.z));
  vColor = aColor;
  vDir = aPos;
  vRight = normalize(uR - aPos * dot(uR, aPos));
  vUp = cross(vRight, aPos);
}`;

const BODY_FS = COMMON + `
in vec3 vColor; in float vAlpha; in float vKind; in vec3 vRight; in vec3 vUp; in vec3 vDir; out vec4 color;
void main() {
  vec2 p = gl_PointCoord * 2.0 - 1.0;
  p.y = -p.y;
  float r2 = dot(p, p);
  if (vKind < 0.5) {
    float r = sqrt(r2);
    float a = exp(-r2 * 7.0) + 0.7 * exp(-r2 * 45.0);
    color = vec4(vColor * vAlpha * a, 1.0);
    return;
  }
  // The moon: a lit sphere with darker "seas"; the sun's direction decides the phase and its tilt.
  float edge = 1.0 - smoothstep(0.93, 1.0, sqrt(r2));
  if (r2 > 1.0) discard;
  vec3 n = p.x * vRight + p.y * vUp - sqrt(1.0 - r2) * vDir;
  float light = smoothstep(-0.04, 0.08, dot(n, uSun));
  float seas = smoothstep(0.42, 0.62, fbm(vec3(p * 2.2, 4.0))) * 0.35 + (fbm(vec3(p * 9.0, 1.0)) - 0.5) * 0.12;
  vec3 surface = vec3(0.93, 0.92, 0.88) * (1.0 - seas);
  vec3 c = surface * (light * 1.05 + 0.025 * (1.0 - uDay));
  color = vec4(c * edge * vAlpha, 1.0);
}`;

// Clouds, haze and hills in front of everything (premultiplied alpha).
const FRONT_FS = COMMON + `
in vec2 vClip; out vec4 color;
vec3 twilightGlow(vec3 d) {
  return exp(-pow((uSunAlt + 4.0) / 7.0, 2.0)) * pow(max(dot(normalize(d.xy), normalize(uSun.xy + 1e-5)), 0.0), 3.0) * vec3(1.0, 0.45, 0.2);
}
float hill(float az, float scale, float base, float height, float seed) {
  vec3 p = vec3(cos(az), sin(az), seed) * scale;
  return (base + height * fbm(p)) * 0.01745;
}
void main() {
  vec3 d = direction(vClip);
  float alt = asin(clamp(d.z, -1.0, 1.0));
  float az = atan(d.x, d.y);
  vec4 c = vec4(0.0);
  // Haze near the horizon (more in fog).
  vec3 hazeColor = mix(vec3(0.03, 0.04, 0.07), vec3(0.7, 0.78, 0.88), uDay);
  float haze = (0.25 + uFog * 0.7) * exp(-max(alt, 0.0) * (12.0 - uFog * 8.0));
  c = vec4(hazeColor * haze, haze);
  if (uClouds > 0.01 && d.z > -0.02) {
    vec2 uv = d.xy / (d.z + 0.08) * 1.3 + vec2(uTime * 0.006, uTime * 0.002);
    vec3 q = vec3(uv, uTime * 0.01);
    float n = fbm(q + 0.6 * vec3(fbm(q * 0.7 + 5.0), fbm(q * 0.7 + 9.0), 0.0));
    float threshold = mix(0.74, 0.3, uClouds);
    float a = max(smoothstep(threshold - 0.02, threshold + 0.06, n), smoothstep(0.85, 1.0, uClouds) * 0.92);
    a *= smoothstep(-0.02, 0.04, d.z);
    // Thicker parts are darker underneath; thin edges let the light through.
    float shade = 1.0 - smoothstep(threshold, threshold + 0.25, fbm(q * 1.8 + 2.0) * 0.5 + n * 0.5) * 0.45;
    float twilight = exp(-pow((uSunAlt + 3.0) / 6.0, 2.0));
    vec3 nightCloud = vec3(0.045, 0.05, 0.065) * (0.5 + uMoonLit * step(-0.05, uMoon.z)) + vec3(0.05, 0.03, 0.02) * (1.0 - d.z);
    vec3 dayCloud = mix(vec3(0.58, 0.62, 0.7), vec3(0.98, 0.98, 1.0), shade) + vec3(1.0, 0.9, 0.75) * pow(max(dot(d, uSun), 0.0), 8.0) * 0.25;
    vec3 cc = mix(nightCloud * (0.7 + 0.3 * shade), dayCloud, uDay) + twilight * vec3(0.85, 0.38, 0.2) * shade * 0.5;
    c = vec4(cc * a, a) + c * (1.0 - a);
  }
  // The ground: two rows of hills (the far one paler), or a flat horizon.
  float far = uHills > 0.5 ? hill(az, 2.5, 0.6, 4.5, 1.0) : 0.0;
  float near = uHills > 0.5 ? hill(az, 5.0, -0.8, 3.6, 7.0) : -1.0;
  if (uHills > 0.5) {
    // A ragged line of trees along the near hills.
    float t = noise(vec3(cos(az), sin(az), 0.0) * 160.0) * 0.6 + noise(vec3(cos(az), sin(az), 3.0) * 420.0) * 0.4;
    near += smoothstep(0.35, 0.8, noise(vec3(cos(az), sin(az), 5.0) * 9.0)) * pow(t, 2.0) * 1.3 * 0.01745;
  }
  vec3 ground = mix(vec3(0.006, 0.008, 0.014), vec3(0.07, 0.1, 0.09), uDay) * (1.0 + 2.0 * clamp(alt + 0.06, 0.0, 0.06));
  vec3 farColor = mix(mix(vec3(0.02, 0.026, 0.045), vec3(0.32, 0.4, 0.48), uDay), ground, 0.35);
  farColor += twilightGlow(d) * 0.08;
  float aa = 0.0015;
  float farMask = 1.0 - smoothstep(far - aa, far + aa, alt);
  float nearMask = 1.0 - smoothstep(near - aa, near + aa, alt);
  c = mix(c, vec4(farColor, 1.0), farMask);
  c = mix(c, vec4(ground, 1.0), nearMask);
  color = c;
}`;

let error = null, frames = 0;
function compile(vs, fs) {
  const p = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    gl.attachShader(p, s);
  }
  gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  return p;
}

function buffer(data) {
  const b = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, b);
  gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW);
  return b;
}

/** Binds float attributes laid out one after another in [buf]: [[name, size], ...]. */
function attributes(program, buf, layout) {
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  const stride = layout.reduce((s, [, n]) => s + n, 0) * 4;
  let offset = 0;
  for (const [name, n] of layout) {
    const loc = gl.getAttribLocation(program, name);
    if (loc >= 0) {
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, n, gl.FLOAT, false, stride, offset);
    }
    offset += n * 4;
  }
}

let programs, buffers, starCount, lineCount, bodyCount = 0;
function setup() {
  programs = {
    sky: compile(QUAD_VS, SKY_FS), star: compile(STAR_VS, STAR_FS), line: compile(LINE_VS, LINE_FS),
    body: compile(BODY_VS, BODY_FS), front: compile(QUAD_VS, FRONT_FS),
  };
  const s = window.SKY.stars;
  starCount = s.length / 4;
  const stars = new Float32Array(starCount * 5);
  for (let i = 0; i < starCount; i++) {
    stars.set(equatorial(s[i * 4], s[i * 4 + 1]), i * 5);
    stars[i * 5 + 3] = s[i * 4 + 2];
    stars[i * 5 + 4] = s[i * 4 + 3];
  }
  const segments = [];
  for (const line of window.SKY.lines) {
    for (let i = 0; i + 3 < line.length; i += 2) segments.push(...equatorial(line[i], line[i + 1]), ...equatorial(line[i + 2], line[i + 3]));
  }
  lineCount = segments.length / 3;
  buffers = { quad: buffer(new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1])), stars: buffer(stars), lines: buffer(new Float32Array(segments)), bodies: gl.createBuffer() };
}

const PLANET_COLORS = { Mercury: [0.9, 0.85, 0.8], Venus: [1, 0.98, 0.9], Mars: [1, 0.6, 0.4], Jupiter: [1, 0.93, 0.8], Saturn: [1, 0.9, 0.65] };

function uniforms(program, values) {
  gl.useProgram(program);
  for (const [name, v] of Object.entries(values)) {
    const loc = gl.getUniformLocation(program, name);
    if (loc === null) continue;
    if (v.length === 9) gl.uniformMatrix3fv(loc, false, v);
    else if (v.length === 3) gl.uniform3fv(loc, v);
    else if (v.length === 2) gl.uniform2fv(loc, v);
    else gl.uniform1f(loc, v);
  }
}

const NGP = equatorial(192.85948, 27.12825);
const GC = equatorial(266.405, -28.936);

function draw(seconds) {
  const dpr = window.devicePixelRatio || 1;
  const w = innerWidth, h = innerHeight;
  if (glCanvas.width !== Math.round(w * dpr) || glCanvas.height !== Math.round(h * dpr)) {
    glCanvas.width = Math.round(w * dpr); glCanvas.height = Math.round(h * dpr);
    textCanvas.width = glCanvas.width; textCanvas.height = glCanvas.height;
    overlayDirty = true;
  }
  updateEarth(now());
  const sun = sunDir();
  const sunAlt = Math.asin(sun[2]) / RAD;
  const moon = moonDir();
  updateCamera(seconds, w, h, moon);
  const night = Math.min(1, Math.max(0, (-sunAlt - 4) / 12)) * (settings.clouds ? 1 - 0.85 * Math.max(0, sky.clouds - 0.75) / 0.25 : 1);
  const r = eqRows;
  const common = {
    uR: camera.R, uU: camera.U, uF: camera.F, uSun: sun, uMoon: moon || [0, 0, -1], uNgp: NGP, uGc: GC,
    uScale: camera.scale, uRes: [glCanvas.width, glCanvas.height],
    uEq: [r[0][0], r[1][0], r[2][0], r[0][1], r[1][1], r[2][1], r[0][2], r[1][2], r[2][2]],
    uTime: seconds, uNight: night, uDay: Math.min(1, Math.max(0, (sunAlt + 8) / 14)), uSunAlt: sunAlt,
    uMoonLit: sky.moon ? sky.moon.lit : 0, uClouds: settings.clouds ? sky.clouds : 0, uFog: settings.clouds ? sky.fog : 0,
    uWash: moon ? (sky.moon.lit * Math.min(1, Math.max(0, (moon[2] + 0.05) / 0.3))) : 0,
    uMilky: settings.milkyway ? 1 : 0, uHills: settings.hills ? 1 : 0, uPx: glCanvas.height / 1080, uBright: settings.brightness,
  };
  gl.viewport(0, 0, glCanvas.width, glCanvas.height);
  gl.disable(gl.BLEND);

  uniforms(programs.sky, common);
  attributes(programs.sky, buffers.quad, [["aPos", 2]]);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

  gl.enable(gl.BLEND);
  gl.blendFunc(gl.ONE, gl.ONE);
  if (night > 0.01) {
    if (settings.lines) {
      uniforms(programs.line, Object.assign({ uAlpha: 0.11 * night }, common));
      attributes(programs.line, buffers.lines, [["aPos", 3]]);
      gl.drawArrays(gl.LINES, 0, lineCount);
    }
    uniforms(programs.star, common);
    attributes(programs.star, buffers.stars, [["aPos", 3], ["aInfo", 2]]);
    gl.drawArrays(gl.POINTS, 0, starCount);
  }

  // Planets (as bright as they really are) and the moon (bigger than life, so it reads on a desktop).
  const bodies = [];
  for (const p of sky.planets) {
    const d = toHorizon(equatorial(p.ra, p.dec));
    const flux = Math.pow(10, -0.4 * (p.mag - 1));
    const visible = Math.min(1, night + (p.mag < -3.5 ? 0.35 : 0));
    bodies.push(...d, (2.6 + 2.4 * Math.pow(Math.min(flux, 30), 0.3)), 0, Math.min(2.2, Math.sqrt(flux)) * visible * settings.brightness, ...(PLANET_COLORS[p.name] || [1, 1, 1]));
  }
  if (moon) {
    const size = camera.k * Math.tan(0.26 * RAD) * 2 * settings.moonSize * dpr;
    bodies.push(...moon, size / (glCanvas.height / 1080), 1, 1, 1, 1, 1);
  }
  if (bodies.length) {
    gl.bindBuffer(gl.ARRAY_BUFFER, buffers.bodies);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(bodies), gl.DYNAMIC_DRAW);
    uniforms(programs.body, common);
    attributes(programs.body, buffers.bodies, [["aPos", 3], ["aInfo", 3], ["aColor", 3]]);
    gl.drawArrays(gl.POINTS, 0, bodies.length / 9);
  }

  gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
  uniforms(programs.front, common);
  attributes(programs.front, buffers.quad, [["aPos", 2]]);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

  drawOverlay(seconds, w, h, dpr, night, sun, moon);
  frames++;
}

// -- names and shooting stars (2D canvas on top) ---------------------------------------------------

let overlayDirty = true, overlayAt = 0, overlayView = [0, 0], meteor = null, nextMeteor = 8 + Math.random() * 20;

function drawOverlay(seconds, w, h, dpr, night, sun, moon) {
  if (settings.meteors && night > 0.5 && !meteor && seconds > nextMeteor) {
    const az = Math.random() * Math.PI * 2, alt = (25 + Math.random() * 45) * RAD;
    meteor = { start: seconds, az, alt, dirAz: Math.random() * Math.PI * 2, length: (8 + Math.random() * 14) * RAD, duration: 0.5 + Math.random() * 0.6 };
    nextMeteor = seconds + 15 + Math.random() * 45;
  }
  // Names follow the view as it turns (a redraw once the view has moved a little).
  const moved = Math.abs(camera.look - overlayView[0]) + Math.abs(camera.alt / RAD - overlayView[1]) > 0.03;
  if (!meteor && !overlayDirty && !moved && seconds - overlayAt < 2) return;
  overlayAt = seconds;
  overlayView = [camera.look, camera.alt / RAD];
  overlayDirty = false;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const px = h / 1080;
  const fade = Math.min(1, night * 1.4);
  const clouds = settings.clouds ? sky.clouds : 0;
  if (settings.labels && fade > 0.05 && clouds < 0.9) {
    ctx.textBaseline = "middle";
    const alpha = fade * (1 - clouds * 0.6);
    const label = (text, d, color, size, dx) => {
      if (d[2] < 0.03) return;
      const p = project(d, w, h);
      if (!p || p[0] < -50 || p[0] > w + 50 || p[1] < -20 || p[1] > h) return;
      ctx.font = `${Math.round(size * px)}px "Segoe UI Variable Text", "Segoe UI", sans-serif`;
      ctx.fillStyle = color;
      ctx.fillText(text, p[0] + dx * px, p[1]);
    };
    if (settings.lines) {
      ctx.textAlign = "center";
      const german = sky.lang === "de";
      for (const [en, de, ra, dec, rank] of window.SKY.labels) {
        if (rank <= 2) label((german ? de : en).toUpperCase(), toHorizon(equatorial(ra, dec)), `rgba(150,170,235,${0.32 * alpha})`, 11, 0);
      }
    }
    ctx.textAlign = "left";
    for (const [name, ra, dec] of window.SKY.named) label(name, toHorizon(equatorial(ra, dec)), `rgba(220,228,255,${0.42 * alpha})`, 12, 9);
    for (const p of sky.planets) label(p.label || p.name, toHorizon(equatorial(p.ra, p.dec)), `rgba(255,232,190,${0.6 * alpha})`, 13, 10);
  }
  if (meteor) {
    const t = (seconds - meteor.start) / meteor.duration;
    if (t >= 1) { meteor = null; overlayDirty = true; return; }
    const at = (s) => {
      const a = meteor.alt - Math.cos(meteor.dirAz) * meteor.length * s, z = meteor.az + Math.sin(meteor.dirAz) * meteor.length * s;
      return [Math.cos(a) * Math.sin(z), Math.cos(a) * Math.cos(z), Math.sin(a)];
    };
    const head = project(at(t), w, h), tail = project(at(Math.max(0, t - 0.35)), w, h);
    if (head && tail) {
      const g = ctx.createLinearGradient(tail[0], tail[1], head[0], head[1]);
      const strength = Math.sin(t * Math.PI) * night * (1 - clouds);
      g.addColorStop(0, "rgba(255,255,255,0)");
      g.addColorStop(1, `rgba(235,242,255,${0.9 * strength})`);
      ctx.strokeStyle = g;
      ctx.lineWidth = 1.6 * px;
      ctx.lineCap = "round";
      ctx.beginPath();
      ctx.moveTo(tail[0], tail[1]);
      ctx.lineTo(head[0], head[1]);
      ctx.stroke();
    }
  }
}

// -- the loop (30 frames a second is plenty for a sky) ---------------------------------------------

let last = 0;
const start = performance.now();
function frame(t) {
  requestAnimationFrame(frame);
  if (t - last < 32) return;
  last = t;
  try {
    draw((t - start) / 1000);
  } catch (e) {
    error = String(e && e.message || e);
    console.error(e);
  }
}
try {
  if (!gl) throw new Error("WebGL 2 is not available");
  setup();
  requestAnimationFrame(frame);
} catch (e) {
  error = String(e && e.message || e);
  document.title = "error: " + error;
  console.error(e);
}
