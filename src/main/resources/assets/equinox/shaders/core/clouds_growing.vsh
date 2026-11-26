#version 150

#moj_import <fog.glsl>

// The game's cloud shader, plus each cube growing out of the cloud next to it when it appears and shrinking back into
// it when it goes (from its middle when it's on its own). v's whole-number part says it (the palette repeats, so other
// shaders see the same colours): 0 settled, else 1 + time stamp + 1024 * (side + 7 if going), side 0 the middle or
// 1-6 the face it grows from (+y, -y, -x, +x, -z, +z). The colour's lowest bit per channel says which corner of its
// cube this vertex is.

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
        float k = code - 1.0;
        float stamp = mod(k, 1024.0), rest = floor(k / 1024.0);
        bool going = rest >= 7.0;
        int from = int(going ? rest - 7.0 : rest);
        float t = clamp(mod(AnimTime - stamp + 1024.0, 1024.0) / AnimLength, 0.0, 1.0);
        t = t * t * (3.0 - 2.0 * t);
        float scale = going ? 1.0 - t : t;
        vec3 corner = vec3(ivec3(round(Color.rgb * 255.0)) & 1);
        vec3 middle = Position - (corner - 0.5) * Cube;
        if (from == 0) {
            p = middle + (Position - middle) * scale;
        } else {
            vec3 dir = from == 1 ? vec3(0, 1, 0) : from == 2 ? vec3(0, -1, 0) : from == 3 ? vec3(-1, 0, 0)
                    : from == 4 ? vec3(1, 0, 0) : from == 5 ? vec3(0, 0, -1) : vec3(0, 0, 1);
            vec3 face = middle + dir * Cube * 0.5;
            p = mix(Position, face + (Position - face) * scale, abs(dir));
        }
    }
    vec4 pos = ModelViewMat * vec4(p, 1.0);
    gl_Position = ProjMat * pos;

    texCoord0 = UV0;
    vertexDistance = fog_distance(pos.xyz, FogShape);
    vertexColor = Color;
}
