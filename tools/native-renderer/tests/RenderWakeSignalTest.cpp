
#include <atomic>
#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <future>
#include <mutex>
#include <thread>
#include "RenderWakeSignal.h"
using namespace std::chrono_literals;
int main() {
 RenderWakeSignal signal;
 std::atomic<bool> ready{false},entered{false},published{false},first{true};
 auto waiter=std::async(std::launch::async,[&]{
  signal.wait([&]{
   bool observed=ready.load();
   if(first.exchange(false)){
    entered=true;
    while(!published.load())std::this_thread::yield();
    // Publisher has reached notify while predicate still holds wait's mutex.
    std::this_thread::sleep_for(20ms);
   }
   return observed;
  });
 });
 while(!entered.load())std::this_thread::yield();
 std::thread publisher([&]{ready=true;published=true;signal.notify();});
 bool woke=waiter.wait_for(200ms)==std::future_status::ready;
 // Rescue a legacy lost notification, so a failing test does not leak a thread.
 if(!woke)signal.notify();
 publisher.join();waiter.get();
 if(!woke){std::fputs("FAIL lost renderer wake between predicate and sleep\n",stderr);return 2;}
 // Publish/wait handshake repeatedly, including notifications before waiting.
 std::atomic<unsigned> requested{0},completed{0};std::atomic<bool> stopped{false};
 std::thread consumer([&]{for(;;){signal.wait([&]{return stopped||requested!=completed;});if(stopped)return;completed=requested.load();}});
 for(unsigned i=1;i<=20000;i++){requested=i;signal.notify();while(completed!=i)std::this_thread::yield();}
 stopped=true;signal.notify();consumer.join();
 {
  // Timed wait (used only while a Present hand-back is pending): gives up with false, returns true at once when the predicate holds,
  // and is woken early by notify().
  RenderWakeSignal timed;std::atomic<bool> flag{false};
  auto t0=std::chrono::steady_clock::now();
  bool r=timed.waitFor(30ms,[&]{return flag.load();});
  auto waited=std::chrono::steady_clock::now()-t0;
  if(r||waited<25ms){std::fputs("FAIL timed wait must give up after its timeout\n",stderr);return 3;}
  flag=true;
  if(!timed.waitFor(30ms,[&]{return flag.load();})){std::fputs("FAIL timed wait with a true predicate must return true\n",stderr);return 4;}
  flag=false;
  std::thread waker([&]{std::this_thread::sleep_for(10ms);flag=true;timed.notify();});
  t0=std::chrono::steady_clock::now();
  bool woke2=timed.waitFor(2000ms,[&]{return flag.load();});
  waited=std::chrono::steady_clock::now()-t0;waker.join();
  if(!woke2||waited>1000ms){std::fputs("FAIL timed wait must be woken early by notify\n",stderr);return 5;}
 }
 std::puts("PASS deterministic lost-wake race + 20000 handshakes + shutdown wake + timed wait");
}
