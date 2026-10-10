package com.homemode4miui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块开关界面：
 *  1) 模式开关的 UI 与回显（模块自有 SP 仅存回显）；负一屏手势拦截已固化、无开关
 *     （自定义模式激活时恒定隔离，用户不可选择）；
 *  2) 向 com.miui.home 发显式广播实时切换/改写（宿主进程写宿主 SP，始终生效）。
 *
 * gate1（用户定案）：权限前置 —— 三个条件全部就绪才允许开启第四桌面：
 *   ① LSPosed hook 生效（ping/pong 探测：首页发 ACTION_HOOK_PING，宿主
 *      MainHook 回 ACTION_HOOK_LIVE；宿主 hook 安装完成时也会主动广播一次）；
 *   ② 通知监听授权（MediaAccess 查询 + 跳系统设置引导）；
 *   ③ root 保活（RootKeepAlive.ensure su 探测，成功即执行 Doze 白名单/
 *      PowerKeeper/自启动三连，并发 ACTION_SET_ROOT_READY 落宿主 SP）。
 * 权限不齐：开关禁用 + Toast 说明缺项；已开启后权限丢失：onResume 自动关闭
 * 模式（宿主侧裁决 hook 同时兜底回落，双保险）。
 *
 * 桌面 UI 本体不在本进程：本项目运行于 com.miui.home 进程内（LSPosed 注入），
 * 自定义界面由 HomeView / HomeViewFactory 提供，见 README「接入你的桌面 UI」。
 */
public class MainActivity extends Activity {

    private static final long HOOK_PING_TIMEOUT_MS = 2000;

    private SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // gate1 三项状态（本会话实时值；root 回显持久化在模块自有 SP）
    private volatile boolean mHookLive;
    private volatile String mHookHostVersion;
    /** 宿主大版本家族标签（OS2(5.x)/OS3(6.x)/UNKNOWN，pong/live 附带） */
    private volatile String mHookFamily;
    /** 未生效 hook 清单（pong/live 附带；空 = 全部生效） */
    private volatile java.util.List<String> mHookFailed;
    private boolean mRootReady;
    // gate6：MIUI 自启动三态（UNKNOWN 不阻断，仅提示无法检测）
    private int mAutoStartState;
    private Switch mModeSwitch;
    private TextView mGateSummary;
    private TextView mHookStatus;
    private TextView mNotifStatus;
    private TextView mRootStatus;
    private TextView mAutoStartStatus;
    private TextView mMediaStatusRef;
    private TextView bridgeDiag;
    /** 上次日志导出的落盘路径（常驻回显） */
    private TextView mExportPath;

