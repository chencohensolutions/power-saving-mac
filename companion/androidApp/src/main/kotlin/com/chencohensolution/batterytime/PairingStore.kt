package com.chencohensolution.batterytime

import android.content.Context
import kotlinx.serialization.json.Json

/** The paired Mac, kept in the app's private preferences */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(): Pairing? = prefs.getString("mac", null)?.let {
        runCatching { Json.decodeFromString(Pairing.serializer(), it) }.getOrNull()
    }

    fun save(pairing: Pairing?) {
        prefs.edit().apply {
            if (pairing == null) remove("mac") else putString("mac", Json.encodeToString(Pairing.serializer(), pairing))
        }.apply()
    }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", false)
        set(value) = prefs.edit().putBoolean("keepScreenOn", value).apply()
}
