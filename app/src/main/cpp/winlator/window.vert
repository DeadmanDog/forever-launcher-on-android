#version 450

layout(push_constant) uniform PC {
    float ndcX0;
    float ndcY0;
    float ndcX1;
    float ndcY1;
    // WindowPushConstants::rotation: clockwise quarter turns of the pre-rotated swapchain.
    layout(offset = 60) int rotation;
    // The part of the texture the quad shows: x0, y0, x1, y1 (0,0,1,1 = the whole texture).
    layout(offset = 64) vec4 uvRect;
} pc;

layout(location = 0) out vec2 fragTexCoord;

void main() {
    int xi = (gl_VertexIndex >> 1) & 1;
    int yi = gl_VertexIndex & 1;
    float x = xi == 1 ? pc.ndcX1 : pc.ndcX0;
    float y = yi == 1 ? pc.ndcY1 : pc.ndcY0;
    vec2 p = vec2(x, y);
    if (pc.rotation == 1)      p = vec2(-y, x);
    else if (pc.rotation == 2) p = vec2(-x, -y);
    else if (pc.rotation == 3) p = vec2(y, -x);
    gl_Position = vec4(p, 0.0, 1.0);
    fragTexCoord = vec2(xi == 1 ? pc.uvRect.z : pc.uvRect.x, yi == 1 ? pc.uvRect.w : pc.uvRect.y);
}
