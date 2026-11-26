package com.chencohensolution.batterytime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What BatteryTime on the Mac reports at GET /v1/state (docs/companion-protocol.md). Every field has a default, so a
 * newer Mac app that adds fields, or an older one missing some, still reads.
 */
@Serializable
data class MacState(
    val v: Int = 1,
    val mac: MacInfo = MacInfo(),
    val battery: Battery = Battery(),
    val lid: Lid = Lid(),
    /** "Stay On With Lid Closed" (pmset disablesleep) */
    val stayAwake: Boolean = false,
    val lowPowerMode: Boolean = false,
    val lowPowerWhenLidClosed: Boolean = false,
    val saver: Saver = Saver(),
    /** Apps using significant energy, then apps with a rule or a limit, as the Mac menu lists them */
    val apps: List<AppRow> = emptyList(),
    /** App name → LimitAction raw value */
    val rules: Map<String, String> = emptyMap(),
    /** Running apps a rule can be added for */
    val runningApps: List<String> = emptyList(),
    val lastRecap: String? = null,
    /** The Mac's clock, ms since 1970 */
    val time: Long? = null,
) {
    @Serializable
    data class MacInfo(val name: String = "Mac")

    @Serializable
    data class Battery(
        val percent: Int = 0,
        val charging: Boolean = false,
        val onAC: Boolean = false,
        /** To empty on battery, to full while charging; null while macOS is still calculating */
        val minutes: Int? = null,
        /** As the Mac menu says it: "5:12 Remaining", "Not Charging" */
        val timeText: String = "",
        val powerSource: String = "",
    )

    @Serializable
    data class Lid(val closed: Boolean = false, val closedSince: Long? = null)

    @Serializable
    data class Saver(
        val preset: String = Preset.Off.raw,
        val activeMode: String = ActiveMode.LidClosed.raw,
        /** Limits are being applied right now */
        val active: Boolean = false,
        val pausedUntil: Long? = null,
        val limitedCount: Int = 0,
        val status: String = "",
        val statusDetail: String = "",
        val otherAppsOnECores: Int = 0,
    )

    @Serializable
    data class AppRow(
        val name: String,
        /** Energy impact, as in Activity Monitor */
        val power: Double = 0.0,
        /** What Battery Saver does with it (LimitAction raw), or null when nothing */
        val action: String? = null,
        val reason: String? = null,
        /** The user's rule for it, or null when it follows Battery Saver */
        val rule: String? = null,
        val limitedNow: Boolean = false,
    )

    val preset: Preset get() = Preset.from(saver.preset)
    val activeMode: ActiveMode get() = ActiveMode.from(saver.activeMode)

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }
    }
}

/** Battery Saver's level (Shared/Shared.swift `Preset`) */
enum class Preset(val raw: String, val title: String, val detail: String) {
    Off("off", "Off", "Only your app rules"),
    Light("light", "Light", "All apps on efficiency cores"),
    Balanced("balanced", "Balanced", "Light, plus power-hungry apps capped at 50%"),
    Max("max", "Max", "Light, plus power-hungry apps paused");

    companion object {
        fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: Off
    }
}

/** When Battery Saver's limits apply (App/main.swift `ActiveMode`) */
enum class ActiveMode(val raw: String, val title: String) {
    Never("never", "Never"),
    LidClosed("lidClosed", "Only when lid is closed"),
    Always("always", "Always");

    companion object {
        fun from(raw: String) = entries.firstOrNull { it.raw == raw } ?: LidClosed
    }
}

/** What happens to one app (App/main.swift `LimitAction`) */
enum class LimitAction(val raw: String, val title: String, val tag: String) {
    ECores("ecores", "Efficiency cores", "E-cores"),
    P50("p50", "Limit to 50%", "50%"),
    P25("p25", "Limit to 25%", "25%"),
    Pause("pause", "Pause", "Paused"),
    None("none", "Never limit", "Never");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}
