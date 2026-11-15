package com.homemode4miui.bridge

import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ThemeMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置行编解码护栏（v2.9.162 起）。
 *
 * 锁的是「铁律④」：LauncherSettings 的 39 列在 **4 处**手写 —— 模型 / encodeRow /
 * decodeRow / applyDelta。任何一处漏同步都不报错，只静默丢数据（设置改了不生效、
 * 重启后回弹成默认值）。历史上这类问题排查成本极高（表现为"开关点了自己弹回去"）。
 *
 * 核心手法 = **diff → apply 闭环**：只要 diffRow 输出的列在 applyDelta 里有对应处理，
 * 结果就必须与目标设置**完全相等**。漏一个字段，`assertEquals(target, applied)` 直接红。
 * 已用变异测试验证有效性（注释掉 applyDelta 任一分支 → 对应用例失败）。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 */
class GraceSettingsBridgeCodecTest {

    // ==================== diff → apply 闭环（覆盖全部标量列）====================

    @Test
    fun `diff then apply reproduces every boolean change`() {
        val base = LauncherSettings()
        val target = base.copy(
            clockEnabled = !base.clockEnabled,
            calendarAgenda = !base.calendarAgenda,
            calendarAboveClock = !base.calendarAboveClock,
            showBatteryPercentage = !base.showBatteryPercentage,
            allowHapticFeedback = !base.allowHapticFeedback,
            useDynamicColors = !base.useDynamicColors,
            amoledMode = !base.amoledMode,
            mediaPlayer = !base.mediaPlayer,
            mediaAlwaysVisible = !base.mediaAlwaysVisible,
            weatherEnabled = !base.weatherEnabled,
            hideStatusBar = !base.hideStatusBar,
            hideAlphabet = !base.hideAlphabet,
            hideFavoriteNames = !base.hideFavoriteNames,
            dimWallpaper = !base.dimWallpaper,
            blurWallpaper = !base.blurWallpaper,
            applyFontToSettings = !base.applyFontToSettings,
        )
        val delta = GraceSettingsBridge.diffRow(base, target)
        assertNotNull("diffRow 返回 null：布尔列没进 encodeRow", delta)
        assertEquals("布尔列变化应全部被 applyDelta 接受", target,
            GraceSettingsBridge.applyDelta(base, delta!!))
    }

    @Test
    fun `diff then apply reproduces every int change`() {
        val base = LauncherSettings()
        val target = base.copy(
            themeColor = base.themeColor + 1,
            weatherForecastDays = base.weatherForecastDays + 1,
            wallpaperDimAmount = base.wallpaperDimAmount + 1,
            wallpaperBlurRadius = base.wallpaperBlurRadius + 1,
        )
        val delta = GraceSettingsBridge.diffRow(base, target)
        assertNotNull("diffRow 返回 null：整型列没进 encodeRow", delta)
        assertEquals(target, GraceSettingsBridge.applyDelta(base, delta!!))
    }

    @Test
    fun `diff then apply reproduces nullable string assignments`() {
        val base = LauncherSettings()
        val target = base.copy(
            iconPackPackage = "com.test.pack",
            mediaAppKey = "media.test",
            weatherLocationId = "loc.test",
            clockAppKey = "clock.test",
            appFontId = "font.test",
        )
        val delta = GraceSettingsBridge.diffRow(base, target)
        assertNotNull("diffRow 返回 null：可空字符串列没进 encodeRow", delta)
        assertEquals(target, GraceSettingsBridge.applyDelta(base, delta!!))
    }

