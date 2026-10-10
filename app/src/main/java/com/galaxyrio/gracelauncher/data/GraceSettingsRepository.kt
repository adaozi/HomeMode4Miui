/*
 * P3 设置仓储（新建文件，非搬运）—— PORTING_GraceLauncher.md B3.2 偏好落地。
 *
 * 语义对齐原版 LauncherSettingsRepository（data/LauncherSettingsRepository.kt，
 * GPL-3.0）：所有读是可观察 Room 查询、所有写是 suspend DAO 操作 + withTransaction
 * read-modify-write（"Atomic edits" 原注释语义），主线程零 DB 访问。
 *
 * 与原版的关系：
 *   - 原版 LauncherSettings 大模型依赖 P4/P6 资产（GraceButtonSettings/ItemIcon/
 *     PrivateSpaceSettings 等），整体搬运留待后续阶段；本仓储只承载 P1 已接线的
 *     GraceHomeSettings 4 字段（clockEnabled/clockStyle/hideFavoriteNames/search），
 *     entity 行全字段保留（copy 只改 4 列，未知列 read-modify-write 原样带回），
 *     P4/P6 搬原版大模型后无缝扩字段。
 *   - SP → Room 一次性 seed：P1 偏好落在 SP grace_launcher_settings（clockStyle
 *     原版 JSON 格式，字节级兼容），Room 无 id=0 行时迁入；SP 保留不删（原版
 *     "legacy 不重置" 语义，切回旧版本模块仍可读）。
 *   - DB 故障兜底：databaseBuilder/首次读失败（磁盘满/目录权限等极端场景）时
 *     整体降级回 SP 直读写 —— 桌面零崩溃铁律，功能不丢（P1 行为）。
 */
package com.galaxyrio.gracelauncher.data

