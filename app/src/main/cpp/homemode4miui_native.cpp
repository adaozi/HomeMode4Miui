// HomeMode4Miui native entry for the HyperOS 4 Flutter launcher family.
//
// Route B PoC (phase 1): observation-only. No business hooks are installed.
//   * accept the HYOS process family (hyos_spawner + com.miui.home child);
//   * verify the LSPosed native hook backend is reachable;
//   * log identity decisions and interesting library loads at WARN level
//     (HyperOS logd filters INFO/DEBUG out of the launcher process).
//
// Process model (reverse-engineered, see docs/RE_HyperOS4_NATIVE_HOOK.md):
//   /system_ext/bin/hyos_spawner (root spawner, cmdline `usap64`)
//        | fork
//   com.miui.home (launcher child)
//        |- libapp.so            Dart AOT UI layer (no dex)
//        |- libapp_launcher.so   Rust core layer
//
// Identity approach mirrors WOMMO / HyperOS4SwipeGate: native_init runs first in
// the spawner; business hooks (later phases) must only act inside the launcher
// child once the target libraries are mapped.

#include "gesture_hook.h"
#include "h4m_log.h"
#include "hook_guard.h"
#include "native_api.h"
#include "obs_hook.h"

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <string.h>
#include <unistd.h>

namespace {

constexpr const char *kTag = "H4MNative";
constexpr const char *kLauncherProcessName = "com.miui.home";
constexpr const char *kSpawnerPath = "/system_ext/bin/hyos_spawner";

// Observation targets only — no hooks in this phase.
constexpr const char *kWatchLibraries[] = {
        "libapp.so",
        "libapp_launcher.so",
        "libhyper_os_flutter.so",
        "librust_maml_sdk.so",
};

bool readCmdline(char *out, size_t outSize) {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n;
    do {
        n = read(fd, out, outSize - 1u);
    } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return false;
    out[n] = '\0';
    return true;
}

bool isLauncherProcess() {
    char cmdline[64]{};
    if (!readCmdline(cmdline, sizeof(cmdline))) return false;
    return strcmp(cmdline, kLauncherProcessName) == 0;
}

bool isHyosSpawnerProcessFamily() {
    char exe[128]{};
    const ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1u);
    if (n <= 0 || static_cast<size_t>(n) >= sizeof(exe)) return false;
    exe[n] = '\0';
    return strcmp(exe, kSpawnerPath) == 0;
}

void logLine(int priority, const char *format, ...) __attribute__((format(printf, 2, 3)));

void logLine(int priority, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    h4m_log::logv(priority, format, ap);  // logcat + durable file mirror
    va_end(ap);
}

bool endsWith(const char *value, const char *suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t valueLen = strlen(value);
    const size_t suffixLen = strlen(suffix);
    if (suffixLen > valueLen) return false;
    return strcmp(value + valueLen - suffixLen, suffix) == 0;
}

// ---- Phase 2: madvise guard probe (one-shot per process) ----

const NativeAPIEntries *g_entries = nullptr;
uint32_t g_phase2_state = 0u;  // 0 = pending, 1 = done

// Called from native_init backfill (launcher, libs mapped) or the first
// libapp.so load callback — whichever comes first. Phase 1's callbacks repeat
// (libapp.so fired 4x on device), so the one-shot guard is mandatory.
void RunPhase2Probe() {
    if (__atomic_exchange_n(&g_phase2_state, 1u, __ATOMIC_ACQ_REL) != 0u) {
        return;
    }
    if (!h4m::EnsureMadviseGuard(g_entries)) {
        logLine(ANDROID_LOG_ERROR, "phase2: madvise guard unavailable — aborting probe");
        return;
    }
    // Guard must be in place BEFORE any protected write; the self test
    // exercises the splitting logic end-to-end through the real madvise PLT.
    h4m::RunGuardSelfTest();
}

// Backfill probe: log which observation targets are already mapped when
// native_init runs in the launcher child (hot reload / late init scenario).
void backfillProbe() {
    bool foundAny = false;
    dl_iterate_phdr(
            [](struct dl_phdr_info *info, size_t, void *opaque) -> int {
                auto *found = static_cast<bool *>(opaque);
                if (info == nullptr || info->dlpi_name == nullptr) return 0;
                for (const char *watch : kWatchLibraries) {
                    if (endsWith(info->dlpi_name, watch)) {
                        logLine(ANDROID_LOG_WARN,
                                "poc backfill: %s mapped base=%p phnum=%u",
                                info->dlpi_name,
                                reinterpret_cast<void *>(info->dlpi_addr),
                                static_cast<unsigned>(info->dlpi_phnum));
                        *found = true;
                    }
                }
                return 0;
            },
            &foundAny);
    if (!foundAny) {
        logLine(ANDROID_LOG_WARN, "poc backfill: no watch libraries mapped yet");
    }
}

void onLibraryLoaded(const char *name, void *handle) {
    if (name == nullptr || handle == nullptr) return;
    if (!isLauncherProcess()) return;
    for (const char *watch : kWatchLibraries) {
        if (endsWith(name, watch)) {
            logLine(ANDROID_LOG_WARN, "poc library loaded: %s handle=%p", name,
                    handle);
            if (endsWith(name, "libapp.so")) {
                // Phase 2: install the madvise guard and run its self test
                // once, on the first Dart image load.
                RunPhase2Probe();
            }
            if (endsWith(name, "libapp_launcher.so")) {
                // Phase 3: read-only observation hook on a Rust export
                // (idempotent; the libapp_launcher callback also repeats).
                if (g_entries != nullptr) {
                    h4m::InstallObservationHook(g_entries->hook_func, handle);
                }
                // Phase 4a: read-only observation hook on on_swipe_process
                // (symbol-vaddr + exact fingerprint, fail closed on mismatch).
                if (g_entries != nullptr) {
                    h4m::InstallGestureObservationHook(g_entries->hook_func);
                }
            }
        }
    }
}

}  // namespace

extern "C" {

// LSPosed native_init entry point (declared in META-INF/xposed/native_init.list).
__attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    const bool entriesReady = entries != nullptr;
    const bool hookReady = entriesReady && entries->hook_func != nullptr;
    const bool launcher = isLauncherProcess();
    const bool spawner = isHyosSpawnerProcessFamily();

    logLine(ANDROID_LOG_WARN,
            "poc native_init: entries=%d hook_func=%d api=%u launcher=%d spawner=%d",
            entriesReady ? 1 : 0, hookReady ? 1 : 0,
            entriesReady ? entries->version : 0u, launcher ? 1 : 0,
            spawner ? 1 : 0);

    if (!entriesReady) return nullptr;
    if (!launcher && !spawner) {
        logLine(ANDROID_LOG_WARN, "poc native_init: rejected non-HYOS process");
        return nullptr;
    }

    g_entries = entries;

    if (launcher) {
        backfillProbe();
        // Launcher child with libapp.so already mapped (hot reload / late
        // init): run the phase 2 probe immediately; the load callback would
        // not fire again for an already-loaded library.
        void *dart_handle = dlopen("libapp.so", RTLD_NOLOAD);
        if (dart_handle != nullptr) {
            logLine(ANDROID_LOG_WARN,
                    "poc native_init: libapp.so already mapped, phase2 now");
            RunPhase2Probe();
        }
    }

    logLine(ANDROID_LOG_WARN,
            "poc native_init: accepted (observation-only, phase 1)");
    return onLibraryLoaded;
}

}  // extern "C"
