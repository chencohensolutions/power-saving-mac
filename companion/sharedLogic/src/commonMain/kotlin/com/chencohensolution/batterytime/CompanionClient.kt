package com.chencohensolution.batterytime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The HTTP client for this platform (OkHttp on Android, NSURLSession on iOS) */
expect fun platformHttpClient(config: io.ktor.client.HttpClientConfig<*>.() -> Unit): HttpClient

/** Talks to BatteryTime's server on the Mac (App/CompanionServer.swift) */
class CompanionClient(private val http: HttpClient = defaultHttpClient()) {

    /** The Mac said no to the code: it was changed with New Code on the Mac */
    class WrongCodeException : Exception("The Mac didn't accept the pairing code")

    /** The Mac answered with an error (a bad command) */
    class MacErrorException(message: String) : Exception(message)

    /** Checks there's a BatteryTime at [pairing]'s address; the Mac's name, or null when something else answered */
    suspend fun hello(pairing: Pairing): String? {
        val r = http.get("${pairing.baseUrl}/v1/hello")
        if (r.status != HttpStatusCode.OK) return null
        val o = json.decodeFromString<JsonObject>(r.bodyAsText())
        if (o["app"]?.jsonPrimitive?.content != "BatteryTime") return null
        return o["name"]?.jsonPrimitive?.content
    }

    suspend fun state(pairing: Pairing): MacState =
        decode(http.get("${pairing.baseUrl}/v1/state") { auth(pairing) })

    /** Runs [command] on the Mac; the state after it */
    suspend fun send(pairing: Pairing, command: Command): MacState =
        decode(http.post("${pairing.baseUrl}/v1/command") {
            auth(pairing)
            contentType(ContentType.Application.Json)
            setBody(command.toJson().toString())
        })

    /** A running app's icon as PNG bytes, or null */
    suspend fun icon(pairing: Pairing, app: String): ByteArray? {
        val r = http.get("${pairing.baseUrl}/v1/icon?app=${app.encodeURLQueryComponent()}") { auth(pairing) }
        return if (r.status == HttpStatusCode.OK) r.readRawBytes() else null
    }

    fun close() = http.close()

    private fun io.ktor.client.request.HttpRequestBuilder.auth(pairing: Pairing) {
        header("Authorization", "Bearer ${pairing.code}")
    }

    private suspend fun decode(r: HttpResponse): MacState {
        val body = r.bodyAsText()
        return when (r.status) {
            HttpStatusCode.OK -> MacState.json.decodeFromString(MacState.serializer(), body)
            HttpStatusCode.Unauthorized -> throw WrongCodeException()
            else -> throw MacErrorException(
                runCatching { json.decodeFromString<JsonObject>(body)["error"]?.jsonPrimitive?.content }.getOrNull()
                    ?: "The Mac answered ${r.status.value}"
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultHttpClient(): HttpClient = platformHttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 8_000
            }
            expectSuccess = false
        }
    }
}
