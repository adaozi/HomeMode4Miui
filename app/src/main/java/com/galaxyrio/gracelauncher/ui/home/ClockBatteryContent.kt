package com.galaxyrio.gracelauncher.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockBattery
import com.galaxyrio.gracelauncher.ui.theme.LauncherBatteryState

/** One text run keeps the icon, percent and charging mark on the region's font baseline. */
@Composable
internal fun ClockBatteryContent(
    battery: LauncherBatteryState,
    config: ClockBattery,
    textStyle: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    iconColor: Color = color,
) {
    if (!config.showIcon && !config.showPercentage) return
    val description = stringResource(
        if (battery.isCharging) R.string.clock_battery_charging else R.string.clock_battery_level,
        battery.percent,
    )
    val showBolt = battery.isCharging && !config.showIcon && config.showPercentage
    val text = buildAnnotatedString {
        if (config.showIcon) appendInlineContent("battery")
        if (config.showPercentage) {
            if (config.showIcon) append(" ")
            append("${battery.percent}%")
        }
        if (showBolt) {
            append(" ")
            appendInlineContent("charging")
        }
    }
    // Keep the parent's long-press action while exposing one complete spoken battery status.
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Text(
            text = text,
            modifier = Modifier.clearAndSetSemantics {},
            inlineContent = buildMap {
                val placeholder = Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)
                if (config.showIcon) put("battery", InlineTextContent(placeholder) {
                    Icon(painterResource(clockBatteryIcon(config.iconStyle, battery.percent, battery.isCharging)),
                        contentDescription = null, tint = iconColor,
                        modifier = Modifier.fillMaxSize().testTag("clock_battery_icon"))
                })
                if (showBolt) put("charging", InlineTextContent(placeholder) {
                    Icon(painterResource(R.drawable.ms_bolt), contentDescription = null, tint = iconColor,
                        modifier = Modifier.fillMaxSize().testTag("clock_battery_bolt"))
                })
            },
            style = textStyle.copy(textDirection = TextDirection.Ltr),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
