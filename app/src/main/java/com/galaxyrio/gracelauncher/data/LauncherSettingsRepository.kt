package com.galaxyrio.gracelauncher.data

import androidx.room.withTransaction
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
                )
            },
        )
    }.distinctUntilChanged()

    suspend fun updateSettings(settings: LauncherSettings) {
        dao.saveSettings(LauncherSettingsEntity(
            clockEnabled = settings.clockEnabled,
            calendarAboveClock = settings.calendarAboveClock,
            mediaAlwaysVisible = settings.mediaAlwaysVisible,
            mediaAppKey = settings.mediaAppKey,
            graceButtonJson = settings.graceButton.encode(),
            calendarAgenda = settings.calendarAgenda,
            showBatteryPercentage = settings.showBatteryPercentage,
            allowHapticFeedback = settings.allowHapticFeedback,
            useDynamicColors = settings.useDynamicColors,
            themeColor = settings.themeColor,
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
            clockAppKey = settings.clockAppKey,
            clockStyleJson = settings.clockStyle.encode(),
            homeLayoutJson = settings.homeLayout.encode(),
            hideStatusBar = settings.hideStatusBar,
            hideAlphabet = settings.hideAlphabet,
            hideFavoriteNames = settings.hideFavoriteNames,
            dimWallpaper = settings.dimWallpaper,
            wallpaperDimAmount = settings.wallpaperDimAmount.coerceIn(0, 100),
            blurWallpaper = settings.blurWallpaper,
            wallpaperBlurRadius = settings.wallpaperBlurRadius.coerceIn(0, 48),
            appFontId = settings.appFontId,
            applyFontToSettings = settings.applyFontToSettings,
            privateSpaceJson = settings.privateSpace.encode(),
            searchJson = settings.search.encode(),
        ))
    }

    /** Read-modify-write is atomic even when a UI emission has not arrived yet. */
    suspend fun mutateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        database.withTransaction {
            val current = dao.readSettings()?.toSettings() ?: LauncherSettings()
            updateSettings(transform(current))
        }
    }

    suspend fun setHiddenApps(keys: Set<String>) {
        // This changes app-list visibility only; other surfaces and memberships are unaffected.
        dao.replaceHiddenApps(keys.filter(String::isNotBlank).map(::HiddenAppEntity))
    }

    suspend fun saveFolder(folder: LauncherFolder) {
        require(folder.id.isNotBlank()) { "A folder must have a stable id" }
        val name = folder.name.trim()
        require(name.isNotEmpty()) { "A folder name must not be blank" }
        val keys = folder.appKeys.filter(String::isNotBlank).distinct()
        database.withTransaction {
            dao.upsertFolder(LauncherFolderEntity(folder.id, name, folder.placement.name, folder.appListAtBottom))
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
        appListAtBottom: Boolean? = null) = database.withTransaction {
        val folder = dao.folder(id) ?: return@withTransaction
        dao.upsertFolder(folder.copy(name = name?.trim()?.takeIf { it.isNotEmpty() } ?: folder.name,
            placement = placement?.name ?: folder.placement, appListAtBottom = appListAtBottom ?: folder.appListAtBottom))
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
    mediaAppKey = mediaAppKey,
    graceButton = GraceButtonSettings.decode(graceButtonJson),
    calendarAgenda = calendarAgenda,
    showBatteryPercentage = showBatteryPercentage,
    allowHapticFeedback = allowHapticFeedback,
    useDynamicColors = useDynamicColors,
    themeColor = themeColor,
    darkMode = ThemeMode.entries.firstOrNull { it.name == darkMode } ?: ThemeMode.System,
    amoledMode = amoledMode,
    iconPackPackage = iconPackPackage,
    iconDesign = iconDesignJson?.let(ItemIcon::decode),
    iconPackPackages = decodeIconPackOrder(iconPackPackagesJson),
    mediaPlayer = mediaPlayer,
    weatherEnabled = weatherEnabled,
    weatherForecastDays = weatherForecastDays.coerceIn(1, 14),
    weatherLocationId = weatherLocationId,
    clockAppKey = clockAppKey,
    clockStyle = ClockStyle.decode(clockStyleJson),
    homeLayout = HomeLayout.decode(homeLayoutJson),
    hideStatusBar = hideStatusBar,
    hideAlphabet = hideAlphabet,
    hideFavoriteNames = hideFavoriteNames,
    dimWallpaper = dimWallpaper,
    wallpaperDimAmount = wallpaperDimAmount.coerceIn(0, 100),
    blurWallpaper = blurWallpaper,
    wallpaperBlurRadius = wallpaperBlurRadius.coerceIn(0, 48),
    appFontId = appFontId,
    applyFontToSettings = applyFontToSettings,
    privateSpace = PrivateSpaceSettings.decode(privateSpaceJson),
    search = SearchSettings.decode(searchJson),
)

private fun decodeIconPackOrder(json: String?): List<String> = runCatching {
    if (json == null) emptyList() else JSONArray(json).let { array ->
        normalizeIconPackOrder((0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? String)?.takeIf(String::isNotBlank)
        })
    }
}.getOrDefault(emptyList())
