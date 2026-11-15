package com.galaxyrio.gracelauncher.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A package transition, not every Activity resume, counts as an app opening. */
internal class AppOpenCounter(
    private val excludedPackage: String,
    private val firstDay: LocalDate,
    private val zone: ZoneId,
    initialDays: Map<String, Map<String, Long>> = emptyMap(),
    initialPackage: String? = null,
) {
    val days = initialDays.filterKeys { it >= firstDay.toString() }
        .mapValues { (_, counts) -> counts.toMutableMap() }.toMutableMap()
    var lastPackage: String? = initialPackage
        private set

    fun resume(pkg: String, time: Long) {
        if (pkg.isEmpty() || pkg == lastPackage) return
        lastPackage = pkg
        val date = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        if (pkg == excludedPackage || date < firstDay) return
        val counts = days.getOrPut(date.toString()) { mutableMapOf() }
        counts[pkg] = (counts[pkg] ?: 0L) + 1L
    }

    fun screenOff() { lastPackage = null }

    fun totals(): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        days.values.forEach { counts -> counts.forEach { (pkg, count) -> result[pkg] = (result[pkg] ?: 0L) + count } }
        return result
    }
}

/** Incremental, on-return history. Persist only daily counts and a query cursor, never raw events. */
internal class AppOpenHistory(private val context: Context, private val manager: UsageStatsManager) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "usage-opens.json"))
    private var saved: JSONObject? = null

    fun clear(revoke: Boolean) {
        saved = null
        if (revoke) file.delete()
    }

    // Called under UsageRepository's IO mutex. [through] is exclusive; replay is idempotent.
    fun update(now: Long, zone: ZoneId): Map<String, Long> {
        val firstDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(6)
        val windowStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val previous = saved ?: runCatching {
            file.openRead().bufferedReader().use { JSONObject(it.readText()) }
        }.getOrDefault(JSONObject())
        val through = previous.optLong("through")
        val usable = through in 1..now && previous.optString("zone") == zone.id
        val days = if (usable) previous.optJSONObject("days") ?: JSONObject() else JSONObject()
        val initial = days.keys().asSequence().associateWith { date ->
            val counts = days.getJSONObject(date)
            counts.keys().asSequence().associateWith { counts.getLong(it).coerceAtLeast(0L) }
        }
        val counter = AppOpenCounter(context.packageName, firstDay, zone, initial,
            previous.optString("lastPackage").takeIf { usable && through >= windowStart && it.isNotEmpty() })
        // Bootstrap as far back as Android still has events, including carry-in state.
        val from = if (usable) maxOf(through, windowStart) else windowStart - 86_400_000L
        val events = manager.queryEvents(from, now) ?: error("Usage events unavailable")
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < from || event.timeStamp >= now) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> counter.resume(event.packageName.orEmpty(), event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> counter.screenOff()
            }
        }
        val encodedDays = JSONObject()
        counter.days.forEach { (date, counts) -> encodedDays.put(date, JSONObject(counts)) }
        val next = JSONObject().put("through", now).put("zone", zone.id)
            .put("lastPackage", counter.lastPackage.orEmpty()).put("days", encodedDays)
        val output = file.startWrite()
        try {
            output.write(next.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
        saved = next
        return counter.totals()
    }
}
