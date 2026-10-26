"use strict";
// The Earth now. Points on the Earth are unit vectors: x = (0°N, 90°E), y = the north pole,
// z = (0°N, 0°E). The globe is drawn as seen from far away, centered on a chosen place; the map
// is a plain longitude/latitude grid centered on the same longitude.

const RAD = Math.PI / 180;
const glCanvas = document.getElementById("gl");
const textCanvas = document.getElementById("text");
const gl = glCanvas.getContext("webgl2", { alpha: false, antialias: true, preserveDrawingBuffer: true });
const ctx = textCanvas.getContext("2d");

// Settings (LivelyProperties.json), changed through livelyPropertyListener.
const settings = { mode: 0, center: 0, size: 0.8, lights: true, glint: true, iss: true, place: true };
// What the app sends. Until the place comes: a guess from the time zone.
const world = { lat: 30, lon: -new Date().getTimezoneOffset() / 4, name: "", status: "", iss: [], offset: 0 };

window.livelyPropertyListener = function (name, value) { if (name in settings) settings[name] = value; };
window.wpm = window.wpm || {};
window.wpm.setSky = function (data) {
  for (const key of ["lat", "lon", "name", "status", "offset"]) if (key in data) world[key] = data[key];
};
window.wpm.setIss = function (points) { world.iss = points; };
// For tests: what is drawn right now.
window.wpm.debug = () => ({ frames, error, textures: Object.keys(textures), iss: world.iss.length, lat: world.lat });

function now() { return Date.now() + (world.offset || 0); }

// -- pictures (each textures/<name>.js calls wpm.texture(name, dataUrl)) ---------------------------

const textures = {};
let textureVersion = null;
window.wpm.setTextures = function (version) {
  if (!version || version === textureVersion) return;
  textureVersion = version;
  for (const name of ["day", "lights", "clouds"]) {
    const script = document.createElement("script");
    script.src = "textures/" + name + ".js?v=" + encodeURIComponent(version);
    script.onload = script.onerror = () => script.remove();
    document.head.appendChild(script);
  }
};
window.wpm.texture = function (name, url) {
  const image = new Image();
  image.onload = () => {
    const t = textures[name] || gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, image);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const ext = gl.getExtension("EXT_texture_filter_anisotropic");
    if (ext) gl.texParameterf(gl.TEXTURE_2D, ext.TEXTURE_MAX_ANISOTROPY_EXT, 8);
    textures[name] = t;
  };
  image.src = url;
};

// -- the sun ---------------------------------------------------------------------------------------

function days2000(ms) { return ms / 86400000 + 2440587.5 - 2451545.0; }
function siderealDeg(ms) { return ((280.46061837 + 360.98564736629 * days2000(ms)) % 360 + 360) % 360; }
function sunRaDec(ms) {
  const n = days2000(ms);
  const g = (357.528 + 0.9856003 * n) * RAD;
  const lambda = (280.46 + 0.9856474 * n + 1.915 * Math.sin(g) + 0.02 * Math.sin(2 * g)) * RAD;
  const eps = (23.439 - 0.0000004 * n) * RAD;
  return [Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda)) / RAD, Math.asin(Math.sin(eps) * Math.sin(lambda)) / RAD];
}
function point(lat, lon) {
  const a = lat * RAD, o = lon * RAD;
  return [Math.cos(a) * Math.sin(o), Math.sin(a), Math.cos(a) * Math.cos(o)];
}
/** Where the sun stands straight overhead right now. */
function subsolar(ms) {
  const [ra, dec] = sunRaDec(ms);
  return [dec, ((ra - siderealDeg(ms)) % 360 + 540) % 360 - 180];
}

// -- the view ---------------------------------------------------------------------------------------

