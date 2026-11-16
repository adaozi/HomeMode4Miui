package com.galaxyrio.gracelauncher.data

import android.content.ContentUris
import android.content.Context
import android.os.SystemClock
import android.provider.CalendarContract
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CalendarSnapshot(
    val query: CalendarQuery,
    val events: List<ScheduleEvent>,
    val refreshedAtNanos: Long,
)

data class CalendarSource(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
    val color: Int?,
    val visible: Boolean,
)

class CalendarRepository(private val context: Context) {
    // HOME and the separate Settings Activity share explicit refresh results.
    val snapshots = sharedSnapshots.asStateFlow()

    fun clearSnapshot() {
        snapshotGeneration.incrementAndGet()
        sharedSnapshots.value = null
    }

    suspend fun refresh(query: CalendarQuery): CalendarSnapshot? {
        val normalizedQuery = query.copy(daysAhead = query.daysAhead.coerceIn(CalendarPreferences.DaysAheadRange))
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        val generation = snapshotGeneration.get()
        return readMutex.withLock {
            if (generation != snapshotGeneration.get()) return@withLock null
            // A concurrent caller can use the same read once it has completed.
            sharedSnapshots.value?.takeIf {
                it.query == normalizedQuery && it.refreshedAtNanos >= requestedAt
            }?.let { return@withLock it }
            val events = upcomingEvents(normalizedQuery)
            if (generation != snapshotGeneration.get()) return@withLock null
            CalendarSnapshot(normalizedQuery, events, SystemClock.elapsedRealtimeNanos()).also {
                sharedSnapshots.value = it
            }
        }
    }

    /** Include hidden calendars in the picker without changing their provider flags. */
    suspend fun calendars(): List<CalendarSource> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        ) ?: error("Calendar provider returned no cursor")
        cursor.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val displayNameIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val nameIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.NAME)
            val accountNameIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val accountTypeIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            val colorIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
            val visibleIndex = it.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            buildList {
                while (it.moveToNext()) {
                    add(CalendarSource(
                        id = it.getLong(idIndex),
                        name = it.getString(displayNameIndex)?.trim()?.takeIf(String::isNotEmpty)
                            ?: it.getString(nameIndex)?.trim().orEmpty(),
                        accountName = it.getString(accountNameIndex).orEmpty(),
                        accountType = it.getString(accountTypeIndex).orEmpty(),
                        color = if (it.isNull(colorIndex)) null else it.getInt(colorIndex),
                        visible = it.getInt(visibleIndex) != 0,
                    ))
                }
            }
        }
    }

    private suspend fun upcomingEvents(query: CalendarQuery): List<ScheduleEvent> = withContext(Dispatchers.IO) {
        val calendarIds = query.selectedCalendarIds
        if (calendarIds != null && calendarIds.isEmpty()) return@withContext emptyList()
        val now = Instant.now()
        val horizon = now.plus(Duration.ofDays(query.daysAhead.toLong()))
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_COLOR,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            // Include UTC all-day instances that may still be today in the user's zone.
            ContentUris.appendId(this, now.minus(Duration.ofDays(1)).toEpochMilli())
            ContentUris.appendId(this, horizon.toEpochMilli())
        }.build()
        // Instances.query() always adds VISIBLE = 1, excluding explicitly selected hidden calendars.
        val selection = if (calendarIds == null) CalendarContract.Calendars.VISIBLE + " = ?"
            else CalendarContract.Instances.CALENDAR_ID + " IN (${calendarIds.joinToString { "?" }})"
        val selectionArgs = calendarIds?.map(Long::toString)?.toTypedArray() ?: arrayOf("1")
        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs, "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: error("Calendar provider returned no cursor")
        cursor.use {
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val colorIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR)
            val displayColorIndex = cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)
            val calendarNameIndex = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

            buildList {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex)?.trim().orEmpty()
                    if (title.isBlank()) continue
                    val calendarColor = if (cursor.isNull(colorIndex)) null else cursor.getInt(colorIndex)
                    add(
                        ScheduleEvent(
                            id = cursor.getLong(idIndex),
                            title = title,
                            startsAt = Instant.ofEpochMilli(cursor.getLong(beginIndex)),
                            endsAt = Instant.ofEpochMilli(cursor.getLong(endIndex)),
                            isAllDay = cursor.getInt(allDayIndex) != 0,
                            location = cursor.getString(locationIndex)?.takeIf(String::isNotBlank),
                            calendarColor = calendarColor,
                            displayColor = if (displayColorIndex >= 0 && !cursor.isNull(displayColorIndex))
                                cursor.getInt(displayColorIndex) else calendarColor,
                            calendarName = if (calendarNameIndex >= 0) cursor.getString(calendarNameIndex)?.trim()
                                ?.takeIf(String::isNotBlank) else null,
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        val sharedSnapshots = MutableStateFlow<CalendarSnapshot?>(null)
        val snapshotGeneration = AtomicLong()
        val readMutex = Mutex()
    }
}
