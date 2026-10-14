package com.homemode4miui.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志导出器（模块首页 bug 反馈用）：
 *   - 来源（按优先级）：
 *     ① LSPosed 模块日志 /data/adb/lspd/log/modules.log（经 su 读取）——
 *        宿主侧 hook 全量日志（hook installed/FAILED、sync、attached）只在这里，
 *        logcat 里没有（MainHook/HomeController 的 log() 仅走 XposedBridge.log）；
 *     ② 兜底 su logcat -d（root 在但 lspd 路径漂移时，含 ModLog 双写的 logcat 部分）；
 *     ③ 再兜底无 su logcat -d（仅模块 App 进程自身日志，文件头注明局限）。
 *   - 两级过滤：
 *     ① 时间范围：逐行解析时间戳（LSPosed ISO 格式 / logcat MM-DD 格式），
 *        多行堆栈（无时间戳的续行）跟随上一行去留；
 *     ② tag 过滤（内容级）：modules.log 是所有 LSPosed 模块共享文件、logcat 是全设备
 *        缓冲，必须按 "HomeMode4Miui" tag 过滤，否则 1 分钟就能导出数 MB 噪音；
 *        无时间戳的续行跟随上一行去留，保证堆栈不割裂。
 *   - 10MB 上限：超限保留【最新】部分（ArrayDeque 头部裁剪）；
 *   - 落盘：MediaStore.Downloads（Download 文件夹，无需存储权限，min-api 29+）。
 *   - 全程 try/catch 兜底：导出失败只影响本功能，绝不二次崩溃。
 */
public final class LogExporter {

    /**
     * 回调（主线程）。
     * strings2（适配）：回调带结构化结果 —— success 标志 + path 独立字段，
     * 替代旧版"已导出"前缀字符串匹配（消息文案资源化后前缀不再稳定）。
     * message 为已本地化的完整展示文案；path 仅成功时非 null。
     */
    public interface Callback {
        void onDone(boolean success, String message, String path);
    }

    /** LSPosed 模块日志候选路径（新旧版本布局漂移，依次探测） */
    private static final String[] LSP_LOG_PATHS = {
            "/data/adb/lspd/log/modules.log",       // LSPosed 1.9.x+（JingMatrix 同）
            "/data/adb/lsposed/logs/modules.log",   // 旧版 1.8.x
            "/data/adb/lsposed/log/modules.log",    // 旧版备选布局
    };

    /** 模块日志 tag（MainHook/HomeController/HostCompat/ModLog 四处出口统一前缀） */
    private static final String TAG = "HomeMode4Miui";

