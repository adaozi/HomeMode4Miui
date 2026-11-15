package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

enum class MediaCoverLayout {
    Mini, Standard, Large, None;

    val informationRows: Int get() = if (this == Large) 3 else 2
    val hasStandardCover: Boolean get() = this == Standard || this == Large
}

enum class MediaInformation { Album, Title, Artist }
enum class MediaPlaybackButtonStyle { Circle, Chevron }
enum class MediaProgressStyle { Linear, Wavy, Circular }

/** The default preset retains the original cover and information layout. */
data class MediaPlayerStyle(
    val layout: MediaCoverLayout = MediaCoverLayout.Standard,
    val coverShape: IconDesign = DefaultCoverShape,
    /** Null follows the shared icon geometry until the user chooses a Mini-specific shape. */
    val miniCoverShape: IconDesign? = null,
    val coverBorder: Boolean = false,
    val coverBorderWidth: Int = 2,
    val coverBorderOpacity: Int = 100,
    val coverBorderColor: IconColor = IconColor.FromApp,
    val coverShadow: Int = 20,
    val information: List<MediaInformation> = DefaultInformation,
    val informationOrder: List<MediaInformation> = DefaultInformationOrder,
    val showPlaybackControls: Boolean = true,
    val playbackButtonStyle: MediaPlaybackButtonStyle = MediaPlaybackButtonStyle.Circle,
    val showProgress: Boolean = false,
    val progressStyle: MediaProgressStyle = MediaProgressStyle.Linear,
) {
    val hasSeparateProgress: Boolean get() = showProgress && progressStyle != MediaProgressStyle.Circular
    val informationLineCount: Int get() = if (layout == MediaCoverLayout.Standard && hasSeparateProgress) 1 else layout.informationRows

    fun normalized(): MediaPlayerStyle {
        val order = completeOrder(informationOrder, MediaInformation.entries)
        return copy(
            // Cover styling shares icon geometry, never icon colors or foreground overrides.
            coverShape = coverShape.coverGeometry(),
            miniCoverShape = miniCoverShape?.coverGeometry(),
            coverBorderWidth = coverBorderWidth.coerceIn(1, 12),
            coverBorderOpacity = coverBorderOpacity.coerceIn(0, 100),
            coverBorderColor = coverBorderColor.normalized(),
            coverShadow = coverShadow.coerceIn(0, 100),
            information = order.filter { it in information },
            informationOrder = order,
            showPlaybackControls = showPlaybackControls || (showProgress && progressStyle == MediaProgressStyle.Circular),
        )
    }

    fun withPlaybackControls(show: Boolean): MediaPlayerStyle = copy(
        showPlaybackControls = show,
        showProgress = showProgress && (show || progressStyle != MediaProgressStyle.Circular),
    )

    fun withProgress(show: Boolean): MediaPlayerStyle = copy(
        showProgress = show,
        showPlaybackControls = showPlaybackControls || (show && progressStyle == MediaProgressStyle.Circular),
    )

    fun coverDesign(iconDesign: IconDesign): IconDesign {
        if (layout != MediaCoverLayout.Mini) return coverShape
        // The cover inherits the mask, never the icon foreground's scale, offset or rotation.
        // Its overall display size is already supplied by the shared iconSize setting.
        return (miniCoverShape ?: iconDesign).coverGeometry()
    }

    fun withCoverShape(design: IconDesign, iconDesign: IconDesign): MediaPlayerStyle =
        if (layout == MediaCoverLayout.Mini) copy(miniCoverShape = design.coverGeometry().takeUnless { it == iconDesign.coverGeometry() })
        else copy(coverShape = design.coverGeometry())

    fun withInformationOrder(order: List<MediaInformation>): MediaPlayerStyle = copy(informationOrder = order).normalized()

    fun toggleInformation(field: MediaInformation): MediaPlayerStyle =
        copy(information = if (field in information) information - field else information + field).normalized()

    /** Earlier lines get one field each; the final line contains the remaining fields. */
    fun informationLines(): List<List<MediaInformation>> {
        val fields = normalized().information
        val count = minOf(informationLineCount, fields.size)
        return List(count) { index -> if (index == count - 1) fields.drop(index) else listOf(fields[index]) }
    }

    fun encode(): String = normalized().let { value ->
        JSONObject().put("version", 4).put("layout", value.layout.name).put("coverShape", value.coverShape.json())
            .put("miniCoverShape", value.miniCoverShape?.json())
            .put("coverBorder", value.coverBorder).put("coverBorderWidth", value.coverBorderWidth)
            .put("coverBorderOpacity", value.coverBorderOpacity).put("coverBorderColor", value.coverBorderColor.json())
            .put("coverShadow", value.coverShadow)
            .put("information", JSONArray(value.information.map { it.name }))
            .put("informationOrder", JSONArray(value.informationOrder.map { it.name }))
            .put("showPlaybackControls", value.showPlaybackControls).put("playbackButtonStyle", value.playbackButtonStyle.name)
            .put("showProgress", value.showProgress).put("progressStyle", value.progressStyle.name)
            .toString()
    }

    companion object {
        val DefaultCoverShape = IconDesign(shape = IconShape.Square, squareCornerRadius = 14)
        val DefaultInformation = listOf(MediaInformation.Title, MediaInformation.Artist)
        val DefaultInformationOrder = listOf(MediaInformation.Title, MediaInformation.Artist, MediaInformation.Album)

        fun decode(json: String?): MediaPlayerStyle = runCatching {
            if (json == null) return MediaPlayerStyle()
            val value = JSONObject(json)
            val defaults = MediaPlayerStyle()
            val oldInformation = value.optInt("version") < 4
            val information = value.optJSONArray("information")?.let { fields ->
                if (!oldInformation) decodeOrder(fields, MediaInformation.entries)
                else {
                    val oldOrders = value.optJSONArray("informationOrder")
                    // Preserve selections in all old rows, including the previously hidden third row.
                    List(3) { index ->
                        val selected = decodeOrder(fields.optJSONArray(index), MediaInformation.entries)
                        val order = oldOrders?.optJSONArray(index)?.let { decodeOrder(it, MediaInformation.entries) } ?: selected
                        completeOrder(order, MediaInformation.entries).filter { it in selected }
                    }.flatten().distinct()
                }
            } ?: defaults.information
            val informationOrder = if (oldInformation) completeOrder(information, defaults.informationOrder)
                else value.optJSONArray("informationOrder")?.let { decodeOrder(it, MediaInformation.entries) }
                    ?: completeOrder(information, defaults.informationOrder)
            val color = IconColor.decode(value.optJSONObject("coverBorderColor")) ?: defaults.coverBorderColor
            val legacyControls = value.optJSONArray("controls")?.let { fields ->
                (0 until fields.length()).map { fields.optString(it) }.toSet()
            }
            defaults.copy(
                layout = MediaCoverLayout.entries.firstOrNull { it.name == value.optString("layout") } ?: defaults.layout,
                coverShape = IconDesign.decode(value.optJSONObject("coverShape")) ?: defaults.coverShape,
                miniCoverShape = IconDesign.decode(value.optJSONObject("miniCoverShape")),
                coverBorder = value.optBoolean("coverBorder", defaults.coverBorder),
                coverBorderWidth = value.optInt("coverBorderWidth", defaults.coverBorderWidth),
                coverBorderOpacity = value.optInt("coverBorderOpacity", defaults.coverBorderOpacity),
                coverBorderColor = if (value.optInt("version") < 2 && color == IconColor(0, materialRole = MaterialColorRole.Outline))
                    defaults.coverBorderColor else color,
                coverShadow = value.optInt("coverShadow", defaults.coverShadow),
                information = information,
                informationOrder = informationOrder,
                showPlaybackControls = value.optBoolean("showPlaybackControls",
                    legacyControls?.any { it in setOf("Previous", "PlayPause", "Next") } ?: defaults.showPlaybackControls),
                playbackButtonStyle = MediaPlaybackButtonStyle.entries.firstOrNull { it.name == value.optString("playbackButtonStyle") }
                    ?: defaults.playbackButtonStyle,
                showProgress = value.optBoolean("showProgress", legacyControls?.contains("Progress") ?: defaults.showProgress),
                progressStyle = MediaProgressStyle.entries.firstOrNull { it.name == value.optString("progressStyle") }
                    ?: defaults.progressStyle,
            ).normalized()
        }.getOrDefault(MediaPlayerStyle())
    }
}

private fun IconDesign.coverGeometry(): IconDesign = normalized().let { shape ->
    IconDesign(shape = shape.shape, cookieSides = shape.cookieSides, pebbleRoundness = shape.pebbleRoundness,
        squareCornerRadius = shape.squareCornerRadius, squareAspectRatio = shape.squareAspectRatio)
}

private fun <T> completeOrder(order: List<T>, options: List<T>): List<T> =
    order.filter { it in options }.distinct().let { it + options.filterNot(it::contains) }

private fun <T : Enum<T>> decodeOrder(array: JSONArray?, options: List<T>): List<T> =
    if (array == null) emptyList() else (0 until array.length()).mapNotNull { index -> options.firstOrNull { it.name == array.optString(index) } }
