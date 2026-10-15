// Phase 5 implementation. See java_bridge.h.

#include "java_bridge.h"

#include "h4m_log.h"

#include <dlfcn.h>
#include <sys/auxv.h>
#include <elf.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>
#include <cerrno>

namespace h4m {
namespace {

uint32_t g_bridge_state = 0u;  // 0 = pending, 1 = started

// Resolve the module APK path from our own loaded .so:
//   <apk_dir>/lib/arm64/libhomemode4miui_native.so → <apk_dir>/base.apk
std::string ModuleApkPath() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(&ModuleApkPath), &info) == 0 ||
            info.dli_fname == nullptr) {
        return {};
    }
    const std::string soPath = info.dli_fname;
    const size_t libPos = soPath.find("/lib/arm64/");
    if (libPos == std::string::npos) return {};
    return soPath.substr(0, libPos) + "/base.apk";
}

// ---------------------------------------------------------------------------
// JavaVM 解析三级降级：
//   ① dlsym(RTLD_DEFAULT) —— Android 17 linker namespace 下不可见（实测）；
//   ② dlopen("libart.so") + dlsym —— 部分环境可用；
//   ③ /proc/self/maps 定位 libart.so 基址 + 手解 ELF dynsym（确定性兜底，
//      文件读取不受 SELinux proc_mem 限制）。
// ---------------------------------------------------------------------------

std::string FindLibartPath() {
    FILE *f = fopen("/proc/self/maps", "r");
    if (f == nullptr) return {};
    char line[512];
    uintptr_t best = UINTPTR_MAX;
    char path[400] = {0};
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strstr(line, "libart.so") == nullptr) continue;
        const char *perms = strchr(line, ' ');
        // perms 形如 " r-xp ..."：'r' 在 [1]，'x' 在 [3]
        if (perms == nullptr || strlen(perms) < 4 || perms[1] != 'r' ||
                perms[3] != 'x') {
            continue;  // 只取可执行段
        }
        uintptr_t start = 0;
        if (sscanf(line, "%lx-", &start) != 1) continue;
        if (start < best) {
            best = start;
            const char *p = strrchr(line, '/');
            if (p != nullptr) {
                char candidate[400];
                if (sscanf(p, "%399s", candidate) == 1) {
                    strncpy(path, candidate, sizeof(path) - 1u);
                }
            }
        }
    }
    fclose(f);
    return best != UINTPTR_MAX ? std::string(path) : std::string();
}