import android.content.Context
import androidx.room.withTransaction
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class GraceSettingsRepository(context: Context) {

    private val spStore = GraceHomeSettingsStore(context)

    /** 单例库；构建失败保持 null，全链路降级 SP。 */
    private val database: LauncherDatabase? = runCatching {
        LauncherDatabase.getInstance(context)
    }.onFailure { ModLog.e("GraceSettingsRepository db init failed, fallback to SP", it) }
        .getOrNull()

    /**
     * 启动首读：Room 无行时从 SP seed（一次性迁移），有行直接读。
     * 永不抛（除取消）：任何异常降级 SP 读。
     */
    suspend fun loadInitial(): GraceHomeSettings {
        val db = database ?: return spStore.load()
        return try {
            val row = db.settingsDao().readSettings()
            if (row == null) {
                val migrated = spStore.load()
                db.settingsDao().saveSettings(seedEntity(migrated))
                ModLog.i("GraceSettingsRepository seeded ${migrated.clockStyle.layout} from legacy SP")
                migrated
            } else {
                row.toGraceSettings()
            }
        } catch (error: Exception) {
            ModLog.e("GraceSettingsRepository loadInitial failed, fallback to SP", error)
            spStore.load()
        }
    }

    /** 设置流：Room 行变化驱动 UI；行未 seed 前回读 SP（P1 值），DB 故障降级 SP。 */
    fun observeSettings(): Flow<GraceHomeSettings> {
        val db = database ?: return flowOf(spStore.load())
        return db.settingsDao().observeSettings()
            .map { row -> row?.toGraceSettings() ?: spStore.load() }
            .catch { error ->
                ModLog.e("GraceSettingsRepository observe failed, fallback to SP", error)
                emit(spStore.load())
            }
    }

    /**
     * 原版 mutateSettings 同构：withTransaction read-modify-write。
     * entity 以库内现行为底 copy，只覆写 P3/P4 承载的 5 列（未来列零破坏）。
     * 返回写入后的值（DB 故障时为 SP 路径结果），调用方据此刷新 UI 状态。
     */
    suspend fun mutateSettings(transform: (GraceHomeSettings) -> GraceHomeSettings): GraceHomeSettings {
        val db = database ?: return mutateSp(transform)
        return try {
            db.withTransaction {
                val row = db.settingsDao().readSettings() ?: seedEntity(spStore.load())
                val next = transform(row.toGraceSettings())
                db.settingsDao().saveSettings(row.copy(
                    clockEnabled = next.clockEnabled,
                    clockStyleJson = next.clockStyle.encode(),
                    hideFavoriteNames = next.hideFavoriteNames,
                    searchJson = next.searchSettings.encode(),
                    homeLayoutJson = next.homeLayout.encode(),
                    // P6（B6.x）：附属特性开关列（schema v18 已有，零 migration）
                    calendarAgenda = next.calendarAgenda,
                    calendarAboveClock = next.calendarAboveClock,
                    showBatteryPercentage = next.showBatteryPercentage,
                    mediaPlayer = next.mediaPlayer,
                    mediaAlwaysVisible = next.mediaAlwaysVisible,
                    weatherEnabled = next.weatherEnabled,
                    weatherForecastDays = next.weatherForecastDays,
                    iconPackPackagesJson = GraceHomeSettings.encodeStringList(next.iconPackPackages),
                    privateSpaceJson = next.privateSpace.encode(),
                    // C 组（hotfix3）：外观列（设置页大模型经桥写入，宿主 mutate 同步承载）
                    hideStatusBar = next.hideStatusBar,
                    hideAlphabet = next.hideAlphabet,
                    dimWallpaper = next.dimWallpaper,
                    blurWallpaper = next.blurWallpaper,
                    wallpaperDimAmount = next.wallpaperDimAmount,
                    wallpaperBlurRadius = next.wallpaperBlurRadius,
                    // 上游同步 v1.1.8（S2）：列表外观 + 弹层/通知策略列（schema v21）
                    listAppearanceJson = next.listAppearance.encode(),
                    shortcutsFoldersJson = next.shortcutsFolders.encode(),
                    homeGesturesJson = next.homeGestures.encode(),
                    graceButtonJson = next.graceButton.encode(),
                    fontLibraryJson = next.fontLibrary.encode(),
                ))
                next
            }
        } catch (error: Exception) {
            ModLog.e("GraceSettingsRepository mutate failed, fallback to SP", error)
            mutateSp(transform)
        }
    }

    /** SP 兜底写（SharedPreferences 同步读写，进程内锁保证线程安全）。 */
    private fun mutateSp(transform: (GraceHomeSettings) -> GraceHomeSettings): GraceHomeSettings =
        transform(spStore.load()).also { spStore.save(it) }

    /** 全字段种子行（默认值 = 原版 LauncherSettings() 逐项），字段来自迁移值。 */
    private fun seedEntity(settings: GraceHomeSettings) = LauncherSettingsEntity(
        id = 0,
        clockEnabled = settings.clockEnabled,
        calendarAgenda = settings.calendarAgenda,
        showBatteryPercentage = settings.showBatteryPercentage,
        allowHapticFeedback = true,
        useDynamicColors = true,
        themeColor = 0xFF6750A4.toInt(),
        darkMode = "System",
        clockStyleJson = settings.clockStyle.encode(),
        hideFavoriteNames = settings.hideFavoriteNames,
        homeLayoutJson = settings.homeLayout.encode(),
        searchJson = settings.searchSettings.encode(),
        // P6：附属特性开关种子（原版默认值）
        calendarAboveClock = settings.calendarAboveClock,
        mediaPlayer = settings.mediaPlayer,
        mediaAlwaysVisible = settings.mediaAlwaysVisible,
        weatherEnabled = settings.weatherEnabled,
        weatherForecastDays = settings.weatherForecastDays,
        iconPackPackagesJson = GraceHomeSettings.encodeStringList(settings.iconPackPackages),
        privateSpaceJson = settings.privateSpace.encode(),
        // C 组（hotfix3）：外观列种子（原版默认值）
        hideStatusBar = settings.hideStatusBar,
        hideAlphabet = settings.hideAlphabet,
        dimWallpaper = settings.dimWallpaper,
        blurWallpaper = settings.blurWallpaper,
        wallpaperDimAmount = settings.wallpaperDimAmount,
        wallpaperBlurRadius = settings.wallpaperBlurRadius,
        // 上游同步 v1.1.8（S2）：新列种子
        listAppearanceJson = settings.listAppearance.encode(),
        shortcutsFoldersJson = settings.shortcutsFolders.encode(),
        homeGesturesJson = settings.homeGestures.encode(),
        graceButtonJson = settings.graceButton.encode(),
        fontLibraryJson = settings.fontLibrary.encode(),
    )

    private fun LauncherSettingsEntity.toGraceSettings() = GraceHomeSettings.decode(
        clockStyleJson = clockStyleJson,
        clockEnabled = clockEnabled,
        hideFavoriteNames = hideFavoriteNames,
        searchSettingsJson = searchJson,
        homeLayoutJson = homeLayoutJson,
        calendarAgenda = calendarAgenda,
        calendarAboveClock = calendarAboveClock,
        showBatteryPercentage = showBatteryPercentage,
        mediaPlayer = mediaPlayer,
        mediaAlwaysVisible = mediaAlwaysVisible,
        weatherEnabled = weatherEnabled,
        weatherForecastDays = weatherForecastDays,
        iconPackPackagesJson = iconPackPackagesJson,
        privateSpaceJson = privateSpaceJson,
        hideStatusBar = hideStatusBar,
        hideAlphabet = hideAlphabet,
        dimWallpaper = dimWallpaper,
        blurWallpaper = blurWallpaper,
        wallpaperDimAmount = wallpaperDimAmount,
        wallpaperBlurRadius = wallpaperBlurRadius,
        // 上游同步 v1.1.8（S2）：新列读出
        listAppearanceJson = listAppearanceJson,
        shortcutsFoldersJson = shortcutsFoldersJson,
        homeGesturesJson = homeGesturesJson,
        graceButtonJson = graceButtonJson,
        fontLibraryJson = fontLibraryJson,
    )
}
