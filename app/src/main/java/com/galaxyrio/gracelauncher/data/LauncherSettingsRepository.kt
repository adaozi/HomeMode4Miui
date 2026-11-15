package com.galaxyrio.gracelauncher.data

import androidx.room.withTransaction
import com.galaxyrio.gracelauncher.data.weather.WeatherConnectionPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray

/**
 * New launcher data lives in Room; legacy favorites, renames, categories and
 * appearance preferences remain in their existing stores without being reset.
 * All reads are observable Room queries and every write is a suspending DAO
 * operation, so no database access blocks the main thread.
 */
class LauncherSettingsRepository(private val database: LauncherDatabase) {
    private val dao = database.settingsDao()

    val snapshots: Flow<LauncherStorageSnapshot> = combine(
        dao.observeSettings(),
        dao.observeHiddenApps(),
        dao.observeFolders(),
        database.itemsDao().popups(),
    ) { settings, hiddenApps, folders, popups ->
        val contents = popups.associate { it.ownerKey to PopupItem.decode(it.itemsJson) }
        LauncherStorageSnapshot(
            settings = settings?.toSettings() ?: LauncherSettings(),
            hiddenAppKeys = hiddenApps.toSet(),
            folders = folders.map { entry ->
                LauncherFolder(
                    id = entry.id,
                    name = entry.name,
                    appKeys = contents["folder:${entry.id}"].orEmpty().filter { it.widget == null }.map { it.key },
                    placement = FolderPlacement.decode(entry.placement),
                    appListAtBottom = entry.appListAtBottom,
                    autoHideApps = entry.autoHideApps,
                    isAppStack = entry.isAppStack,
                )
            },
        )
    }.distinctUntilChanged()

    suspend fun updateSettings(settings: LauncherSettings) {
        // The raw hideFavoriteNames flag is stored untouched; hidden-icon safety is
        // derived on read so the user's preference survives a Hidden round-trip.
        dao.saveSettings(LauncherSettingsEntity(
            clockEnabled = settings.clockEnabled,
            calendarAboveClock = settings.calendarAboveClock,
            mediaAlwaysVisible = settings.mediaAlwaysVisible,
            mediaKeepWhilePlaying = settings.mediaKeepWhilePlaying,
            dismissResistance = settings.dismissResistance.coerceIn(0, 90),
            dismissThresholdDp = settings.dismissThresholdDp.coerceIn(24, 160),
            mediaAppKey = settings.mediaAppKey,
            mediaStyleJson = settings.mediaStyle.encode(),
            graceButtonJson = settings.graceButton.encode(),
            calendarAgenda = settings.calendarAgenda,
            showBatteryPercentage = settings.showBatteryPercentage,
            allowHapticFeedback = settings.allowHapticFeedback,
            allowScreenRotation = settings.allowScreenRotation,
            useDynamicColors = settings.useDynamicColors,
            themeColor = settings.themeColor,
            paletteStyle = settings.paletteStyle.name,
            darkMode = settings.darkMode.name,
            amoledMode = settings.amoledMode,
            iconPackPackage = settings.iconPackPackage,
            iconDesignJson = settings.iconDesign?.encode(),
            iconPackPackagesJson = normalizeIconPackOrder(settings.iconPackPackages).takeIf { it.isNotEmpty() }
                ?.let { JSONArray(it).toString() },
            mediaPlayer = settings.mediaPlayer,
            weatherEnabled = settings.weatherEnabled,
            weatherForecastDays = settings.weatherForecastDays.coerceIn(1, 14),
            weatherLocationId = settings.weatherLocationId,
            weatherConnectionJson = settings.weatherConnection.encode(),
            clockAppKey = settings.clockAppKey,
            clockStyleJson = settings.clockStyle.encode(),
            agendaAppearanceJson = settings.agendaAppearance.encode(),
            homeLayoutJson = settings.homeLayout.encode(),
            hideStatusBar = settings.hideStatusBar,
            hideAlphabet = settings.hideAlphabet,
            hideFavoriteNames = settings.hideFavoriteNames,
            dimWallpaper = settings.dimWallpaper,
            wallpaperDimAmount = settings.wallpaperDimAmount.coerceIn(0, 100),
            blurWallpaper = settings.blurWallpaper,
            wallpaperBlurRadius = settings.wallpaperBlurRadius.coerceIn(0, 48),
            appFontId = settings.appFontId,
            fontLibraryJson = settings.fontLibrary.encode(),
            fontColorsJson = settings.fontColors.encode(),
            listAppearanceJson = settings.listAppearance.encode(),
            applyFontToSettings = settings.applyFontToSettings,
            privateSpaceJson = settings.privateSpace.encode(),
            workProfileJson = settings.workProfile.encode(),
            homeGesturesJson = settings.homeGestures.encode(),
            searchJson = settings.search.encode(),
            shortcutsFoldersJson = settings.shortcutsFolders.encode(),
            digitalWellbeingJson = settings.digitalWellbeing.encode(),
            widgetScreenJson = settings.widgetScreen.encode(),
            appCategoriesJson = settings.appCategories?.let(AppCategory::encode),
            preferNonAccessibility = settings.preferNonAccessibility,
            calendarPreferencesJson = settings.calendar.encode(),
        ))
    }

