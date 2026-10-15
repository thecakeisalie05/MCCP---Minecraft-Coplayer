#version 330 core
in vec2 v_uv;
out vec4 FragColor;
uniform sampler3D u_blocks;
uniform vec3 u_eye;
uniform vec3 u_forward;
uniform vec3 u_right;
uniform vec3 u_up;
uniform vec3 u_size;
uniform float u_aspect;
uniform float u_tanFov;

// Low-cost GPU semantic voxel preview. CPU supplies the opaque block-color grid.
// This does not render Minecraft textures, entities, shaders, particles, or GUI.
void main() {
    vec2 screen = v_uv * 2.0 - 1.0;
    vec3 ray = normalize(u_forward + screen.x * u_aspect * u_tanFov * u_right
                       + screen.y * u_tanFov * u_up);
    vec3 sky = mix(vec3(0.10, 0.16, 0.24), vec3(0.25, 0.35, 0.48),
                   clamp(ray.y * 0.55 + 0.5, 0.0, 1.0));
    vec3 color = sky;
    float maxDistance = 20.0;
    for (int i = 1; i < 84; ++i) {
        float distance = float(i) * 0.25;
        if (distance > maxDistance) break;
        vec3 point = u_eye + ray * distance;
        if (any(lessThan(point, vec3(0.0))) || any(greaterThanEqual(point, u_size)))
            break;
        ivec3 voxel = ivec3(floor(point));
        vec4 sampled = texelFetch(u_blocks, voxel, 0);
        if (sampled.a > 0.5) {
            float fog = clamp(distance / maxDistance, 0.0, 0.8);
            color = mix(sampled.rgb, sky, fog);
            break;
        }
    }
    FragColor = vec4(color, 1.0);
}