    /** 单次导出上限 10MB（超出保留最新部分） */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** LSPosed 模块日志行时间戳：[ 2026-10-10T03:49:46.391 ... */
    private static final Pattern LSP_TIME =
            Pattern.compile("\\[\\s*(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})");
    /** logcat -v time 行时间戳：10-10 03:49:46.391 ... */
    private static final Pattern LOGCAT_TIME =
            Pattern.compile("^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})");

    private LogExporter() {
    }

    /** 单飞闸：连点导出会产生重名文件竞态，进行中忽略后续点击 */
    private static final java.util.concurrent.atomic.AtomicBoolean sExporting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 异步导出：minutes ∈ {60,30,10,5,1}；结果经主线程回调 */
    public static void export(Context ctx, final int minutes, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        if (!sExporting.compareAndSet(false, true)) {
            cb.onDone(false, app.getString(com.homemode4miui.R.string.log_export_busy), null);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String message;
                String path = null;
                try {
                    String[] r = doExport(app, minutes);
                    message = r[0];
                    path = r[1];
                    ok = true;
                } catch (Throwable t) {
                    message = app.getString(com.homemode4miui.R.string.log_export_failed, t);
                }
                final boolean fOk = ok;
                final String msg = message;
                final String fPath = path;
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        sExporting.set(false);
                        cb.onDone(fOk, msg, fPath);
                    }
                });
            }
        }, "log-export").start();
    }

    /* ==================== 主流程 ==================== */

    /**
     * @return 二元组 {本地化成功文案, 落盘绝对路径}（导出文件头为文件内容而非 UI，
     *         保持中文原样 —— 反馈文件不随设备语言变化，便于问题定位）
     */
    private static String[] doExport(Context ctx, int minutes) throws Exception {
        long startMs = System.currentTimeMillis() - minutes * 60_000L;

        StringBuilder body = new StringBuilder();
        String source;
        boolean rootUsed = false;

        // ① LSPosed 模块日志（su）
        String lsp = tryLspLog(startMs);
        if (lsp != null) {
            body.append(lsp);
            source = "LSPosed 模块日志";
            rootUsed = true;
        } else {
            // ② su logcat（root 在但 lspd 路径全空）——-s 源头按 tag 过滤，防全设备缓冲灌入
            String lcRoot = runCommandCapture(new String[]{"su", "-c",
                    "logcat -d -v time -s " + TAG + ":V"}, startMs, 30_000);
            if (lcRoot != null) {
                body.append(filterByTag(lcRoot));
                source = "logcat（root，tag 过滤：" + TAG + "）";
                rootUsed = true;
            } else {
                // ③ 无 su：仅本进程 logcat（Android 4.1+ UID 隔离，读不到宿主进程日志）
                body.append(filterByTag(runCommandCapture(new String[]{"logcat", "-d",
                        "-v", "time", "-s", TAG + ":V"}, startMs, 30_000)));
                source = "logcat（无 root，仅模块 App 进程 —— 宿主 hook 日志请在"
                        + " LSPosed 管理器「日志」页导出后一并提供）";
            }
        }

        // 文件头（反馈诊断上下文）
        StringBuilder out = new StringBuilder();
        out.append("# ==== HomeMode4Miui 日志导出 ====\n");
        out.append("# 导出时间 : ").append(now("yyyy-MM-dd HH:mm:ss")).append('\n');
        out.append("# 时间范围 : 最近 ").append(humanRange(minutes)).append('\n');
        out.append("# 日志来源 : ").append(source).append('\n');
        out.append("# 模块版本 : ").append(selfVersion(ctx)).append('\n');
        out.append("# 设备     : ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" / Android ")
                .append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
                .append(")\n");
        out.append("# root     : ").append(rootUsed ? "已使用（可读宿主侧日志）\n" : "未使用\n");
        out.append("# 提示     : 提交 bug 反馈前请浏览一遍，确认不含个人隐私内容\n");
        out.append("# ==============================\n\n");
        out.append(body);

        // OS4 适配诊断段（root 时追加；见方法注释）
        out.append(buildOs4Diag(rootUsed));

        return writeToDownloads(ctx, out.toString(), minutes);
    }

    /* ==================== OS4 适配诊断 ==================== */

    /**
     * OS4 环境诊断段（v2.9.59；v2.9.60 修正抓取逻辑）—— 服务于 OS4 适配取证：
     * 宿主 OS4（Flutter+Rust 重写）的 Java Activity 壳由系统侧共享库
     * hyperos.rustruntime.v5 提供，需要拿到该库的 APK。
     * v2.9.60 教训（实机 log 20261014_154905 实锤）：`grep -i rust` 会撞
     * t**rust**service（com.xiaomi.trustservice，普通 Java 应用）—— 改为从
     * `pm list libraries` 精确取 rustruntime 库名（hyperos.rustruntime.v5/v3），
     * 静态共享库包名 ≈ 库名，直接 pm path 定位 APK 再 cp 到 Download。
     * 全部命令独立 try：单条失败只留 "N/A"，不影响日志主体。
     */
    private static String buildOs4Diag(boolean rootUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n# ==== OS4 适配诊断（rustruntime 库定位） ====\n");
        if (!rootUsed) {
            sb.append("# 未取得 root，跳过（本段需要 su）\n");
            return sb.toString();
        }
        // ① 宿主 APK 路径（确认设备上装的是哪族桌面）
        sb.append("pm path com.miui.home:\n")
                .append(orNa(runSu("pm path com.miui.home"))).append('\n');
        // ② 系统共享库声明表（uses-library 名单；grep 必须钉死 rustruntime，
        //    裸 "rust" 会撞 trustservice —— v2.9.59 实机误抓教训）
        String libs = runSu("pm list libraries | grep -i rustruntime");
        sb.append("pm list libraries | grep -i rustruntime:\n").append(orNa(libs)).append('\n');
        // ③ 包名兜底探测（rustruntime 库若以独立包安装，多半含 hyperos/rustruntime 字样）
        String grepPkgs = runSu("pm list packages | grep -iE 'rustruntime|hyperos'");
        sb.append("pm list packages | grep -iE 'rustruntime|hyperos':\n")
                .append(orNa(grepPkgs)).append('\n');
        // ④ 文件系统直查（v2.9.61 新增，实机 20261014_160334 实锤：提供方包名
        //    既不含 hyperos 也不含 rustruntime，pm path <库名> 全部落空）——
        //    静态共享库 APK 物理上必有落点（/system_ext /product /vendor 等）
        String found = runSu("find /system /system_ext /product /vendor"
                + " -maxdepth 4 -iname '*rustruntime*' 2>/dev/null");
        sb.append("find *rustruntime*（系统分区）:\n").append(orNa(found)).append('\n');
        // ⑤ dumpsys 兜底（find 全空时：包 dump 里必现 rustruntime 字样，含提供方包名）
        String dump = found == null || found.length() == 0
                ? runSu("dumpsys package | grep -i rustruntime | head -20", 30_000) : "";
        if (dump.length() > 0) {
            sb.append("dumpsys package | grep -i rustruntime:\n").append(dump).append('\n');
        }
        // ⑥ 抓取：find 命中路径优先（v5 优先）→ pm path 库名兜底 → cp APK 到 Download
        String copied = tryCopyRustruntimeApk(libs, found);
        sb.append("rustruntime APK 拷贝:\n").append(copied).append('\n');
        sb.append("# =======================================\n");
        return sb.toString();
    }

    /** su 执行单条 shell（管道 OK）；失败/空返回 ""（诊断段内再统一标注） */
    private static String runSu(String shellCmd) {
        return runSu(shellCmd, 15_000);
    }

    /** 重命令变体：dumpsys package 在大 ROM 上可达 10s+，给 30s */
    private static String runSu(String shellCmd, long timeoutMs) {
        try {
            String r = runCommandCapture(new String[]{"su", "-c", shellCmd},
                    0L, timeoutMs);
            return r == null ? "" : r.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 定位 rustruntime 共享库本体并 cp 到 /sdcard/Download/。
     * v2.9.62 实机实锤（log 20261014_162759）：rustruntime 不是 APK 包，是
     * 传统 uses-library 型共享库 JAR —— /system_ext/framework/hyperos.rustruntime.jar
     * （带 oat 编译缓存，内含壳层 dex）+ /product/etc/permissions/rustruntime_cfg_v3_v5.xml
     * （库声明映射）。故候选 = find 命中的 .jar/.xml（排除 oat/fsv_meta），
     * 兜底固定路径；pm path 库名兜底保留（未来若改回 static-shared-lib 形态）。
     */
    private static String tryCopyRustruntimeApk(String libsOutput, String foundPaths) {
        // ① find 命中的物理路径直接拷（jar=壳层 dex 本体；xml=库映射配置）
        if (foundPaths != null && foundPaths.length() > 0) {
            java.util.ArrayList<String> paths = new java.util.ArrayList<>();
            for (String line : foundPaths.split("\n")) {
                String p = line.trim();
                if (!p.startsWith("/")) continue;
                // 只拷本体：oat 编译缓存与 fsv_meta 校验文件无逆向价值且体积大
                if (p.contains("/oat/") || p.contains(".fsv_meta")) continue;
                if (!(p.endsWith(".jar") || p.endsWith(".apk") || p.endsWith(".xml"))) continue;
                paths.add(p);
            }
            // jar（dex 本体）优先于 xml（配置）
            paths.sort((a, b) -> {
                boolean ja = a.endsWith(".jar"), jb = b.endsWith(".jar");
                if (ja != jb) return ja ? -1 : 1;
                return a.compareTo(b);
            });
            for (String src : paths) {
                String dest = "/sdcard/Download/"
                        + new java.io.File(src).getName();
                String cp = runSu("cp -f \"" + src + "\" \"" + dest
                        + "\" && chmod 644 \"" + dest + "\" && echo COPY_OK");
                if (cp.contains("COPY_OK")) {
                    return "OK → " + dest + "（源: " + src + "）";
                }
            }
        }
        // ② 固定路径兜底（find 因 maxdepth/权限漏扫时）
        String[] fallbacks = {
                "/system_ext/framework/hyperos.rustruntime.jar",
                "/product/etc/permissions/rustruntime_cfg_v3_v5.xml",
        };
        for (String src : fallbacks) {
            String test = runSu("test -f \"" + src + "\" && echo EXISTS");
            if (!test.contains("EXISTS")) continue;
            String dest = "/sdcard/Download/" + new java.io.File(src).getName();
            String cp = runSu("cp -f \"" + src + "\" \"" + dest
                    + "\" && chmod 644 \"" + dest + "\" && echo COPY_OK");
            if (cp.contains("COPY_OK")) {
                return "OK → " + dest + "（源: " + src + "）";
            }
        }
        // ③ pm path 库名兜底（未来若改回 static-shared-lib APK 形态）
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        if (libsOutput != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("library:([^\\s\\r\\n]+)").matcher(libsOutput);
            while (m.find()) candidates.add(m.group(1));
        }
        for (String lib : candidates) {
            String apkPath = runSu("pm path " + lib);
            if (apkPath == null || !apkPath.startsWith("package:")) continue;
            apkPath = apkPath.split("\n")[0].replaceFirst("^package:", "").trim();
            String dest = "/sdcard/Download/" + lib.replace('.', '_') + ".apk";
            String cp = runSu("cp -f \"" + apkPath + "\" \"" + dest
                    + "\" && chmod 644 \"" + dest + "\" && echo COPY_OK");
            if (cp.contains("COPY_OK")) {
                return "OK [" + lib + "] → " + dest + "（源: " + apkPath + "）";
            }
            return "FAILED [" + lib + "]（cp 失败: " + cp + "）";
        }
        return "N/A（三路均未命中 —— 请把 ②③④⑤ 段原样反馈）";
    }

    /** 诊断段占位：空串 → N/A 标注 */
    private static String orNa(String s) {
        return (s == null || s.length() == 0) ? "N/A" : s;
    }

    /* ==================== 来源采集 ==================== */
    // 来源描述（source）与文件头同属导出文件内容，非设备 UI，保持中文原样。

    /** 逐路径探测 LSPosed 模块日志；按 tag 过滤后非空即返回，全失败返回 null */
    private static String tryLspLog(long startMs) {
        for (String path : LSP_LOG_PATHS) {
            try {
                String content = runCommandCapture(
                        new String[]{"su", "-c", "cat " + path}, startMs, 10_000);
                if (content != null && content.trim().length() > 0) {
                    // 内容级 tag 过滤：modules.log 是所有 LSPosed 模块共享文件，
                    // 不按 tag 过滤时其他模块的日志量可轻松达到每分钟数 MB
                    content = filterByTag(content);
                    if (content.trim().length() == 0) continue; // 本路径无本模块日志
                    StringBuilder sb = new StringBuilder("# ---- ").append(path).append(" ----\n");
                    sb.append(content);
                    return sb.toString();
                }
            } catch (Throwable ignored) {
                // su 不存在 / 路径不存在 / 读失败 → 试下一路径
            }
        }
        return null;
    }

    /* ==================== tag 过滤 ==================== */

    /**
     * 内容级 tag 过滤：
     *   - 有时间戳的行：含 TAG → 保留并标记；不含 → 丢弃并清除标记；
     *   - 无时间戳的行（多行堆栈续行）：跟随上一行去留，保证堆栈不与正文割裂。
     */
    private static String filterByTag(String content) {
        if (content == null || content.length() == 0) return content;
        StringBuilder out = new StringBuilder(content.length() / 2);
        boolean keep = false;
        for (String line : content.split("\n", -1)) {
            boolean hasTs = LSP_TIME.matcher(line).find()
                    || LOGCAT_TIME.matcher(line).find();
            if (hasTs) {
                keep = line.contains(TAG);
            }
            if (keep) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * 执行命令并收集 stdout（按时间范围过滤 + 10MB 尾部截断）。
     * @return 过滤后的日志文本；命令启动失败（如无 su）返回 null；执行失败返回空串
     */
    private static String runCommandCapture(String[] cmd, long startMs, long timeoutMs) {
        // hotfix：destroy 兜底 —— 旧实现读流抛异常时进程既不 destroy 也不关流（泄漏）
        final Process[] proc = new Process[1];
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            proc[0] = pb.start();
            final Process p = proc[0];

            // stderr 单线程排空（防管道阻塞；内容仅用于失败诊断）
            final StringBuilder err = new StringBuilder();
            Thread errDrain = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader er = new BufferedReader(
                                new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                        String l;
                        while ((l = er.readLine()) != null) {
                            if (err.length() < 2000) err.append(l).append('\n');
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "log-export-stderr");
            errDrain.setDaemon(true);
            errDrain.start();

            BoundedLines bounded = new BoundedLines(MAX_BYTES);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            // prevVerdict：上一行的判定结果（真实 ts = 保留 / Long.MIN_VALUE = 丢弃）。
            // 堆栈续行（无时间戳）跟随上一行去留 —— 保证多行异常不与正文割裂。
            long prevVerdict = startMs;
            while ((line = r.readLine()) != null) {
                long ts = lineTs(line, startMs, prevVerdict);
                if (ts == Long.MIN_VALUE) {
                    prevVerdict = Long.MIN_VALUE;
                    continue;
                }
                prevVerdict = ts;
                bounded.offer(line);
            }
            r.close();

            boolean finished = false;
            try {
                finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (!finished) {
                p.destroy();
            }
            if (bounded.total() == 0 && finished && p.exitValue() != 0
                    && err.indexOf("Permission denied") >= 0) {
                return null; // 命令被拒（典型：无 root 时 su 启动失败）
            }
            return bounded.drainText();
        } catch (Throwable t) {
            // IOException（无 su 二进制等）→ null 让上层走下一来源
            return null;
        } finally {
            // hotfix：异常路径 destroy 兜底（正常路径 waitFor 超时分支已 destroy，此处幂等无害）
            if (proc[0] != null) {
                proc[0].destroy();
            }
        }
    }

    /* ==================== 时间过滤 + 限幅 ==================== */

    /** 行级限幅缓冲：超过 MAX_BYTES 时从头（最旧）裁剪，保留最新 */
    private static final class BoundedLines {
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private long bytes;
        private final long max;

        BoundedLines(long max) {
            this.max = max;
        }

        void offer(String line) {
            lines.addLast(line);
            bytes += line.length() + 1;
            while (bytes > max && lines.size() > 1) {
                String dropped = lines.pollFirst();
                bytes -= dropped.length() + 1;
            }
        }

        long total() {
            return bytes;
        }

        String drainText() {
            StringBuilder sb = new StringBuilder((int) Math.min(bytes + 16, Integer.MAX_VALUE));
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 解析行时间戳：
     *   - 有时间戳且 < startMs → null（丢弃）；
     *   - 有时间戳且 ≥ startMs → tsMs（保留）；
     *   - 无时间戳（多行堆栈续行）→ 继承上一个判定结果。
     */
    private static long lineTs(String line, long startMs, long inherit) {
        try {
            Matcher m = LSP_TIME.matcher(line);
            Date d = null;
            if (m.find()) {
                d = tsFormat().parse(m.group(1) + "T" + m.group(2));
            } else {
                m = LOGCAT_TIME.matcher(line);
                if (m.find()) {
                    String year = now("yyyy");
                    d = tsFormat().parse(year + "-" + m.group(1) + "T" + m.group(2));
                }
            }
            if (d == null) return inherit; // 堆栈续行：跟随上一行
            return d.getTime() >= startMs ? d.getTime() : Long.MIN_VALUE;
        } catch (ParseException pe) {
            return inherit;
        }
    }

    private static SimpleDateFormat tsFormat() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    }

    /* ==================== 落盘（Download） ==================== */

    private static String[] writeToDownloads(Context ctx, String content, int minutes)
            throws Exception {
        String name = "HomeMode4Miui_log_"
                + now("yyyyMMdd_HHmmss") + "_"
                + rangeTag(minutes) + ".log";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("MediaStore insert 返回 null");

        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        OutputStream os = null;
        try {
            os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) throw new IllegalStateException("openOutputStream 返回 null");
            os.write(data);
            os.flush();
        } finally {
            if (os != null) try {
                os.close();
            } catch (Throwable ignored) {
            }
        }

        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, cv, null, null);

        // 查询最终文件名（MediaStore 可能自动加 " (1)" 后缀防重名）+ 真实落盘路径
        String finalName = name;
        String fullPath = null;
        try {
            android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    if (c.getString(0) != null) finalName = c.getString(0);
                    // DATA（绝对路径）在 API 29+ 仍由 MediaStore 兼容返回；取不到时
                    // 用 RELATIVE_PATH + 标准主存储前缀拼出，最后兜底 Download 直拼
                    if (c.getString(1) != null && c.getString(1).length() > 0) {
                        fullPath = c.getString(1);
                    } else if (c.getString(2) != null) {
                        String rel = c.getString(2);
                        fullPath = "/storage/emulated/0/"
                                + (rel.endsWith("/") ? rel : rel + "/") + finalName;
                    }
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        if (fullPath == null) {
            fullPath = "/storage/emulated/0/Download/" + finalName;
        }

        return new String[]{
                ctx.getString(com.homemode4miui.R.string.log_export_done,
                        humanSize(data.length)) + "\n" + fullPath,
                fullPath};
    }

    /* ==================== 小工具 ==================== */

    private static String now(String fmt) {
        return new SimpleDateFormat(fmt, Locale.US).format(new Date());
    }

    private static String humanRange(int minutes) {
        return minutes >= 60 ? (minutes / 60) + " 小时" : minutes + " 分钟";
    }

    private static String rangeTag(int minutes) {
        return minutes >= 60 ? (minutes / 60) + "h" : minutes + "m";
    }

    private static String humanSize(long bytes) {
        return bytes >= 1024 * 1024
                ? String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
                : String.format(Locale.US, "%.1f KB", bytes / 1024.0);
    }

    private static String selfVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