    /** Read-modify-write is atomic even when a UI emission has not arrived yet. */
    suspend fun mutateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        database.withTransaction {
            val current = dao.readSettings()?.toSettings() ?: LauncherSettings()
            updateSettings(transform(current))
        }
    }

    /**
     * 上游 1.2.2/1.2.4：主页小组件从 homeLayout 迁到 home-content 弹层项（可多小组件）。
     * 首次编辑 home-content 时幂等迁移；迁移后 homeLayout 只保留排序与位置。
     */
    suspend fun migrateLegacyHomeWidget() = database.withTransaction {
        val current = dao.readSettings()?.toSettings() ?: LauncherSettings()
        if (current.homeLayout.hasWidget) {
            val layout = current.homeLayout
            val items = database.itemsDao()
            val previous = items.popup(HomeContentKey)?.let { PopupItem.decode(it.itemsJson) }.orEmpty()
            val widget = PopupItem("widget:${layout.widgetId}", layout.copy(homeOrder = null))
            items.savePopup(AppPopupEntity(HomeContentKey, PopupItem.encode((listOf(widget) + previous).distinctBy { it.key })))
            updateSettings(current.copy(homeLayout = layout.withoutWidget().copy(
                homeOrder = layout.homeOrder ?: legacyHomeOrder(current))))
        }
    }

    suspend fun setHiddenApps(keys: Set<String>) {
        // This changes app-list visibility only; other surfaces and memberships are unaffected.
        dao.replaceHiddenApps(keys.filter(String::isNotBlank).map(::HiddenAppEntity))
    }

    suspend fun hideApp(key: String) {
        if (key.isNotBlank()) dao.hideApp(HiddenAppEntity(key))
    }

    suspend fun saveFolder(folder: LauncherFolder) {
        require(folder.id.isNotBlank()) { "A folder must have a stable id" }
        val name = folder.name.trim()
        require(name.isNotEmpty()) { "A folder name must not be blank" }
        val keys = folder.appKeys.filter(String::isNotBlank).distinct()
        database.withTransaction {
            dao.upsertFolder(LauncherFolderEntity(folder.id, name, folder.placement.name, folder.appListAtBottom, folder.autoHideApps, folder.isAppStack))
            val itemsDao = database.itemsDao()
            val previous = itemsDao.popup(folder.key)?.let { PopupItem.decode(it.itemsJson) }.orEmpty()
            // Membership callers must not discard widgets or move them to the end.
            val remaining = keys.toMutableList()
            val next = previous.mapNotNull { item ->
                if (item.widget != null) item else remaining.firstOrNull()?.let { remaining.removeAt(0); PopupItem(it) }
            } + remaining.map { PopupItem(it) }
            itemsDao.savePopup(AppPopupEntity(folder.key, PopupItem.encode(next)))
        }
    }

    suspend fun updateFolder(id: String, name: String? = null, placement: FolderPlacement? = null,
        appListAtBottom: Boolean? = null, autoHideApps: Boolean? = null,
        isAppStack: Boolean? = null) = database.withTransaction {
        val folder = dao.folder(id) ?: return@withTransaction
        dao.upsertFolder(folder.copy(name = name?.trim()?.takeIf { it.isNotEmpty() } ?: folder.name,
            placement = placement?.name ?: folder.placement, appListAtBottom = appListAtBottom ?: folder.appListAtBottom,
            autoHideApps = autoHideApps ?: folder.autoHideApps,
            isAppStack = isAppStack ?: folder.isAppStack))
    }

    /** The favorites picker edits the same placement as the folder's settings. */
    suspend fun setFolderFavorites(selection: Map<String, Boolean>) = database.withTransaction {
        selection.forEach { (id, favorite) ->
            val folder = dao.folder(id) ?: return@forEach
            // The favorites picker must not change app-list visibility or sorting.
            val placement = FolderPlacement.decode(folder.placement).withFavorites(favorite)
            dao.upsertFolder(folder.copy(placement = placement.name))
        }
    }

    suspend fun deleteFolder(id: String): List<Int> = database.withTransaction {
        val key = "folder:$id"
        val itemsDao = database.itemsDao()
        val widgets = itemsDao.popup(key)?.let { PopupItem.decode(it.itemsJson) }.orEmpty().mapNotNull { it.widget?.widgetId }
        itemsDao.deletePopup(key)
        itemsDao.resetIcon(key)
        dao.deleteFolder(id)
        widgets
    }
}

