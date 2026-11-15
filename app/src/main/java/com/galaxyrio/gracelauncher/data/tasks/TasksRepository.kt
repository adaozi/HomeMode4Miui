package com.galaxyrio.gracelauncher.data.tasks

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TasksSnapshot(val listIds: Set<Long>?, val state: TasksState, val refreshedAtNanos: Long)

/** Tasks.org's versioned local API. Sync and recurring-task behavior belong to Tasks.org. */
class TasksRepository(private val context: Context) {
    val snapshots = sharedSnapshots.asStateFlow()

    fun clearSnapshot() {
        generation.incrementAndGet()
        sharedSnapshots.value = null
    }

    fun changes() = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val registered = if (hasReadPermission()) runCatching {
            resolver.registerContentObserver(BaseUri, true, observer)
        }.isSuccess else false
        trySend(Unit)
        awaitClose { if (registered) runCatching { resolver.unregisterContentObserver(observer) } }
    }.flowOn(Dispatchers.IO)

    suspend fun refresh(listIds: Set<Long>?): TasksSnapshot? {
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        val requestedGeneration = generation.get()
        return providerMutex.withLock {
            if (requestedGeneration != generation.get()) return@withLock null
            sharedSnapshots.value?.takeIf { it.listIds == listIds && it.refreshedAtNanos >= requestedAt }
                ?.let { return@withLock it }
            val state = read(listIds)
            if (requestedGeneration != generation.get()) return@withLock null
            TasksSnapshot(listIds, state, SystemClock.elapsedRealtimeNanos()).also { sharedSnapshots.value = it }
        }
    }

    fun hasReadPermission() = ContextCompat.checkSelfPermission(context, ReadPermission) == PackageManager.PERMISSION_GRANTED

    suspend fun completeTask(id: Long): TaskCompletionResult = providerMutex.withLock {
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, WritePermission) != PackageManager.PERMISSION_GRANTED) {
                return@withContext TaskCompletionResult.PermissionRequired
            }
            try {
                // This is the provider's completion operation, including recurrence and subtasks.
                val values = ContentValues().apply { put("completed_at", System.currentTimeMillis()) }
                val changed = context.contentResolver.update(taskUri(id), values, null, null)
                if (changed > 0) TaskCompletionResult.Success else TaskCompletionResult.NotFound
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SecurityException) {
                TaskCompletionResult.PermissionRequired
            } catch (error: UnsupportedOperationException) {
                TaskCompletionResult.ReadOnly
            } catch (error: Exception) {
                TaskCompletionResult.Error
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun read(listIds: Set<Long>?): TasksState = withContext(Dispatchers.IO) {
        try {
            val provider = context.packageManager.resolveContentProvider(Authority, 0)
            if (provider == null) {
                val installed = runCatching { context.packageManager.getApplicationInfo(Package, 0) }.isSuccess
                return@withContext TasksState(if (installed) TasksStatus.UpdateRequired else TasksStatus.NotInstalled)
            }
            if (!hasReadPermission()) return@withContext TasksState(TasksStatus.PermissionRequired)
            val accounts = queryPages("accounts", arrayOf("_id", "name")) { cursor ->
                cursor.long("_id") to cursor.text("name")
            }.toMap()
            val lists = queryPages("lists", arrayOf("_id", "title", "color", "account_id")) { cursor ->
                val accountId = cursor.long("account_id")
                TaskList(cursor.long("_id"), cursor.text("title"), cursor.long("color").toInt().takeUnless { it == 0 },
                    accountId, accounts[accountId].orEmpty())
            }
            val byId = lists.associateBy { it.id }
            val tasks = if (listIds != null && listIds.isEmpty()) emptyList() else queryPages(
                "tasks", arrayOf("_id", "title", "notes", "due_date", "due_all_day", "start_date", "start_all_day",
                    "priority", "list_id", "completed_at", "is_read_only"),
                parameters = buildList {
                    add("completed" to "0")
                    listIds?.sorted()?.forEach { add("list_id" to it.toString()) }
                },
            ) { cursor ->
                if (cursor.long("completed_at") != 0L) null else {
                    val listId = cursor.long("list_id")
                    TaskItem(
                        id = cursor.long("_id"), title = cursor.text("title").trim(), notes = cursor.text("notes"),
                        dueAt = cursor.instant("due_date"), dueAllDay = cursor.long("due_all_day") == 1L,
                        startsAt = cursor.instant("start_date"), startAllDay = cursor.long("start_all_day") == 1L,
                        priority = TaskPriority.entries.firstOrNull { it.name.equals(cursor.text("priority"), ignoreCase = true) }
                            ?: TaskPriority.None,
                        listId = listId, listName = byId[listId]?.title.orEmpty(), listColor = byId[listId]?.color,
                        isReadOnly = cursor.long("is_read_only") == 1L,
                    )
                }
            }.filterNotNull().distinctBy { it.id }
            if (!hasReadPermission()) TasksState(TasksStatus.PermissionRequired)
            else TasksState(TasksStatus.Ready, tasks, lists)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            TasksState(TasksStatus.PermissionRequired)
        } catch (error: IllegalArgumentException) {
            TasksState(TasksStatus.UpdateRequired)
        } catch (error: Exception) {
            TasksState(TasksStatus.Error)
        }
    }

    private suspend fun <T> queryPages(
        endpoint: String, projection: Array<String>, parameters: List<Pair<String, String>> = emptyList(),
        row: (Cursor) -> T,
    ): List<T> = buildList {
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val uri = BaseUri.buildUpon().appendPath(endpoint).apply {
                parameters.forEach { (name, value) -> appendQueryParameter(name, value) }
                appendQueryParameter("limit", PageSize.toString())
                appendQueryParameter("offset", offset.toString())
            }.build()
            // This API rejects SQL selection and sortOrder; filtering belongs in the URI.
            val count = (context.contentResolver.query(uri, projection, null, null, null)
                ?: error("Tasks.org provider returned no cursor")).use { cursor ->
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    add(row(cursor))
                }
                cursor.count
            }
            if (count < PageSize) break
            offset += count
        }
    }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
    private fun Cursor.instant(column: String): Instant? = long(column).takeUnless { it == 0L }?.let(Instant::ofEpochMilli)

    companion object {
        const val Authority = "org.tasks.api"
        const val Package = "org.tasks"
        const val ReadPermission = "org.tasks.permission.READ_TASKS"
        const val WritePermission = "org.tasks.permission.WRITE_TASKS"
        val BaseUri: Uri = Uri.parse("content://$Authority/v0")
        fun taskUri(id: Long): Uri = BaseUri.buildUpon().appendPath("tasks").appendPath(id.toString()).build()
        private const val PageSize = 200
        private val sharedSnapshots = MutableStateFlow<TasksSnapshot?>(null)
        private val generation = AtomicLong()
        private val providerMutex = Mutex()
    }
}
