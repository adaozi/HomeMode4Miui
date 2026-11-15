/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/LauncherDatabase.kt
 * P3 移植：实体/DAO/migration 逐字拷贝。上游同步 v1.1.8（2026-10-11）：schema
 *   v18 → v21，补 Migration18To19/19To20/20To21（launcher_settings 加列，见 §9.3）。
 * 宿主适配（唯一改动点）：getInstance 的建库路径 —— 原版落宿主 databases 根目录，
 *   本模块对齐底座"独立 DB 目录"惯例（CustomLauncherMode = databases/custom/），
 *   落 databases/custom/grace_launcher.db（Room name 传绝对路径；原版文件名已带
 *   grace_ 前缀，不与宿主库撞名；卸载模式时保留用户数据）。
 */
package com.galaxyrio.gracelauncher.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

import java.io.File

@Entity(tableName = "launcher_settings")
data class LauncherSettingsEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(defaultValue = "1") val clockEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val calendarAboveClock: Boolean = false,
    val calendarAgenda: Boolean,
    val showBatteryPercentage: Boolean,
    val allowHapticFeedback: Boolean,
    /** 上游 1.2.4（DB v33）：屏幕旋转开关，默认允许。 */
    @ColumnInfo(defaultValue = "1") val allowScreenRotation: Boolean = true,
    val useDynamicColors: Boolean,
    val themeColor: Int,
    @ColumnInfo(defaultValue = "'TonalSpot'") val paletteStyle: String = "TonalSpot",
    val darkMode: String,
    @ColumnInfo(defaultValue = "0") val amoledMode: Boolean = false,
    val iconPackPackage: String? = null,
    val iconPackPackagesJson: String? = null,
    val iconDesignJson: String? = null,
    @ColumnInfo(defaultValue = "1") val mediaPlayer: Boolean = true,
    @ColumnInfo(defaultValue = "0") val mediaAlwaysVisible: Boolean = false,
    /** 上游 1.2.4（DB v27）：媒体组件播放中保持显示 + 下滑关闭手感参数。 */
    @ColumnInfo(defaultValue = "1") val mediaKeepWhilePlaying: Boolean = true,
    @ColumnInfo(defaultValue = "55") val dismissResistance: Int = 75,
    @ColumnInfo(defaultValue = "72") val dismissThresholdDp: Int = 72,
    val mediaAppKey: String? = null,
    /** 上游 1.2.4（DB v32）：媒体组件样式。 */
    @ColumnInfo(defaultValue = "NULL") val mediaStyleJson: String? = null,
    val graceButtonJson: String? = null,
    @ColumnInfo(defaultValue = "0") val weatherEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "7") val weatherForecastDays: Int = 7,
    val weatherLocationId: String? = null,
    val clockAppKey: String? = null,
    val clockStyleJson: String? = null,
    /** 上游 1.2.4（DB v34）：Agenda Page 外观。 */
    @ColumnInfo(defaultValue = "NULL") val agendaAppearanceJson: String? = null,
    val homeLayoutJson: String? = null,
    @ColumnInfo(defaultValue = "1") val hideStatusBar: Boolean = true,
    @ColumnInfo(defaultValue = "0") val hideAlphabet: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hideFavoriteNames: Boolean = false,
    @ColumnInfo(defaultValue = "0") val dimWallpaper: Boolean = false,
    @ColumnInfo(defaultValue = "20") val wallpaperDimAmount: Int = 20,
    @ColumnInfo(defaultValue = "1") val blurWallpaper: Boolean = true,
    @ColumnInfo(defaultValue = "16") val wallpaperBlurRadius: Int = 16,
    val appFontId: String? = null,
    val fontLibraryJson: String? = null,
    /** 上游 1.2.4（DB v29）：逐元素字体颜色（集中管理）。 */
    val fontColorsJson: String? = null,
    val listAppearanceJson: String? = null,
    @ColumnInfo(defaultValue = "1") val applyFontToSettings: Boolean = true,
    val privateSpaceJson: String? = null,
    val workProfileJson: String? = null,
    val homeGesturesJson: String? = null,
    val searchJson: String? = null,
    val shortcutsFoldersJson: String? = null,
    /** 上游 1.2.4（DB v25~v31）：数字健康 / Widget Screen / 应用类别 / 免无障碍优先 / 日历偏好 / 天气连接。 */
    val digitalWellbeingJson: String? = null,
    val widgetScreenJson: String? = null,
    val appCategoriesJson: String? = null,
    @ColumnInfo(defaultValue = "0") val preferNonAccessibility: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val calendarPreferencesJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val weatherConnectionJson: String? = null,
)