const view = { lat: 20, lon: 0, m: [1, 0, 0, 0, 1, 0, 0, 0, 1], radius: 300, cx: 0, cy: 0, scale: 1, started: performance.now() };
function updateView(w, h, sunLatLon, seconds) {
  let lat, lon;
  if (settings.center === 1) { lat = sunLatLon[0] * 0.5; lon = sunLatLon[1] - 25; }               // the sunlit side
  else if (settings.center === 2) { lat = 15; lon = world.lon + seconds * 0.3; }                    // turning slowly
  else { lat = Math.max(-40, Math.min(40, world.lat * 0.6)); lon = world.lon; }                      // my place
  view.lat = lat;
  view.lon = lon;
  const a = -lat * RAD, b = lon * RAD;
  const ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b);
  // Ry(b) * Rx(a), column-major for GLSL: view direction (x right, y up, z towards the viewer) to the Earth.
  const r = [[cb, sb * sa, sb * ca], [0, ca, -sa], [-sb, cb * sa, cb * ca]];
  view.rows = r;
  view.m = [r[0][0], r[1][0], r[2][0], r[0][1], r[1][1], r[2][1], r[0][2], r[1][2], r[2][2]];
  view.radius = Math.min(w, h) * 0.5 * settings.size;
  view.cx = w / 2;
  view.cy = h / 2;
  view.scale = Math.max(w / 360, h / 180); // map: pixels per degree
}
/** Screen position (CSS pixels) of a point on the Earth, or null when it is on the far side. */
function project(p, w, h) {
  if (settings.mode === 1) {
    const lat = Math.asin(p[1]) / RAD, lon = Math.atan2(p[0], p[2]) / RAD;
    const dlon = ((lon - view.lon) % 360 + 540) % 360 - 180;
    return [w / 2 + dlon * view.scale, h / 2 - lat * view.scale, 1];
  }
  const r = view.rows;
  const v = [r[0][0] * p[0] + r[1][0] * p[1] + r[2][0] * p[2], r[0][1] * p[0] + r[1][1] * p[1] + r[2][1] * p[2], r[0][2] * p[0] + r[1][2] * p[1] + r[2][2] * p[2]];
  if (v[2] < 0) return null;
  return [view.cx + v[0] * view.radius, view.cy - v[1] * view.radius, v[2]];
}

// -- WebGL -----------------------------------------------------------------------------------------

const VS = `#version 300 es
in vec2 aPos; out vec2 vPos;
void main() { vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }`;

const FS = `#version 300 es
precision highp float;
in vec2 vPos; out vec4 color;
uniform sampler2D uDay, uLights, uClouds;
uniform float uHave, uMode, uLightsOn, uGlint, uRadius, uScale, uLon;
uniform vec2 uRes;
uniform vec3 uSun;
uniform mat3 uView;

float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }

vec4 tex(sampler2D t, float uA, float v) {
  // Pick the smoother of two longitude coordinates, so mipmaps don't draw a seam at ±180°.
  float uB = fract(uA + 0.5);
  vec2 dA = vec2(dFdx(uA), dFdy(uA)), dB = vec2(dFdx(uB), dFdy(uB));
  vec2 du = dot(dA, dA) < dot(dB, dB) ? dA : dB;
  return textureGrad(t, vec2(uA, v), vec2(du.x, dFdx(v)), vec2(du.y, dFdy(v)));
}

vec3 surface(vec3 n, vec3 eye) {
  float lat = asin(clamp(n.y, -1.0, 1.0));
  float lon = atan(n.x, n.z);
  float u = lon / 6.2831853 + 0.5, v = 0.5 - lat / 3.1415927;
  float l = dot(n, uSun);
  float day = smoothstep(-0.1, 0.12, l);
  vec3 ground = uHave > 0.5 ? tex(uDay, u, v).rgb : vec3(0.05, 0.15, 0.35);
  vec2 map = uHave > 0.5 ? tex(uClouds, u, v).rg : vec2(0.0);
  float cloud = map.r, water = map.g;
  // Day: the satellite picture, lit by the sun, warmer near the line between day and night.
  vec3 c = ground * (0.12 + 1.0 * pow(max(l, 0.0), 0.55)) * day;
  c *= mix(vec3(1.0, 0.72, 0.55), vec3(1.0), smoothstep(0.0, 0.1, l));
  if (uGlint > 0.5) {
    float spec = pow(max(dot(reflect(-uSun, n), eye), 0.0), 45.0);
    c += vec3(1.0, 0.92, 0.75) * spec * water * (1.0 - cloud) * 0.55 * day;
  }
  // Night: city lights (dimmer under clouds) and a hint of the land.
  if (uHave > 0.5) {
    vec3 lights = tex(uLights, u, v).rgb;
    vec3 glow = max(lights - vec3(0.06, 0.07, 0.16), 0.0);
    float night = 1.0 - smoothstep(-0.12, 0.05, l);
    vec3 n2 = lights * vec3(0.08, 0.09, 0.14) + (uLightsOn > 0.5 ? glow * vec3(1.6, 1.3, 0.8) * 1.6 * (1.0 - 0.7 * cloud) : vec3(0.0));
    c += n2 * night + vec3(0.05, 0.06, 0.08) * cloud * night;
  }
  return c;
}

void main() {
  vec2 frag = (vPos * 0.5 + 0.5) * uRes;
  vec2 center = uRes * 0.5;
  // Space: a few faint stars.
  vec2 cell = floor(frag / 2.0);
  vec3 bg = vec3(0.004, 0.005, 0.01) + vec3(0.8, 0.85, 1.0) * pow(hash(cell), 400.0) * 0.8;
  if (uMode > 0.5) {
    // The map: longitude across, latitude up.
    float lon = uLon + (frag.x - center.x) / uScale;
    float lat = (frag.y - center.y) / uScale;
    if (abs(lat) > 90.0) { color = vec4(bg, 1.0); return; }
    vec3 n = vec3(cos(radians(lat)) * sin(radians(lon)), sin(radians(lat)), cos(radians(lat)) * cos(radians(lon)));
    color = vec4(surface(n, n), 1.0);
    return;
  }
  vec2 p = (frag - center) / uRadius;
  float r2 = dot(p, p);
  vec3 eye = uView * vec3(0.0, 0.0, 1.0);
  if (r2 > 1.0) {
    // The atmosphere's glow just outside the edge, on the sunlit side.
    float r = sqrt(r2);
    vec3 n = uView * normalize(vec3(p / r, 0.0));
    float lit = smoothstep(-0.3, 0.4, dot(n, uSun));
    float halo = exp(-(r - 1.0) * 28.0) * (0.15 + 0.85 * lit);
    color = vec4(bg + vec3(0.25, 0.5, 1.0) * halo * 0.6, 1.0);
    return;
  }
  vec3 v = vec3(p, sqrt(1.0 - r2));
  vec3 n = uView * v;
  vec3 c = surface(n, eye);
  float rim = pow(1.0 - v.z, 2.2);
  float lit = smoothstep(-0.25, 0.35, dot(n, uSun));
  c = mix(c, vec3(0.35, 0.6, 1.0) * (0.1 + 0.9 * lit), rim * 0.7 * (0.25 + 0.75 * lit));
  float edge = smoothstep(1.0, 0.996, sqrt(r2));
  color = vec4(mix(bg, c, edge), 1.0);
}`;

