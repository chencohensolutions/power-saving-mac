package com.chencohensolution.batterytime

/** Text shared by the phone UIs, so Android and iOS say the same thing */
object Format {
    /** 312 → "5:12", as the Mac's menu bar shows time */
    fun hm(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"

    /** The big line under the percentage: "5:12 left", "Full in 0:45", "Plugged in", "Calculating…" */
    fun batteryHeadline(b: MacState.Battery): String = when {
        b.charging -> b.minutes?.let { "Full in ${hm(it)}" } ?: "Charging"
        b.onAC -> "Plugged in · not charging"
        else -> b.minutes?.let { "${hm(it)} left" } ?: "Calculating time left…"
    }

    /** A length of time: "45s", "12m", "2h 05m" */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            else -> "${s / 3600}h ${(s % 3600 / 60).toString().padStart(2, '0')}m"
        }
    }

    /** "Lid closed for 2h 05m" or "Lid open" */
    fun lid(lid: MacState.Lid, nowMs: Long): String =
        if (!lid.closed) "Lid open"
        else lid.closedSince?.let { "Lid closed for ${duration(nowMs - it)}" } ?: "Lid closed"

    /** Under an app's name: what Battery Saver does with it, as the Mac menu's tag */
    fun appTag(app: MacState.AppRow, saverActive: Boolean): String {
        val action = LimitAction.from(app.action) ?: return ""
        return when {
            action == LimitAction.None -> "Never limited"
            saverActive && app.limitedNow -> action.tag
            else -> "${action.tag} on lid close"
        }
    }
}