    /** 空值往返：encodeRow 用 JSONObject.NULL 占位，decode 必须还原成 null 而不是字符串 "null"。 */
    @Test
    fun `diff then apply reproduces nullable string clearing`() {
        val base = LauncherSettings(
            iconPackPackage = "com.test.pack",
            mediaAppKey = "media.test",
            weatherLocationId = "loc.test",
            clockAppKey = "clock.test",
            appFontId = "font.test",
        )
        val target = base.copy(
            iconPackPackage = null, mediaAppKey = null, weatherLocationId = null,
            clockAppKey = null, appFontId = null,
        )
        val applied = GraceSettingsBridge.applyDelta(base, GraceSettingsBridge.diffRow(base, target)!!)
        assertEquals(target, applied)
    }

    @Test
    fun `diff then apply reproduces enum change`() {
        val base = LauncherSettings()
        val target = base.copy(darkMode = ThemeMode.Dark)
        val applied = GraceSettingsBridge.applyDelta(base, GraceSettingsBridge.diffRow(base, target)!!)
        assertEquals(ThemeMode.Dark, applied.darkMode)
        assertEquals(target, applied)
    }

    // ==================== 增量语义 ====================

    /** 未出现在 delta 里的列必须保持宿主现值（双端并发写不互相覆盖）。 */
    @Test
    fun `applyDelta keeps columns absent from the delta`() {
        val base = LauncherSettings(clockEnabled = false, themeColor = 11, hideAlphabet = true)
        val next = GraceSettingsBridge.applyDelta(base, JSONObject().put("themeColor", 22))
        assertEquals(22, next.themeColor)
        assertEquals(false, next.clockEnabled)
        assertEquals(true, next.hideAlphabet)
    }

    /** 两行无差异时 diffRow 必须返回 null（否则每次保存都会发一次空增量广播）。 */
    @Test
    fun `diffRow returns null when nothing changed`() {
        val s = LauncherSettings()
        assertEquals(null, GraceSettingsBridge.diffRow(s, s.copy()))
    }

    // ==================== 行解码容错 ====================

    /** 全空 JSON 必须解成全默认值：首装 seed 走这条路径，漏列不能变成"字段丢失"。 */
    @Test
    fun `decodeRow falls back to defaults on empty json`() {
        val decoded = GraceSettingsBridge.decodeRow(JSONObject())
        assertEquals("空 JSON 解码应等于默认构造", LauncherSettings(), decoded)
    }

    /**
     * 回归（vc178）：org.json 把能装进 int 的数字解析成 Integer，
     * 旧代码 `a.opt("s") as? Long` 恒为 null → 工作资料 serial 跨桥丢失。
     */
    @Test
    fun `snapshot app wire serial survives json roundtrip`() {
        val snapshot = GraceSettingsBridge.Snapshot(
            appCatalog = listOf(GraceSettingsBridge.AppWire("pkg/.Cls", "Label", serial = 0L)),
        )
        val json = GraceSettingsBridge.encodeSnapshot(snapshot)
        val decoded = GraceSettingsBridge.decodeSnapshot(json)
        assertNotNull("快照解码失败", decoded)
        val wire = decoded!!.appCatalog.single()
        assertEquals("serial 0 必须原样还原（Integer→Long）", 0L, wire.serial)
        assertEquals("pkg/.Cls", wire.component)
        assertEquals("Label", wire.label)
    }

    /** 快照往返：settings 行 + 收藏序 + 文件夹成员不能丢。 */
    @Test
    fun `snapshot roundtrip keeps settings and favorites`() {
        val settings = LauncherSettings(themeColor = 0x123456, hideAlphabet = true)
        val snapshot = GraceSettingsBridge.Snapshot(
            settings = settings,
            favoriteOrder = listOf("a", "b"),
            favoriteKeys = setOf("a", "b"),
            hiddenAppKeys = setOf("x"),
        )
        val decoded = GraceSettingsBridge.decodeSnapshot(
            GraceSettingsBridge.encodeSnapshot(snapshot))!!
        assertEquals(settings, decoded.settings)
        assertEquals(listOf("a", "b"), decoded.favoriteOrder)
        assertEquals(setOf("x"), decoded.hiddenAppKeys)
        assertTrue(decoded.ok)
    }
}
