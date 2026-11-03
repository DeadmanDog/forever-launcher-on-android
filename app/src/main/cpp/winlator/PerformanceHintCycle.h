#pragma once
#include <cstdint>
#include <cstddef>

// Injected API permits host verification without an Android power HAL.
class PerformanceHintCycle {
public:
    struct Api {
        void* (*manager)() = nullptr;
        void* (*create)(void*, const int32_t*, size_t, int64_t) = nullptr;
        int (*update)(void*, int64_t) = nullptr;
        int (*report)(void*, int64_t) = nullptr;
        void (*close)(void*) = nullptr;
    };
    explicit PerformanceHintCycle(Api api) : api(api) {}
    ~PerformanceHintCycle() { reset(); }
    void configure(bool enabled, int32_t tid, int64_t target) {
        if (!enabled || tid <= 0 || target <= 0) { reset(); return; }
        if (!api.manager || !api.create || !api.update || !api.report || !api.close) return;
        if (!session && !attempted) {
            attempted = true;
            if (void* manager = api.manager()) session = api.create(manager, &tid, 1, target);
            duration = target;
        } else if (session && duration != target) {
            if (api.update(session, target) != 0) { reset(); attempted = true; return; }
            duration = target;
        }
    }
    bool active() const { return session != nullptr; }
    void report(int64_t actual) {
        if (session && actual > 0 && api.report(session, actual) != 0) { reset(); attempted = true; }
    }
private:
    void reset() { if (session) api.close(session); session = nullptr; attempted = false; }
    Api api;
    void* session = nullptr;
    int64_t duration = 0;
    bool attempted = false;
};
