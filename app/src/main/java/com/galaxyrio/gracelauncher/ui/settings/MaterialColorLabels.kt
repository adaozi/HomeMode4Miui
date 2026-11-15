package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.MaterialColorRole

@Composable
internal fun MaterialColorRole.localizedLabel(): String = stringResource(when (this) {
    MaterialColorRole.Primary -> R.string.material_color_primary
    MaterialColorRole.OnPrimary -> R.string.material_color_on_primary
    MaterialColorRole.PrimaryContainer -> R.string.material_color_primary_container
    MaterialColorRole.OnPrimaryContainer -> R.string.material_color_on_primary_container
    MaterialColorRole.Secondary -> R.string.material_color_secondary
    MaterialColorRole.OnSecondary -> R.string.material_color_on_secondary
    MaterialColorRole.SecondaryContainer -> R.string.material_color_secondary_container
    MaterialColorRole.OnSecondaryContainer -> R.string.material_color_on_secondary_container
    MaterialColorRole.Tertiary -> R.string.material_color_tertiary
    MaterialColorRole.OnTertiary -> R.string.material_color_on_tertiary
    MaterialColorRole.TertiaryContainer -> R.string.material_color_tertiary_container
    MaterialColorRole.OnTertiaryContainer -> R.string.material_color_on_tertiary_container
    MaterialColorRole.Surface -> R.string.material_color_surface
    MaterialColorRole.OnSurface -> R.string.material_color_on_surface
    MaterialColorRole.SurfaceVariant -> R.string.material_color_surface_variant
    MaterialColorRole.OnSurfaceVariant -> R.string.material_color_on_surface_variant
    MaterialColorRole.SurfaceDim -> R.string.material_color_surface_dim
    MaterialColorRole.SurfaceBright -> R.string.material_color_surface_bright
    MaterialColorRole.SurfaceContainerLowest -> R.string.material_color_surface_container_lowest
    MaterialColorRole.SurfaceContainerLow -> R.string.material_color_surface_container_low
    MaterialColorRole.SurfaceContainer -> R.string.material_color_surface_container
    MaterialColorRole.SurfaceContainerHigh -> R.string.material_color_surface_container_high
    MaterialColorRole.SurfaceContainerHighest -> R.string.material_color_surface_container_highest
    MaterialColorRole.Background -> R.string.material_color_background
    MaterialColorRole.OnBackground -> R.string.material_color_on_background
    MaterialColorRole.Outline -> R.string.material_color_outline
    MaterialColorRole.OutlineVariant -> R.string.material_color_outline_variant
    MaterialColorRole.Error -> R.string.material_color_error
    MaterialColorRole.OnError -> R.string.material_color_on_error
    MaterialColorRole.ErrorContainer -> R.string.material_color_error_container
    MaterialColorRole.OnErrorContainer -> R.string.material_color_on_error_container
    MaterialColorRole.InverseSurface -> R.string.material_color_inverse_surface
    MaterialColorRole.InverseOnSurface -> R.string.material_color_inverse_on_surface
    MaterialColorRole.InversePrimary -> R.string.material_color_inverse_primary
    MaterialColorRole.SurfaceTint -> R.string.material_color_surface_tint
    MaterialColorRole.Scrim -> R.string.material_color_scrim
})

