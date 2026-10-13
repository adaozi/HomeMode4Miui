/*
 * P6（B6.1/B6.3/B6.2）跨进程桥契约 —— 新建文件（非搬运）。
 *
 * 背景：PORTING_GraceLauncher.md B6.1 规定媒体/通知的 NotificationListenerService
 * 跑在【模块 App 进程】（系统按 manifest 绑定，只能落在 com.homemode4miui 进程），
 * 而 GraceLauncher UI 运行在【宿主进程 com.miui.home】。两进程数据目录不互通
 * （原版是同进程单例 appNotifications/mediaNotifications 直接 collect）。
 *
 * 通道选型（广播桥，前作 SP 桥同思路的轻量进化版）：
 *   - 快照下发：模块 App 进程 → 宿主进程。显式包名广播（setPackage("com.miui.home")），
 *     宿主 GraceHomeView 挂载期动态注册 receiver 接收。JSON 字符串 extra +
 *     媒体封面独立 PNG bytes extra（binder 1MB 缓冲限制，封面限 512px JPEG/PNG ≈ 100KB）。
 *   - 命令上行：宿主进程 → 模块 App 进程。显式包名广播，模块 manifest receiver
 *     接收（exported=true，onReceive 内校验发送方 uid 必须是 com.miui.home）。
 *   - 时效性：通知监听服务被系统绑定的期间模块 App 进程常活，广播路径可靠；
 *     广播被 MIUI 拦截/服务被杀 = 快照过期 = 宿主侧组件自然空缺（隐藏），不阻塞主链路。
 *
 * 隐私红线（对齐原版 AppNotificationStore 注释）：通知正文/媒体元数据仅进程内存
 * 与本桥 JSON 快照，从不持久化落盘、从不上传。
 */
package com.homemode4miui.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.weather.WeatherCurrent
import com.galaxyrio.gracelauncher.data.weather.WeatherDay
import com.galaxyrio.gracelauncher.data.weather.WeatherHour
import com.galaxyrio.gracelauncher.data.weather.WeatherLocation
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.galaxyrio.gracelauncher.data.weather.WeatherTemperature
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

object GraceBridge {

    /** notif6：Java 互操作（HomeController.attach 每次挂载拉取快照）。 */
    @JvmStatic
    fun snapshotRefreshIntent(): android.content.Intent = command(Cmd.SNAPSHOT_REFRESH)

    // ---- 快照下发（模块 App 进程 → 宿主进程）----
    const val ACTION_MEDIA = "com.homemode4miui.ACTION_GRACE_MEDIA"
    const val ACTION_NOTIFICATIONS = "com.homemode4miui.ACTION_GRACE_NOTIFICATIONS"
    // ACTION_WEATHER 废止：天气读取上移宿主进程（GraceWeatherHostEngine），不走广播

    // ---- 命令上行（宿主进程 → 模块 App 进程）----
    const val ACTION_COMMAND = "com.homemode4miui.ACTION_GRACE_COMMAND"

    const val PACKAGE_HOST = "com.miui.home"
    const val PACKAGE_MODULE = "com.homemode4miui"

    const val EXTRA_JSON = "json"
    const val EXTRA_ART = "art"

    // 命令字段
    const val EXTRA_CMD = "cmd"
    const val EXTRA_ARG1 = "arg1"
    const val EXTRA_ARG2 = "arg2"

    /** 通知快照限幅（hotfix1）：每 app 条数 / 总字节（广播 binder 事务安全区）。 */
    private const val NOTIFICATIONS_PER_APP_LIMIT = 3
    private const val NOTIFICATIONS_TOTAL_BYTES_LIMIT = 192 * 1024

    /** 命令字面量（对齐 MediaSessionRepository.command / dismiss / AppNotificationStore）。 */
    object Cmd {
        const val MEDIA_COMMAND = "media_command"      // arg1=sessionId, arg2=command.name
        const val MEDIA_DISMISS = "media_dismiss"      // arg1=sessionId, arg2=revision
        const val NOTIFICATION_OPEN = "notification_open"   // arg1=key, arg2=revision
        const val NOTIFICATION_DISMISS = "notification_dismiss" // arg1=key, arg2=revision
        // WEATHER_REFRESH 废止：天气读取上移宿主，设置页刷新走 SETTINGS_COMMAND 通道
        // notif1：宿主挂载拉取快照（无参数）。引擎指纹去重是持久单例 —— 宿主桌面重启后
        // （装新 APK / 桌面进程被杀），重启前发出的通知/媒体快照不会重发，必须显式拉取。
        const val SNAPSHOT_REFRESH = "snapshot_refresh"
        // SYSTEM_ACTION 废止（hotfix）：全仓零发送方 —— Route 直接进程内调
        // GraceSystemActions（原生 → root → 无障碍三层链在宿主进程本地完成），
        // 跨进程委派路径连同 receiver 分支一并删除（导出 receiver 崩溃面收敛）。
    }

    // ================= 媒体快照 =================

