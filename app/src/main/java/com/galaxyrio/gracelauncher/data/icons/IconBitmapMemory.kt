package com.galaxyrio.gracelauncher.data.icons

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Shared source/monochrome images count once. Eviction never recycles pixels still used by the UI. */
internal fun iconBitmapBytes(vararg icons: ImageBitmap?): Int = icons.filterNotNull()
    .map { it.asAndroidBitmap() }.distinct().sumOf { it.allocationByteCount }