// 在文件 ELF 的 dynsym 里查找符号，返回 runtime 地址（base + st_value）。
void *FindSymbolInElfFile(const char *filePath, uintptr_t base,
                          const char *symbol) {
    const int fd = open(filePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: elf open failed path=%s errno=%d", filePath,
                     errno);
        return nullptr;
    }
    ElfW(Ehdr) ehdr;
    if (pread(fd, &ehdr, sizeof(ehdr), 0) != (ssize_t) sizeof(ehdr) ||
            memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
        close(fd);
        return nullptr;
    }
    // Program headers → PT_DYNAMIC 与 PT_LOAD（vaddr→offset 换算）
    std::vector<ElfW(Phdr)> phdrs(ehdr.e_phnum);
    if (pread(fd, phdrs.data(), sizeof(ElfW(Phdr)) * ehdr.e_phnum,
              (off_t) ehdr.e_phoff) !=
            (ssize_t)(sizeof(ElfW(Phdr)) * ehdr.e_phnum)) {
        close(fd);
        return nullptr;
    }
    ElfW(Addr) dynVaddr = 0;
    ElfW(Xword) dynSize = 0;
    for (const ElfW(Phdr) &ph : phdrs) {
        if (ph.p_type == PT_DYNAMIC) {
            dynVaddr = ph.p_vaddr;
            dynSize = ph.p_memsz;
            break;
        }
    }
    if (dynVaddr == 0) {
        close(fd);
        return nullptr;
    }
    auto vaddrToFile = [&phdrs](ElfW(Addr) vaddr) -> off_t {
        for (const ElfW(Phdr) &ph : phdrs) {
            if (ph.p_type == PT_LOAD && vaddr >= ph.p_vaddr &&
                    vaddr < ph.p_vaddr + ph.p_filesz) {
                return (off_t)(ph.p_offset + (vaddr - ph.p_vaddr));
            }
        }
        return -1;
    };
    const off_t dynOff = vaddrToFile(dynVaddr);
    if (dynOff < 0) {
        close(fd);
        return nullptr;
    }
    std::vector<ElfW(Dyn)> dyns(dynSize / sizeof(ElfW(Dyn)));
    if (pread(fd, dyns.data(), dynSize, dynOff) != (ssize_t) dynSize) {
        close(fd);
        return nullptr;
    }
    ElfW(Addr) symtab = 0, strtab = 0;
    ElfW(Xword) syment = sizeof(ElfW(Sym));
    for (const ElfW(Dyn) &d : dyns) {
        if (d.d_tag == DT_SYMTAB) symtab = d.d_un.d_ptr;
        if (d.d_tag == DT_STRTAB) strtab = d.d_un.d_ptr;
        if (d.d_tag == DT_SYMENT) syment = d.d_un.d_val;
    }
    if (symtab == 0 || strtab == 0 || strtab <= symtab) {
        close(fd);
        return nullptr;
    }
    // 符号数启发式：symtab 紧邻 strtab 之前（linker 布局惯例，libart 适用）
    const size_t symbolCount =
            static_cast<size_t>((strtab - symtab) / syment);
    const off_t symtabOff = vaddrToFile(symtab);
    const off_t strtabOff = vaddrToFile(strtab);
    if (symtabOff < 0 || strtabOff < 0) {
        close(fd);
        return nullptr;
    }
    void *result = nullptr;
    for (size_t i = 0; i < symbolCount; ++i) {
        ElfW(Sym) sym;
        if (pread(fd, &sym, sizeof(sym), symtabOff + (off_t)(i * syment)) !=
                (ssize_t) sizeof(sym)) {
            break;
        }
        if (sym.st_name == 0 || sym.st_value == 0) continue;
        char name[128];
        if (pread(fd, name, sizeof(name) - 1u,
                  strtabOff + (off_t) sym.st_name) <= 0) {
            continue;
        }
        name[sizeof(name) - 1u] = '\0';
        if (strcmp(name, symbol) == 0) {
            result = reinterpret_cast<void *>(base + sym.st_value);
            break;
        }
    }
    close(fd);
    return result;
}

using GetCreatedJavaVMsFn = jint (*)(JavaVM **, jsize, jsize *);

