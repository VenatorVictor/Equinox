// Equinox clouds: the cloud density, shared by the passes (the game keeps the same function in CloudField.java).
// Positions are blocks relative to the sky's centre (whole wind-space blocks), y above the game's cloud height.

layout(std430, binding = 0) readonly buffer Domes { vec4 domes[]; };            // 3 vec4 per dome
layout(std430, binding = 1) readonly buffer List { uint listCount; uint list[]; }; // domes near this chunk

uniform sampler2D uMap0;   // low amount, low bottom, low top, storm level
uniform sampler2D uMap1;   // middle amount, high amount, lumpiness, thunder
uniform ivec2 uCentre;     // the sky's centre, wind-space blocks (for the noise)
uniform vec2 uMapOrigin;   // the map's corner, relative to the centre
uniform float uMapSpan;    // the map's width in blocks
uniform int uAngle;        // wind direction: index of the nearest pattern angle below (16 a turn) ...
uniform float uAngleF;     // ... and how far toward the next
uniform int uCube;         // cube size (blocks)

const float MID_LOW = 64.0, MID_HIGH = 72.0, HIGH_LOW = 150.0, HIGH_HIGH = 156.0;
const float TWO_PI = 6.28318530718;

uint hash3(ivec3 p, uint salt) {
    uint h = uint(p.x) * 0x8da6b343u ^ uint(p.y) * 0xd8163841u ^ uint(p.z) * 0xcb1ab31fu ^ salt * 0x165667b1u;
    // the PCG generator's output step
    uint state = h * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float u01(uint h) {
    return float(h >> 8) * (1.0 / 16777216.0);
}

int fdiv(int a, int s) {
    return a >= 0 ? a / s : -((-a + s - 1) / s);
}

float vnoise3(ivec3 a, int s, uint salt) {
    ivec3 i = ivec3(fdiv(a.x, s), fdiv(a.y, s), fdiv(a.z, s));
    vec3 f = vec3(a - i * s) / float(s);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = u01(hash3(i, salt)), n100 = u01(hash3(i + ivec3(1, 0, 0), salt));
    float n010 = u01(hash3(i + ivec3(0, 1, 0), salt)), n110 = u01(hash3(i + ivec3(1, 1, 0), salt));
    float n001 = u01(hash3(i + ivec3(0, 0, 1), salt)), n101 = u01(hash3(i + ivec3(1, 0, 1), salt));
    float n011 = u01(hash3(i + ivec3(0, 1, 1), salt)), n111 = u01(hash3(i + ivec3(1, 1, 1), salt));
    float x00 = mix(n000, n100, f.x), x10 = mix(n010, n110, f.x), x01 = mix(n001, n101, f.x), x11 = mix(n011, n111, f.x);
    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

float vnoise2(ivec2 a, int s, uint salt) {
    ivec2 i = ivec2(fdiv(a.x, s), fdiv(a.y, s));
    vec2 f = vec2(a - i * s) / float(s);
    f = f * f * (3.0 - 2.0 * f);
    float n00 = u01(hash3(ivec3(i.x, 0, i.y), salt)), n10 = u01(hash3(ivec3(i.x + 1, 0, i.y), salt));
    float n01 = u01(hash3(ivec3(i.x, 0, i.y + 1), salt)), n11 = u01(hash3(ivec3(i.x + 1, 0, i.y + 1), salt));
    return mix(mix(n00, n10, f.x), mix(n01, n11, f.x), f.y);
}

float vnoise2f(vec2 q, uint salt) {
    vec2 fl = floor(q);
    ivec2 i = ivec2(fl);
    vec2 f = q - fl;
    f = f * f * (3.0 - 2.0 * f);
    float n00 = u01(hash3(ivec3(i.x, 0, i.y), salt)), n10 = u01(hash3(ivec3(i.x + 1, 0, i.y), salt));
    float n01 = u01(hash3(ivec3(i.x, 0, i.y + 1), salt)), n11 = u01(hash3(ivec3(i.x + 1, 0, i.y + 1), salt));
    return mix(mix(n00, n10, f.x), mix(n01, n11, f.x), f.y);
}

// A pattern stretched along one of 16 fixed wind angles (fixed, so it stays put in wind space).
float oriented(ivec2 a, int index, float along, float across, uint salt) {
    int i = index & 15;
    float angle = float(i) * TWO_PI / 16.0;
    float c = cos(angle), s = sin(angle);
    vec2 v = vec2(a);
    return vnoise2f(vec2((v.x * c + v.y * s) / along, (-v.x * s + v.y * c) / across), salt + uint(i) * 7919u);
}

float orientedWind(ivec2 a, float along, float across, uint salt) {
    return mix(oriented(a, uAngle, along, across, salt), oriented(a, uAngle + 1, along, across, salt), uAngleF);
}

vec4 mapAt(sampler2D m, vec2 rel) {
    return texture(m, (rel - uMapOrigin) / uMapSpan);
}

// How far inside a band [lo, hi] a height is (blocks); at least one cube thick, so coarse cubes keep thin layers.
float band(float y, float lo, float hi) {
    float hb = max((hi - lo) * 0.5, float(uCube) * 0.5);
    return hb - abs(y - (lo + hi) * 0.5) + 0.5;
}

// x: blocks inside the cloud (> 0 inside), y: storm level (0..2), z: kind (0 heap or deck, 1 middle, 2 high).
vec3 density(vec3 p, ivec3 a) {
    vec3 best = vec3(-99.0, 0.0, 0.0);
    float bump = vnoise3(a, 24, 1u) * 0.65 + vnoise3(a, 9, 2u) * 0.35 - 0.5;
    uint n = listCount;
    for (uint k = 0u; k < n; k++) {
        uint d = list[k] * 3u;
        vec4 A = domes[d], B = domes[d + 1u], C = domes[d + 2u];
        // A: x z centre-y base; B: radius along, across, up, along x; C: along z, storm level, kind, size
        if (p.y < A.w) continue;
        vec2 dd = p.xz - A.xy;
        float al = (dd.x * B.w + dd.y * C.x) / B.x, ac = (-dd.x * C.x + dd.y * B.w) / B.y, up = (p.y - A.z) / B.z;
        float r = sqrt(al * al + ac * ac + up * up);
        float amp = C.z > 0.5 ? 3.0 : min(C.w * 0.45, 14.0);
        float v = (1.0 - r) * C.w + bump * 2.0 * amp;
        if (v > best.x) best = vec3(v, C.y, 0.0);
    }
    vec4 m0 = mapAt(uMap0, p.xz), m1 = mapAt(uMap1, p.xz);
    // low deck: stratocumulus lumps in warm air, flat stratus in cold, thick nimbostratus in the rain
    if (m0.x > 0.02 && p.y > m0.y - float(uCube) && p.y < m0.z + float(uCube)) {
        float m = vnoise2(a.xz, 96, 3u) * 0.6 + vnoise2(a.xz, 32, 4u) * 0.4;
        float h = (m0.x * 1.1 - 0.05 - m) * 60.0;
        float top = m0.z - m1.z * (m0.z - m0.y) * 0.6 * vnoise2(a.xz, 16, 5u);
        float v = min(h, band(p.y, m0.y, max(top, m0.y + 4.0)));
        if (v > best.x) best = vec3(v, m0.w, 0.0);
    }
    // middle: altocumulus in bands across the wind ahead of a front
    if (m1.x > 0.02) {
        float bv = band(p.y, MID_LOW, MID_HIGH);
        if (bv > 0.0) {
            float val = orientedWind(a.xz, 26.0, 160.0, 6u) * 0.55 + vnoise2(a.xz, 20, 7u) * 0.45;
            float v = min((val - (1.0 - 0.6 * m1.x)) * 40.0, bv);
            if (v > best.x) best = vec3(v, m0.w * 0.8, 1.0);
        }
    }
    // high: cirrus streaks along the wind, far ahead of a front
    if (m1.y > 0.02) {
        float bv = band(p.y, HIGH_LOW, HIGH_HIGH);
        if (bv > 0.0) {
            float val = orientedWind(a.xz, 420.0, 22.0, 8u);
            float v = min((val - (1.0 - 0.35 * m1.y)) * 50.0, bv);
            if (v > best.x) best = vec3(v, m0.w * 0.5, 2.0);
        }
    }
    return best;
}

ivec3 absBlock(vec3 p) {
    return ivec3(floor(p)) + ivec3(uCentre.x, 0, uCentre.y);
}
