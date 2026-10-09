#!/bin/bash
# ============================================================================
# HomeMode4Miui 手动构建管线（无 Gradle / 无 Android Studio 的最小化构建）
#
# 用途：只依赖 JDK + Android build-tools + platform android.jar + Xposed API，
#       完成 javac → d8 → aapt2 → 注入 dex → zipalign → apksigner 全流程。
#       适合 CI / 沙箱 / 不想装 Android Studio 的场景。
#
# 用法：
#   bash tools/build_apk.sh
#
# 依赖（通过环境变量覆盖，默认值见下方 CONFIG）：
#   JAVA_HOME     JDK 17（javac/d8/apksigner 都需要）
#   BUILD_TOOLS   Android build-tools 目录（内含 d8.bat / aapt2 / zipalign / apksigner）
#   ANDROID_JAR   platform android-34 的 android.jar
#   XPOSED_JAR    XposedBridgeApi-82.jar
#
# 升级版本：改下面的 VERSION_CODE / VERSION_NAME 即可（会写进构建副本 manifest）。
# ============================================================================
set -e

# ---- CONFIG（按本机实际路径覆盖）--------------------------------------------
PROJ="${PROJ:-$(cd "$(dirname "$0")/.." && pwd)}"
BUILD="${BUILD:-$PROJ/.build}"

# 本机 dev-env（2026-10-08 布置：JDK21 + build-tools 36.1.0 + platform 34/37）
JAVA_HOME="${JAVA_HOME:-/c/Users/Administrator/dev-env/jdk-21}"
BUILD_TOOLS="${BUILD_TOOLS:-/c/Users/Administrator/dev-env/android-sdk/build-tools/36.1.0}"
ANDROID_JAR="${ANDROID_JAR:-/c/Users/Administrator/dev-env/android-sdk/platforms/android-34/android.jar}"
XPOSED_JAR="${XPOSED_JAR:-$PROJ/app/libs/XposedBridgeApi-82.jar}"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0-base}"
# ----------------------------------------------------------------------------

JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"
D8="$BUILD_TOOLS/d8"
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

# 原生 Windows 工具（javac/java）需要 Windows 形态路径；Git Bash 下显式转换，其他环境原样
if command -v cygpath >/dev/null 2>&1; then
  ANDROID_JAR_W="$(cygpath -m "$ANDROID_JAR")"
  XPOSED_JAR_W="$(cygpath -m "$XPOSED_JAR")"
else
  ANDROID_JAR_W="$ANDROID_JAR"
  XPOSED_JAR_W="$XPOSED_JAR"
fi
CLASSPATH="$ANDROID_JAR_W;$XPOSED_JAR_W"

APP="$PROJ/app"
OUT="$BUILD/out"
APK_NAME="HomeMode4Miui-v${VERSION_NAME}.apk"
KS="$BUILD/ks/debug.keystore"

export JAVA_HOME
rm -rf "$OUT"; mkdir -p "$OUT/obj" "$OUT/dex"

echo "== 1. javac (Java 11, classpath = android.jar + xposed api) =="
# Git Bash 环境：@file 内的路径 javac 原生进程不认 /c/... 形态，统一转成 C:/...（cygpath -m）
if command -v cygpath >/dev/null 2>&1; then
  find "$APP/src/main/java" "$APP/src/host/java" -name '*.java' | \
    while IFS= read -r f; do cygpath -m "$f"; done > "$OUT/sources.txt"
else
  find "$APP/src/main/java" "$APP/src/host/java" -name '*.java' > "$OUT/sources.txt"
fi
wc -l < "$OUT/sources.txt"
set +e
# -J-Duser.language=en：避免非英文 locale 下 javac 报错信息乱码/中断
"$JAVAC" -J-Duser.language=en -source 11 -target 11 -encoding UTF-8 -nowarn \
  -classpath "$CLASSPATH" \
  -d "$OUT/obj" @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1
JAVAC_RC=$?
set -e
head -40 "$OUT/javac.log"
N_CLASSES=$(find "$OUT/obj" -name '*.class' | wc -l)
echo "   javac rc=$JAVAC_RC, classes: $N_CLASSES"
if [ "$JAVAC_RC" -ne 0 ] || [ "$N_CLASSES" -eq 0 ]; then echo "JAVAC FAILED"; exit 1; fi

echo "== 2. d8 desugar/dex (min-api 33) =="
"$JAR" cf "$OUT/classes.jar" -C "$OUT/obj" .
# Windows build-tools 只有 d8.bat；用 java -jar 直调 lib/d8.jar 更稳（绕开 .bat 参数转换）
if [ -f "$BUILD_TOOLS/lib/d8.jar" ]; then
  "$JAVA_HOME/bin/java" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api 33 --lib "$ANDROID_JAR_W" --output "$OUT/dex" "$OUT/classes.jar"
else
  "$D8" --release --min-api 33 --lib "$ANDROID_JAR_W" --output "$OUT/dex" "$OUT/classes.jar"
fi
ls -la "$OUT/dex/"

echo "== 3. aapt2 compile + link =="
"$AAPT2" compile --dir "$APP/src/main/res" -o "$OUT/res.zip"
# aapt2 需要 manifest 带 package 属性（AGP 场景由插件注入，这里生成构建副本并注入版本号）
python - "$APP/src/main/AndroidManifest.xml" "$OUT/AndroidManifest.xml" \
        "$VERSION_CODE" "$VERSION_NAME" <<'PYEOF'
import sys
src, dst, vc, vn = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
xml = open(src, encoding='utf-8').read()
xml = xml.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    package="com.homemode4miui"\n'
    f'    android:versionCode="{vc}"\n'
    f'    android:versionName="{vn}">')
open(dst, 'w', encoding='utf-8').write(xml)
print("manifest copy written:", vc, vn)
PYEOF
"$AAPT2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 33 --target-sdk-version 34 \
  -A "$APP/src/main/assets" \
  "$OUT/res.zip"
echo "   base.apk: $(ls -la "$OUT/base.apk" | awk '{print $5}') bytes"

echo "== 4. add classes.dex into APK =="
python - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
data = open(dex, 'rb').read()  # d8 输出的是裸 DEX，不是 zip
with zipfile.ZipFile(apk, 'a') as z:
    if 'classes.dex' in z.namelist():
        raise SystemExit('classes.dex already present!')
    z.writestr(zipfile.ZipInfo('classes.dex', date_time=(2026,10,5,0,0,0)), data, zipfile.ZIP_DEFLATED)
print("classes.dex added:", len(data), "bytes")
PYEOF

echo "== 5. zipalign =="
"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$ZIPALIGN" -c 4 "$OUT/aligned.apk" && echo "   align OK"

echo "== 6. debug keystore + apksigner =="
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias homemode4miui \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=HomeMode4Miui Debug,O=homemode4miui,C=CN"
fi
# Windows build-tools 只有 apksigner.bat；同样用 java -jar 直调
if [ -f "$BUILD_TOOLS/lib/apksigner.jar" ]; then
  "$JAVA_HOME/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KS" --ks-key-alias homemode4miui \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
  "$JAVA_HOME/bin/java" -jar "$BUILD_TOOLS/lib/apksigner.jar" verify --print-certs "$OUT/$APK_NAME" | head -4
else
  "$APKSIGNER" sign --ks "$KS" --ks-key-alias homemode4miui \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/$APK_NAME" "$OUT/aligned.apk"
  "$APKSIGNER" verify --print-certs "$OUT/$APK_NAME" | head -4
fi

echo "== DONE =="
ls -la "$OUT/$APK_NAME"