@Entity(tableName = "hidden_apps")
data class HiddenAppEntity(@PrimaryKey val appKey: String)

@Entity(tableName = "folders")
data class LauncherFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val placement: String,
    @ColumnInfo(defaultValue = "1") val appListAtBottom: Boolean = true,
    @ColumnInfo(defaultValue = "0") val autoHideApps: Boolean = false,
    /** 上游同步 1.2.0：横向应用堆标记（与文件夹共用成员/位置存储）。 */
    @ColumnInfo(defaultValue = "0") val isAppStack: Boolean = false,
)

@Dao
abstract class LauncherSettingsDao {
    @Query("SELECT * FROM launcher_settings WHERE id = 0")
    abstract fun observeSettings(): Flow<LauncherSettingsEntity?>

    @Query("SELECT * FROM launcher_settings WHERE id = 0")
    abstract suspend fun readSettings(): LauncherSettingsEntity?

    @Query("SELECT appKey FROM hidden_apps ORDER BY appKey")
    abstract fun observeHiddenApps(): Flow<List<String>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE, id")
    abstract fun observeFolders(): Flow<List<LauncherFolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    abstract suspend fun folder(id: String): LauncherFolderEntity?

    @Upsert
    abstract suspend fun saveSettings(settings: LauncherSettingsEntity)

    @Query("DELETE FROM hidden_apps")
    protected abstract suspend fun clearHiddenApps()

    @Insert
    protected abstract suspend fun insertHiddenApps(apps: List<HiddenAppEntity>)

    @Upsert
    abstract suspend fun hideApp(app: HiddenAppEntity)

    @Transaction
    open suspend fun replaceHiddenApps(apps: List<HiddenAppEntity>) {
        clearHiddenApps()
        insertHiddenApps(apps)
    }

    @Upsert
    abstract suspend fun upsertFolder(folder: LauncherFolderEntity)

    @Query("DELETE FROM folders WHERE id = :folderId")
    abstract suspend fun deleteFolder(folderId: String)
}

