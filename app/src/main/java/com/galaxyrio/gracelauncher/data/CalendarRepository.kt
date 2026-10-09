package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.provider.CalendarContract
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarRepository(private val context: Context) {
    suspend fun upcomingEvents(): List<ScheduleEvent> = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val horizon = now.plus(Duration.ofDays(14))
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_COLOR,
        )

        CalendarContract.Instances.query(
            context.contentResolver,
            projection,
            // Include UTC all-day instances that may still be today in the user's zone.
            now.minus(Duration.ofDays(1)).toEpochMilli(),
            horizon.toEpochMilli(),
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val colorIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_COLOR)

            buildList {
                while (cursor.moveToNext()) {
                    val title = cursor.getString(titleIndex)?.trim().orEmpty()
                    if (title.isBlank()) continue
                    add(
                        ScheduleEvent(
                            id = cursor.getLong(idIndex),
                            title = title,
                            startsAt = Instant.ofEpochMilli(cursor.getLong(beginIndex)),
                            endsAt = Instant.ofEpochMilli(cursor.getLong(endIndex)),
                            isAllDay = cursor.getInt(allDayIndex) != 0,
                            location = cursor.getString(locationIndex)?.takeIf(String::isNotBlank),
                            calendarColor = if (cursor.isNull(colorIndex)) null else cursor.getInt(colorIndex),
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}