let program, quad, error = null, frames = 0;
function setup() {
  program = gl.createProgram();
  for (const [type, src] of [[gl.VERTEX_SHADER, VS], [gl.FRAGMENT_SHADER, FS]]) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    gl.attachShader(program, s);
  }
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  quad = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, quad);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
}

function draw(seconds) {
  const dpr = window.devicePixelRatio || 1;
  const w = innerWidth, h = innerHeight;
  if (glCanvas.width !== Math.round(w * dpr) || glCanvas.height !== Math.round(h * dpr)) {
    glCanvas.width = Math.round(w * dpr); glCanvas.height = Math.round(h * dpr);
    textCanvas.width = glCanvas.width; textCanvas.height = glCanvas.height;
  }
  const ms = now();
  const sunLatLon = subsolar(ms);
  updateView(w, h, sunLatLon, seconds);
  gl.viewport(0, 0, glCanvas.width, glCanvas.height);
  gl.useProgram(program);
  const loc = (n) => gl.getUniformLocation(program, n);
  const have = textures.day && textures.lights && textures.clouds ? 1 : 0;
  [["uDay", textures.day], ["uLights", textures.lights], ["uClouds", textures.clouds]].forEach(([name, t], i) => {
    gl.activeTexture(gl.TEXTURE0 + i);
    gl.bindTexture(gl.TEXTURE_2D, t || null);
    gl.uniform1i(loc(name), i);
  });
  gl.uniform1f(loc("uHave"), have);
  gl.uniform1f(loc("uMode"), settings.mode === 1 ? 1 : 0);
  gl.uniform1f(loc("uLightsOn"), settings.lights ? 1 : 0);
  gl.uniform1f(loc("uGlint"), settings.glint ? 1 : 0);
  gl.uniform1f(loc("uRadius"), view.radius * dpr);
  gl.uniform1f(loc("uScale"), view.scale * dpr);
  gl.uniform1f(loc("uLon"), view.lon);
  gl.uniform2f(loc("uRes"), glCanvas.width, glCanvas.height);
  gl.uniform3fv(loc("uSun"), point(sunLatLon[0], sunLatLon[1]));
  gl.uniformMatrix3fv(loc("uView"), false, view.m);
  const a = gl.getAttribLocation(program, "aPos");
  gl.bindBuffer(gl.ARRAY_BUFFER, quad);
  gl.enableVertexAttribArray(a);
  gl.vertexAttribPointer(a, 2, gl.FLOAT, false, 0, 0);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  drawOverlay(ms, w, h, dpr, have);
  frames++;
}

