package com.chencohensolution.batterytime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the phone asks the Mac to do: POST /v1/command (docs/companion-protocol.md) */
sealed class Command(val type: String) {
    data class SetStayAwake(val on: Boolean) : Command("setStayAwake")
    data class SetLowPowerMode(val on: Boolean) : Command("setLowPowerMode")
    data class SetLowPowerWhenLidClosed(val on: Boolean) : Command("setLowPowerWhenLidClosed")
    data class SetPreset(val preset: Preset) : Command("setPreset")
    data class SetActiveMode(val mode: ActiveMode) : Command("setActiveMode")
    data class PauseLimits(val minutes: Int = 60) : Command("pauseLimits")
    data object ResumeLimits : Command("resumeLimits")
    /** [action] null: the app follows Battery Saver again */
    data class SetAppRule(val app: String, val action: LimitAction?) : Command("setAppRule")
    /** Re-measure energy use now instead of at the next 10 s sample */
    data object Refresh : Command("refresh")

    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        when (val c = this@Command) {
            is SetStayAwake -> put("on", c.on)
            is SetLowPowerMode -> put("on", c.on)
            is SetLowPowerWhenLidClosed -> put("on", c.on)
            is SetPreset -> put("preset", c.preset.raw)
            is SetActiveMode -> put("mode", c.mode.raw)
            is PauseLimits -> put("minutes", JsonPrimitive(c.minutes))
            is SetAppRule -> {
                put("app", c.app)
                put("action", c.action?.raw ?: "auto")
            }
            ResumeLimits, Refresh -> Unit
        }
    }
}

/**
 * The state as it will look once the Mac has run [command], shown right away so a tap feels instant. The Mac's reply
 * replaces it a moment later.
 */
fun MacState.applying(command: Command, nowMs: Long): MacState = when (command) {
    is Command.SetStayAwake -> copy(stayAwake = command.on)
    is Command.SetLowPowerMode -> copy(lowPowerMode = command.on)
    is Command.SetLowPowerWhenLidClosed -> copy(lowPowerWhenLidClosed = command.on)
    is Command.SetPreset -> copy(saver = saver.copy(preset = command.preset.raw))
    is Command.SetActiveMode -> copy(saver = saver.copy(activeMode = command.mode.raw))
    is Command.PauseLimits -> copy(saver = saver.copy(pausedUntil = nowMs + command.minutes * 60_000L, active = false))
    Command.ResumeLimits -> copy(saver = saver.copy(pausedUntil = null))
    is Command.SetAppRule -> {
        val newRules = if (command.action == null) rules - command.app else rules + (command.app to command.action.raw)
        copy(
            rules = newRules,
            apps = apps.map { if (it.name == command.app) it.copy(rule = command.action?.raw) else it },
        )
    }
    Command.Refresh -> this
}
