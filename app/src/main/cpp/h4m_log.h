// Shared native logging for the HomeMode4Miui native probe: logcat (WARN/ERROR
// only — HyperOS logd filters INFO/DEBUG out of the launcher process) plus a
// durable file mirror in the launcher's own cache directory. The file mirror
// survives logd ring-buffer churn during heavy cold starts; the module's log
// exporter reads it via su. File layout follows the SwipeGate / WOMMO pattern
// (MIT / GPL-3.0 reference projects).
#pragma once

#include <android/log.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

namespace h4m_log {

inline void write_file(const char *message) {
    static const char *const kPaths[] = {
            "/data/user_de/0/com.miui.home/cache/h4m_native.log",
            "/data/user/0/com.miui.home/cache/h4m_native.log",
            "/data/data/com.miui.home/cache/h4m_native.log",
    };
    for (const char *path : kPaths) {
        const int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC,
                            0644);
        if (fd >= 0) {
            dprintf(fd, "%s\n", message == nullptr ? "" : message);
            close(fd);
            return;
        }
        // 诊断：每个候选路径失败原因各报一次（首行起 50 次封顶防刷屏）
        static uint32_t fail_logs = 0u;
        if (__atomic_load_n(&fail_logs, __ATOMIC_RELAXED) < 50u) {
            __atomic_fetch_add(&fail_logs, 1u, __ATOMIC_RELAXED);
            __android_log_print(ANDROID_LOG_WARN, "H4MNative",
                                "file mirror open failed path=%s errno=%d",
                                path, errno);
        }
    }
}

inline void logv(int priority, const char *format, va_list ap) {
    char buffer[1024]{};
    vsnprintf(buffer, sizeof(buffer), format, ap);

    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    tm local{};
    localtime_r(&ts.tv_sec, &local);
    char stamped[1104]{};
    snprintf(stamped, sizeof(stamped), "%02d:%02d:%02d.%03ld %s",
             local.tm_hour, local.tm_min, local.tm_sec,
             ts.tv_nsec / 1000000L, buffer);

    __android_log_print(priority, "H4MNative", "%s", buffer);
    write_file(stamped);
}

inline void log(int priority, const char *format, ...)
        __attribute__((format(printf, 2, 3)));

inline void log(int priority, const char *format, ...) {
    va_list ap;
    va_start(ap, format);
    logv(priority, format, ap);
    va_end(ap);
}

}  // namespace h4m_log