    private final BroadcastReceiver mHookLiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (MainHook.ACTION_HOOK_LIVE.equals(action)) {
                mHookLive = true;
                mHookHostVersion = i.getStringExtra(MainHook.EXTRA_HOST_VERSION);
                mHookFamily = i.getStringExtra(MainHook.EXTRA_HOST_FAMILY);
                mHookFailed = i.getStringArrayListExtra(MainHook.EXTRA_HOOK_FAILED);
                mHandler.removeCallbacks(mHookTimeout);
                refreshGateUi();
                // gate3：探测结果落定（通过）后才做自动回落判定
                autoCloseIfGateLost();
                return;
            }
            // gate4：宿主拒绝开启的回执 —— 无声失败可见化
            if (MainHook.ACTION_TOGGLE_REJECTED.equals(action)) {
                String reason = i.getStringExtra(MainHook.EXTRA_GATE_REASON);
                boolean notif = reason != null && reason.startsWith("notification");
                boolean autostart = reason != null && reason.startsWith("autostart");
                Toast.makeText(MainActivity.this,
                        "桌面已拒绝开启第四桌面：宿主侧" + (notif
                                ? "未读到通知使用权（回首页后重试）"
                                : autostart ? "MIUI 自启动未开启（首页第④项引导开启）"
                                : "root 标志未同步（回首页自动补发，或重新检测 root）"),
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    private final Runnable mHookTimeout = new Runnable() {
        @Override public void run() {
            if (!mHookLive) {
                refreshGateUi();
                // gate3：探测结果落定（超时未应答）后才做自动回落判定
                autoCloseIfGateLost();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // C 组（C1/C4）：桌面"Grace settings"/时钟样式行 → 模块主入口转发到设置页。
        // 经 LAUNCHER Activity 中转是 P6 hotfix3 实锤的 MIUI BAL 可行路径。
        if ("grace_settings".equals(getIntent() != null ? getIntent().getStringExtra("route") : null)) {
            Intent settings = new Intent(this, SettingsActivity.class);
            String page = getIntent().getStringExtra("page");
            if (page != null) settings.putExtra("page", page);
            startActivity(settings);
            finish();
            return;
        }

        mPrefs = getSharedPreferences(ModePrefs.PREF_FILE, MODE_PRIVATE);
        mRootReady = mPrefs.getBoolean(ModePrefs.KEY_ROOT_READY, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("HomeMode4Miui");
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\n为小米桌面（com.miui.home）新增第四种桌面模式（原生同款机制）。\n"
                + "切换走原生链路，与三种原生模式互不干扰；"
                + "也可在桌面设置页「实验性第四选项」切换。\n"
                + "提示：请先关闭系统「极简模式」");
        desc.setTextSize(15);
        root.addView(desc);

        // ==================== gate1：使用前置条件 ====================
        TextView gateTitle = new TextView(this);
        gateTitle.setText("\n使用前置条件（全部就绪后才能启用第四桌面）");
        gateTitle.setTextSize(16);
        gateTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(gateTitle);

        mHookStatus = new TextView(this);
        root.addView(mHookStatus);

        Button hookReprobe = new Button(this);
        hookReprobe.setText("重新检测 hook");
        hookReprobe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeHook();
            }
        });
        root.addView(hookReprobe);

        TextView hookHint = new TextView(this);
        hookHint.setText("刚更新模块 APK 的话，hook 代码只在桌面进程启动时注入 ——\n"
                + "必须强停「系统桌面」（或重启手机）后重新检测。");
        hookHint.setTextSize(13);
        hookHint.setTextColor(0xFF888888);
        root.addView(hookHint);

        // 日志导出（bug 反馈用）：导出到 Download 文件夹，范围可选
        Button exportLog = new Button(this);
        exportLog.setText("导出日志（bug 反馈用）");
        exportLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogExportDialog();
            }
        });
        root.addView(exportLog);

        // 上次导出路径常驻回显（导出后显示完整落盘路径，方便用户到文件管理器取文件）
        mExportPath = new TextView(this);
        mExportPath.setTextSize(12);
        mExportPath.setTextColor(0xFF555555);
        mExportPath.setPadding(0, dp(2), 0, dp(8));
        mExportPath.setVisibility(View.GONE);
        root.addView(mExportPath);

        mNotifStatus = new TextView(this);
        root.addView(mNotifStatus);

        mRootStatus = new TextView(this);
        root.addView(mRootStatus);

        // gate6：MIUI 自启动（第④项前置条件）
        mAutoStartStatus = new TextView(this);
        root.addView(mAutoStartStatus);

        Button autoStartBtn = new Button(this);
        autoStartBtn.setText("打开 MIUI 自启动设置");
        autoStartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(AutoStartProbe.settingsIntent());
                } catch (Throwable t) {
                    // HyperOS 页面路径漂移：降级到应用详情页手动找「自启动」
                    try {
                        Intent detail = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:com.homemode4miui"));
                        startActivity(detail);
                        Toast.makeText(MainActivity.this,
                                "未能直达自启动页，请在应用详情里找到「自启动」开关并开启",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
        root.addView(autoStartBtn);

        Button rootProbe = new Button(this);
        rootProbe.setText("检测 root 权限并启用保活");
        rootProbe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRootStatus.setText("root 状态：检测中…（首次会弹出 Magisk 授权框，请选择允许）");
                com.homemode4miui.bridge.RootKeepAlive.INSTANCE.ensureJava(ok -> onRootProbeResult(ok));
            }
        });
        root.addView(rootProbe);

        Button notifAccess = new Button(this);
        notifAccess.setText("打开通知使用权设置");
        notifAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 前台 Activity 直启系统设置页：无后台启动限制
                startActivity(com.galaxyrio.gracelauncher.data.media.MediaAccess.INSTANCE.settingsIntent(MainActivity.this));
            }
        });
        root.addView(notifAccess);

        mGateSummary = new TextView(this);
        mGateSummary.setTextSize(14);
        mGateSummary.setPadding(0, dp(8), 0, dp(8));
        root.addView(mGateSummary);

        TextView gateHint = new TextView(this);
        gateHint.setText("root 保活用于阻止 MIUI 省电策略解绑通知监听服务（媒体/通知行的\n"
                + "前置）；通知权限丢失时桌面会自动回落原生，权限补齐后自动恢复，\n"
                + "无需重新开关。通知内容仅内存桥接，从不落盘或上传。");
        gateHint.setTextSize(13);
        gateHint.setTextColor(0xFF888888);
        root.addView(gateHint);

        // ==================== 模式开关 ====================
        final Switch sw = new Switch(this);
        sw.setText("启用第四桌面");
        sw.setChecked(mPrefs.getBoolean(ModePrefs.KEY_MODE_ENABLE, false));
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = sw.isChecked();
                if (on && !gateSatisfied()) {
                    // 前置条件不齐：弹回 + 指出缺项
                    sw.setChecked(false);
                    Toast.makeText(MainActivity.this,
                            "无法启用：" + missingGateText(), Toast.LENGTH_LONG).show();
                    return;
                }
                mPrefs.edit().putBoolean(ModePrefs.KEY_MODE_ENABLE, on).apply();
                sendToggle(on);
            }
        });
        mModeSwitch = sw;
        root.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 【已固化】负一屏手势拦截无开关：自定义模式激活时 GestureIsolator
        // 恒定隔离全部宿主手势控制器，右滑不进负一屏；负一屏由自定义 UI 经
        // hook/HomeController.openMinusOneScreen() 主动打开。

        // ---- hotfix4：桥自诊断（系统授权记录 / 活动媒体会话 / 引擎状态） ----
        // hotfix6：授权状态之后（用户最关心），根布局 ScrollView 防截断
        bridgeDiag = new TextView(this);
        bridgeDiag.setTextSize(12);
        bridgeDiag.setTextColor(0xFF555555);
        root.addView(bridgeDiag);

        // 从本 Activity 前台拉起桥引擎（幂等）：即使 listener 尚未被系统 bind，
        // 空 快照 也会发往宿主 —— 桌面日志出现 bridge snapshot = 桥通
        try {
            com.homemode4miui.bridge.GraceBridgeEngine.INSTANCE.start(this);
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "engine start failed", t);
        }

        TextView portHint = new TextView(this);
        portHint.setText("\n移植你的桌面 UI：\n"
                + "1. 新建类继承 home/HomeView，实现 onHomeAttached 等生命周期；\n"
                + "2. 把 home/HomeViewFactory.create() 的返回值换成你的根视图；\n"
                + "3. 完事。挂载/隐藏/手势隔离/切换链全部由底座处理。");
        portHint.setTextSize(13);
        portHint.setTextColor(0xFF888888);
        root.addView(portHint);

        Button restore = new Button(this);
        restore.setText("恢复原生桌面（发广播关闭自定义模式）");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.setChecked(false);
                mPrefs.edit().putBoolean(ModePrefs.KEY_MODE_ENABLE, false).apply();
                sendToggle(false);
            }
        });
        root.addView(restore, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // hotfix6：根布局套 ScrollView —— 竖屏小屏上内容超出首屏（自诊断区块被截断）
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(root);
        setContentView(scroller);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // gate1：hook pong + gate4 拒绝回执接收（随 Activity 窗口存活，exported 收宿主显式广播）
        try {
            IntentFilter f = new IntentFilter(MainHook.ACTION_HOOK_LIVE);
            f.addAction(MainHook.ACTION_TOGGLE_REJECTED);
            registerReceiver(mHookLiveReceiver, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "register hook-live receiver failed", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // gate1：hook 探测（发 ping，超时按未生效回显）+ 权限齐备性回显。
        // gate3 修正（装机实锤）：自动回落判定【不能】在这里做 —— probeHook 刚把
        // mHookLive 清 false，pong 未返回（广播往返几百 ms）时 gateSatisfied()
        // 必然 false，会把正常开启的模式误关（"三项全绿却提示前置条件丢失"
        // Toast 的根因）。判定移到 mHookTimeout（落定失败）与 pong receiver
        // （落定通过）两处执行。
        probeHook();
        // gate6：自启动探测（本进程查自己包，无跨包限制）+ 状态变化落宿主 SP
        probeAutoStart();
        // gate4：root 标志补发 —— 时序洞自愈。root 检测成功若发生在宿主【旧代码】
        // 在跑期间，SET_ROOT_READY 广播没人写宿主 SP；宿主强停换新代码后标志缺失
        // → toggle 校验拒绝开启（开关开着却切不过去的根因）。模块侧已确认 root
        // 就绪就补发一次，宿主新代码在即同步落 SP（广播丢失面与 ping/pong 同）。
        if (mRootReady) sendRootReady(true);
        // 从设置页授权返回后回显最新状态
        refreshGateUi();
        if (mMediaStatusRef != null) refreshMediaStatus(mMediaStatusRef);
    }

    /** gate1/gate4：root 标志广播（探测成功 / onResume 补发共用） */
    private void sendRootReady(boolean ready) {
        try {
            Intent i = new Intent(MainHook.ACTION_SET_ROOT_READY);
            i.setPackage("com.miui.home");
            i.putExtra(MainHook.EXTRA_READY, ready);
            sendBroadcast(i);
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "root-ready broadcast failed", t);
        }
    }

    /**
     * gate6：自启动探测（onResume 每次；本进程查自己包无跨包限制）。
     * 状态变化才广播落宿主 SP（首次探测时上次值视为 -1 强制发送一次，
     * 复用 gate4 补发语义 —— 宿主旧代码期间探测结果会丢）。
     */
    private void probeAutoStart() {
        int last = mAutoStartState;
        mAutoStartState = AutoStartProbe.probe(this);
        boolean ready = mAutoStartState != AutoStartProbe.DENIED;
        if (mAutoStartState != last || last == 0) {
            try {
                Intent i = new Intent(MainHook.ACTION_SET_AUTOSTART_READY);
                i.setPackage("com.miui.home");
                i.putExtra(MainHook.EXTRA_READY, ready);
                sendBroadcast(i);
            } catch (Throwable t) {
                android.util.Log.e("HomeMode4Miui", "autostart-ready broadcast failed", t);
            }
        }
    }

    /** gate3：自动回落 —— 仅在 hook 探测结果【落定】后调用（pong 到达或超时）。 */
    private void autoCloseIfGateLost() {
        if (!mPrefs.getBoolean(ModePrefs.KEY_MODE_ENABLE, false) || gateSatisfied()) return;
        mPrefs.edit().putBoolean(ModePrefs.KEY_MODE_ENABLE, false).apply();
        if (mModeSwitch != null) mModeSwitch.setChecked(false);
        sendToggle(false);
        Toast.makeText(this, "前置条件丢失（" + missingGateText() + "），已自动恢复原生桌面",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mHookTimeout);
        try {
            unregisterReceiver(mHookLiveReceiver);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 日志导出（bug 反馈） ==================== */

    /** 范围选择：1h / 30m / 10m / 5m / 1m */
    private void showLogExportDialog() {
        final CharSequence[] ranges = new CharSequence[]{
                "最近 1 小时", "最近 30 分钟", "最近 10 分钟", "最近 5 分钟", "最近 1 分钟"};
        final int[] minutes = new int[]{60, 30, 10, 5, 1};
        new AlertDialog.Builder(this)
                .setTitle("导出日志范围（保存到 Download 文件夹，上限 10MB）")
                .setItems(ranges, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        doExportLog(minutes[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExportLog(final int minutes) {
        Toast.makeText(this, "正在导出日志…（范围：最近 "
                + (minutes >= 60 ? (minutes / 60) + " 小时" : minutes + " 分钟") + "）",
                Toast.LENGTH_SHORT).show();
        com.homemode4miui.util.LogExporter.export(this, minutes,
                new com.homemode4miui.util.LogExporter.Callback() {
                    @Override
                    public void onDone(String message) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                        // 路径常驻显示在导出按钮下方（失败信息也回显，方便截图反馈）
                        if (mExportPath != null) {
                            String pathLine = com.homemode4miui.util.LogExporter
                                    .pathLineOf(message);
                            mExportPath.setVisibility(View.VISIBLE);
                            mExportPath.setText(message.startsWith("已导出")
                                    ? "上次导出路径：\n" + pathLine
                                    : message);
                        }
                    }
                });
    }

    /* ==================== gate1 判定与回显 ==================== */

    /** hook 存活探测：发 ping → 宿主回 pong；超时（2s）按未生效回显。 */
    private void probeHook() {
        mHookLive = false;
        try {
            Intent ping = new Intent(MainHook.ACTION_HOOK_PING).setPackage("com.miui.home");
            sendBroadcast(ping);
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "hook ping failed", t);
        }
        mHandler.removeCallbacks(mHookTimeout);
        mHandler.postDelayed(mHookTimeout, HOOK_PING_TIMEOUT_MS);
    }

    private boolean gateNotif() {
        // gate5：与宿主侧同一查询通道（Settings.Secure），双端判定永远同源
        return PermissionGate.notificationAccessReady(this);
    }

    private boolean gateSatisfied() {
        return mHookLive && hookStatusOk() && gateNotif() && mRootReady && autoStartSatisfied();
    }

    /** gate6：自启动 GRANTED/UNKNOWN 都算满足（UNKNOWN 不阻断防误锁） */
    private boolean autoStartSatisfied() {
        return mAutoStartState != AutoStartProbe.DENIED;
    }

    private String missingGateText() {
        if (!mHookLive) return "LSPosed hook 未生效（启用模块+勾选系统桌面作用域，且强停系统桌面/重启手机让 hook 注入）";
        if (criticalHookFailed(mHookFailed)) {
            return "无法 hook 当前系统桌面（核心 hook 未生效：" + failedSummary()
                    + "）—— 桌面版本可能暂不受支持，请导出日志反馈";
        }
        if (!gateNotif()) return "缺少通知使用权";
        if (!mRootReady) return "未确认 root 权限（点击上方检测按钮）";
        if (mAutoStartState == AutoStartProbe.DENIED) return "MIUI 自启动未开启（点击上方按钮进入自启动设置开启 HomeMode4Miui）";
        return "";
    }

    private void refreshGateUi() {
        boolean notif = gateNotif();
        setTextState(mHookStatus, hookStatusText(), hookStatusColor());
        setTextState(mNotifStatus, notif
                        ? "② 通知使用权：已授权 ✓"
                        : "② 通知使用权：未授权 ✗（点击下方按钮，在列表中允许 HomeMode4Miui）",
                notif);
        setTextState(mRootStatus, mRootReady
                        ? "③ root 保活：已就绪 ✓"
                        : "③ root 保活：未确认 ✗（点击下方检测按钮，Magisk 授权框选允许）",
                mRootReady);
        // gate6：自启动三态回显（UNKNOWN 黄色：无法检测时不阻断，仅提示）
        if (mAutoStartStatus != null) {
            String text;
            int color;
            if (mAutoStartState == AutoStartProbe.GRANTED) {
                text = "④ MIUI 自启动：已开启 ✓";
                color = 0xFF2E7D32;
            } else if (mAutoStartState == AutoStartProbe.DENIED) {
                text = "④ MIUI 自启动：未开启 ✗（点击下方按钮开启，否则模块后台进程无法被拉起）";
                color = 0xFFC62828;
            } else {
                text = "④ MIUI 自启动：无法检测（不阻断；若被省电策略杀后台请手动开启）";
                color = 0xFFF57F17;
            }
            mAutoStartStatus.setText(text);
            mAutoStartStatus.setTextSize(14);
            mAutoStartStatus.setTextColor(color);
            mAutoStartStatus.setPadding(0, dp(4), 0, dp(4));
        }
        boolean all = gateSatisfied();
        mGateSummary.setText(all
                ? "✓ 前置条件全部就绪，可以启用第四桌面"
                : "✗ 尚未就绪：" + missingGateText());
        mGateSummary.setTextColor(all ? 0xFF2E7D32 : 0xFFC62828);
        if (mModeSwitch != null) mModeSwitch.setEnabled(all);
        refreshBridgeDiag();
    }

    private void setTextState(TextView tv, String text, boolean ok) {
        setTextState(tv, text, ok ? 0xFF2E7D32 : 0xFFC62828);
    }

    private void setTextState(TextView tv, String text, int color) {
        if (tv == null) return;
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setPadding(0, dp(4), 0, dp(4));
    }

    /* ---- gate1 hook 状态分级（含"无法 hook/部分生效"提示） ---- */

    /** 核心挂钩：任一失败即"无法 hook 当前桌面"（模式注册/裁决/接收器锚点） */
    private static boolean criticalHookFailed(java.util.List<String> failed) {
        if (failed == null) return false;
        for (String f : failed) {
            if (f.startsWith("1.getAllModes") || f.startsWith("2.modeAdjudication")
                    || f.startsWith("0.Application.onCreate")) return true;
        }
        return false;
    }

    private boolean hookStatusOk() {
        return mHookLive && !criticalHookFailed(mHookFailed);
    }

    private int hookStatusColor() {
        if (!mHookLive) return 0xFFC62828;                 // 红：未检测到
        if (criticalHookFailed(mHookFailed)) return 0xFFC62828; // 红：核心 hook 失败
        if (mHookFailed != null && !mHookFailed.isEmpty()) return 0xFFF57F17; // 黄：部分失败
        return 0xFF2E7D32;                                  // 绿：全部生效
    }

    private String hookStatusText() {
        if (!mHookLive) {
            return "① LSPosed hook：未检测到 ✗（若刚更新模块：先强停「系统桌面」再点重新检测）";
        }
        String host = "宿主桌面 " + mHookHostVersion
                + (mHookFamily != null ? " · " + mHookFamily : "");
        boolean critical = criticalHookFailed(mHookFailed);
        int failedCount = mHookFailed == null ? 0 : mHookFailed.size();
        if (critical || failedCount >= 3) {
            // 无法 hook 当前桌面：核心链路失败或大面积失败 —— 明确引导反馈
            return "① LSPosed hook：无法 hook 当前桌面 ✗（" + host + "）\n"
                    + "未生效：" + failedSummary() + "\n"
                    + "当前系统桌面版本可能暂不受模块支持 —— 请点上方「导出日志（bug 反馈用）」"
                    + "并把日志文件发给我们";
        }
        if (failedCount > 0) {
            return "① LSPosed hook：部分生效 ⚠（" + host + "）\n"
                    + "未生效 " + failedCount + " 项：" + failedSummary() + "\n"
                    + "相关功能可能受限；建议「导出日志」反馈";
        }
        return "① LSPosed hook：已生效 ✓（" + host + "）";
    }

    private String failedSummary() {
        if (mHookFailed == null || mHookFailed.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String f : mHookFailed) {
            if (sb.length() > 0) sb.append("；");
            sb.append(f);
            if (sb.length() > 150) {
                sb.append("…（共 ").append(mHookFailed.size()).append(" 项）");
                break;
            }
        }
        return sb.toString();
    }

    /** root 探测回调（主线程）：更新回显（模块自有 SP 持久化）+ 通知宿主落 SP */
    private void onRootProbeResult(boolean ok) {
        mRootReady = ok;
        mPrefs.edit().putBoolean(ModePrefs.KEY_ROOT_READY, ok).apply();
        sendRootReady(ok);
        refreshGateUi();
        Toast.makeText(this, ok ? "root 保活已启用（Doze 白名单 + MIUI 省电策略豁免）"
                        : "root 不可用：未授权或未刷入 root（自愈链仍会兜底，但通知可能被 MIUI 解绑）",
                Toast.LENGTH_LONG).show();
    }

    private void refreshMediaStatus(TextView status) {
        mMediaStatusRef = status;
        boolean granted = com.galaxyrio.gracelauncher.data.media.MediaAccess.INSTANCE.isGranted(this);
        status.setText(granted ? "授权状态：已开启 ✓（回桌面即可使用媒体控制与通知预览）"
                : "授权状态：未开启 ✗（点击下方按钮，在列表中允许 HomeMode4Miui）");
        status.setTextSize(14);
        status.setTextColor(granted ? 0xFF2E7D32 : 0xFFC62828);
        status.setPadding(0, dp(8), 0, dp(8));
        refreshBridgeDiag();
    }

    /** hotfix4：桥自诊断 —— 把系统授权记录与活动媒体会话直接显示出来。 */
    private void refreshBridgeDiag() {
        if (bridgeDiag == null) return;
        StringBuilder sb = new StringBuilder("\n[桥自诊断]\n");
        // ① 系统层授权记录（NotificationManagerService 持久化的 listener 列表）
        String enabled = android.provider.Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        boolean registered = enabled != null && enabled.contains("com.homemode4miui");
        sb.append("系统授权记录: ").append(registered ? "含本模块 ✓" : "不含本模块 ✗").append('\n');
        // ② 活动媒体会话（有监听权限才能查询；播放中应有 ≥1 条）
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            java.util.List<android.media.session.MediaController> sessions = msm.getActiveSessions(
                    com.galaxyrio.gracelauncher.data.media.MediaAccess.INSTANCE.component(this));
            sb.append("活动媒体会话: ").append(sessions.size()).append(" 条");
            for (android.media.session.MediaController c : sessions) {
                sb.append("\n  · ").append(c.getPackageName());
            }
        } catch (SecurityException e) {
            sb.append("活动媒体会话: 查询被拒（SecurityException —— 系统授权记录与实际权限不同步）");
        } catch (Throwable t) {
            sb.append("活动媒体会话: 查询失败 ").append(t);
        }
        sb.append('\n');
        bridgeDiag.setText(sb.toString());
    }

    private void sendToggle(boolean on) {
        Intent i = new Intent(MainHook.ACTION_TOGGLE);
        i.setPackage("com.miui.home"); // 显式广播直达桌面进程
        i.putExtra(MainHook.EXTRA_ON, on);
        sendBroadcast(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
