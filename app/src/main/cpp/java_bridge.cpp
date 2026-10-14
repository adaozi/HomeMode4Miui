// Phase 5 implementation. See java_bridge.h.

#include "java_bridge.h"

#include "h4m_log.h"

#include <dlfcn.h>
#include <jni.h>
#include <string.h>
#include <string>
#include <thread>

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

void JavaBridgeThread() {
    using GetCreatedJavaVMsFn = jint (*)(JavaVM **, jsize, jsize *);
    auto getVMs = reinterpret_cast<GetCreatedJavaVMsFn>(
            dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
    if (getVMs == nullptr) {
        h4m_log::log(ANDROID_LOG_ERROR,
                     "java bridge: JNI_GetCreatedJavaVMs not found");
        return;
    }
    JavaVM *vm = nullptr;
    jsize count = 0;
    getVMs(&vm, 1, &count);
    if (vm == nullptr || count < 1) {
        h4m_log::log(ANDROID_LOG_ERROR, "java bridge: no java vm");
        return;
    }
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
    }
    vm->DetachCurrentThread();
}

}  // namespace

void StartJavaBridge(const NativeAPIEntries *entries) {
    (void) entries;  // reserved: future phases may pass hook_func to Java side
    if (__atomic_exchange_n(&g_bridge_state, 1u, __ATOMIC_ACQ_REL) != 0u) {
        return;
    }
    std::thread(JavaBridgeThread).detach();
}

}  // namespace h4m
