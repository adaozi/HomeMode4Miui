// madvise(MADV_DONTNEED) guard + LSPosed hookFunc wrapper for HomeMode4Miui.
// Mechanism adapted from WOMMO (GPL-3.0, github.com/YifePlayte/WOMMO,
// lsposed_hook_backend.cpp): HyperOS memory cleanup issues
// madvise(MADV_DONTNEED) over the launcher image, which drops modified
// copy-on-write code pages and silently reverts every patch. The guard hooks
// libc madvise and withholds registered hook pages from DONTNEED ranges.
// TIMING CONTRACT: ProtectHookRange MUST be called before any byte of the
// range is modified; a range that cannot be protected must not be patched.
#pragma once

#include "native_api.h"

#include <stddef.h>
#include <stdint.h>

namespace h4m {

// One-shot install of the madvise guard (state-machine guarded, idempotent
// across repeated library-load callbacks). `entries` must be the pointer
// received in native_init. Returns true when the guard is installed.
bool EnsureMadviseGuard(const NativeAPIEntries *entries);

// Register [address, address+size) pages as protected (before patching).
bool ProtectHookRange(uintptr_t address, size_t size);

// Read process memory through /proc/self/mem (works even for hooked text).
bool ReadMem(uintptr_t address, void *out, size_t size);

// Address of the hooked madvise symbol (for trampoline diagnostics), 0 if the
// guard is not installed.
uintptr_t MadviseTarget();

// End-to-end self test of the guard splitting logic:
//   mmap two anonymous pages, fill with a pattern, register page 0 only,
//   madvise(page0, 2 pages, MADV_DONTNEED) — must route through the guard:
//   page 0 content survives, page 1 (control) is zeroed by the kernel.
// Returns true when both conditions hold. Consumes one protected-page slot.
bool RunGuardSelfTest();

}  // namespace h4m
