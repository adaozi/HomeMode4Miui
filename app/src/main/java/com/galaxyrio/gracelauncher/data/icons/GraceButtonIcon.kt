package com.galaxyrio.gracelauncher.data.icons

import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.IconShape
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp

/** A designer-only identity, never the settings Activity or an app-list entry. */
internal object GraceButtonIcon {
    private val component = ComponentName("com.galaxyrio.gracelauncher", "GraceButton")
    val key: String = component.flattenToString()
    val defaults: IconDesign = IconDesign.defaults().copy(shape = IconShape.Circle)

    fun item(context: Context) = LauncherApp(component, context.getString(R.string.settings_grace_button),
        icon = null, isAdaptiveIcon = true)

    fun choice(saved: ItemIcon?): ItemIcon = (saved ?: ItemIcon.System).let {
        it.copy(design = (it.design?.withThemeDefaults(defaults) ?: defaults).copy(iconSize = 100))
    }

    /** Use stable names in storage, not resource IDs which may change in a new build. */
    enum class Suggestion(val id: String, val drawable: Int, val label: Int) {
        Default("default", R.drawable.ic_grace_solid, R.string.icon_edit_default),
        Search("search", R.drawable.ms_search, R.string.settings_search),
        List("format_list_bulleted", R.drawable.ms_format_list_bulleted, R.string.icon_suggestion_list),
        Power("power_settings_new", R.drawable.ms_power_settings_new, R.string.icon_suggestion_power),
        Bolt("bolt", R.drawable.ms_bolt, R.string.icon_suggestion_bolt),
        Notifications("notifications", R.drawable.ms_notifications, R.string.icon_suggestion_notifications),
        Add("add", R.drawable.ms_add, R.string.icon_suggestion_add),
    }

    fun layers(context: Context, choice: ItemIcon, size: Int, colors: Pair<Int, Int>): IconLayers {
        val suggestion = Suggestion.entries.firstOrNull { choice.kind == "symbol" && it.id == choice.name }
        val glyph = requireNotNull(ContextCompat.getDrawable(context, suggestion?.drawable ?: R.drawable.ic_grace_solid)).mutate()
        glyph.setTint(colors.second)
        // Match the 40dp solid layer in the 54dp FAB; Material symbols use a 26dp viewport.
        val fraction = if (suggestion == null || suggestion == Suggestion.Default) 40f / 54f else 26f / 54f
        val inset = (size * (1f - fraction) / 2f).toInt()
        val foreground = createBitmap(size, size).also {
            glyph.setBounds(inset, inset, size - inset, size - inset)
            glyph.draw(Canvas(it))
        }
        val background = createBitmap(size, size).also { it.eraseColor(colors.first) }
        val mask = iconShapePath(IconShape.Circle, size = size.toFloat())
        val original = createBitmap(size, size).also {
            Canvas(it).apply {
                clipPath(mask)
                drawBitmap(background, 0f, 0f, null)
                drawBitmap(foreground, 0f, 0f, null)
            }
        }
        return IconLayers(original, background, foreground, foreground, mask)
    }
}

internal val LauncherApp.isGraceButton: Boolean get() = key == GraceButtonIcon.key
