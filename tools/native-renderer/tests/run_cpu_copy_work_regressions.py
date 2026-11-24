#!/usr/bin/env python3
"""Check pixel/stride correctness and count memcpy dispatches in the actual CPU update body.
Benchmark current and archived sources with identical host flags, on the same machine.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument("--source", type=Path)
p.add_argument("--out", type=Path, required=True)
p.add_argument("--baseline", action="store_true")
a = p.parse_args()
root = Path(__file__).resolve().parents[3]
source = a.source or root / "app/src/main/cpp/winlator/VulkanRendererContext.cpp"
s = source.read_text()
begin = s.index("        auto* source=static_cast<const uint8_t*>(px);", s.index("void VulkanRendererContext::updateWindowContent("))
end = s.index("        ++wt.revision;", begin)
body = s[begin:end]
cpp = r"""
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>
static unsigned long calls = 0;
static void* countedCopy(void* dst, const void* src, size_t size) {
    ++calls;
    return std::memcpy(dst, src, size);
}
struct Texture { std::shared_ptr<std::vector<uint8_t>> pixels; };
static void copyPixels(Texture& wt, const void* px, int w, int h, int stride) {
    const size_t dstPitch=(size_t)w*4;
    const size_t srcPitch=(size_t)(stride>0?stride:w)*4;
#define memcpy countedCopy
/* BODY */
#undef memcpy
}
int main(int argc, char** argv) {
    int width=1344, height=608;
    if(argc>1) { width=std::atoi(argv[1]); height=std::atoi(argv[2]); }
    Texture wt{std::make_shared<std::vector<uint8_t>>((size_t)width*height*4)};
    bool ok=true;
    for (int padding : {0, 7}) {
        int stride=width+padding;
        std::vector<uint8_t> source((size_t)stride*height*4);
        for(size_t i=0;i<source.size();++i)source[i]=(uint8_t)(i*37);
        calls=0;
        copyPixels(wt,source.data(),width,height,stride);
        for(int y=0;y<height;++y)
            if(std::memcmp(wt.pixels->data()+(size_t)y*width*4,source.data()+(size_t)y*stride*4,(size_t)width*4))
                return 3;
        printf("%dx%d padding=%d memcpy_calls=%lu pixels=PASS\n",width,height,padding,calls);
        if(padding==0 && calls!=1)ok=false;
        if(padding!=0 && calls!=(unsigned)height)ok=false;
    }
    std::vector<uint8_t> source((size_t)width*height*4,42);
    std::vector<double> samples;
    volatile unsigned checksum=0;
    for(int round=0;round<7;++round){
        auto begin=std::chrono::steady_clock::now();
        for(int n=0;n<300;++n){
            copyPixels(wt,source.data(),width,height,width);
            checksum+=(*wt.pixels)[n%wt.pixels->size()];
        }
        auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count();
        samples.push_back(ns/300.0);
    }
    std::sort(samples.begin(),samples.end());
    printf("copy_median_ns=%.0f checksum=%u\n",samples[3],checksum);
    return ok?0:2;
}
"""
a.out.mkdir(parents=True, exist_ok=False)
file = a.out / "copy.cpp"
file.write_text(cpp.replace("/* BODY */",body))
exe = a.out / "copy"
subprocess.run(["g++","-std=c++17","-O3","-fno-builtin-memcpy",str(file),"-o",str(exe)],check=True)
results=[]
for w,h in ((1344,608),(1792,810),(2688,1216)):
    r=subprocess.run([str(exe),str(w),str(h)],capture_output=True,text=True,timeout=20)
    results.append(dict(width=w,height=h,exit=r.returncode,output=r.stdout+r.stderr))
print(json.dumps(results,indent=2))
(a.out/"results.json").write_text(json.dumps(results,indent=2)+"\n")
if not a.baseline:
    assert all(r["exit"]==0 for r in results)