private fun LauncherSettingsEntity.toSettings() = LauncherSettings(
    clockEnabled = clockEnabled,
    calendarAboveClock = calendarAboveClock,
    mediaAlwaysVisible = mediaAlwaysVisible,
    mediaKeepWhilePlaying = mediaKeepWhilePlaying,
    dismissResistance = dismissResistance.coerceIn(0, 90),
    dismissThresholdDp = dismissThresholdDp.coerceIn(24, 160),
    mediaAppKey = mediaAppKey,
    mediaStyle = MediaPlayerStyle.decode(mediaStyleJson),
    graceButton = GraceButtonSettings.decode(graceButtonJson),
    calendarAgenda = calendarAgenda,
    showBatteryPercentage = showBatteryPercentage,
    allowHapticFeedback = allowHapticFeedback,
    allowScreenRotation = allowScreenRotation,
    useDynamicColors = useDynamicColors,
    themeColor = themeColor,
    paletteStyle = decodePaletteStyle(paletteStyle),
    darkMode = ThemeMode.entries.firstOrNull { it.name == darkMode } ?: ThemeMode.System,
    amoledMode = amoledMode,
    iconPackPackage = iconPackPackage,
    iconDesign = iconDesignJson?.let(ItemIcon::decode),
    iconPackPackages = decodeIconPackOrder(iconPackPackagesJson),
    mediaPlayer = mediaPlayer,
    weatherEnabled = weatherEnabled,
    weatherForecastDays = weatherForecastDays.coerceIn(1, 14),
    weatherLocationId = weatherLocationId,
    weatherConnection = WeatherConnectionPreferences.decode(weatherConnectionJson),
    clockAppKey = clockAppKey,
    clockStyle = ClockStyle.decode(clockStyleJson),
    agendaAppearance = AgendaAppearance.decode(agendaAppearanceJson),
    homeLayout = HomeLayout.decode(homeLayoutJson),
    hideStatusBar = hideStatusBar,
    hideAlphabet = hideAlphabet,
    hideFavoriteNames = hideFavoriteNames,
    dimWallpaper = dimWallpaper,
    wallpaperDimAmount = wallpaperDimAmount.coerceIn(0, 100),
    blurWallpaper = blurWallpaper,
    wallpaperBlurRadius = wallpaperBlurRadius.coerceIn(0, 48),
    appFontId = appFontId,
    fontLibrary = FontLibrary.decode(fontLibraryJson, appFontId),
    fontColors = FontColors.decode(fontColorsJson),
    listAppearance = ListAppearance.decode(listAppearanceJson, hideFavoriteNames),
    applyFontToSettings = applyFontToSettings,
    privateSpace = ProfileSettings.decode(privateSpaceJson),
    workProfile = ProfileSettings.decode(workProfileJson, ProfileSettings.workDefaults()),
    homeGestures = GraceButtonSettings.decode(homeGesturesJson, GraceButtonSettings.homeDefaults()),
    search = SearchSettings.decode(searchJson),
    shortcutsFolders = ShortcutsFoldersSettings.decode(shortcutsFoldersJson),
    digitalWellbeing = DigitalWellbeingSettings.decode(digitalWellbeingJson),
    widgetScreen = WidgetScreenSettings.decode(widgetScreenJson),
    appCategories = AppCategory.decode(appCategoriesJson),
    preferNonAccessibility = preferNonAccessibility,
    calendar = CalendarPreferences.decode(calendarPreferencesJson),
)

private fun decodeIconPackOrder(json: String?): List<String> = runCatching {
    if (json == null) emptyList() else JSONArray(json).let { array ->
        normalizeIconPackOrder((0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? String)?.takeIf(String::isNotBlank)
        })
    }
}.getOrDefault(emptyList())
