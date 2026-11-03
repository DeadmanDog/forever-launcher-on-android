// Runs the production window shaders on lavapipe with the FSR effect and checks where a hard vertical edge lands.
// The texture is black on its left half and white on its right half; an exact mapping puts the 50% crossing of every
// output row on the edge's own position scaled to the output (2x upscale: 8.0 of 16, 1:1: 4.0 of 8). EASU must be given
// the integer output pixel; the pixel centre (x + 0.5) moves the whole picture half an output pixel.
// Usage: RenderFsrGpuTest (VK_ICD_FILENAMES=lvp)
#include <vulkan/vulkan.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include "window_vert.h"
#include "window_frag.h"

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


static float crossing(const uint32_t* px, uint32_t W, uint32_t row) {
    for (uint32_t x = 0; x + 1 < W; x++) {
        const float a = (float)(px[row * W + x] & 255) / 255.f, b = (float)(px[row * W + x + 1] & 255) / 255.f;
        if (a < 0.5f && b >= 0.5f) return (float)x + 0.5f + (0.5f - a) / (b - a);
    }
    return -1.f;
}

int main() {
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    VkInstance inst; VK_CHECK(vkCreateInstance(&ici, nullptr, &inst));
    uint32_t n = 1; vkEnumeratePhysicalDevices(inst, &n, &phys);
    if (!n) { std::printf("no device\n"); return 2; }
    float prio = 1; qfam = 0;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex = 0; qi.queueCount = 1; qi.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qi;
    VK_CHECK(vkCreateDevice(phys, &dci, nullptr, &dev));
    vkGetDeviceQueue(dev, 0, 0, &queue);
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.queueFamilyIndex = 0;
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    VkCommandPool pool; VK_CHECK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool = pool; cai.commandBufferCount = 1;
    VkCommandBuffer cb; VK_CHECK(vkAllocateCommandBuffers(dev, &cai, &cb));

    const uint32_t TW = 8, TH = 4;
    uint32_t texels[TW * TH];
    for (uint32_t y = 0; y < TH; y++) for (uint32_t x = 0; x < TW; x++) texels[y * TW + x] = x < TW / 2 ? 0xff000000u : 0xffffffffu;
    Img tex = makeImage(TW, TH, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
    Buf up = makeBuffer(sizeof(texels), VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    std::memcpy(up.map, texels, sizeof(texels));

    // The renderer's default filter (VulkanRendererContext::createSampler, filterMode 0 without cubic).
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
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}; w.dstSet = ds; w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w.pImageInfo = &dii;
    vkUpdateDescriptorSets(dev, 1, &w, 0, nullptr);

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

    auto module = [](const uint32_t* code, size_t size) {
        VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; mi.codeSize = size; mi.pCode = code;
        VkShaderModule m; VK_CHECK(vkCreateShaderModule(dev, &mi, nullptr, &m)); return m;
    };
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_VERTEX_BIT, module(window_vert_code, sizeof(window_vert_code)), "main", nullptr};
    stages[1] = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_FRAGMENT_BIT, module(window_frag_code, sizeof(window_frag_code)), "main", nullptr};
    VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO}; ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    VkPipelineViewportStateCreateInfo vps{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO}; vps.viewportCount = 1; vps.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO}; rs.cullMode = VK_CULL_MODE_NONE; rs.lineWidth = 1;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO}; ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{}; cba.colorWriteMask = 0xf;
    VkPipelineColorBlendStateCreateInfo cbs{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO}; cbs.attachmentCount = 1; cbs.pAttachments = &cba;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dys{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO}; dys.dynamicStateCount = 2; dys.pDynamicStates = dyn;
    VkGraphicsPipelineCreateInfo gpi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpi.stageCount = 2; gpi.pStages = stages; gpi.pVertexInputState = &vin; gpi.pInputAssemblyState = &ia; gpi.pViewportState = &vps;
    gpi.pRasterizationState = &rs; gpi.pMultisampleState = &ms; gpi.pColorBlendState = &cbs; gpi.pDynamicState = &dys;
    gpi.layout = layout; gpi.renderPass = rp;
    VkPipeline pipe; VK_CHECK(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &gpi, nullptr, &pipe));

    int failures = 0;
    // Output sizes: the RedMagic case (2x: 1344 -> 2688) and 1:1.
    const uint32_t scales[] = {2, 1};
    for (uint32_t s : scales) {
        const uint32_t W = TW * s, H = TH * s;
        Img target = makeImage(W, H, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
        VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO}; fi.renderPass = rp; fi.attachmentCount = 1;
        fi.pAttachments = &target.view; fi.width = W; fi.height = H; fi.layers = 1;
        VkFramebuffer fb; VK_CHECK(vkCreateFramebuffer(dev, &fi, nullptr, &fb));
        Buf out = makeBuffer(W * H * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        VK_CHECK(vkBeginCommandBuffer(cb, &bi));
        barrier(cb, tex.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy copy{}; copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; copy.imageExtent = {TW, TH, 1};
        vkCmdCopyBufferToImage(cb, up.buf, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
        barrier(cb, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        VkClearValue clear{}; VkRenderPassBeginInfo rbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
        rbi.renderPass = rp; rbi.framebuffer = fb; rbi.renderArea = {{0, 0}, {W, H}}; rbi.clearValueCount = 1; rbi.pClearValues = &clear;
        vkCmdBeginRenderPass(cb, &rbi, VK_SUBPASS_CONTENTS_INLINE);
        VkViewport vp{0, 0, (float)W, (float)H, 0, 1}; vkCmdSetViewport(cb, 0, 1, &vp);
        VkRect2D sc{{0, 0}, {W, H}}; vkCmdSetScissor(cb, 0, 1, &sc);
        vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
        vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, 1, &ds, 0, nullptr);
        // As VulkanRendererContext::recordDraws fills them for a window that covers the whole output.
        WindowPushConstants pc{}; pc.ndcX0 = -1; pc.ndcY0 = -1; pc.ndcX1 = 1; pc.ndcY1 = 1;
        pc.effectId = 1; pc.sharpness = 0; pc.resW = (float)TW; pc.resH = (float)TH; pc.gamma = 1;
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
        const uint32_t* px = (const uint32_t*)out.map;
        const float want = (float)(TW / 2 * s);
        for (uint32_t y = 0; y < H; y++) {
            const float got = crossing(px, W, y);
            std::printf("scale %u row %u: 50%% crossing at %.3f (edge %.1f)\n", s, y, got, want);
            if (std::fabs(got - want) > 0.15f) failures++;
        }
    }
    if (failures) { std::printf("FAILED %d rows: the FSR picture is shifted\n", failures); return 1; }
    std::printf("PASS FSR keeps a hard edge in place at 2x and 1:1\n");
    return 0;
}
