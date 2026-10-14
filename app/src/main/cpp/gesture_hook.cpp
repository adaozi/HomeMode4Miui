// Phase 4a implementation. See gesture_hook.h.
// Fingerprint = first 24 bytes of GestureInputBackHelper::on_swipe_process in
// the RELEASE-8.01.02.7748 build, extracted offline from the APK's
// libapp_launcher.so (.gnu_debugdata symbol on_swipe_process, vaddr
// 0x702690). The bytes are byte-identical to SwipeGate's verified
// `gesture-frame-v2` fingerprint — two independent confirmations of the
// target. Fingerprint is a behavior contract, not a version whitelist; the
// hook installs only on an exact match and fails closed otherwise.

#include "gesture_hook.h"

#include "h4m_log.h"

#include "native_api.h"

#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

extern "C" {
// Defined in gesture_hook.S.
void gesture_on_swipe_hook();
// Defined below (file scope, unmangled — referenced by the .S).
void gesture_obs_enter(uint32_t ready_finish, uint32_t side, float dist);
void *g_gesture_original = nullptr;
}

namespace h4m {
namespace {

constexpr uintptr_t kOnSwipeProcessVaddr = 0x702690;
constexpr size_t kFingerprintLen = 24;

// gesture-frame-v2 (verified on 7748; byte-identical to SwipeGate's).
constexpr uint8_t kFingerprint[kFingerprintLen] = {
        0xff, 0x83, 0x04, 0xd1, 0xeb, 0x2b, 0x0a, 0x6d,
        0xe9, 0x23, 0x0b, 0x6d, 0xfd, 0x7b, 0x0c, 0xa9,
        0xfc, 0x6b, 0x00, 0xf9, 0xfa, 0x67, 0x0e, 0xa9,
};

// 0 = pending, 1 = installed, 2 = failed.
uint32_t g_gesture_state = 0u;
uint64_t g_call_count = 0u;
int64_t g_last_log_ms = 0;
uint32_t g_last_ready = 0;

int64_t monotonicMs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

int findLauncherBaseCallback(dl_phdr_info *info, size_t, void *opaque) {
    auto *base = static_cast<uintptr_t *>(opaque);
    if (info == nullptr || info->dlpi_name == nullptr) return 0;
    if (strstr(info->dlpi_name, "libapp_launcher.so") == nullptr) return 0;
    *base = static_cast<uintptr_t>(info->dlpi_addr);
    return 1;  // stop at first match
}

}  // namespace

}  // namespace h4m

// Called from the asm trampoline on every on_swipe_process invocation.
// Read-only: counts calls, logs the first call, the Ready edge and a sparse
// periodic summary. Never modifies the gesture state. Unmangled definition —
// the .S references the hidden C symbol.
extern "C" void gesture_obs_enter(uint32_t ready_finish, uint32_t side,
                                  float dist) {
    const uint64_t n = __atomic_add_fetch(&h4m::g_call_count, 1u,
                                          __ATOMIC_ACQ_REL);
    const int64_t now = h4m::monotonicMs();
    const bool ready_edge = ready_finish != 0u && h4m::g_last_ready == 0u;
    __atomic_store_n(&h4m::g_last_ready, ready_finish, __ATOMIC_RELAXED);
    if (n == 1u) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "phase4a: on_swipe_process call #1 ready=%u side=%u "
                     "dist=%.2f",
                     ready_finish, side, static_cast<double>(dist));
        h4m::g_last_log_ms = now;
        return;
    }
    if (ready_edge) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "phase4a: Ready edge #%lld ready=%u side=%u dist=%.2f",
                     static_cast<long long>(n), ready_finish, side,
                     static_cast<double>(dist));
        h4m::g_last_log_ms = now;
        return;
    }
    if (now - h4m::g_last_log_ms >= 5000) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "phase4a: alive, total calls=%llu (5s window)",
                     static_cast<unsigned long long>(n));
        h4m::g_last_log_ms = now;
    }
}

namespace h4m {

bool InstallGestureObservationHook(HookFunType hook_func) {
    if (hook_func == nullptr) return false;
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(&g_gesture_state, &expected, 1u, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return __atomic_load_n(&g_gesture_state, __ATOMIC_ACQUIRE) == 1u;
    }

    uintptr_t base = 0;
    dl_iterate_phdr(findLauncherBaseCallback, &base);
    if (base == 0) {
        __atomic_store_n(&g_gesture_state, 2u, __ATOMIC_RELEASE);
        h4m_log::log(ANDROID_LOG_ERROR,
                     "phase4a: libapp_launcher.so base not found");
        return false;
    }
    const uintptr_t target = base + kOnSwipeProcessVaddr;

    uint8_t head[kFingerprintLen];
    memcpy(head, reinterpret_cast<const void *>(target), sizeof(head));
    if (memcmp(head, kFingerprint, kFingerprintLen) != 0) {
        __atomic_store_n(&g_gesture_state, 2u, __ATOMIC_RELEASE);
        h4m_log::log(ANDROID_LOG_ERROR,
                     "phase4a: fingerprint mismatch at base+0x%llx — fail "
                     "closed, actual head=%02x%02x%02x%02x %02x%02x%02x%02x",
                     static_cast<unsigned long long>(kOnSwipeProcessVaddr),
                     head[0], head[1], head[2], head[3], head[4], head[5],
                     head[6], head[7]);
        return false;
    }

    void *backup = nullptr;
    if (hook_func(reinterpret_cast<void *>(target),
                  reinterpret_cast<void *>(gesture_on_swipe_hook),
                  &backup) != 0 ||
            backup == nullptr) {
        __atomic_store_n(&g_gesture_state, 2u, __ATOMIC_RELEASE);
        h4m_log::log(ANDROID_LOG_ERROR,
                     "phase4a: hook_func failed target=%p",
                     reinterpret_cast<void *>(target));
        return false;
    }
    g_gesture_original = backup;
    h4m_log::log(ANDROID_LOG_WARN,
                 "phase4a: gesture observation hook installed target=%p "
                 "backup=%p (read-only, fail-closed fingerprint ok)",
                 reinterpret_cast<void *>(target), backup);
    return true;
}

}  // namespace h4m
