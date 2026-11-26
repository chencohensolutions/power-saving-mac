package com.chencohensolution.batterytime.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Battery green, as the Mac's menu shows a healthy battery */
val BatteryGreen = Color(0xFF34C759)
val BatteryAmber = Color(0xFFFF9F0A)
val BatteryRed = Color(0xFFFF453A)

@Composable
fun BatteryTimeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // The phone's own colors (Material You), else green on neutral
    val scheme = when {
        dark -> runCatching { dynamicDarkColorScheme(context) }.getOrElse { darkColorScheme(primary = BatteryGreen) }
        else -> runCatching { dynamicLightColorScheme(context) }.getOrElse { lightColorScheme(primary = Color(0xFF1E8E3E)) }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun batteryColor(percent: Int, charging: Boolean): Color = when {
    charging || percent > 20 -> BatteryGreen
    percent > 10 -> BatteryAmber
    else -> BatteryRed
}
