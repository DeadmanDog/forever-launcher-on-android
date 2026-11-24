#!/usr/bin/env python3
"""Exercise the actual cursor snapshot/upload body with cursor hidden then visible."""
from pathlib import Path
import argparse, json, os, subprocess
p=argparse.ArgumentParser();p.add_argument('--source',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
r=Path(__file__).resolve().parents[3];s=(a.source or r/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
begin=s.index('        if (',s.index('        for (const auto& entry:renderList) appendWindowDraw(entry);',s.index('void VulkanRendererContext::renderFrame()')))
end=s.index('\n    }',begin)
body=s[begin:end]
cpp=r"""
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <vector>
#define RLOG_E(...)
using VkBuffer=uint64_t; using VkDeviceSize=uint64_t;
constexpr uint64_t VK_NULL_HANDLE=0;
struct Slot {VkBuffer buffer=1; void* mapped=nullptr;};
struct Context {
 std::atomic<bool> isCursorImageDirty{true};uint64_t cursorImg=1;short cursorW=32,cursorH=32,cursorTexW=64,cursorTexH=64;
 short kCursorEdgePad=2;std::vector<uint32_t>cursorPixels=std::vector<uint32_t>(32*32,0x80808080);
 Slot cursorStaging[1];int currentFrame=0;std::atomic<uint64_t>cursorStageGen{1};uint64_t cursorSnapshotGen=0;
 unsigned allocations=0,uploads=0;std::vector<uint32_t>storage;
 void ensureCursorStaging(size_t bytes){++allocations;storage.resize(bytes/4);cursorStaging[0].mapped=storage.data();}
 bool render(bool curVis){short curUpW=0,curUpH=0;VkBuffer curUpload=0;bool hasCurUpload=false;
 /* BODY */
 if(hasCurUpload){++uploads;isCursorImageDirty=false;}
 return hasCurUpload;
 }
};
static void check(bool ok,const char* msg){if(!ok){fprintf(stderr,"FAIL %s\n",msg);exit(2);}}
int main(){Context c;
 for(int n=0;n<120;n++) c.render(false);
 printf("hidden cursor: frames=120 uploads=%u staging_calls=%u\n",c.uploads,c.allocations);
 check(c.uploads==0 && c.allocations==0,"hidden cursor issued unused staging upload");
 check(c.isCursorImageDirty,"hidden cursor lost pending image");
 // A newer cursor while hidden must replace the older one, not be lost on reveal.
 c.cursorPixels.assign(32*32,0xff987654);c.cursorStageGen=2;
 check(c.render(true),"first visible frame uploads pending image (no blank cursor)");
 check(c.storage.front()==0xff987654 && c.storage.back()==0xff987654,"visible pixels and padded edges are current");
 check(c.cursorSnapshotGen==2,"visible snapshot records newest generation");
 check(!c.render(true),"unchanged visible image uploads only once");
 check(c.uploads==1,"exactly one reveal upload");
 puts("PASS hidden cursor and reveal correctness");}
"""
a.out.mkdir(parents=True,exist_ok=False);f=a.out/'hidden_cursor.cpp';f.write_text(cpp.replace('/* BODY */',body));exe=a.out/'hidden_cursor'
subprocess.run(['g++','-std=c++17','-O1','-g','-fsanitize=address,undefined','-no-pie',str(f),'-o',str(exe)],check=True)
z=subprocess.run([str(exe)],text=True,capture_output=True);report={'exit':z.returncode,'output':z.stdout+z.stderr};(a.out/'result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));raise SystemExit(z.returncode)