// Tier 4（重写）：主可执行文件（/proc/self/exe = hyos_spawner，ART 静态链接假设）
// —— **纯文件解析**，所有读取走 pread，任何失败只返回 null，绝无内存直读
//（vc112 的内存 magic 扫描在 base 误判时 SIGSEGV → 桌面崩溃循环，已废除）。
//
// base 推导：AT_PHDR = base + e_phoff → base = AT_PHDR - e_phoff（强校验：
// 文件头 magic/machine/e_phoff 必须与 auxv 自洽）。
// 符号地址 = base + st_value。符号计数用 DT_HASH(nchain) 精确值，缺省时用
// strtab-symtab 启发式并封顶。
void *FindSymbolInMainExecutableByFile(const char *symbol) {
    const uintptr_t phdrAddr = getauxval(AT_PHDR);
    const size_t phnum = static_cast<size_t>(getauxval(AT_PHNUM));
    if (phdrAddr == 0u || phnum == 0u) return nullptr;

    char exePath[256] = {0};
    const ssize_t rl = readlink("/proc/self/exe", exePath,
                                sizeof(exePath) - 1u);
    if (rl <= 0) return nullptr;
    exePath[rl] = '\0';

    const int fd = open(exePath, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: exe open failed path=%s errno=%d", exePath,
                     errno);
        return nullptr;
    }
    ElfW(Ehdr) ehdr;
    if (pread(fd, &ehdr, sizeof(ehdr), 0) != (ssize_t) sizeof(ehdr) ||
            memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0 ||
            ehdr.e_machine != EM_AARCH64) {
        close(fd);
        return nullptr;
    }
    // 强校验：AT_PHDR 必须等于 base + e_phoff（base 由该关系反推）
    const uintptr_t base = phdrAddr - ehdr.e_phoff;
    if (base == 0u || base > phdrAddr) {
        close(fd);
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: exe base derivation failed (phdr=%lx "
                     "phoff=%llx)",
                     static_cast<unsigned long>(phdrAddr),
                     static_cast<unsigned long long>(ehdr.e_phoff));
        return nullptr;
    }

    std::vector<ElfW(Phdr)> phdrs(ehdr.e_phnum);
    if (pread(fd, phdrs.data(), sizeof(ElfW(Phdr)) * ehdr.e_phnum,
              (off_t) ehdr.e_phoff) !=
            (ssize_t)(sizeof(ElfW(Phdr)) * ehdr.e_phnum)) {
        close(fd);
        return nullptr;
    }
    ElfW(Addr) dynVaddr = 0;
    ElfW(Xword) dynSize = 0;
    for (const ElfW(Phdr) &ph : phdrs) {
        if (ph.p_type == PT_DYNAMIC) {
            dynVaddr = ph.p_vaddr;
            dynSize = ph.p_memsz;
            break;
        }
    }
    if (dynVaddr == 0) {
        close(fd);
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: exe has no PT_DYNAMIC (fully static?)");
        return nullptr;
    }
    auto vaddrToFile = [&phdrs](ElfW(Addr) vaddr) -> off_t {
        for (const ElfW(Phdr) &ph : phdrs) {
            if (ph.p_type == PT_LOAD && vaddr >= ph.p_vaddr &&
                    vaddr < ph.p_vaddr + ph.p_filesz) {
                return (off_t)(ph.p_offset + (vaddr - ph.p_vaddr));
            }
        }
        return -1;
    };
    const off_t dynOff = vaddrToFile(dynVaddr);
    if (dynOff < 0) {
        close(fd);
        return nullptr;
    }
    std::vector<ElfW(Dyn)> dyns(dynSize / sizeof(ElfW(Dyn)));
    if (pread(fd, dyns.data(), dynSize, dynOff) != (ssize_t) dynSize) {
        close(fd);
        return nullptr;
    }
    ElfW(Addr) symtab = 0, strtab = 0, hash = 0, gnuHash = 0;
    ElfW(Xword) syment = sizeof(ElfW(Sym));
    for (const ElfW(Dyn) &d : dyns) {
        if (d.d_tag == DT_SYMTAB) symtab = d.d_un.d_ptr;
        if (d.d_tag == DT_STRTAB) strtab = d.d_un.d_ptr;
        if (d.d_tag == DT_SYMENT) syment = d.d_un.d_val;
        if (d.d_tag == DT_HASH) hash = d.d_un.d_ptr;
        if (d.d_tag == DT_GNU_HASH) gnuHash = d.d_un.d_ptr;
    }
    if (symtab == 0u || strtab == 0u || strtab <= symtab || syment == 0u) {
        close(fd);
        return nullptr;
    }
    // 符号计数：优先 DT_HASH nchain（精确）；GNU_HASH 无链表长度、退化启发式
    size_t symbolCount = 0;
    if (hash != 0u) {
        const off_t hashOff = vaddrToFile(hash);
        if (hashOff >= 0) {
            uint32_t nchain = 0;
            // DT_HASH 布局：nbucket, nchain, buckets..., chain...
            if (pread(fd, &nchain, sizeof(nchain), hashOff + sizeof(uint32_t)) ==
                    sizeof(nchain)) {
                symbolCount = nchain;
            }
        }
    }
    if (symbolCount == 0u && gnuHash == 0u) {
        symbolCount = static_cast<size_t>((strtab - symtab) / syment);
    }
    if (symbolCount == 0u || symbolCount > 8000000u) {
        close(fd);
        h4m_log::log(ANDROID_LOG_WARN, "java bridge: exe symbol count=%zu",
                     symbolCount);
        return nullptr;
    }
    const off_t symtabOff = vaddrToFile(symtab);
    const off_t strtabOff = vaddrToFile(strtab);
    if (symtabOff < 0 || strtabOff < 0) {
        close(fd);
        return nullptr;
    }

    void *result = nullptr;
    for (size_t i = 0; i < symbolCount; ++i) {
        ElfW(Sym) sym;
        if (pread(fd, &sym, sizeof(sym), symtabOff + (off_t)(i * syment)) !=
                (ssize_t) sizeof(sym)) {
            break;
        }
        if (sym.st_value == 0u || sym.st_name == 0u) continue;
        char name[128];
        if (pread(fd, name, sizeof(name) - 1u,
                  strtabOff + (off_t) sym.st_name) <= 0) {
            continue;
        }
        name[sizeof(name) - 1u] = '\0';
        if (strcmp(name, symbol) == 0) {
            result = reinterpret_cast<void *>(base + sym.st_value);
            h4m_log::log(ANDROID_LOG_WARN,
                         "java bridge: exe symbol %s at base+%llx", symbol,
                         static_cast<unsigned long long>(sym.st_value));
            break;
        }
    }
    close(fd);
    return result;
}

