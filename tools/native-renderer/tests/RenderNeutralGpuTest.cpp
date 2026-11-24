// 1.2.22: the compositor draws windows without blending and skips neutral colour adjustments. On lavapipe with the production
// shaders: (1) the new shader on the opaque pipeline returns every source texel exactly at neutral settings; (2) with real
// adjustments it matches the old shader bit for bit; (3) blending on or off gives the same picture over a coloured clear.
// Usage: RenderNeutralGpuTest (VK_ICD_FILENAMES=lvp)
#include <vulkan/vulkan.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include "window_vert.h"
#include "window_frag.h"
#include "window_frag_old.h"
#include "CompositorCover.h"
#include <vector>

#define VK_CHECK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { std::printf("Vulkan error %d at line %d (%s)\n", r_, __LINE__, #x); std::exit(2); } } while (0)

// Must match WindowPushConstants in VulkanRendererContext.h (80 bytes: rotation at offset 60, the texture rectangle at 64).
struct WindowPushConstants {
    float ndcX0, ndcY0, ndcX1, ndcY1;
    int useTexAlpha, effectId;
    float sharpness, resW, resH;
    int effectMask;
    float brightness, contrast, gamma, outW, outH;
    int rotation;
    float uvX0, uvY0, uvX1, uvY1;
};
static_assert(sizeof(WindowPushConstants) == 80, "push constant layout");
static_assert(offsetof(WindowPushConstants, rotation) == 60, "rotation offset");
static_assert(offsetof(WindowPushConstants, uvX0) == 64, "texture rectangle offset");

static VkDevice dev; static VkPhysicalDevice phys; static VkQueue queue; static uint32_t qfam;

static uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties p; vkGetPhysicalDeviceMemoryProperties(phys, &p);
    for (uint32_t i = 0; i < p.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (p.memoryTypes[i].propertyFlags & want) == want) return i;
    std::printf("no memory type\n"); std::exit(2);
}

