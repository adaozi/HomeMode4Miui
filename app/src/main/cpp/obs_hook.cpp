// Phase 3 read-only observation hook. See obs_hook.h for the contract.

#include "obs_hook.h"

#include "h4m_log.h"

#include <stdint.h>
#include <string.h>

namespace h4m {
namespace {

constexpr const char *kCandidates[] = {
        // FRB ARC increment, extern "C" fn(*const c_void) — single-pointer
        // ABI by codegen convention, called on every Uri opaque clone.
        "frbgen_hyper_launcher_libraries_rust_arc_increment_strong_count_"
        "RustOpaque_flutter_rust_bridgefor_generatedRustAutoOpaqueInnerUri",
        // FRB wire free, same 1-pointer shape.
        "frb_free_wire_sync_rust2dart_dco",
};

// 0 = pending, 1 = installed.
uint32_t g_installed = 0u;
void *g_original = nullptr;
uint64_t g_call_count = 0u;

using TargetFn = void (*)(void *);

// Plain C replacement for the 1-pointer extern "C" target: count, log
// sparsely, forward verbatim. No Dart ABI involvement (Rust export).
void obs_trampoline(void *opaque) {
    const uint64_t n = __atomic_add_fetch(&g_call_count, 1u, __ATOMIC_ACQ_REL);
    if (n == 1u || (n % 256u) == 0u) {
        h4m_log::log(ANDROID_LOG_WARN, "phase3: observation hook call #%llu",
                     static_cast<unsigned long long>(n));
    }
    const TargetFn original =
            reinterpret_cast<TargetFn>(__atomic_load_n(&g_original,
                                                       __ATOMIC_ACQUIRE));
    if (original != nullptr) {
        original(opaque);
    } else {
        // Tiny race window between hook_func returning and backup store.
        // Dropping an ARC increment leaks one reference — acceptable for a
        // read-only observation phase; logged so it is never silent.
        h4m_log::log(ANDROID_LOG_ERROR, "phase3: original not ready, call dropped");
    }
}

}  // namespace

bool InstallObservationHook(HookFunType hook_func, void *launcher_handle) {
    if (hook_func == nullptr || launcher_handle == nullptr) return false;
    if (__atomic_exchange_n(&g_installed, 1u, __ATOMIC_ACQ_REL) != 0u) {
        return true;  // already installed on a previous callback
    }

    for (const char *name : kCandidates) {
        void *sym = dlsym(launcher_handle, name);
        if (sym == nullptr) {
            h4m_log::log(ANDROID_LOG_WARN,
                         "phase3: candidate not exported: %s", name);
            continue;
        }
        void *backup = nullptr;
        if (hook_func(sym, reinterpret_cast<void *>(obs_trampoline),
                      &backup) != 0 ||
                backup == nullptr) {
            h4m_log::log(ANDROID_LOG_ERROR,
                         "phase3: hook_func failed on %s target=%p", name, sym);
            continue;
        }
        g_original = backup;
        h4m_log::log(ANDROID_LOG_WARN,
                     "phase3: observation hook installed on %s target=%p "
                     "backup=%p",
                     name, sym, backup);
        return true;
    }
    __atomic_store_n(&g_installed, 0u, __ATOMIC_RELEASE);
    h4m_log::log(ANDROID_LOG_ERROR, "phase3: no observation candidate resolved");
    return false;
}

}  // namespace h4m
