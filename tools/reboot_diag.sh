#!/bin/bash
# ============================================================================
# HomeMode4Miui 负一屏重启失效 —— 重启全程取证脚本
#
# 用途：复现「重启手机后负一屏小部件显示"无法添加微件"」时，从开机时刻起
#       分阶段采集系统侧状态，把 PA widget 绑定链的死亡时刻钉死在时间线上。
#
# 用法（子命令制，可单独调用，也可由外部脚本串联）：
#   bash tools/reboot_diag.sh snap  <dir> <label>   # 采一份阶段快照
#   bash tools/reboot_diag.sh wait_boot <dir>       # 等设备回连+开机完成
#   bash tools/reboot_diag.sh wait_unlock <dir>     # 等解锁进桌面（focus=miui.home）
#   bash tools/reboot_diag.sh dump <dir> <label>    # dump 全量 logcat buffer
#
# 判定要点（对照分析用）：
#   1. boot_early vs boot_settled 的 dumpsys appwidget：
#      - Providers 注册表何时出现/缺失（notes / misettings 的私有协议 widget）
#      - Widgets 段 PA host 的 widget id 演变（旧 id 何时消失、有无新 id 分配）
#   2. logcat dump 的 PA(10012 段) WidgetLoader / removeInvalidWidgets /
#      became invalid 打点时刻 —— PA 判死 widget 的确切时机
#   3. 模块打点（HomeMode4Miui tag）在开机段是否被触发过
# ============================================================================
set -u

PROJ="$(cd "$(dirname "$0")/.." && pwd)"

# adb 探测：PATH 优先，回退 SDK platform-tools（local.properties 的 sdk.dir）
ADB="adb"
command -v adb >/dev/null 2>&1 || {
  SDK_DIR="$(grep -oP 'sdk\.dir=\K.*' "$PROJ/local.properties" 2>/dev/null | tr -d '\r')"
  [ -n "$SDK_DIR" ] && ADB="$(cygpath -u "$SDK_DIR")/platform-tools/adb.exe"
}

# Git Bash：禁 MSYS 路径转换（设备内路径 /data/... 不得被改写）
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

# 快照内容：进程 / widget 系统态 / overlay service 绑定 / 窗口焦点 / 进程 adj
snap() {
  local dir="$1" label="$2"
  mkdir -p "$dir/$label"
  {
    echo "== snap $label at $(date '+%F %T') =="
    echo "-- device uptime --"
    "$ADB" shell uptime 2>/dev/null
    echo "-- PA / Home processes --"
    "$ADB" shell ps -A 2>/dev/null | grep -E 'miui\.home|personalassistant'
    echo "-- window focus --"
    "$ADB" shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedWindow' | head -2
  } > "$dir/$label/state.txt" 2>&1

  "$ADB" shell dumpsys appwidget > "$dir/$label/appwidget.txt" 2>&1
  "$ADB" shell dumpsys activity services com.miui.personalassistant \
    > "$dir/$label/pa_services.txt" 2>&1
  "$ADB" shell dumpsys activity processes 2>/dev/null \
    | grep -B1 -A3 -E 'personalassistant|miui\.home' > "$dir/$label/processes.txt" 2>&1
  echo "snap done: $label"
}

# 全量 logcat dump（buffer 会滚动；相邻 dump 间隔内 buffer 足够大，并集覆盖全程）
dump() {
  local dir="$1" label="$2"
  "$ADB" logcat -d -v time > "$dir/logcat_$label.log" 2>&1
  echo "logcat dump done: $label ($(wc -c < "$dir/logcat_$label.log") bytes)"
}

wait_boot() {
  local dir="$1"
  echo "waiting for device to come back (reboot) ..."
  "$ADB" wait-for-device
  echo "device reconnected at $(date '+%F %T')" | tee -a "$dir/timeline.txt"
  # 开机完成轮询（ unauthorized 时命令失败，保持等待并提示 ）
  local i=0
  while true; do
    local boot
    boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [ "$boot" = "1" ]; then break; fi
    if [ $((i % 15)) -eq 0 ]; then
      "$ADB" shell getprop sys.boot_completed 2>&1 | grep -q unauthorized \
        && echo "(device unauthorized: 请在手机上确认 USB 调试授权弹窗)"
    fi
    i=$((i + 1)); sleep 2
    [ $i -gt 300 ] && { echo "timeout waiting boot_completed"; return 1; }
  done
  echo "boot_completed=1 at $(date '+%F %T')" | tee -a "$dir/timeline.txt"
  dump "$dir" boot
}

wait_unlock() {
  local dir="$1"
  echo "waiting for unlock (window focus -> miui.home) ..."
  local i=0
  while true; do
    if "$ADB" shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedWindow' \
        | grep -q 'miui.home'; then
      break
    fi
    i=$((i + 1)); sleep 2
    [ $i -gt 300 ] && { echo "timeout waiting unlock"; return 1; }
  done
  echo "unlocked (focus=miui.home) at $(date '+%F %T')" | tee -a "$dir/timeline.txt"
}

case "${1:-}" in
  snap)  snap "$2" "$3" ;;
  dump)  dump "$2" "$3" ;;
  wait_boot)  wait_boot "$2" ;;
  wait_unlock) wait_unlock "$2" ;;
  *) echo "usage: $0 {snap|dump|wait_boot|wait_unlock} <dir> [label]"; exit 2 ;;
esac
