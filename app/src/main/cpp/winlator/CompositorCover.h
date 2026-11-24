#pragma once

/**
 * 1.2.22: whether a window quad (normalised device coordinates, before the swapchain's quarter-turn pre-rotation, which maps the
 * [-1,1] square onto itself) covers the whole target. Windows are drawn opaque, so the render pass then need not clear the target
 * first (VK_ATTACHMENT_LOAD_OP_DONT_CARE): with hardware scaling the game picture fills the swapchain every frame and the clear was
 * a full-screen write for nothing. Anything short of full cover keeps the clear. Pure: tools/native-renderer/tests.
 */
struct CompositorCover {
    static bool coversTarget(float x0, float y0, float x1, float y1) {
        const float left = x0 < x1 ? x0 : x1, right = x0 < x1 ? x1 : x0;
        const float top = y0 < y1 ? y0 : y1, bottom = y0 < y1 ? y1 : y0;
        return left <= -1.0f && right >= 1.0f && top <= -1.0f && bottom >= 1.0f;
    }
};
