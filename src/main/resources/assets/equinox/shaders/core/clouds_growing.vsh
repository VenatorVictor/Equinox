#version 150

#moj_import <fog.glsl>

// The game's cloud shader, plus each cube growing out of its middle when it appears and shrinking away when it goes.
// The texture coordinates carry it in their whole-number parts (the palette repeats, so other shaders see the same
// colours): u = which corner of its cube this is, v = 0 settled, 1 + time stamp growing, 1025 + time stamp going.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
uniform float AnimTime;   // now, in 2-tick steps, 0..1024
uniform float AnimLength; // how long the growing takes, 2-tick steps
uniform float Cube;       // cube size in this chunk

out vec2 texCoord0;
out float vertexDistance;
out vec4 vertexColor;

void main() {
    vec3 p = Position;
    float code = floor(UV0.y);
    if (code >= 1.0) {
        bool going = code >= 1025.0;
        float stamp = going ? code - 1025.0 : code - 1.0;
        float t = clamp(mod(AnimTime - stamp + 1024.0, 1024.0) / AnimLength, 0.0, 1.0);
        t = t * t * (3.0 - 2.0 * t);
        float scale = going ? 1.0 - t : t;
        int c = int(floor(UV0.x));
        vec3 side = vec3(float(c & 1), float((c >> 1) & 1), float((c >> 2) & 1));
        vec3 middle = Position - (side - 0.5) * Cube;
        p = middle + (Position - middle) * scale;
    }
    vec4 pos = ModelViewMat * vec4(p, 1.0);
    gl_Position = ProjMat * pos;

    texCoord0 = UV0;
    vertexDistance = fog_distance(pos.xyz, FogShape);
    vertexColor = Color;
}
