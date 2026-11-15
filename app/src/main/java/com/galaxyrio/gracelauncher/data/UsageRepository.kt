package com.galaxyrio.gracelauncher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** On-demand only. Shared across HOME/settings Activities; no timer, service, alarm or worker. */
internal class UsageRepository private constructor(private val context: Context) {
    private val mutex = Mutex()
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    private val rankingFile = AtomicFile(File(context.noBackupFilesDir, "usage-ranking.json"))
    private val opens = AppOpenHistory(context, manager)
    private var rankingLoaded = false
    private var cached = UsageSnapshot()
    private var lastAttempt = 0L
    private var attemptKey: String? = null
    private var historyKey: String? = null
    private var weekHistory: Map<String, Long> = emptyMap()
    private var monthHistory: Map<String, Long> = emptyMap()
    private var loadedDisplays = emptySet<UsageDisplay>()
    private var openCountsLoaded = false

    fun hasAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    suspend fun refresh(settings: DigitalWellbeingSettings, forceRanking: Boolean = false,
        needOpenCounts: Boolean = false): UsageSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val access = hasAccess()
            // Dynamic categories never collect or expose usage data while Digital Wellbeing is off.
            if (!settings.enabled || !access) {
                cached = UsageSnapshot(hasAccess = access)
                historyKey = null; loadedDisplays = emptySet(); lastAttempt = 0
                rankingLoaded = false; openCountsLoaded = false
                opens.clear(revoke = !access)
                if (!access) rankingFile.delete()
                return@withLock cached
            }
            if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return@withLock UsageSnapshot(hasAccess = true)
            if (!rankingLoaded) {
                cached = readRanking().copy(hasAccess = true)
                rankingLoaded = true
            }
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val key = "$today/$zone"
            val plan = usageReadPlan(settings, needOpenCounts, forceRanking, cached.rankedAt, now, zone)
            val rankDue = plan.ranking
            val needsLabels = plan.labels
            if (!needsLabels && !plan.opens) return@withLock cached
            val sameDay = historyKey == key && now >= cached.updatedAt
            if (!forceRanking && cached.failed && attemptKey == key && elapsed - lastAttempt in 0 until 60_000) return@withLock cached
            if (!forceRanking && attemptKey == key && elapsed - lastAttempt in 0 until 60_000 &&
                (!needsLabels || (sameDay && settings.display.all { it in loadedDisplays })) &&
                (!plan.opens || openCountsLoaded) && !rankDue) return@withLock cached
            lastAttempt = elapsed
            attemptKey = key
            try {
                // Categories use fresh counts even while the drawer's saved ordering is frozen.
                // Keep this result if a separate duration-label query subsequently fails.
                val openCounts = if (plan.opens) opens.update(now, zone) else cached.openCounts
                if (plan.opens) {
                    cached = cached.copy(openCounts = openCounts)
                    openCountsLoaded = true
                }
                val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
                val day = if (needsLabels) readDay(start, now) else cached.day
                // Historical daily buckets are queried only once per date/time-zone,
                // or when enabling an additional range/manual refresh. Today uses events.
                if (needsLabels && (!sameDay || forceRanking || !settings.display.all { it in loadedDisplays })) {
                    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
                    val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val from = minOf(if (UsageDisplay.Week in settings.display) weekStart else start,
                        if (UsageDisplay.Month in settings.display) monthStart else start)
                    val buckets = if (from < start) manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, start)
                        ?: error("Usage data unavailable") else emptyList()
                    fun sum(since: Long): Map<String, Long> = buckets.asSequence()
                        .filter { it.firstTimeStamp >= since && it.firstTimeStamp < start && it.packageName != context.packageName }
                        .groupBy { it.packageName }.mapValues { (_, entries) -> entries.sumOf { it.totalTimeInForeground.coerceAtLeast(0) } }
                    weekHistory = sum(weekStart); monthHistory = sum(monthStart)
                    historyKey = key; loadedDisplays = settings.display
                }
                val rank = if (rankDue) openCounts else cached.ranking
                val rankedAt = if (rankDue) now else cached.rankedAt
                // A manual refresh is successful only if its snapshot is durable (including Never).
                if (rankDue) saveRanking(rank, rankedAt)
                cached = UsageSnapshot(hasAccess = true, updatedAt = if (needsLabels) now else cached.updatedAt, day = day,
                    week = if (needsLabels) plus(weekHistory, day) else cached.week,
                    month = if (needsLabels) plus(monthHistory, day) else cached.month,
                    ranking = rank, rankedAt = rankedAt, openCounts = openCounts)
            } catch (_: Exception) {
                cached = cached.copy(hasAccess = hasAccess(), failed = true)
                if (!cached.hasAccess) {
                    cached = UsageSnapshot(); rankingFile.delete(); rankingLoaded = false; openCountsLoaded = false
                    opens.clear(revoke = true)
                }
            }
            cached
        }
    }

    private fun readDay(start: Long, end: Long): Map<String, Long> {
        // One previous day supplies the state of sessions spanning local midnight.
        val events = manager.queryEvents(start - 86_400_000L, end) ?: error("Usage events unavailable")
        val usage = ForegroundUsage(start, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName.orEmpty()
            val activity = event.className.orEmpty()
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (pkg != context.packageName) usage.resume(pkg, activity, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> usage.pause(pkg, activity, event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> usage.stopAll(event.timeStamp)
            }
        }
        return usage.finish()
    }

    private fun plus(a: Map<String, Long>, b: Map<String, Long>) = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }

    private fun readRanking(): UsageSnapshot = runCatching {
        val json = rankingFile.openRead().bufferedReader().use { JSONObject(it.readText()) }
        // Do not interpret the previous duration-based snapshot as opening counts.
        require(json.optString("metric") == "opens-v1")
        val scores = json.getJSONObject("scores")
        UsageSnapshot(rankedAt = json.getLong("at"), ranking = scores.keys().asSequence().associateWith { scores.getLong(it).coerceAtLeast(0) })
    }.getOrDefault(UsageSnapshot())

    private fun saveRanking(scores: Map<String, Long>, at: Long) {
        val output = rankingFile.startWrite()
        try {
            output.write(JSONObject().put("metric", "opens-v1").put("at", at).put("scores", JSONObject(scores))
                .toString().toByteArray(Charsets.UTF_8))
            rankingFile.finishWrite(output)
        } catch (error: Exception) { rankingFile.failWrite(output); throw error }
    }

    companion object {
        @Volatile private var instance: UsageRepository? = null
        fun get(context: Context): UsageRepository = instance ?: synchronized(this) {
            instance ?: UsageRepository(context.applicationContext).also { instance = it }
        }
    }
}
