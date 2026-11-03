#include "../../app/src/main/cpp/winlator/PerformanceHintCycle.h"
#include <cassert>
static int creates, reports, updates, closes; static int64_t target, actual; static bool available=true;
static void* manager(){return available ? (void*)1 : nullptr;}
static void* create(void*,const int32_t* ids,size_t count,int64_t duration){assert(count==1 && ids[0]==123);creates++;target=duration;return (void*)2;}
static int update(void*,int64_t duration){updates++;target=duration;return 0;}
static int report(void*,int64_t duration){reports++;actual=duration;return 0;}
static void close(void*){closes++;}
int main(){
 PerformanceHintCycle::Api api{manager,create,update,report,close};
 {PerformanceHintCycle h(api);h.configure(false,123,16666667);assert(creates==0);h.configure(true,123,16666667);assert(creates==1 && h.active());h.report(2000000);assert(reports==1 && actual==2000000);h.configure(true,123,33333333);assert(updates==1 && target==33333333);h.configure(false,123,33333333);assert(closes==1 && !h.active());}
 assert(closes==1);available=false;{PerformanceHintCycle h(api);h.configure(true,123,16666667);h.configure(true,123,16666667);assert(!h.active() && creates==1);}
 {PerformanceHintCycle h({});h.configure(true,123,16666667);assert(!h.active());}
 {PerformanceHintCycle h(api);h.configure(true,0,16666667);h.configure(true,123,0);assert(!h.active());}
 return 0;
}
