package com.galaxyrio.gracelauncher.data

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Persist the Material role so the chosen color follows the current theme. */
enum class MaterialColorRole(val label: String) {
    Primary("primary"), OnPrimary("onPrimary"), PrimaryContainer("primaryContainer"), OnPrimaryContainer("onPrimaryContainer"),
    Secondary("secondary"), OnSecondary("onSecondary"), SecondaryContainer("secondaryContainer"), OnSecondaryContainer("onSecondaryContainer"),
    Tertiary("tertiary"), OnTertiary("onTertiary"), TertiaryContainer("tertiaryContainer"), OnTertiaryContainer("onTertiaryContainer"),
    Surface("surface"), OnSurface("onSurface"), SurfaceVariant("surfaceVariant"), OnSurfaceVariant("onSurfaceVariant"),
    SurfaceDim("surfaceDim"), SurfaceBright("surfaceBright"), SurfaceContainerLowest("surfaceContainerLowest"),
    SurfaceContainerLow("surfaceContainerLow"), SurfaceContainer("surfaceContainer"),
    SurfaceContainerHigh("surfaceContainerHigh"), SurfaceContainerHighest("surfaceContainerHighest"),
    Background("background"), OnBackground("onBackground"), Outline("outline"), OutlineVariant("outlineVariant"),
    Error("error"), OnError("onError"), ErrorContainer("errorContainer"), OnErrorContainer("onErrorContainer"),
    InverseSurface("inverseSurface"), InverseOnSurface("inverseOnSurface"), InversePrimary("inversePrimary"),
    SurfaceTint("surfaceTint"), Scrim("scrim");

    fun color(scheme: ColorScheme): Color = with(scheme) {
        when (this@MaterialColorRole) {
            Primary -> primary
            OnPrimary -> onPrimary
            PrimaryContainer -> primaryContainer
            OnPrimaryContainer -> onPrimaryContainer
            Secondary -> secondary
            OnSecondary -> onSecondary
            SecondaryContainer -> secondaryContainer
            OnSecondaryContainer -> onSecondaryContainer
            Tertiary -> tertiary
            OnTertiary -> onTertiary
            TertiaryContainer -> tertiaryContainer
            OnTertiaryContainer -> onTertiaryContainer
            Surface -> surface
            OnSurface -> onSurface
            SurfaceVariant -> surfaceVariant
            OnSurfaceVariant -> onSurfaceVariant
            SurfaceDim -> surfaceDim
            SurfaceBright -> surfaceBright
            SurfaceContainerLowest -> surfaceContainerLowest
            SurfaceContainerLow -> surfaceContainerLow
            SurfaceContainer -> surfaceContainer
            SurfaceContainerHigh -> surfaceContainerHigh
            SurfaceContainerHighest -> surfaceContainerHighest
            Background -> background
            OnBackground -> onBackground
            Outline -> outline
            OutlineVariant -> outlineVariant
            Error -> error
            OnError -> onError
            ErrorContainer -> errorContainer
            OnErrorContainer -> onErrorContainer
            InverseSurface -> inverseSurface
            InverseOnSurface -> inverseOnSurface
            InversePrimary -> inversePrimary
            SurfaceTint -> surfaceTint
            Scrim -> scrim
        }
    }
}
