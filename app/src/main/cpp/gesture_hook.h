// Phase 4a: read-only observation hook on GestureInputBackHelper::on_swipe_process
// (libapp_launcher.so, Rust core). See gesture_hook.h.

#pragma once

#include "native_api.h"

namespace h4m {

// One-shot. Resolves the launcher base, verifies the exact instruction
// fingerprint at base + kOnSwipeProcessVaddr (fail closed on mismatch or
// ambiguity), and installs the ABI-transparent observation hook.
// Safe to call on every libapp_launcher.so load callback.
bool InstallGestureObservationHook(HookFunType hook_func);

}  // namespace h4m
