// LSPosed native API surface (Modern API 102, native_init).
// Structure definitions adapted from HyperOS4SwipeGate (MIT, github.com/PzHown/HyperOS4SwipeGate),
// which mirrors the LSPosed native module ABI.
#pragma once

#include <stdint.h>

typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);
