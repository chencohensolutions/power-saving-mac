package com.chencohensolution.batterytime

import kotlinx.serialization.Serializable

/** A Mac this phone is paired with: where to reach it and the code it accepts */
@Serializable
data class Pairing(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** 8 characters, without the dash */
    val code: String,
    /** The Mac's name, also its Bonjour service name, used to find it again when its address changes */
    val name: String = "Mac",
) {
    val baseUrl: String get() = "http://${if (':' in host) "[$host]" else host}:$port"

    /** The code as the Mac shows it: ABCD-EFGH */
    val displayCode: String get() = if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code

    companion object {
        const val DEFAULT_PORT = 47070
        const val SERVICE_TYPE = "_batterytime._tcp"

        /** The QR code in the Mac's Phone Companion window: batterytime://pair?h=…&p=…&c=…&n=… */
        fun fromLink(link: String): Pairing? {
            val trimmed = link.trim()
            if (!trimmed.startsWith("batterytime://pair", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?', "")
            val params = query.split('&').filter { '=' in it }.associate {
                it.substringBefore('=') to percentDecode(it.substringAfter('='))
            }
            val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
            val code = normalizeCode(params["c"] ?: return null) ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return Pairing(host, port, code, params["n"]?.takeIf { it.isNotBlank() } ?: "Mac")
        }

        /** Typed by hand: "192.168.1.20" or "192.168.1.20:47070", and the code with or without its dash */
        fun fromTyped(address: String, code: String, name: String = "Mac"): Pairing? {
            val a = address.trim()
            if (a.isEmpty()) return null
            val (host, port) = if (a.count { it == ':' } == 1) {
                a.substringBefore(':') to (a.substringAfter(':').toIntOrNull() ?: return null)
            } else {
                a.removePrefix("[").removeSuffix("]") to DEFAULT_PORT
            }
            if (host.isEmpty() || port !in 1..65535) return null
            return Pairing(host, port, normalizeCode(code) ?: return null, name)
        }

        /** Uppercase, without spaces and dashes; null unless 8 characters from the Mac's alphabet */
        fun normalizeCode(code: String): String? {
            val c = code.filterNot { it == '-' || it.isWhitespace() }.uppercase()
            return c.takeIf { it.length == 8 && it.all { ch -> ch in CODE_ALPHABET } }
        }

        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private fun percentDecode(s: String): String {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '%' && i + 3 <= s.length) {
                    val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (b != null) {
                        out.add(b.toByte())
                        i += 3
                        continue
                    }
                }
                out.addAll(ch.toString().encodeToByteArray().toList())   // the Mac leaves '+' as it is
                i++
            }
            return out.toByteArray().decodeToString()
        }
    }
}
