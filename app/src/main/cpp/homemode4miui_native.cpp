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

#include "native_api.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <stdarg.h>
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
    __android_log_vprint(priority, kTag, format, ap);
    va_end(ap);
}

bool endsWith(const char *value, const char *suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t valueLen = strlen(value);
    const size_t suffixLen = strlen(suffix);
    if (suffixLen > valueLen) return false;
    return strcmp(value + valueLen - suffixLen, suffix) == 0;
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
                // Phase 1 verification aid: confirm the Dart snapshot symbol is
                // resolvable through the LSPosed-reported handle.
                void *snapshot = dlsym(handle, "_kDartIsolateSnapshotInstructions");
                logLine(ANDROID_LOG_WARN,
                        "poc dart image check: _kDartIsolateSnapshotInstructions=%p",
                        snapshot);
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

    if (launcher) {
        backfillProbe();
    }

    logLine(ANDROID_LOG_WARN,
            "poc native_init: accepted (observation-only, phase 1)");
    return onLibraryLoaded;
}

}  // extern "C"