// -- the space station, the user's place and messages (2D canvas on top) ----------------------------

function slerp(a, b, t) {
  const d = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
  const angle = Math.acos(d);
  if (angle < 1e-6) return a;
  const s = Math.sin(angle), wa = Math.sin((1 - t) * angle) / s, wb = Math.sin(t * angle) / s;
  return [a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb, a[2] * wa + b[2] * wb];
}
/** Where the station is at [ms]: between known positions, or a little way on along its orbit after the last one. */
function issAt(ms) {
  const pts = world.iss;
  if (pts.length < 2) return pts.length ? point(pts[0][1], pts[0][2]) : null;
  let i = pts.findIndex(p => p[0] > ms);
  if (i === 0) i = 1;
  if (i < 0) i = pts.length - 1;
  const [t0, la0, lo0] = pts[i - 1], [t1, la1, lo1] = pts[i];
  // Over a few minutes its path is close to a great circle (the Earth turns a little meanwhile).
  return slerp(point(la0, lo0), point(la1, lo1), Math.min(3, (ms - t0) / (t1 - t0)));
}

function drawOverlay(ms, w, h, dpr, have) {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const px = h / 1080;
  ctx.font = `${Math.round(13 * px)}px "Segoe UI Variable Text", "Segoe UI", sans-serif`;
  ctx.textBaseline = "middle";
  if (!have && world.status) {
    ctx.fillStyle = "rgba(220,230,255,0.75)";
    ctx.textAlign = "center";
    ctx.fillText(world.status, w / 2, h - 60 * px);
  }
  ctx.textAlign = "left";
  if (settings.place && world.name) {
    const p = project(point(world.lat, world.lon), w, h);
    if (p) {
      const pulse = 0.5 + 0.5 * Math.sin(ms / 600);
      ctx.fillStyle = `rgba(255,214,120,${0.9 * Math.min(1, p[2] * 3)})`;
      ctx.beginPath(); ctx.arc(p[0], p[1], 3 * px, 0, Math.PI * 2); ctx.fill();
      ctx.strokeStyle = `rgba(255,214,120,${0.5 * (1 - pulse) * Math.min(1, p[2] * 3)})`;
      ctx.lineWidth = 1.2 * px;
      ctx.beginPath(); ctx.arc(p[0], p[1], (4 + 10 * pulse) * px, 0, Math.PI * 2); ctx.stroke();
      ctx.fillStyle = `rgba(255,236,200,${0.85 * Math.min(1, p[2] * 3)})`;
      ctx.fillText(world.name, p[0] + 10 * px, p[1]);
    }
  }
  if (settings.iss && world.iss.length) {
    // Its track over the last 45 minutes, fading out, then the station itself.
    const first = world.iss[0][0];
    let previous = null;
    for (let t = Math.max(first, ms - 45 * 60000); t <= ms; t += 20000) {
      const p = issAt(t);
      const s = p && project(p, w, h);
      if (s && previous && Math.abs(s[0] - previous[0]) < w / 3) {
        ctx.strokeStyle = `rgba(140,220,255,${0.55 * (1 - (ms - t) / (45 * 60000))})`;
        ctx.lineWidth = 1.5 * px;
        ctx.beginPath(); ctx.moveTo(previous[0], previous[1]); ctx.lineTo(s[0], s[1]); ctx.stroke();
      }
      previous = s;
    }
    const now = issAt(ms);
    const s = now && project(now, w, h);
    if (s) {
      const g = ctx.createRadialGradient(s[0], s[1], 0, s[0], s[1], 12 * px);
      g.addColorStop(0, "rgba(200,240,255,0.9)");
      g.addColorStop(1, "rgba(120,200,255,0)");
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.arc(s[0], s[1], 12 * px, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "#ffffff";
      ctx.beginPath(); ctx.arc(s[0], s[1], 2.6 * px, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "rgba(200,235,255,0.9)";
      ctx.fillText("ISS", s[0] + 12 * px, s[1] - 10 * px);
    }
  }
}

// -- the loop (30 frames a second is plenty) -------------------------------------------------------

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
