#!/usr/bin/env python3
"""Wire/production-JNI JUnit output -> actual native cursor copy/staging -> production Vulkan shaders -> readback.

Run CursorColorPathTest first. This is a host lavapipe proof, not a Wine-on-phone capture.
The two mutations are sensitivity controls, not claims about an earlier production defect.
Existing tests and production color code are not edited. Generated harness and logs remain in --out.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--out', required=True, type=Path)
p.add_argument('--pixels', type=Path)
p.add_argument('--source-root', type=Path, help='Optional baseline native source tree; shaders remain the verified unchanged production shaders')
a = p.parse_args()
root = Path(__file__).resolve().parents[3]
cpp = root / 'app/src/main/cpp/winlator'
tests = Path(__file__).resolve().parent
pixels = (a.pixels or root / 'app/build/cursor-color-proof/argb-straight.bin').resolve()
assert pixels.stat().st_size == 128, 'Run CursorColorPathTest before the GPU proof'
out = a.out.resolve()
out.mkdir(parents=True, exist_ok=False)
native_cpp = a.source_root.resolve() / 'app/src/main/cpp/winlator' if a.source_root else cpp
source = (native_cpp / 'VulkanRendererContext.cpp').read_text()
scanout = (native_cpp / 'VulkanRendererScanout.cpp').read_text()

def between(text, begin, end):
    start = text.index(begin)
    return text[start:text.index(end, start)]

update = between(source, 'void VulkanRendererContext::updateCursorImage(', '\nvoid VulkanRendererContext::noteSlowCursorUpdate(')
staging = between(source, '                for (int y=0; y<curUpH; y++)', '\n                cursorSnapshotGen =')
blend = between(source, '    VkPipelineColorBlendAttachmentState ba{};', '\n    VkPipelineColorBlendStateCreateInfo cb{};')
blend = blend.replace('ba', 'cba')
texture = between(source, 'bool VulkanRendererContext::ensureCursorTex(', '\nvoid VulkanRendererContext::cleanupCursorTex(')
fmt = re.search(r'ii.format=(VK_FORMAT_\w+);', texture).group(1)
scan_pixel = between(scanout, 'inline uint32_t scanoutCursorPixel(', '\n\n// One transaction')
assert 'cpc.useTexAlpha = 1;' in source and 'cpc.gamma = 1.0f;' in source

native = r'''
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <shared_mutex>
#include <vector>
static constexpr short kCursorCapacity=128;
struct VulkanRendererContext {
    std::mutex renderMutex; std::shared_mutex frameMutex;
    VkImage cursorImg=VK_NULL_HANDLE;
    short cursorW=0,cursorH=0,cursorHotX=0,cursorHotY=0,cursorTexW=0,cursorTexH=0;
    std::vector<uint32_t> cursorPixels;
    std::atomic<uint64_t> cursorStageGen{0}; std::atomic<bool> isCursorImageDirty{false};
    struct Wake { void notify() {} } renderWake;
    void markSceneDirty() {}
    void noteSlowCursorUpdate(std::chrono::steady_clock::time_point,short,short,bool) {}
    bool ensureCursorTex(short w,short h) { cursorTexW=w;cursorTexH=h;return true; }
    void updateCursorImage(void*,short,short,short,short);
    std::vector<uint32_t> upload() {
        short curUpW=10,curUpH=6;
        std::vector<uint32_t> result(curUpW*curUpH); auto* dst=result.data();
        /* STAGING */
        return result;
    }
};
/* UPDATE */
/* SCAN_PIXEL */
'''.replace('/* STAGING */', staging).replace('/* UPDATE */', update).replace('/* SCAN_PIXEL */', scan_pixel)

# Share the existing offscreen Vulkan scaffolding, not its FSR test or its blend/format constants.
fixture = (tests / 'RenderFsrGpuTest.cpp').read_text()
fixture = fixture[fixture.index('#include <vulkan/vulkan.h>'):]
fixture = fixture.replace('int main() {', native + '\nint main(int argc,char**argv) {')
fixture = fixture.replace('static Img makeImage(uint32_t w, uint32_t h, VkImageUsageFlags usage) {',
    'static Img makeImage(uint32_t w,uint32_t h,VkImageUsageFlags usage,VkFormat format=VK_FORMAT_R8G8B8A8_UNORM) {')
fixture = fixture.replace('ci.format = VK_FORMAT_R8G8B8A8_UNORM;', 'ci.format = format;')
old = between(fixture, '    const uint32_t TW = 8, TH = 4;', '\n    Buf up =')
new = r'''
    const uint32_t TW=128,TH=128;
    uint32_t input[32]; FILE* f=std::fopen(argv[1],"rb");
    if(!f || std::fread(input,sizeof(input),1,f)!=1) return 2;
    std::fclose(f);
    const bool doubleAlpha=argc>2 && std::strcmp(argv[2],"double-alpha")==0;
    if(doubleAlpha) {
        for(auto& c:input) {
            uint32_t a=c>>24,r=(c>>16)&255,g=(c>>8)&255,b=c&255;
            c=(a<<24)|(((r*a+127)/255)<<16)|(((g*a+127)/255)<<8)|((b*a+127)/255);
        }
    }
    VulkanRendererContext context;
    context.updateCursorImage(input,8,4,1,1);
    auto staged=context.upload();
    const uint32_t* texels=staged.data();
    const size_t uploadBytes=staged.size()*sizeof(uint32_t);
    const bool swap=argc>2 && std::strcmp(argv[2],"swap-rb")==0;
    const VkFormat cursorFormat=swap?VK_FORMAT_R8G8B8A8_UNORM:/* CURSOR_FORMAT */;
    Img tex = makeImage(TW,TH,VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT,cursorFormat);
'''.replace('/* CURSOR_FORMAT */', fmt)
fixture = fixture.replace(old, new).replace('sizeof(texels)', 'uploadBytes')
fixture = fixture.replace('    VkPipelineColorBlendAttachmentState cba{}; cba.colorWriteMask = 0xf;',
    '    const bool blend=true;\n' + blend)
fixture = fixture.replace('const uint32_t scales[] = {2, 1};', 'const uint32_t scales[] = {0, 1};')
fixture = fixture.replace('const uint32_t W = TW * s, H = TH * s;', 'const uint32_t W=8,H=4;')
fixture = fixture.replace('copy.imageExtent = {TW, TH, 1};', 'copy.imageExtent = {10,6,1};')
fixture = fixture.replace('VkClearValue clear{};', 'VkClearValue clear{}; for(int c=0;c<4;c++) clear.color.float32[c]=(float)s;')
fixture = fixture.replace('pc.effectId = 1;', 'pc.useTexAlpha=1; pc.effectId = 0;')
fixture = fixture.replace('pc.uvX1 = 1; pc.uvY1 = 1;', 'pc.uvX1 = 8.0f/TW; pc.uvY1 = 4.0f/TH;')
begin = fixture.index('        const float want =')
end = fixture.index('\n    }\n    if (failures)', begin)
fixture = fixture[:begin] + r'''
        const uint32_t wanted[2][4]={{0x80404040u,0xc0406080u,0xff808080u,0x00000000u},
                                    {0x80bfbfbfu,0xc07f9fbfu,0xff808080u,0x00ffffffu}};
        for(uint32_t i=0;i<W*H;i++) {
            uint32_t expected=wanted[s][i%4];
            std::printf("background %u pixel %u RGBA %08x expected %08x\n",s,i,px[i],expected);
            for(int shift=0;shift<32;shift+=8)
                if(std::abs(int((px[i]>>shift)&255)-int((expected>>shift)&255))>1) failures++;
            if(s==0 && scanoutCursorPixel(input[i])!=expected) failures++;
        }
        vkUnmapMemory(dev,out.mem);vkDestroyBuffer(dev,out.buf,nullptr);vkFreeMemory(dev,out.mem,nullptr);
        vkDestroyFramebuffer(dev,fb,nullptr);vkDestroyImageView(dev,target.view,nullptr);
        vkDestroyImage(dev,target.image,nullptr);vkFreeMemory(dev,target.mem,nullptr);
        VK_CHECK(vkResetCommandBuffer(cb,0));
''' + fixture[end:]
fixture = fixture.replace('FAILED %d rows: the FSR picture is shifted', 'FAILED %d cursor channels')
fixture = fixture.replace('PASS FSR keeps a hard edge in place at 2x and 1:1', 'PASS cursor colors over black and white, plus scanout premultiplication')
(out / 'CursorColorGpuTest.cpp').write_text(fixture)
for name in ('window.vert', 'window.frag'):
    symbol = name.replace('.', '_')
    subprocess.run(['glslangValidator', '-V', '--vn', symbol + '_code', '-x', '-o', str(out / (symbol + '.h')),
                    str(cpp / name)], check=True, capture_output=True)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / '.local/share/wow-build-tools/sdk')))
include = sdk / 'ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include'
exe = out / 'cursor-color'
subprocess.run(['g++', '-std=c++17', '-O1', '-g', '-pthread', '-no-pie', '-fsanitize=address,undefined',
                '-idirafter', str(include), '-I' + str(out), str(out / 'CursorColorGpuTest.cpp'),
                '-o', str(exe), '/lib/x86_64-linux-gnu/libvulkan.so.1'], check=True)
env = dict(os.environ, VK_ICD_FILENAMES='/usr/share/vulkan/icd.d/lvp_icd.json', ASAN_OPTIONS='detect_leaks=0')
results = []
for label in ('production', 'double-alpha', 'swap-rb'):
    q = subprocess.run([str(exe), str(pixels), *([] if label == 'production' else [label])],
                       capture_output=True, text=True, env=env, timeout=120)
    (out / (label + '.log')).write_text(q.stdout + q.stderr)
    results.append({'case': label, 'exit': q.returncode})
report = {'pixelsSha256': hashlib.sha256(pixels.read_bytes()).hexdigest(), 'cases': results,
          'productionMustPass': True, 'mutationsMustFail': True, 'deviceMeasured': False,
          'nativeSource': str(native_cpp)}
(out / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
assert results[0]['exit'] == 0 and all(x['exit'] == 1 for x in results[1:]), 'Cursor proof or its sensitivity control failed'