@Database(
    entities = [LauncherSettingsEntity::class, HiddenAppEntity::class,
        LauncherFolderEntity::class, ItemIconEntity::class,
        SavedShortcutEntity::class, AppPopupEntity::class],
    version = 34,
    exportSchema = true,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun settingsDao(): LauncherSettingsDao
    abstract fun itemsDao(): LauncherItemsDao

    companion object {
        val Migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN iconPackPackage TEXT DEFAULT NULL")
            }
        }

        val Migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN mediaPlayer INTEGER NOT NULL DEFAULT 1")
            }
        }

        val Migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN weatherEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN weatherForecastDays INTEGER NOT NULL DEFAULT 7")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN weatherLocationId TEXT DEFAULT NULL")
            }
        }

        val Migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN clockAppKey TEXT DEFAULT NULL")
            }
        }

        @Volatile private var instance: LauncherDatabase? = null

        val Migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN clockStyleJson TEXT DEFAULT NULL")
            }
        }

        val Migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN homeLayoutJson TEXT DEFAULT NULL")
            }
        }

        val Migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN hideStatusBar INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN hideAlphabet INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN hideFavoriteNames INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN dimWallpaper INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN wallpaperDimAmount INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN blurWallpaper INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN wallpaperBlurRadius INTEGER NOT NULL DEFAULT 16")
            }
        }

        val Migration8To9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS item_icons (itemKey TEXT NOT NULL PRIMARY KEY, iconJson TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS saved_shortcuts (itemKey TEXT NOT NULL PRIMARY KEY, packageName TEXT NOT NULL, shortcutId TEXT NOT NULL, activity TEXT NOT NULL, label TEXT NOT NULL, showInAppList INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_popups (ownerKey TEXT NOT NULL PRIMARY KEY, itemsJson TEXT NOT NULL)")
            }
        }

        val Migration9To10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN appFontId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN applyFontToSettings INTEGER NOT NULL DEFAULT 1")
            }
        }

        val Migration10To11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A null order reads the existing single selection, without rewriting it.
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN iconPackPackagesJson TEXT DEFAULT NULL")
            }
        }

        val Migration11To12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN iconDesignJson TEXT DEFAULT NULL")
            }
        }

        val Migration12To13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Keep unavailable members and their order when adopting shared pop-up storage.
                db.query("SELECT id FROM folders").use { folders ->
                    while (folders.moveToNext()) {
                        val id = folders.getString(0)
                        val items = JSONArray()
                        db.query("SELECT appKey FROM folder_apps WHERE folderId = ? ORDER BY position", arrayOf(id)).use { apps ->
                            while (apps.moveToNext()) items.put(JSONObject().put("key", apps.getString(0)))
                        }
                        db.execSQL("INSERT OR IGNORE INTO app_popups (ownerKey, itemsJson) VALUES (?, ?)", arrayOf("folder:$id", items.toString()))
                    }
                }
                db.execSQL("DROP TABLE folder_apps")
            }
        }

        val Migration13To14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN privateSpaceJson TEXT DEFAULT NULL")
            }
        }

        val Migration14To15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN searchJson TEXT DEFAULT NULL")
            }
        }

        val Migration15To16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN amoledMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        val Migration16To17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN clockEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN calendarAboveClock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN mediaAlwaysVisible INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN mediaAppKey TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN graceButtonJson TEXT DEFAULT NULL")
            }
        }

        val Migration17To18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN appListAtBottom INTEGER NOT NULL DEFAULT 1")
            }
        }

        // ---- 上游同步 v1.1.8（2026-10-11）：schema v18 → v21，三条加列 migration 逐字 ----
        val Migration18To19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN workProfileJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN homeGesturesJson TEXT DEFAULT NULL")
            }
        }

        val Migration19To20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN fontLibraryJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN listAppearanceJson TEXT DEFAULT NULL")
            }
        }

        val Migration20To21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN shortcutsFoldersJson TEXT DEFAULT NULL")
            }
        }

        val Migration21To22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN paletteStyle TEXT NOT NULL DEFAULT 'TonalSpot'")
                db.execSQL("ALTER TABLE folders ADD COLUMN autoHideApps INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 上游同步 1.2.0：App Stack 标记列。
         * 上游 22→23 加的是 widgetScreenJson、23→24 才加 isAppStack；本模块不移植
         * Widget Screen 数据（入口复用原生负一屏），故直接一步到 23，只加 isAppStack，
         * 避免多一个永不使用的 settings 列。
         */
        val Migration22To23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isAppStack INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2.9.141（快捷方式三连修）：saved_shortcuts 加 dismissed 列 ——
         * 用户移除的 pin 快捷方式保留记忆但不再显示（纯删行会被 syncPinnedShortcuts
         * 按系统 pinned 集合自愈回来，"删不掉"实锤根因）。
         */
        val Migration23To24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_shortcuts ADD COLUMN dismissed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 上游同步 1.2.4（v2.9.164 / P0 地基）：DB v24 → v34 与上游列集合对齐。
         * 注：上游 v23 的 widgetScreenJson 在模块 v22→23 时被跳过（当时不移植 Widget Screen），
         * 本批全量跟进 1.2.4 起补回 —— 与 preferNonAccessibility 一起落在 24→25，
         * 使模块 v25 的 launcher_settings 列集合与上游 v25 完全一致，后续迁移可逐字沿用。
         */
        val Migration24To25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN widgetScreenJson TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN preferNonAccessibility INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 上游 1.2.3：数字健康（应用使用时长）设置。 */
        val Migration25To26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN digitalWellbeingJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.1：媒体组件常驻 + 弹层下滑关闭手感（dismissResistance 默认值只初始化新库）。 */
        val Migration26To27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN mediaKeepWhilePlaying INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN dismissResistance INTEGER NOT NULL DEFAULT 55")
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN dismissThresholdDp INTEGER NOT NULL DEFAULT 72")
                // Keep the SQL default compatible with existing v27 databases. Only initialize
                // this newly added preference; never overwrite an existing user's chosen value.
                db.execSQL("UPDATE launcher_settings SET dismissResistance = 75")
            }
        }

        /** 上游 1.2.2：应用类别（按条件决定桌面/应用列表可见）。 */
        val Migration27To28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN appCategoriesJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.2：逐元素字体颜色集中管理。 */
        val Migration28To29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN fontColorsJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.3：日历偏好（议程页/日期行细则）。 */
        val Migration29To30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN calendarPreferencesJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.3：Gadgetbridge 天气协议连接配置。 */
        val Migration30To31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN weatherConnectionJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.3：音乐组件样式。 */
        val Migration31To32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN mediaStyleJson TEXT DEFAULT NULL")
            }
        }

        /** 上游 1.2.4：屏幕旋转开关。 */
        val Migration32To33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN allowScreenRotation INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** 上游 1.2.4：Agenda Page 外观。 */
        val Migration33To34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE launcher_settings ADD COLUMN agendaAppearanceJson TEXT DEFAULT NULL")
            }
        }

        /**
         * 宿主适配：库文件落 databases/custom/grace_launcher.db（对齐底座独立 DB 目录惯例）。
         * hotfix1（装机 2026-10-08 实锤）：getDatabasePath("custom/x.db") 走 ContextImpl
         * makeFilename —— 无条件拒绝带路径分隔符的名字，直接 IllegalArgumentException
         * （被 GraceSettingsRepository 兜底吞掉降级 SP，桌面零崩溃但 Room 未生效）。
         * 修复：占位名拿 databases 根目录，custom/ 子目录自己拼自己 mkdirs，
         * 最终把【绝对路径】交给 Room —— 其建库走 openOrCreateDatabase → validateFilePath，
         * 该分支对含分隔符的绝对路径宽容（按文件路径处理 + 父目录 mkdirs）。
         */
        private fun databasePath(context: Context): String {
            val databasesDir = runCatching {
                context.getDatabasePath("grace_launcher.db").parentFile
            }.getOrNull() ?: context.getDir("databases", Context.MODE_PRIVATE)
            val customDir = File(databasesDir, "custom")
            runCatching { if (!customDir.exists()) customDir.mkdirs() }
            return File(customDir, "grace_launcher.db").absolutePath
        }

        fun getInstance(context: Context): LauncherDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LauncherDatabase::class.java,
                databasePath(context),
            )
                // hotfix1（2026-10-21 实锤）：v1.2.0 同步战役曾把 DB 升到 v24，回装 v22 版后
                // Room 降级无迁移路径 → snapshot build 全挂（第四桌面应用/设置装载失败）。
                // 仅降级时销毁重建（升级仍走迁移链），用户数据以桌面重置为代价换取功能恢复。
                .fallbackToDestructiveMigrationOnDowngrade()
                .addMigrations(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6, Migration6To7, Migration7To8, Migration8To9, Migration9To10, Migration10To11, Migration11To12, Migration12To13, Migration13To14, Migration14To15, Migration15To16, Migration16To17, Migration17To18, Migration18To19, Migration19To20, Migration20To21, Migration21To22, Migration22To23, Migration23To24, Migration24To25, Migration25To26, Migration26To27, Migration27To28, Migration28To29, Migration29To30, Migration30To31, Migration31To32, Migration32To33, Migration33To34).build().also { instance = it }
        }
    }
}
