package com.galaxyrio.gracelauncher.data.tasks

import java.time.Instant
import java.time.ZoneId
import java.text.Collator
import java.util.Locale

enum class TaskPriority { High, Medium, Low, None }

data class TaskItem(
    val id: Long,
    val title: String,
    val notes: String = "",
    val dueAt: Instant? = null,
    val dueAllDay: Boolean = false,
    val startsAt: Instant? = null,
    val startAllDay: Boolean = false,
    val priority: TaskPriority = TaskPriority.None,
    val listId: Long = 0L,
    val listName: String = "",
    val listColor: Int? = null,
    val isReadOnly: Boolean = false,
)

data class TaskList(val id: Long, val title: String, val color: Int?, val accountId: Long, val accountName: String)

enum class TasksStatus { Disabled, Loading, Ready, NotInstalled, PermissionRequired, UpdateRequired, Error }

enum class TaskCompletionResult { Success, PermissionRequired, ReadOnly, NotFound, Error }

data class TasksState(
    val status: TasksStatus = TasksStatus.Disabled,
    val tasks: List<TaskItem> = emptyList(),
    val lists: List<TaskList> = emptyList(),
)

/** Include every unfinished task, regardless of start date, and apply the saved display order. */
fun visibleTaskItems(
    tasks: List<TaskItem>,
    preferences: TasksPreferences = TasksPreferences(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<TaskItem> {
    val byDueDate = compareBy<TaskItem> { task -> task.dueAt?.let { due ->
        if (task.dueAllDay) due.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant() else due
    } ?: Instant.MAX }
    val order = when (preferences.sortMode) {
        TaskSortMode.DueDate -> byDueDate
        TaskSortMode.Priority -> compareBy<TaskItem> { it.priority.ordinal }.then(byDueDate)
        TaskSortMode.Name -> {
            val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
            Comparator<TaskItem> { first, second -> collator.compare(first.title, second.title) }
        }
    }
    val grouped = if (preferences.undatedFirst) compareBy<TaskItem> { it.dueAt != null }.then(order) else order
    return tasks.distinctBy { it.id }.sortedWith(grouped.thenBy { it.id })
}
