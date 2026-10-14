# =============================================================================
# HomeMode4Miui — R8 放行规则（release 瘦身）
#
# 背景：本模块运行在宿主 com.miui.home 进程内，LSPosed 通过 assets/xposed_init
#       按【全限定类名】反射实例化入口；模块还直接调用与宿主【同名同包】的编译期桩。
# 原则：凡是「运行期靠字符串 / 反射 / 宿主同名」解析的东西，一律禁止改名或裁除。
#       其余（Compose / Kotlin / AndroidX / Room 实现体）交 R8 正常压缩裁剪。
# =============================================================================

# ---- 1. Xposed 模块入口（xposed_init 按类名实例化，改名即失效）--------------
-keep class com.homemode4miui.MainHook { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# ---- 1b. Modern API 入口（META-INF/xposed/java_init.list 引用，OS4 派发路径）--
-keep class com.homemode4miui.ModernEntry { *; }
-keep class com.homemode4miui.hook.Os4Probe { *; }

# ---- 2. 宿主同名桩：方法调用运行期解析到宿主真实类 --------------------------
#    类名/成员名一旦被 R8 改写，调用点将指向不存在的类 → NoSuchMethodError，桌面必崩。
-keep class com.miui.home.** { *; }

# ---- 3. 清单组件（AGP 默认已保，此处显式声明为双保险）----------------------
#    MainActivity 另被 HomeSheets.kt 以字符串 "com.homemode4miui.MainActivity" 启动
-keep class com.homemode4miui.MainActivity { *; }
-keep class com.homemode4miui.SettingsActivity { *; }
-keep class com.homemode4miui.bridge.GraceKeepAliveService { *; }
-keep class com.galaxyrio.gracelauncher.data.media.MediaNotificationListener { *; }
-keep class com.galaxyrio.gracelauncher.platform.BreezyWeatherUpdateReceiver { *; }

# ---- 4. Room 生成物（运行时按注解约定加载，双保险）------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ---- 5. 编译期依赖告警抑制（Xposed API 与宿主类不在 APK 内）----------------
-dontwarn de.robv.android.xposed.**
-dontwarn com.miui.home.**

# ---- 6. 自有代码保留可读类名/成员名（设备日志定位崩溃的前提）----------------
#    铁律：异常必须带 stack trace。混淆后栈帧变成 a.b.c() 无法定位，
#    故 com.homemode4miui（底座）+ com.galaxyrio.gracelauncher（移植 UI）不参与改名。
#    仍允许 R8 裁除未引用成员（这是主要瘦身来源，不冲突）。
-keepnames class com.homemode4miui.** { *; }
-keepnames class com.galaxyrio.gracelauncher.** { *; }

# ---- 7. 保留行号（崩溃日志可精确到行）--------------------------------------
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod,AnnotationDefault
