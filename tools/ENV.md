# 本机开发环境（dev-env）清单

布置日期：2026-10-08。全部组件经镜像站获取，无需 Android Studio / 管理员权限。

## 目录布局 `C:\Users\Administrator\dev-env\`

```
dev-env/
├── jdk-21/                      Temurin OpenJDK 21.0.12.1+1 LTS（清华 Adoptium 镜像）
└── android-sdk/
    ├── build-tools/36.1.0/      aapt2/d8/apksigner/zipalign（dl.google.com）
    ├── platforms/
    │   ├── android-34/          android.jar（现模块 build_apk.sh 用）
    │   ├── android-37/          android.jar（GraceLauncher 移植用，= 37.2）
    │   └── android-37.2/        同上原始目录名
    └── cmdline-tools/latest/    sdkmanager（后续按需装组件）
```

## Gradle

- 发行版：**9.5.0**（与 GraceLauncher 的 AGP 9.3.3 配套），腾讯镜像下载，sha256 与官方锚定一致
- 双路就位：
  1. 直接命令：`C:\Users\Administrator\dev-env\gradle-9.5.0\bin\gradle.bat`
  2. 项目 wrapper：仓库根 `gradlew` / `gradle/wrapper/*`（从 GraceLauncher-src 复制，
     `distributionUrl` 已改指腾讯镜像，sha256 保留官方锚定；wrapper 缓存已预热到
     `~/.gradle/wrapper/dists/gradle-9.5.0-bin/estug5qhtw5xqrldjyqnn9yr5/`）
- 全局 maven 镜像：`~/.gradle/init.gradle`（阿里云 google/public/gradle-plugin + 腾讯聚合，镜像优先、原仓库兜底）

## Xposed API

`app/libs/XposedBridgeApi-82.jar` —— 阿里云 maven 的 `de.robv.android.xposed:api:82`
（45 个 class，含 XposedBridge / IXposedHookLoadPackage）。编译期依赖，运行时由 LSPosed 提供。

## 已验证（2026-10-08）

- [x] `bash tools/build_apk.sh` 全链路出包：javac(45 classes) → d8 → aapt2 → dex 注入 → zipalign → apksigner 签名
      → `.build/out/HomeMode4Miui-v1.0.0-base.apk`
- [x] android-37 android.jar 编译冒烟通过（JDK21 javac）
- [x] `./gradlew --version` → Gradle 9.5.0（走预热缓存，未触网）

## build_apk.sh 本轮适配记录（Windows/Git Bash 兼容）

1. `@sources.txt` 内路径统一 `cygpath -m` 转 `C:/...`（javac 原生进程不认 `/c/...`）
2. `-classpath` 分隔路径同样显式转换（`ANDROID_JAR_W` / `CLASSPATH`）
3. d8 / apksigner 优先走 `java -jar lib/xxx.jar` 直调（Windows build-tools 只有 .bat）
4. 默认 CONFIG 路径已指向本 dev-env（JDK21 + build-tools 36.1.0 + android-34）

## 环境变量速查（Git Bash）

```bash
export JAVA_HOME=/c/Users/Administrator/dev-env/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/c/Users/Administrator/dev-env/android-sdk   # sdkmanager 等工具识别
```
