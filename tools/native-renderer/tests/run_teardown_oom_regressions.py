#!/usr/bin/env python3
"""Run real teardown and texture retirement bodies under global allocation failure."""
from pathlib import Path
import argparse,subprocess,json,os
p=argparse.ArgumentParser();p.add_argument('--source',type=Path);p.add_argument('--out',type=Path,required=True);a=p.parse_args();r=Path(__file__).resolve().parents[3]
s=(a.source or r/'app/src/main/cpp/winlator/VulkanRendererContext.cpp').read_text()
def method(marker):
 start=s.index(marker);i=s.index('{',start)+1;depth=1
 while depth:depth+=(s[i]=='{')-(s[i]=='}');i+=1
 return s[start:i]
body='\n'.join(method(m) for m in ['void VulkanRendererContext::teardown(', 'void VulkanRendererContext::destroyWinTex(', 'void VulkanRendererContext::freeRetiredTextures('])
cpp=r"""
#include <vector>
#include <map>
#include <mutex>
#include <thread>
#include <memory>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <new>
static bool failNew=false;
void* operator new(size_t n){if(failNew)throw std::bad_alloc();if(void*p=malloc(n?n:1))return p;throw std::bad_alloc();}
void operator delete(void*p)noexcept{free(p);}void operator delete(void*p,size_t)noexcept{free(p);}
#define RLOG_W(...)
constexpr int VK_NULL_HANDLE=0;constexpr unsigned MAX_FRAMES_IN_FLIGHT=2;
static int releasedAhb=0,releasedWindow=0,closedDriver=0;
struct AHardwareBuffer{};void AHardwareBuffer_release(AHardwareBuffer*){releasedAhb++;}
void ANativeWindow_release(void*){releasedWindow++;}void dlclose(void*){closedDriver++;}
struct VulkanRendererContext{
 struct Upload{int buffer=0,memory=0;};
 struct WinTex{int ds=0,view=0,img=0,mem=0,stg=0,stgMem=0;bool isAHB=false;long idleToken=0;Upload uploads[2];std::shared_ptr<std::vector<unsigned char>>pixels;};
 struct Vk{int imageCount=0,bufferCount=0,memoryCount=0,descriptorCount=0;
 int(*DeviceWaitIdle)(int)=[](int){return 0;};
 void DestroyImage(int,int h,void*){if(h)imageCount++;}void DestroyImageView(int,int,void*){}
 void DestroyBuffer(int,int h,void*){if(h)bufferCount++;}void FreeMemory(int,int h,void*){if(h)memoryCount++;}
 void FreeDescriptorSets(int,int,int,int*h){if(*h)descriptorCount++;}
 void DestroySampler(int,int,void*){}void DestroyDescriptorPool(int,int,void*){}void DestroyPipeline(int,int,void*){}
 void DestroyPipelineLayout(int,int,void*){}void DestroyDescriptorSetLayout(int,int,void*){}void DestroySemaphore(int,int,void*){}
 void DestroyFence(int,int,void*){}void DestroyCommandPool(int,int,void*){}void DestroyRenderPass(int,int,void*){}
 void DestroyDevice(int,void*){}void DestroySurfaceKHR(int,int,void*){}void DestroyInstance(int,void*){}
 }vk_;
 struct Wake{void notify(){}}renderWake;struct Waiter{void stop(){}};std::shared_ptr<Waiter>scanoutReleaser;
 bool isRunning=false;std::thread renderThread;std::mutex renderMutex;
 int device=1,instance=2,surface=3,sampler=0,winTexPool=0,pipeline=0,opaquePipeline=0,pipeLayout=0,dsLayout=0,cmdPool=0,renderPass=0,renderPassNoClear=0;
 void*window=(void*)1;void*adrenotoolsHandle=(void*)2;
 std::map<long,WinTex>texMap;std::map<AHardwareBuffer*,WinTex>ahbImportCache;
 std::vector<WinTex>deleteQueue,pendingRetirements;std::vector<WinTex>submittedRetirements[2];std::vector<long>idleReleased;
 std::vector<int>imgAvailSems{0,0},inFlightFences{0,0};
 void teardown(bool);void destroyWinTex(WinTex&);void freeRetiredTextures(std::vector<WinTex>&);
 void cleanupSwapchain(){}void cleanupCursorTex(){}void destroyScanout(){}
};
/* PRODUCTION */
static void check(bool ok,const char*m){if(!ok){fprintf(stderr,"FAIL %s\n",m);exit(2);}}
int main(int,char**argv){int n=atoi(argv[1]);VulkanRendererContext c;AHardwareBuffer ahb;
 VulkanRendererContext::WinTex cpu;cpu.img=11;cpu.mem=12;cpu.ds=13;cpu.view=14;cpu.uploads[0]={15,16};
 VulkanRendererContext::WinTex imported;imported.img=21;imported.mem=22;imported.ds=23;imported.view=24;imported.isAHB=true;
 if(n==0||n==2)c.texMap.emplace(1,cpu);
 if(n==1||n==2){c.ahbImportCache.emplace(&ahb,imported);c.texMap.emplace(2,imported);}
 if(n==2){auto retired=cpu;retired.img=31;retired.uploads[0]={0,0};c.deleteQueue.push_back(retired);}
 failNew=true;bool threw=false;try{c.teardown(true);}catch(...){threw=true;}failNew=false;
 check(!threw,"teardown allocated and threw under OOM (destructor would terminate process)");
 const int expected=n==2?3:1;check(c.vk_.imageCount==expected,"all uniquely owned images freed exactly once");
 check(c.texMap.empty()&&c.ahbImportCache.empty()&&c.deleteQueue.empty(),"owners/retirements cleared");
 check(releasedAhb==(n==0?0:1),"AHB reference released exactly once");
 check(releasedWindow==1&&closedDriver==1,"external window and driver released");
 check(c.device==0&&c.instance==0,"renderer fully closed");
 printf("PASS teardown OOM case%d images=%d\n",n,c.vk_.imageCount);
}
"""
a.out.mkdir(parents=True,exist_ok=False);f=a.out/'teardown.cpp';f.write_text(cpp.replace('/* PRODUCTION */',body));exe=a.out/'teardown'
subprocess.run(['g++','-std=c++17','-O1','-g','-pthread',str(f),'-o',str(exe)],check=True)
results=[]
for n in range(3):
 z=subprocess.run([str(exe),str(n)],capture_output=True,text=True,timeout=10);results.append({'case':n,'exit':z.returncode,'output':z.stdout+z.stderr})
(a.out/'result.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2));raise SystemExit(any(z['exit'] for z in results))
