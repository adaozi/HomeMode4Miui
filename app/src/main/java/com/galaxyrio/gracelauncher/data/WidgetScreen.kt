package com.galaxyrio.gracelauncher.data

import android.content.ComponentName
import org.json.JSONObject

/** Reuse mixed-content storage and widget ownership, without creating a fake folder. */
const val WidgetScreenKey = "com.galaxyrio.gracelauncher/widget-screen"

fun widgetScreenOwner(label: String) = LauncherApp(
    requireNotNull(ComponentName.unflattenFromString(WidgetScreenKey)), label, null,
)

data class WidgetScreenSettings(
    val enabled: Boolean = true,
    val swipeToOpen: Boolean = true,
    val columns: Int = 4,
    val editingLocked: Boolean = false,
) {
    fun encode(): String = JSONObject().put("enabled", enabled).put("swipeToOpen", swipeToOpen)
        .put("columns", columns.coerceIn(2, 8)).put("editingLocked", editingLocked).toString()

    companion object {
        fun decode(json: String?): WidgetScreenSettings = runCatching {
            val data = JSONObject(json ?: "{}")
            WidgetScreenSettings(data.optBoolean("enabled", true), data.optBoolean("swipeToOpen", true),
                data.optInt("columns", 4).coerceIn(2, 8), data.optBoolean("editingLocked", false))
        }.getOrDefault(WidgetScreenSettings())
    }
}

/** Null coordinates retain a resized widget's span when the list editor reorders it. */
data class WidgetGridPlacement(val column: Int?, val row: Int?, val columns: Int = 1, val rows: Int = 1) {
    fun json(): JSONObject = JSONObject().put("columns", columns).put("rows", rows).apply {
        column?.let { put("column", it) }; row?.let { put("row", it) }
    }
    companion object {
        fun decode(value: JSONObject?): WidgetGridPlacement? = value?.let {
            WidgetGridPlacement(it.optInt("column", -1).takeIf { n -> n >= 0 },
                it.optInt("row", -1).takeIf { n -> n >= 0 },
                it.optInt("columns", 1).coerceIn(1, 8), it.optInt("rows", 1).coerceIn(1, 20))
        }
    }
}

internal data class WidgetGridSize(val columns: Int = 1, val rows: Int = 1)
internal data class WidgetGridCell(val key: String, val column: Int, val row: Int, val size: WidgetGridSize) {
    fun placement() = WidgetGridPlacement(column, row, size.columns, size.rows)
    fun overlaps(other: WidgetGridCell): Boolean = column < other.column + other.size.columns &&
        column + size.columns > other.column && row < other.row + other.size.rows && row + size.rows > other.row
}

/** Existing coordinates win over new entries. Adding/removing an item never fills unrelated holes. */
internal fun restoreWidgetGrid(entries: List<PopupItem>, columns: Int, sizes: Map<String, WidgetGridSize>): List<WidgetGridCell> {
    val width = columns.coerceIn(2, 8)
    val placed = mutableListOf<WidgetGridCell>()
    val ordered = entries.distinctBy { it.key }.sortedBy { it.grid?.row == null || it.grid.column == null }
    for (entry in ordered) {
        val saved = entry.grid
        val requested = if (entry.widget == null) sizes[entry.key] ?: WidgetGridSize() else
            saved?.let { WidgetGridSize(it.columns, it.rows) } ?: sizes[entry.key] ?: WidgetGridSize(2, 2)
        val size = WidgetGridSize(requested.columns.coerceIn(1, width), requested.rows.coerceIn(1, 20))
        val cell = WidgetGridCell(entry.key, (saved?.column ?: 0).coerceIn(0, width - size.columns),
            saved?.row?.coerceAtLeast(0) ?: 0, size)
        placed += vacantWidgetCell(cell, width, placed)
    }
    return compactWidgetRows(placed)
}

/** Move only colliding items forward. Unoccupied cells elsewhere keep their positions. */
internal fun moveWidgetGrid(cells: List<WidgetGridCell>, moving: WidgetGridCell, columns: Int): List<WidgetGridCell> {
    val placed = mutableListOf(moving)
    for (cell in cells.filterNot { it.key == moving.key }.sortedWith(compareBy({ it.row }, { it.column }))) {
        placed += vacantWidgetCell(cell, columns, placed)
    }
    return placed.sortedWith(compareBy({ it.row }, { it.column }))
}

private fun vacantWidgetCell(cell: WidgetGridCell, columns: Int, occupied: List<WidgetGridCell>): WidgetGridCell {
    var next = cell
    while (occupied.any(next::overlaps)) {
        next = if (next.column + next.size.columns < columns) next.copy(column = next.column + 1)
            else next.copy(column = 0, row = next.row + 1)
    }
    return next
}

/** Compress row indices, not cells. A partly occupied row (including a widget span) is never collapsed. */
internal fun compactWidgetRows(cells: List<WidgetGridCell>): List<WidgetGridCell> {
    val occupiedRows = cells.flatMap { it.row until it.row + it.size.rows }.distinct().sorted()
    return cells.map { it.copy(row = occupiedRows.binarySearch(it.row)) }
        .sortedWith(compareBy({ it.row }, { it.column }))
}
