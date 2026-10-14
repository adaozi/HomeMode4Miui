// madvise guard implementation. See hook_guard.h for the contract.
// Mechanism adapted from WOMMO (GPL-3.0, github.com/YifePlayte/WOMMO).

#include "hook_guard.h"

#include "h4m_log.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <sched.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define H4M_LOGW(...) h4m_log::log(ANDROID_LOG_WARN, __VA_ARGS__)
#define H4M_LOGE(...) h4m_log::log(ANDROID_LOG_ERROR, __VA_ARGS__)

namespace h4m {
namespace {

using MadviseFn = int (*)(void *, size_t, int);

constexpr size_t kMaxProtectedPages = 32u;

// 0 = idle, 1 = installing, 2 = installed, 3 = failed.
uint32_t g_guard_state = 0u;
MadviseFn g_original_madvise = nullptr;
uintptr_t g_madvise_target = 0u;
uintptr_t g_protected_pages[kMaxProtectedPages]{};
size_t g_protected_page_count = 0u;
uint32_t g_protected_page_lock = 0u;

size_t PageSize() {
    static size_t value = 0u;
    if (value == 0u) {
        const long queried = sysconf(_SC_PAGESIZE);
        value = queried > 0 ? static_cast<size_t>(queried) : size_t{4096};
    }
    return value;
}

uintptr_t PageStart(uintptr_t address) {
    return address & ~(static_cast<uintptr_t>(PageSize()) - 1u);
}

bool AddProtectedPage(uintptr_t address) {
    const uintptr_t page = PageStart(address);
    while (__atomic_exchange_n(&g_protected_page_lock, 1u, __ATOMIC_ACQUIRE) !=
            0u) {
        sched_yield();
    }
    const size_t count =
            __atomic_load_n(&g_protected_page_count, __ATOMIC_RELAXED);
    for (size_t index = 0u; index < count; ++index) {
        if (g_protected_pages[index] == page) {
            __atomic_store_n(&g_protected_page_lock, 0u, __ATOMIC_RELEASE);
            return true;
        }
    }
    if (count >= kMaxProtectedPages) {
        __atomic_store_n(&g_protected_page_lock, 0u, __ATOMIC_RELEASE);
        H4M_LOGE("guard: protected page table exhausted");
        return false;
    }
    g_protected_pages[count] = page;
    __atomic_store_n(&g_protected_page_count, count + 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&g_protected_page_lock, 0u, __ATOMIC_RELEASE);
    return true;
}

// libc madvise replacement: withholds registered pages from MADV_DONTNEED
// ranges by splitting the request around them; everything else passes through
// untouched (including non-DONTNEED advice and misaligned requests).
int GuardedMadvise(void *address, size_t length, int advice) {
    // Plain read is safe: g_original_madvise is written exactly once inside
    // the install state machine before the state flips to 2 (installed).
    MadviseFn original = g_original_madvise;
    if (original == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    if (advice != MADV_DONTNEED || length == 0u) {
        return original(address, length, advice);
    }
    const uintptr_t begin = reinterpret_cast<uintptr_t>(address);
    const size_t ps = PageSize();
    if (begin % ps != 0u || length > UINTPTR_MAX - begin) {
        return original(address, length, advice);
    }
    const uintptr_t end = begin + length;
    uintptr_t cursor = begin;
    bool protected_page_found = false;
    while (cursor < end) {
        uintptr_t next_page = end;
        const size_t count =
                __atomic_load_n(&g_protected_page_count, __ATOMIC_ACQUIRE);
        for (size_t index = 0u; index < count; ++index) {
            const uintptr_t page = g_protected_pages[index];
            if (page >= cursor && page < end && page < next_page) {
                next_page = page;
            }
        }
        if (next_page == end) break;
        if (next_page > cursor &&
                original(reinterpret_cast<void *>(cursor),
                         next_page - cursor, advice) != 0) {
            return -1;
        }
        cursor = next_page + ps;
        if (cursor > end) cursor = end;
        protected_page_found = true;
    }
    if (!protected_page_found) {
        return original(address, length, advice);
    }
    H4M_LOGW("guard: preserved registered hook pages in %p+0x%zx", address,
             length);
    if (cursor < end &&
            original(reinterpret_cast<void *>(cursor), end - cursor, advice) !=
                    0) {
        return -1;
    }
    return 0;
}

bool InstallMadviseHook(const NativeAPIEntries *entries) {
    void *target = dlsym(RTLD_DEFAULT, "madvise");
    if (target == nullptr) {
        H4M_LOGE("guard: madvise symbol not found");
        return false;
    }
    void *backup = nullptr;
    if (entries->hook_func(target, reinterpret_cast<void *>(GuardedMadvise),
                           &backup) != 0 ||
            backup == nullptr) {
        H4M_LOGE("guard: hook_func failed on madvise target=%p", target);
        return false;
    }
    g_madvise_target = reinterpret_cast<uintptr_t>(target);
    // Plain store is safe: writes happen only inside the one-shot install
    // state machine, and readers see non-null only after state becomes 2.
    g_original_madvise = reinterpret_cast<MadviseFn>(backup);
    return true;
}

}  // namespace

bool EnsureMadviseGuard(const NativeAPIEntries *entries) {
    if (entries == nullptr || entries->hook_func == nullptr) return false;
    for (;;) {
        const uint32_t state =
                __atomic_load_n(&g_guard_state, __ATOMIC_ACQUIRE);
        if (state == 2u) return true;
        if (state == 3u) return false;
        if (state == 0u) {
            uint32_t expected = 0u;
            if (__atomic_compare_exchange_n(&g_guard_state, &expected, 1u,
                                            false, __ATOMIC_ACQ_REL,
                                            __ATOMIC_ACQUIRE)) {
                const bool installed = InstallMadviseHook(entries);
                __atomic_store_n(&g_guard_state, installed ? 2u : 3u,
                                 __ATOMIC_RELEASE);
                if (installed) {
                    H4M_LOGW("guard: madvise guard installed target=%p",
                             reinterpret_cast<void *>(g_madvise_target));
                }
                return installed;
            }
            continue;
        }
        sched_yield();
    }
}

bool ProtectHookRange(uintptr_t address, size_t size) {
    if (address == 0u || size == 0u || size > UINTPTR_MAX - address) {
        return false;
    }
    if (__atomic_load_n(&g_guard_state, __ATOMIC_ACQUIRE) != 2u) {
        H4M_LOGE("guard: ProtectHookRange before guard installed (0x%" PRIxPTR
                 ")",
                 address);
        return false;
    }
    const uintptr_t end = address + size;
    for (uintptr_t page = PageStart(address); page < end;
         page += PageSize()) {
        if (!AddProtectedPage(page)) return false;
    }
    return true;
}

bool ReadMem(uintptr_t address, void *out, size_t size) {
    const int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        H4M_LOGW("ReadMem: open /proc/self/mem failed errno=%d", errno);
        return false;
    }
    const bool ok = lseek(fd, static_cast<off_t>(address), SEEK_SET) ==
                            static_cast<off_t>(address) &&
                    read(fd, out, size) == static_cast<ssize_t>(size);
    if (!ok) {
        H4M_LOGW("ReadMem: lseek/read failed errno=%d", errno);
    }
    close(fd);
    return ok;
}

uintptr_t MadviseTarget() {
    return __atomic_load_n(&g_madvise_target, __ATOMIC_ACQUIRE);
}

bool RunGuardSelfTest() {
    // Splitting test FIRST — it is the real functional proof: with the hook
    // live, the registered page survives DONTNEED and the control page is
    // zeroed by the kernel; without the hook both are zeroed. The
    // /proc/self/mem trampoline check below is diagnostic only (SELinux may
    // deny proc_mem read in this domain).
    const size_t ps = PageSize();
    uint8_t *pages = reinterpret_cast<uint8_t *>(
            mmap(nullptr, 2u * ps, PROT_READ | PROT_WRITE,
                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    if (pages == MAP_FAILED) {
        H4M_LOGE("guard self-test: mmap failed errno=%d", errno);
        return false;
    }
    memset(pages, 0xA5, 2u * ps);
    if (!ProtectHookRange(reinterpret_cast<uintptr_t>(pages), ps)) {
        H4M_LOGE("guard self-test: ProtectHookRange failed");
        munmap(pages, 2u * ps);
        return false;
    }
    const int rc = madvise(pages, 2u * ps, MADV_DONTNEED);
    if (rc != 0) {
        H4M_LOGE("guard self-test: madvise rc=%d errno=%d", rc, errno);
        munmap(pages, 2u * ps);
        return false;
    }
    bool preserved = true;
    bool control_zeroed = true;
    for (size_t i = 0u; i < ps; ++i) {
        if (pages[i] != 0xA5) {
            preserved = false;
            break;
        }
    }
    for (size_t i = ps; i < 2u * ps; ++i) {
        if (pages[i] != 0x00) {
            control_zeroed = false;
            break;
        }
    }
    munmap(pages, 2u * ps);
    const bool passed = preserved && control_zeroed;
    H4M_LOGW("guard self-test: preserved=%d control_zeroed=%d => %s",
             preserved ? 1 : 0, control_zeroed ? 1 : 0,
             passed ? "PASSED" : "FAILED");

    // Diagnostic only: trampoline visibility through /proc/self/mem.
    const uintptr_t target = MadviseTarget();
    uint8_t head[16]{};
    if (!ReadMem(target, head, sizeof(head))) {
        H4M_LOGW("guard self-test: trampoline check skipped (mem read denied)");
    } else {
        uint32_t first_word = 0u;
        memcpy(&first_word, head, sizeof(first_word));
        const bool trampoline_ok = first_word != 0xd50324bful;
        H4M_LOGW("guard self-test: madvise "
                 "head=%02x%02x%02x%02x %02x%02x%02x%02x trampoline=%d",
                 head[0], head[1], head[2], head[3], head[4], head[5], head[6],
                 head[7], trampoline_ok ? 1 : 0);
    }
    return passed;
}

}  // namespace h4m