GetCreatedJavaVMsFn ResolveGetCreatedJavaVMs() {
    // ① 全局符号表
    void *sym = dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs");
    if (sym != nullptr) {
        h4m_log::log(ANDROID_LOG_WARN, "java bridge: vm resolver tier1 ok");
        return reinterpret_cast<GetCreatedJavaVMsFn>(sym);
    }
    // ② dlopen libart
    void *handle = dlopen("libart.so", RTLD_NOW);
    if (handle != nullptr) {
        sym = dlsym(handle, "JNI_GetCreatedJavaVMs");
        if (sym != nullptr) {
            h4m_log::log(ANDROID_LOG_WARN, "java bridge: vm resolver tier2 ok");
            return reinterpret_cast<GetCreatedJavaVMsFn>(sym);
        }
    }
    // ③ maps + 手解 ELF dynsym
    const std::string libart = FindLibartPath();
    h4m_log::log(ANDROID_LOG_WARN, "java bridge: maps libart path='%s'",
                 libart.c_str());
    if (!libart.empty()) {
        FILE *f = fopen(libart.c_str(), "r");
        if (f != nullptr) {
            char line[512];
            uintptr_t base = UINTPTR_MAX;
            while (fgets(line, sizeof(line), f) != nullptr) {
                if (strstr(line, libart.c_str()) == nullptr) continue;
                uintptr_t start = 0;
                if (sscanf(line, "%lx-", &start) == 1 && start < base) {
                    base = start;
                }
            }
            fclose(f);
            if (base != UINTPTR_MAX) {
                sym = FindSymbolInElfFile(libart.c_str(), base,
                                          "JNI_GetCreatedJavaVMs");
                if (sym != nullptr) {
                    h4m_log::log(ANDROID_LOG_WARN,
                                 "java bridge: vm resolver tier3 ok base=%lx",
                                 static_cast<unsigned long>(base));
                    return reinterpret_cast<GetCreatedJavaVMsFn>(sym);
                }
            }
        }
    }
    // ④ 主可执行文件内存内 dynsym 直查（静态 ART 假设：ART 可能静态链接进
    // hyos_spawner 单体 exe —— maps 里根本没有 libart.so）。经 AT_PHDR/AT_PHNUM
    // 定位 program headers，base 由 ELF magic 向下扫描校验，dynsym 表直接在
    // 内存中遍历（无文件读取、无 namespace/SELinux 限制）。
    sym = FindSymbolInMainExecutableByFile("JNI_GetCreatedJavaVMs");
    if (sym != nullptr) {
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: vm resolver tier4 ok (main exe in-memory)");
        return reinterpret_cast<GetCreatedJavaVMsFn>(sym);
    }

    h4m_log::log(ANDROID_LOG_ERROR,
                 "java bridge: vm resolver exhausted (libart='%s')",
                 libart.c_str());
    return nullptr;
}

// ---- 断路器（防崩溃循环）：状态文件记录 "running:<apkPath>" / "ok:<apkPath>"。
// 同一 APK 路径下若上次状态为 running（未跑到 ok = 上次崩了），本次直接跳过
// bootstrap —— 任何 bootstrap 缺陷最多崩溃一次，绝不进重启循环。APK 更新后
// 路径变化自动重试。

std::string BridgeStatePath() {
    return "/data/user_de/0/com.miui.home/cache/h4m_bridge_state";
}

