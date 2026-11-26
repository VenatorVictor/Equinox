#version 330 core
// Cloud shadows on the ground: each pixel's place (from the depth), followed toward the sun up to the cloud base,
// darkened by how little sun gets through the clouds there (the shadow map). Multiplies the picture.
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uDepth;
uniform sampler2D uShadow;
uniform mat4 uInverse;      // screen to camera-relative position
uniform vec3 uSun;
uniform float uCloudY;      // cloud base height, relative to the camera
uniform vec2 uShadowOrigin; // the shadow map's corner, relative to the camera
uniform float uShadowSpan;
uniform float uStrength;
uniform float uReach;

void main() {
    float depth = texture(uDepth, vUv).r;
    if (depth >= 1.0) discard;
    vec4 w = uInverse * vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 pos = w.xyz / w.w;
    if (pos.y > uCloudY) discard;
    float dist = length(pos.xz);
    if (dist > uReach) discard;
    vec3 q = pos + uSun * ((uCloudY - pos.y) / max(uSun.y, 0.2));
    vec2 t = (q.xz - uShadowOrigin) / uShadowSpan;
    if (t.x < 0.0 || t.y < 0.0 || t.x > 1.0 || t.y > 1.0) discard;
    float light = texture(uShadow, t).r;
    float k = uStrength * (1.0 - smoothstep(uReach * 0.7, uReach, dist));
    fragColor = vec4(vec3(mix(1.0, light, k)), 1.0);
}