struct Img { VkImage image; VkDeviceMemory mem; VkImageView view; };
static Img makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage) {
    Img im{};
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM; ci.extent = {w, h, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL; ci.usage = usage; ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_CHECK(vkCreateImage(dev, &ci, nullptr, &im.image));
    VkMemoryRequirements req; vkGetImageMemoryRequirements(dev, im.image, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size; ai.memoryTypeIndex = memType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VK_CHECK(vkAllocateMemory(dev, &ai, nullptr, &im.mem));
    VK_CHECK(vkBindImageMemory(dev, im.image, im.mem, 0));
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = im.image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = ci.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_CHECK(vkCreateImageView(dev, &vi, nullptr, &im.view));
    return im;
}

struct Buf { VkBuffer buf; VkDeviceMemory mem; void* map; };
static Buf makeBuffer(VkDeviceSize size, VkBufferUsageFlags usage) {
    Buf b{};
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; ci.size = size; ci.usage = usage;
    VK_CHECK(vkCreateBuffer(dev, &ci, nullptr, &b.buf));
    VkMemoryRequirements req; vkGetBufferMemoryRequirements(dev, b.buf, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VK_CHECK(vkAllocateMemory(dev, &ai, nullptr, &b.mem));
    VK_CHECK(vkBindBufferMemory(dev, b.buf, b.mem, 0));
    VK_CHECK(vkMapMemory(dev, b.mem, 0, size, 0, &b.map));
    return b;
}

static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,
                    VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags s, VkPipelineStageFlags d) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from; b.newLayout = to; b.srcAccessMask = src; b.dstAccessMask = dst;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(cb, s, d, 0, 0, nullptr, 0, nullptr, 1, &b);
}



int main() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    VkInstance inst; VK_CHECK(vkCreateInstance(&ici, nullptr, &inst));
    uint32_t n = 1; vkEnumeratePhysicalDevices(inst, &n, &phys);
    if (!n) { std::printf("no device\n"); return 2; }
    float prio = 1;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex = 0; qi.queueCount = 1; qi.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qi;
    VK_CHECK(vkCreateDevice(phys, &dci, nullptr, &dev));
    vkGetDeviceQueue(dev, 0, 0, &queue);
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.queueFamilyIndex = 0;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    VkCommandPool pool; VK_CHECK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = pool; cai.commandBufferCount = 1;
    VkCommandBuffer cb; VK_CHECK(vkAllocateCommandBuffers(dev, &cai, &cb));

    const uint32_t W = 64, H = 32;
    std::vector<uint32_t> texels(W * H);
    uint32_t seed = 12345;
    for (auto& t : texels) { seed = seed * 1664525u + 1013904223u; t = (seed >> 8) | 0xff000000u; }
    Img tex = makeImage(W, H, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    Buf up = makeBuffer(W * H * 4, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    std::memcpy(up.map, texels.data(), W * H * 4);
    VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VkSampler sampler; VK_CHECK(vkCreateSampler(dev, &sci, nullptr, &sampler));
    VkDescriptorSetLayoutBinding bind{0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount = 1; dli.pBindings = &bind;
    VkDescriptorSetLayout dsl; VK_CHECK(vkCreateDescriptorSetLayout(dev, &dli, nullptr, &dsl));
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets = 1; dpi.poolSizeCount = 1; dpi.pPoolSizes = &ps;
    VkDescriptorPool dpool; VK_CHECK(vkCreateDescriptorPool(dev, &dpi, nullptr, &dpool));
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dai.descriptorPool = dpool; dai.descriptorSetCount = 1; dai.pSetLayouts = &dsl;
    VkDescriptorSet ds; VK_CHECK(vkAllocateDescriptorSets(dev, &dai, &ds));
    VkDescriptorImageInfo dii{sampler, tex.view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wr{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; wr.dstSet = ds; wr.descriptorCount = 1;
    wr.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; wr.pImageInfo = &dii;
    vkUpdateDescriptorSets(dev, 1, &wr, 0, nullptr);
    VkPushConstantRange pcr{VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(WindowPushConstants)};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1; pli.pSetLayouts = &dsl; pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pcr;
    VkPipelineLayout layout; VK_CHECK(vkCreatePipelineLayout(dev, &pli, nullptr, &layout));
    VkAttachmentDescription att{}; att.format = VK_FORMAT_R8G8B8A8_UNORM; att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE; att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; att.finalLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{}; sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS; sub.colorAttachmentCount = 1; sub.pColorAttachments = &ref;
    VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO}; rpi.attachmentCount = 1; rpi.pAttachments = &att; rpi.subpassCount = 1; rpi.pSubpasses = &sub;
    VkRenderPass rp; VK_CHECK(vkCreateRenderPass(dev, &rpi, nullptr, &rp));
    att.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    VkRenderPass rpNoClear; VK_CHECK(vkCreateRenderPass(dev, &rpi, nullptr, &rpNoClear));
    auto module = [](const uint32_t* code, size_t size) {
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; mi.codeSize = size; mi.pCode = code;
        VkShaderModule m; VK_CHECK(vkCreateShaderModule(dev, &mi, nullptr, &m)); return m;
    };
    // The production pipeline state (VulkanRendererContext::createPipeline), blending on or off.
    auto makePipe = [&](const uint32_t* frag, size_t fragSize, bool blend) {
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, module(window_vert_code, sizeof(window_vert_code)), "main", nullptr};
        stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, module(frag, fragSize), "main", nullptr};
        VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
        VkPipelineViewportStateCreateInfo vps{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO}; vps.viewportCount = 1; vps.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO}; rs.cullMode = VK_CULL_MODE_NONE; rs.lineWidth = 1;
        VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineColorBlendAttachmentState ba{}; ba.colorWriteMask = 0xf; ba.blendEnable = blend ? VK_TRUE : VK_FALSE;
        if (blend) { ba.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA; ba.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA; ba.colorBlendOp = VK_BLEND_OP_ADD;
                     ba.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE; ba.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO; ba.alphaBlendOp = VK_BLEND_OP_ADD; }
        VkPipelineColorBlendStateCreateInfo cbs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; cbs.attachmentCount = 1; cbs.pAttachments = &ba;
        VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dys{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO}; dys.dynamicStateCount = 2; dys.pDynamicStates = dyn;
        VkGraphicsPipelineCreateInfo gpi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        gpi.stageCount = 2; gpi.pStages = stages; gpi.pVertexInputState = &vin; gpi.pInputAssemblyState = &ia; gpi.pViewportState = &vps;
        gpi.pRasterizationState = &rs; gpi.pMultisampleState = &ms; gpi.pColorBlendState = &cbs; gpi.pDynamicState = &dys;
        gpi.layout = layout; gpi.renderPass = rp;
        VkPipeline p; VK_CHECK(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gpi, nullptr, &p)); return p;
    };
    VkPipeline newOpaque = makePipe(window_frag_code, sizeof(window_frag_code), false);
    VkPipeline newBlend = makePipe(window_frag_code, sizeof(window_frag_code), true);
    VkPipeline oldBlend = makePipe(window_frag_old_code, sizeof(window_frag_old_code), true);
    Img target = makeImage(W, H, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
    VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; fi.renderPass = rp; fi.attachmentCount = 1;
    fi.pAttachments = &target.view; fi.width = W; fi.height = H; fi.layers = 1;
    VkFramebuffer fb; VK_CHECK(vkCreateFramebuffer(dev, &fi, nullptr, &fb));
    Buf out = makeBuffer(W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    auto render = [&](VkPipeline pipe, float brightness, float contrast, float gamma, VkRenderPass pass = VK_NULL_HANDLE) {
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        VK_CHECK(vkBeginCommandBuffer(cb, &bi));
        barrier(cb, tex.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageExtent = {W, H, 1};
        vkCmdCopyBufferToImage(cb, up.buf, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        barrier(cb, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        VkClearValue clear{{{0.3f, 0.6f, 0.9f, 1.0f}}}; VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rbi.renderPass = pass ? pass : rp; rbi.framebuffer = fb; rbi.renderArea = {{0, 0}, {W, H}}; rbi.clearValueCount = 1; rbi.pClearValues = &clear;
        vkCmdBeginRenderPass(cb, &rbi, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport vp{0, 0, (float)W, (float)H, 0, 1}; vkCmdSetViewport(cb, 0, 1, &vp);
        VkRect2D sc{{0, 0}, {W, H}}; vkCmdSetScissor(cb, 0, 1, &sc);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, 1, &ds, 0, nullptr);
        WindowPushConstants pc{}; pc.ndcX0 = -1; pc.ndcY0 = -1; pc.ndcX1 = 1; pc.ndcY1 = 1;
        pc.useTexAlpha = 0; pc.effectId = 0; pc.resW = (float)W; pc.resH = (float)H; pc.effectMask = 0;
        pc.brightness = brightness; pc.contrast = contrast; pc.gamma = gamma;
        pc.outW = (float)W; pc.outH = (float)H; pc.rotation = 0; pc.uvX0 = 0; pc.uvY0 = 0; pc.uvX1 = 1; pc.uvY1 = 1;
        vkCmdPushConstants(cb, layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        vkCmdDraw(cb, 4, 1, 0, 0);
        vkCmdEndRenderPass(cb);
        VkBufferImageCopy back{}; back.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; back.imageExtent = {W, H, 1};
        vkCmdCopyImageToBuffer(cb, target.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, out.buf, 1, &back);
        VK_CHECK(vkEndCommandBuffer(cb));
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &cb;
        VK_CHECK(vkQueueSubmit(queue, 1, &si, VK_NULL_HANDLE));
        VK_CHECK(vkQueueWaitIdle(queue));
        return std::vector<uint32_t>((const uint32_t*)out.map, (const uint32_t*)out.map + W * H);
    };
    auto diff = [&](const std::vector<uint32_t>& a, const std::vector<uint32_t>& b, int& maxd) {
        int n = 0; maxd = 0;
        for (size_t i = 0; i < a.size(); i++) if (a[i] != b[i]) { n++;
            for (int c = 0; c < 4; c++) maxd = std::max(maxd, std::abs((int)((a[i] >> (8 * c)) & 255) - (int)((b[i] >> (8 * c)) & 255))); }
        return n;
    };
    int fail = 0, md;
    auto neutralNew = render(newOpaque, 0, 0, 1);
    int d1 = diff(neutralNew, texels, md);
    std::printf("neutral, new shader, opaque pipeline vs source texels: %d of %u differ (max %d)\n", d1, W * H, md); if (d1) fail++;
    auto neutralOld = render(oldBlend, 0, 0, 1);
    int d0 = diff(neutralOld, texels, md);
    std::printf("neutral, old shader, blended pipeline vs source texels: %d of %u differ (max %d) [the old pow() path]\n", d0, W * H, md);
    if (d0 && md > 1) fail++;
    auto adjOld = render(oldBlend, 0.05f, 0.1f, 1.3f), adjNew = render(newOpaque, 0.05f, 0.1f, 1.3f);
    int d2 = diff(adjOld, adjNew, md);
    std::printf("adjusted (0.05/0.1/1.3), old+blended vs new+opaque: %d differ (max %d)\n", d2, md); if (d2) fail++;
    auto blendNew = render(newBlend, 0, 0, 1);
    int d3 = diff(blendNew, neutralNew, md);
    std::printf("over a coloured clear, blended vs opaque (new shader): %d differ\n", d3); if (d3) fail++;
    render(newOpaque, 0.3f, 0.5f, 2.0f);   // leave a different picture in the target
    auto noClear = render(newOpaque, 0, 0, 1, rpNoClear);
    int d4 = diff(noClear, neutralNew, md);
    std::printf("full cover without the clear (DONT_CARE) vs with it: %d differ\n", d4); if (d4) fail++;
    const bool coverOk = CompositorCover::coversTarget(-1, -1, 1, 1) && CompositorCover::coversTarget(1, 1, -1, -1) &&
        CompositorCover::coversTarget(-1.01f, -1, 1.01f, 1) && !CompositorCover::coversTarget(-0.999f, -1, 1, 1) &&
        !CompositorCover::coversTarget(-1, -1, 1, 0.999f) && !CompositorCover::coversTarget(-1, -1, 0, 1);
    std::printf("coversTarget: full / mirrored / overscan yes, a sub-pixel gap or half no: %s\n", coverOk ? "ok" : "WRONG"); if (!coverOk) fail++;
    if (fail) { std::printf("FAILED %d checks\n", fail); return 1; }
    std::printf("PASS opaque windows, neutral colour skip and the skipped clear keep the picture\n");
    return 0;
}