    fun encodeMedia(np: NowPlaying, art: ByteArray?): JSONObject = JSONObject()
        .put("id", np.sessionId)
        .put("player", np.playerName)
        .put("title", np.title ?: JSONObject.NULL)
        .put("artist", np.artist ?: JSONObject.NULL)
        .put("playing", np.playing)
        .put("canT", np.canToggle)
        .put("canP", np.canPrevious)
        .put("canN", np.canNext)
        .put("rev", np.revision)

    /** art == null 表示封面未变化（宿主沿用上次缓存）。 */
    fun decodeMedia(json: String?, art: ByteArray?): NowPlaying? = runCatching {
        val v = JSONObject(json ?: return null)
        val artwork = art?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
        NowPlaying(
            sessionId = v.getString("id"),
            playerName = v.getString("player"),
            title = v.opt("title") as? String,
            artist = v.opt("artist") as? String,
            artwork = artwork,
            playing = v.getBoolean("playing"),
            canToggle = v.getBoolean("canT"),
            canPrevious = v.getBoolean("canP"),
            canNext = v.getBoolean("canN"),
            revision = v.getLong("rev"),
        )
    }.getOrNull()

    fun encodeArtwork(bitmap: Bitmap?): ByteArray? = runCatching {
        bitmap ?: return null
        val out = ByteArrayOutputStream()
        // JPEG：封面无透明需求，体积 ≈ PNG 的 1/5，稳居广播 extra 安全区。
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }.getOrNull()

    // ================= 通知快照 =================

    /**
     * icon 不跨桥（favorites 行预览仅消费 title/text/时间；弹层 icon 属 C 组）。
     * hotfix1：限幅 —— 通知多/正文长的场景 JSON 可达数百 KB，广播 binder 事务
     * 共享 1MB 缓冲，超限 TransactionTooLargeException 会被引擎 runCatching 吞掉
     * （快照静默丢失）。每包最多 3 条、总字节数 192KB 封顶，超出部分丢弃。
     */
    fun encodeNotifications(map: Map<String, List<AppNotification>>): String {
        val root = JSONArray()
        var totalBytes = 0
        map.forEach { (pkg, list) ->
            val items = JSONArray()
            list.take(NOTIFICATIONS_PER_APP_LIMIT).forEach { n ->
                val item = JSONObject()
                    .put("key", n.key)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("at", n.postedAt)
                    .put("rev", n.revision)
                    .put("canDismiss", n.canDismiss)
                    .put("canOpen", n.canOpen)
                totalBytes += item.toString().toByteArray(Charsets.UTF_8).size + pkg.length + 8
                if (totalBytes > NOTIFICATIONS_TOTAL_BYTES_LIMIT) return@forEach
                items.put(item)
            }
            if (items.length() > 0) root.put(JSONObject().put("pkg", pkg).put("items", items))
        }
        return root.toString()
    }

    fun decodeNotifications(json: String?): Map<String, List<AppNotification>> = runCatching {
        val array = JSONArray(json ?: return emptyMap())
        buildMap {
            (0 until array.length()).forEach { i ->
                val pkgObj = array.getJSONObject(i)
                val pkg = pkgObj.getString("pkg")
                val items = pkgObj.getJSONArray("items")
                this[pkg] = (0 until items.length()).mapNotNull { j ->
                    runCatching {
                        val n = items.getJSONObject(j)
                        AppNotification(
                            key = n.getString("key"),
                            packageName = pkg,
                            title = n.getString("title"),
                            text = n.getString("text"),
                            postedAt = n.getLong("at"),
                            revision = n.getLong("rev"),
                            canDismiss = n.getBoolean("canDismiss"),
                            canOpen = n.getBoolean("canOpen"),
                        )
                    }.getOrNull()
                }
            }
        }
    }.getOrDefault(emptyMap())

    // ================= 天气快照（含 locations 的全量 wire 版） =================

    /**
     * 全量 wire：status + snapshot（UI 消费面）+ locations（设置页地点选择器）。
     * 天气读取在宿主进程（com.miui.home 被天气 provider 白名单放行）后，
     * 该 wire 有两个去向：随 GraceSettingsBridge.Snapshot 回带设置页（wthr 字段），
     * 以及宿主进程内 GraceWeatherHostEngine 的指纹去重。
     */
    fun encodeWeatherWire(state: WeatherState): String = JSONObject()
        .put("status", state.status.name)
        .put("snapshot", state.snapshot?.let(::encodeWeatherSnapshot) ?: JSONObject.NULL)
        .put("locs", JSONArray().apply {
            state.locations.forEach { l ->
                put(JSONObject()
                    .put("id", l.id)
                    .put("name", l.name)
                    .put("cur", l.isCurrentPosition))
            }
        })
        .toString()

