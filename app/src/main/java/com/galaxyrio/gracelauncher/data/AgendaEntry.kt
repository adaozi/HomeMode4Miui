package com.galaxyrio.gracelauncher.data

import com.galaxyrio.gracelauncher.data.tasks.TaskItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal sealed interface AgendaEntry {
    data class Calendar(val event: ScheduleEvent) : AgendaEntry
    data class Task(val task: TaskItem) : AgendaEntry

    val key: String get() = when (this) {
        is Calendar -> "event:${event.id}:${event.startsAt}"
        is Task -> "task:${task.id}"
    }
}

/** Keep calendar/weather dates and add all dated tasks, including overdue and distant tasks. */
internal fun agendaDaysWithTasks(
    calendarDays: Map<LocalDate, List<ScheduleEvent>>,
    tasks: List<TaskItem>,
    zone: ZoneId = ZoneId.systemDefault(),
    deprioritizeAllDay: Boolean = true,
    tasksFirst: Boolean = false,
): Map<LocalDate, List<AgendaEntry>> {
    val days = calendarDays.mapValues { (_, events) ->
        events.mapTo(mutableListOf<AgendaEntry>()) { AgendaEntry.Calendar(it) }
    }.toSortedMap()
    tasks.distinctBy { it.id }.forEach { task ->
        // Tasks.org all-day dates use the device's local zone, unlike Calendar Provider dates.
        val date = task.dueAt?.atZone(zone)?.toLocalDate() ?: return@forEach
        days.getOrPut(date) { mutableListOf() }.add(AgendaEntry.Task(task))
    }
    val chronological = compareBy<AgendaEntry> { it.scheduledAt(zone) }
    val order = if (deprioritizeAllDay) compareBy<AgendaEntry> {
        when (it) {
            is AgendaEntry.Calendar -> it.event.isAllDay
            is AgendaEntry.Task -> it.task.dueAllDay
        }
    }.then(chronological) else chronological
    val groupedOrder = if (tasksFirst) compareBy<AgendaEntry> { it !is AgendaEntry.Task }.then(order) else order
    days.values.forEach { it.sortWith(groupedOrder) }
    return days
}

private fun AgendaEntry.scheduledAt(zone: ZoneId): Instant = when (this) {
    is AgendaEntry.Calendar -> event.localBoundary(event.startsAt, zone)
    is AgendaEntry.Task -> requireNotNull(task.dueAt).let { due ->
        if (task.dueAllDay) due.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant() else due
    }
}
