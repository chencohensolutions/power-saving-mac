package com.chencohensolution.batterytime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardControllerTest {
    private val pairing = Pairing("192.168.1.20", 47070, "ABCD2345", "MacBook Pro")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** A mock Mac on the test's clock, so runCurrent() runs its answers */
    private fun TestScope.mac(handler: MockRequestHandler) = MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler(handler)
    })

    private fun TestScope.controller(engine: MockEngine, finder: MacFinder? = null, saved: MutableList<Pairing> = mutableListOf()) =
        DashboardController(pairing, CompanionClient(HttpClient(engine) { expectSuccess = false }), this, finder,
            onPairingChanged = { saved += it }, now = { testScheduler.currentTime })

    @Test
    fun pollsAndSendsTheCode() = runTest {
        val auth = mutableListOf<String?>()
        val engine = mac { req ->
            auth += req.headers[HttpHeaders.Authorization]
            respond("""{"stayAwake":true,"battery":{"percent":80}}""", HttpStatusCode.OK, json)
        }
        val c = controller(engine)
        c.poll()
        assertEquals(Connection.Connected, c.state.value.connection)
        assertEquals(80, c.state.value.mac?.battery?.percent)
        assertEquals(listOf<String?>("Bearer ABCD2345"), auth)
    }

    @Test
    fun aTapShowsAtOnceAndAPollDoesntUndoIt() = runTest {
        val answer = CompletableDeferred<Unit>()
        var body = ""
        val engine = mac { req ->
            if (req.url.encodedPath == "/v1/command") {
                body = req.body.toByteArray().decodeToString()
                answer.await()
                respond("""{"stayAwake":false}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"stayAwake":true}""", HttpStatusCode.OK, json)   // the Mac hasn't caught up yet
            }
        }
        val c = controller(engine)
        c.poll()
        assertTrue(c.state.value.mac!!.stayAwake)

        c.send(Command.SetStayAwake(false))
        assertFalse(c.state.value.mac!!.stayAwake)
        runCurrent()
        assertTrue("setStayAwake" in c.state.value.pending)
        c.poll()
        assertFalse(c.state.value.mac!!.stayAwake, "a poll during the command keeps the tap")

        answer.complete(Unit)
        runCurrent()
        assertFalse(c.state.value.mac!!.stayAwake)
        assertTrue(c.state.value.pending.isEmpty())
        assertEquals("""{"type":"setStayAwake","on":false}""", body)
    }

    @Test
    fun aFailedTapIsUndone() = runTest {
        val engine = mac { req ->
            if (req.url.encodedPath == "/v1/command") throw IOException("unreachable")
            respond("""{"stayAwake":true}""", HttpStatusCode.OK, json)
        }
        val c = controller(engine)
        c.poll()
        c.send(Command.SetStayAwake(false))
        runCurrent()
        assertTrue(c.state.value.mac!!.stayAwake)
        assertTrue(c.state.value.error!!.startsWith("Couldn't reach MacBook Pro"))
    }

    @Test
    fun aNewCodeOnTheMacAsksToPairAgain() = runTest {
        val c = controller(mac { respond("""{"error":"Wrong pairing code"}""", HttpStatusCode.Unauthorized, json) })
        c.poll()
        assertEquals(Connection.WrongCode, c.state.value.connection)
    }

    @Test
    fun findsTheMacAgainWhenItsAddressChanges() = runTest {
        val engine = mac { req ->
            if (req.url.host == "192.168.1.20") throw IOException("no route")
            respond("""{"battery":{"percent":50}}""", HttpStatusCode.OK, json)
        }
        val saved = mutableListOf<Pairing>()
        val c = controller(engine, finder = { name -> if (name == "MacBook Pro") "192.168.1.31" else null }, saved = saved)
        repeat(3) { c.poll() }
        assertIs<Connection.Offline>(c.state.value.connection)
        assertEquals("192.168.1.31", c.state.value.pairing.host)
        assertEquals(listOf(pairing.copy(host = "192.168.1.31")), saved)
        c.poll()
        assertEquals(Connection.Connected, c.state.value.connection)
        assertEquals(50, c.state.value.mac?.battery?.percent)
    }
}