    private fun encodeWeatherSnapshot(s: WeatherSnapshot): JSONObject = JSONObject()
        .put("locId", s.location.id)
        .put("locName", s.location.name)
        .put("tz", s.location.timeZone.id)
        .put("cur", s.current?.let { JSONObject()
            .put("t", it.temperature?.value ?: JSONObject.NULL)
            .put("u", it.temperature?.unit ?: JSONObject.NULL)
            .put("c", it.condition.name)
            .put("d", it.isDaylight)
            .put("desc", it.description ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("hourly", JSONArray().apply {
            s.hourly.forEach { h ->
                put(JSONObject()
                    .put("at", h.at.toEpochMilli())
                    .put("t", h.temperature?.value ?: JSONObject.NULL)
                    .put("u", h.temperature?.unit ?: JSONObject.NULL)
                    .put("c", h.condition.name)
                    .put("d", h.isDaylight)
                    .put("desc", h.description ?: JSONObject.NULL))
            }
        })
        .put("daily", JSONArray().apply {
            s.daily.forEach { d ->
                put(JSONObject()
                    .put("date", d.date.toString())
                    .put("hi", d.high?.let { JSONObject().put("v", it.value).put("u", it.unit) } ?: JSONObject.NULL)
                    .put("lo", d.low?.let { JSONObject().put("v", it.value).put("u", it.unit) } ?: JSONObject.NULL)
                    .put("c", d.condition.name)
                    .put("desc", d.description ?: JSONObject.NULL))
            }
        })
        .put("upd", s.updatedAt?.toEpochMilli() ?: JSONObject.NULL)
        .put("attr", s.attribution)

    fun decodeWeatherWire(json: String?): WeatherState = runCatching {
        val v = JSONObject(json ?: return WeatherState(WeatherStatus.Disabled))
        val status = WeatherStatus.entries.firstOrNull { it.name == v.optString("status") } ?: WeatherStatus.Disabled
        val locations = v.optJSONArray("locs")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val l = arr.getJSONObject(i)
                    WeatherLocation(
                        id = l.optString("id"),
                        name = l.optString("name", "—"),
                        timeZone = ZoneId.systemDefault(),
                        isCurrentPosition = l.optBoolean("cur", false),
                    )
                }.getOrNull()
            }
        } ?: emptyList()
        val snapshot = v.optJSONObject("snapshot")?.let { s ->
            fun temp(o: JSONObject?, keyV: String = "t", keyU: String = "u"): WeatherTemperature? {
                val unit = o?.opt(keyU) as? String ?: return null
                val value = (o.opt(keyV) as? Number)?.toDouble() ?: return null
                return WeatherTemperature(value, unit)
            }
            fun condition(name: String?) = com.galaxyrio.gracelauncher.data.weather.WeatherCondition.entries
                .firstOrNull { it.name == name } ?: com.galaxyrio.gracelauncher.data.weather.WeatherCondition.Unknown
            WeatherSnapshot(
                location = WeatherLocation(
                    id = s.optString("locId", "host"),
                    name = s.optString("locName", "MIUI Weather"),
                    timeZone = runCatching { ZoneId.of(s.getString("tz")) }.getOrDefault(ZoneId.systemDefault()),
                ),
                current = s.optJSONObject("cur")?.let { c ->
                    WeatherCurrent(
                        temperature = temp(c),
                        condition = condition(c.optString("c")),
                        isDaylight = c.optBoolean("d", true),
                        description = (c.opt("desc") as? String),
                    )
                },
                hourly = s.optJSONArray("hourly")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val h = arr.getJSONObject(i)
                            WeatherHour(
                                at = Instant.ofEpochMilli(h.getLong("at")),
                                temperature = temp(h),
                                condition = condition(h.optString("c")),
                                isDaylight = h.optBoolean("d", true),
                                description = h.opt("desc") as? String,
                            )
                        }.getOrNull()
                    }
                } ?: emptyList(),
                daily = s.optJSONArray("daily")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val d = arr.getJSONObject(i)
                            WeatherDay(
                                date = LocalDate.parse(d.getString("date")),
                                high = temp(d.optJSONObject("hi"), "v", "u"),
                                low = temp(d.optJSONObject("lo"), "v", "u"),
                                condition = condition(d.optString("c")),
                                description = d.opt("desc") as? String,
                            )
                        }.getOrNull()
                    }
                } ?: emptyList(),
                updatedAt = (s.opt("upd") as? Number)?.toLong()?.let(Instant::ofEpochMilli),
                attribution = s.optString("attr", ""),
            )
        }
        WeatherState(
            status = status,
            snapshot = snapshot,
            locations = locations,
            selectedLocationId = snapshot?.location?.id,
        )
    }.getOrDefault(WeatherState(WeatherStatus.Disabled))

    // ================= 命令构造 =================

    fun command(cmd: String, arg1: String? = null, arg2: String? = null) = android.content.Intent(ACTION_COMMAND)
        .setPackage(PACKAGE_MODULE)
        .putExtra(EXTRA_CMD, cmd)
        .putExtra(EXTRA_ARG1, arg1)
        .putExtra(EXTRA_ARG2, arg2)

    fun mediaCommandName(command: MediaCommand): String = command.name

    fun mediaCommand(name: String?): MediaCommand? = MediaCommand.entries.firstOrNull { it.name == name }
}
