// Phase 3: read-only observation hook on a libapp_launcher.so export.
// Goal: prove the full chain — dlsym resolution on the LSPosed-reported
// handle, hook_func over launcher code, trampoline stability, original-call
// forwarding — on a low-risk extern "C" target before touching gesture paths.
//
// Target: flutter_rust_bridge ARC increment extern "C" fn(*const c_void) —
// single-pointer ABI by FRB codegen convention, invoked on every opaque
// clone. Fallback: frb_free_wire_sync_rust2dart_dco (same 1-pointer shape).
// Installer is idempotent (libapp_launcher load callback repeats).
#pragma once

#include "native_api.h"

#include <dlfcn.h>

namespace h4m {

// One-shot: hook the first resolvable observation candidate. Returns true
// when a hook is installed. Safe to call on every libapp_launcher.so load
// callback.
bool InstallObservationHook(HookFunType hook_func, void *launcher_handle);

}  // namespace h4m