bool ReadBridgeState(std::string *out) {
    const int fd = open(BridgeStatePath().c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[512]{};
    const ssize_t n = read(fd, buf, sizeof(buf) - 1u);
    close(fd);
    if (n <= 0) return false;
    buf[n] = '\0';
    *out = buf;
    return true;
}

void WriteBridgeState(const char *content) {
    const int fd = open(BridgeStatePath().c_str(),
                        O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    const ssize_t n = strlen(content);
    if (write(fd, content, static_cast<size_t>(n)) != n) {
        // 写失败不影响主流程
    }
    close(fd);
}


void JavaBridgeThread() {
    auto getVMs = ResolveGetCreatedJavaVMs();
    if (getVMs == nullptr) {
        return;
    }
    JavaVM *vm = nullptr;
    jsize count = 0;
    getVMs(&vm, 1, &count);
    if (vm == nullptr || count < 1) {
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: no java vm");
        return;
    }
    h4m_log::log(ANDROID_LOG_WARN, "java bridge: vm acquired");
    JNIEnv *env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: attach failed");
        return;
    }

    const std::string apkPath = ModuleApkPath();
    if (apkPath.empty()) {
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: apk path unresolved");
        vm->DetachCurrentThread();
        return;
    }
    h4m_log::log(ANDROID_LOG_WARN, "java bridge: apk=%s", apkPath.c_str());

    // Context = ActivityThread.currentApplication()（进程专精早期可能为 null，
    // 重试兜底；launcher 场景下 libapp.so 加载时 Application 必已就绪）
    jclass activityThread = env->FindClass("android/app/ActivityThread");
    if (activityThread == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: ActivityThread not found");
        vm->DetachCurrentThread();
        return;
    }
    jmethodID currentApp = env->GetStaticMethodID(
            activityThread, "currentApplication",
            "()Landroid/app/Application;");
    jobject context = nullptr;
    for (int attempt = 0; attempt < 60 && context == nullptr; ++attempt) {
        context = env->CallStaticObjectMethod(activityThread, currentApp);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            context = nullptr;
        }
        if (context == nullptr) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }
    if (context == nullptr) {
        h4m_log::log(ANDROID_LOG_ERROR,
                     "java bridge: currentApplication never ready");
        vm->DetachCurrentThread();
        return;
    }

    // PathClassLoader(moduleApk, appClassLoader)
    jclass pathLoaderClass = env->FindClass("dalvik/system/PathClassLoader");
    if (pathLoaderClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: PathClassLoader not found");
        vm->DetachCurrentThread();
        return;
    }
    jmethodID loaderCtor = env->GetMethodID(
            pathLoaderClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jstring apkJ = env->NewStringUTF(apkPath.c_str());
    jobject parent = env->CallObjectMethod(
            context,
            env->GetMethodID(env->GetObjectClass(context), "getClassLoader",
                             "()Ljava/lang/ClassLoader;"));
    jobject loader = env->NewObject(pathLoaderClass, loaderCtor, apkJ, parent);
    if (env->ExceptionCheck() || loader == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: loader creation failed");
        vm->DetachCurrentThread();
        return;
    }

    // Class.forName equivalent: loader.loadClass(Os4Bootstrap)
    jmethodID loadClass = env->GetMethodID(
            pathLoaderClass, "loadClass",
            "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring bootName = env->NewStringUTF("com.homemode4miui.os4.Os4Bootstrap");
    jclass bootstrap = static_cast<jclass>(
            env->CallObjectMethod(loader, loadClass, bootName));
    if (env->ExceptionCheck() || bootstrap == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: Os4Bootstrap load failed");
        vm->DetachCurrentThread();
        return;
    }

    jmethodID init = env->GetStaticMethodID(
            bootstrap, "init",
            "(Landroid/content/Context;Ljava/lang/String;)V");
    if (init == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: init method missing");
        vm->DetachCurrentThread();
        return;
    }
    env->CallStaticVoidMethod(bootstrap, init, context, apkJ);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: init threw");
    } else {
        h4m_log::log(ANDROID_LOG_WARN,
                     "java bridge: bootstrap invoked — Java side is live");
        WriteBridgeState(("ok:" + apkPath).c_str());
    }
    vm->DetachCurrentThread();
}

}  // namespace

void StartJavaBridge(const NativeAPIEntries *entries) {
    (void) entries;  // reserved: future phases may pass hook_func to Java side
    if (__atomic_exchange_n(&g_bridge_state, 1u, __ATOMIC_ACQ_REL) != 0u) {
        return;
    }
    // 断路器：同 APK 路径下上次未走到 ok = 上次崩溃 → 本次跳过
    const std::string apk = ModuleApkPath();
    std::string prev;
    if (ReadBridgeState(&prev) && prev.rfind("running:", 0) == 0 &&
            prev.find(apk) != std::string::npos) {
        h4m_log::log(ANDROID_LOG_ERROR,
                     "java bridge: circuit breaker — previous attempt with"
                     " this build crashed, bootstrap skipped");
        return;
    }
    WriteBridgeState(("running:" + apk).c_str());
    std::thread(JavaBridgeThread).detach();
}

}  // namespace h4m
