package com.galaxyrio.gracelauncher.data

/*
 * 上游同步 1.2.0：App Stack（横向应用堆）。
 * 与上游的差异：上游本文件的 expandWidgetStacks / collapseWidgetStacks 服务于
 * widget 网格里的整宽堆叠展开，本模块未移植 widget 网格（WidgetGridCell 不存在），
 * 故只保留堆叠几何计算部分。
 */

internal const val AppStackInset = 8f
private const val AppStackColumnGap = 12f
internal const val AppStackRowGap = 8f

/** Shared by the inline surface and widget-grid footprint, in density-independent units. */
internal fun appStackColumns(width: Float, iconSize: Float): Int =
    ((width - AppStackInset * 2 + AppStackColumnGap) / (iconSize + AppStackColumnGap)).toInt().coerceAtLeast(2)

internal fun appStackHeight(rows: Int, iconSize: Float): Float =
    AppStackInset * 2 + iconSize + (rows.coerceAtLeast(1) - 1) * (iconSize + AppStackRowGap)

/** Reserve the last slot for the toggle only when a stack overflows one row. */
internal fun appStackRows(count: Int, columns: Int, expanded: Boolean): Int {
    val width = columns.coerceAtLeast(2)
    return if (!expanded || count <= width) 1 else (count + width) / width
}
