package com.chencohensolution.batterytime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Finds the Mac on the network by its Bonjour name, when its address has changed. Per platform (Android: NSD). */
fun interface MacFinder {
    /** The Mac's current address, or null when it isn't found in a few seconds */
    suspend fun find(name: String): String?
}

/** What the dashboard screen shows */
data class DashboardState(
    val pairing: Pairing,
    /** The last state from the Mac (with taps not answered yet applied), or null before the first answer */
    val mac: MacState? = null,
    val connection: Connection = Connection.Connecting,
    /** Commands sent and not answered yet, by type: their controls show progress */
    val pending: Set<String> = emptySet(),
    /** The last command that failed, to show once */
    val error: String? = null,
)

sealed interface Connection {
    data object Connecting : Connection
    data object Connected : Connection
    /** Not reachable since [sinceMs]: the Mac is asleep, off, or on another network */
    data class Offline(val sinceMs: Long, val reason: String) : Connection
    /** The Mac changed its code: pair again */
    data object WrongCode : Connection
}

/**
 * The dashboard's controller: polls the Mac every [pollMs] while [start]ed, sends commands, and finds the Mac again
 * with [finder] when it stops answering at its address. The UI renders [state] and calls the methods; [onPairingChanged]
 * is told when the Mac's address changed, to save it.
 */
class DashboardController(
    pairing: Pairing,
    private val client: CompanionClient,
    private val scope: CoroutineScope,
    private val finder: MacFinder? = null,
    private val onPairingChanged: (Pairing) -> Unit = {},
    private val now: () -> Long,
    private val pollMs: Long = 2_000,
) {
    private val _state = MutableStateFlow(DashboardState(pairing))
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var poller: Job? = null
    private var failures = 0

    /** Starts polling: call when the screen is visible */
    fun start() {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                poll()
                if (_state.value.connection == Connection.WrongCode) break
                // Slower while the Mac doesn't answer, so a sleeping Mac doesn't cost the phone battery
                delay(if (failures == 0) pollMs else (pollMs * (1 + failures)).coerceAtMost(15_000))
            }
        }
    }

    /** Stops polling: call when the screen is hidden */
    fun stop() {
        poller?.cancel()
        poller = null
    }

    /** Polls once now (pull to refresh, or Retry) */
    fun refreshNow() {
        scope.launch {
            send(Command.Refresh)
        }
    }

    suspend fun poll() {
        val pairing = _state.value.pairing
        try {
            val mac = client.state(pairing)
            failures = 0
            _state.update { s -> s.copy(mac = mac.withPending(s.pending), connection = Connection.Connected) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CompanionClient.WrongCodeException) {
            _state.update { it.copy(connection = Connection.WrongCode) }
        } catch (e: Exception) {
            failures++
            val since = (_state.value.connection as? Connection.Offline)?.sinceMs ?: now()
            _state.update { it.copy(connection = Connection.Offline(since, e.message ?: "Not reachable")) }
            if (failures % 3 == 0) rediscover()
        }
    }

    private val sent = mutableMapOf<String, Command>()

    /** Sends [command]; the screen shows its result right away and the Mac's answer when it comes */
    fun send(command: Command) {
        val previous = _state.value.mac
        if (command != Command.Refresh) {
            sent[command.key] = command
            _state.update { s ->
                s.copy(mac = s.mac?.applying(command, now()), pending = s.pending + command.key, error = null)
            }
        }
        scope.launch {
            try {
                val mac = client.send(_state.value.pairing, command)
                sent.remove(command.key)
                failures = 0
                _state.update { s ->
                    val pending = s.pending - command.key
                    s.copy(mac = mac.withPending(pending), pending = pending, connection = Connection.Connected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompanionClient.WrongCodeException) {
                sent.remove(command.key)
                _state.update { it.copy(connection = Connection.WrongCode, pending = it.pending - command.key) }
            } catch (e: Exception) {
                sent.remove(command.key)
                _state.update { s ->
                    s.copy(
                        mac = if (command == Command.Refresh) s.mac else previous?.withPending(s.pending - command.key),
                        pending = s.pending - command.key,
                        error = if (command == Command.Refresh) null else "Couldn't reach ${s.pairing.name}: ${e.message ?: "try again"}",
                    )
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** A poll that left before a command's answer mustn't undo the tap on screen */
    private fun MacState.withPending(pending: Set<String>): MacState =
        pending.mapNotNull { sent[it] }.fold(this) { s, c -> s.applying(c, now()) }

    private suspend fun rediscover() {
        val finder = finder ?: return
        val pairing = _state.value.pairing
        val host = finder.find(pairing.name) ?: return
        if (host == pairing.host) return
        val moved = pairing.copy(host = host)
        _state.update { it.copy(pairing = moved) }
        onPairingChanged(moved)
    }

    /** Commands of the same kind replace each other (two taps on the same switch) */
    private val Command.key: String get() = if (this is Command.SetAppRule) "$type:$app" else type
}
