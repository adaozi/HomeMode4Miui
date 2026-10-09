# 换机开发指南（迁移到新机器）

> 把 HomeMode4Miui 的开发环境从一台机器完整搬到另一台机器的清单。
> 结论先行：**git 里的东西随包/随 clone 自动走；git 外必须人工带走的只有 3 样**——
> 签名密钥、Gradle 镜像配置、（可选）GraceLauncher 参考源码。
> 随附的开发套件 zip（`HomeMode4Miui-devkit-*.zip`）已把可预置的都放到位，解压即用。

---

## 0. 一分钟结论

```
新机器上你要做的全部事情：
1. 装 JDK 21 + Android SDK（约 2GB 磁盘）
2. 设 JAVA_HOME / ANDROID_HOME 两个环境变量
3. 把 _devkit/init.gradle 复制到 ~/.gradle/init.gradle
4. 解压套件（密钥已预置在 <项目>/.build/ks/，无需再动）
5. ./gradlew :app:assembleRelease  ← 首跑自动从腾讯镜像拉 Gradle 9.5.0 + 依赖
6. 按第 5 节清单验证（重点：签名 SHA-256 必须与旧机一致）
```

---

## 1. 随 git 自动走的东西（无需操心）

| 内容 | 说明 |
| --- | --- |
| 全部源码 / 资源 / 字体 / `docs/` / `tools/` | 254+ 文件 |
| `gradle/wrapper/gradle-wrapper.jar` | wrapper 引导 jar，`.gitignore` 有 `!gradle/wrapper/gradle-wrapper.jar` 例外保它入库 |
| `gradle/wrapper/gradle-wrapper.properties` | `distributionUrl` 已指腾讯镜像 + `distributionSha256Sum` sha256 锚定，新机器首跑自动下载 Gradle 9.5.0 并校验完整性 |
| `app/libs/XposedBridgeApi-82.jar` | Xposed 编译期 API，`.gitignore` 有 `!app/libs/*.jar` 例外保它入库 |
| `app/proguard-rules.pro` | R8 放行规则（发布瘦身，见 PORTING_GraceLauncher.md §④） |

---

## 2. **不在 git 里、必须人工带走的东西**

| 文件 | 旧机位置 | 为什么必须 | 套件内位置 |
| --- | --- | --- | --- |
| **`debug.keystore`** | `<项目>/.build/ks/debug.keystore` | **签名连续性（最关键）**。丢了就只能 keytool 重新生成 → 新 APK 签名与设备上已装模块不一致 → 无法覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），被迫卸载，**用户设置/Room 数据库全丢** | 已预置在 `<项目>/.build/ks/` ✅ |
| **`init.gradle`** | `~/.gradle/init.gradle` | 用户级全局镜像（阿里云 google/public/gradle-plugin + 腾讯聚合）。国内网络没它，依赖解析极慢或超时 | `_devkit/init.gradle`，需手动复制 |
| `GraceLauncher-src/` | 项目根 | GPL-3.0 参考源码（364 文件，9.2MB），移植对照用（C 组/ItemIcon 等）。丢了可按 `docs/PORTING_GraceLauncher.md` §1 重新下载 | 已预置在项目根 ✅（可选） |

> 另：`.workbuddy/` 是助手本机会话数据、`.gradle/` `.kotlin/` `build/` 是缓存，**都不需要带**。

---

## 3. 新机器需要安装的（约 2GB 磁盘）

| 组件 | 版本 | 获取方式 |
| --- | --- | --- |
| JDK | **Temurin OpenJDK 21 LTS**（旧机 21.0.12.1+1） | 清华 Adoptium 镜像；解压/安装到任意目录，如 `dev-env/jdk-21` |
| Android SDK build-tools | **36.1.0** | `sdkmanager "build-tools;36.1.0"`（走 dl.google.com，必要时挂代理/镜像） |
| Android SDK platform | **android-37**（= 37.2） | `sdkmanager "platforms;android-37"` |
| SDK 许可 | — | `yes \| sdkmanager --licenses`（没接受 license 构建会直接失败） |

NDK 不需要（本项目无 native 代码）。

---

## 4. 环境变量

Git Bash（`~/.bashrc`）：

```bash
export JAVA_HOME=/path/to/jdk-21          # 指向 JDK 根目录（其下有 bin/java）
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/path/to/android-sdk  # sdkmanager 与 AGP 都识别
```

PowerShell（`$PROFILE`）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
```

**`local.properties`（可选）**：本机没有此文件，靠 `ANDROID_HOME` 环境变量定位 SDK。若新机器不便设全局变量，在**项目根**建一个（路径用正斜杠或双反斜杠）：

```properties
sdk.dir=C:/path/to/android-sdk
```

> ⚠️ `local.properties` 在 `.gitignore` 里，是机器相关文件，**不要提交**。

---

## 5. 首次构建验证清单（按序执行）

| # | 命令 | 预期 |
| --- | --- | --- |
| 1 | `java -version` | `21.x`（Temurin） |
| 2 | `./gradlew --version` | Gradle **9.5.0**；JVM 显示 21（首跑从腾讯镜像下载发行版，`distributionSha256Sum` 自动校验，国内网络约 1~2 分钟） |
| 3 | `./gradlew :app:assembleRelease` | `BUILD SUCCESSFUL`；首次构建需联网拉依赖（走阿里云镜像），R8/KSP 任务正常执行 |
| 4 | **签名校验（必做）** | 见下方命令，`SHA-256 digest` 必须等于 `88a9536994a63fcb590b78f5d83b0119c114fa30bab3f7482e50306be367afd0` |
| 5 | APK 体积 | `app/build/outputs/apk/release/app-release.apk` ≈ **7.3MB**（R8 已开启） |

第 4 步命令（build-tools 自带 apksigner）：

```bash
"$JAVA_HOME/bin/java" -jar "$ANDROID_HOME/build-tools/36.1.0/lib/apksigner.jar" \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

> 签名 SHA-256 不一致 = 用错/重新生成了 keystore。**铁律：调试 keystore 只能用 `.build/ks/debug.keystore` 这一份，绝不能 keytool 重生成**，否则设备上已装的模块无法覆盖升级。

---

## 6. 常见坑

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| `SDK location not found` | 既没设 `ANDROID_HOME` 也没 `local.properties` | 二者取其一（见 §4） |
| `Failed to install the following Android SDK packages... licenses not accepted` | 新装 SDK 未接受许可 | `yes \| sdkmanager --licenses` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 新 APK 签名 ≠ 已装模块签名 | 核对 keystore 是不是原来那份（§5 第 4 步） |
| Gradle 下载慢/校验失败 | 改动了 `distributionUrl` | 别动它——已锚定腾讯镜像 + sha256 |
| 依赖解析超时 | `~/.gradle/init.gradle` 没复制 | 按 §2 放好（镜像优先、原仓库兜底） |
| 旧脚本 `tools/build_apk.sh` 路径报错 | Windows/Unix 路径差异 | 该脚本已做 cygpath 适配；日常构建走 Gradle 链路即可，无需此脚本 |

---

## 7. 可选加速：把 Gradle 缓存也带过去

旧机 `~/.gradle/`（含 wrapper 发行版缓存 + 全部依赖缓存）约 1.5GB。整体复制到新机 `~/.gradle/` 可跳过所有首次下载；不复制也只是首跑多花几分钟，无功能影响。
