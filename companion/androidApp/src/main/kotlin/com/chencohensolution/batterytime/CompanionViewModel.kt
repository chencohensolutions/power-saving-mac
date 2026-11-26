package com.chencohensolution.batterytime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the pair screen shows while it checks a Mac */
sealed interface PairStatus {
    data object Idle : PairStatus
    data class Checking(val pairing: Pairing) : PairStatus
    data class Failed(val message: String) : PairStatus
}

/** Holds the paired Mac and its dashboard controller for the activity */
class CompanionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PairingStore(app)
    val client = CompanionClient()
    val finder = NsdMacFinder(app)

    private val _controller = MutableStateFlow(store.load()?.let(::controllerFor))
    /** The paired Mac's dashboard, or null when no Mac is paired */
    val controller: StateFlow<DashboardController?> = _controller.asStateFlow()

    private val _pairStatus = MutableStateFlow<PairStatus>(PairStatus.Idle)
    val pairStatus: StateFlow<PairStatus> = _pairStatus.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(store.keepScreenOn)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun setKeepScreenOn(on: Boolean) {
        store.keepScreenOn = on
        _keepScreenOn.value = on
    }

    private fun controllerFor(pairing: Pairing) = DashboardController(
        pairing = pairing,
        client = client,
        scope = viewModelScope,
        finder = finder,
        onPairingChanged = store::save,
        now = System::currentTimeMillis,
    )

    /** Checks the Mac answers and accepts the code, then opens its dashboard */
    fun pair(pairing: Pairing) {
        if (_pairStatus.value is PairStatus.Checking) return
        _pairStatus.value = PairStatus.Checking(pairing)
        viewModelScope.launch {
            val result = runCatching {
                val name = client.hello(pairing)
                    ?: return@runCatching "Something else answered at ${pairing.host}:${pairing.port}, not BatteryTime."
                val named = pairing.copy(name = name)
                client.state(named)
                _controller.value?.stop()
                store.save(named)
                _controller.value = controllerFor(named)
                null
            }
            _pairStatus.value = when (val e = result.exceptionOrNull()) {
                null -> result.getOrNull()?.let { PairStatus.Failed(it) } ?: PairStatus.Idle
                is CompanionClient.WrongCodeException ->
                    PairStatus.Failed("The Mac didn't accept code ${pairing.displayCode}. Check the code in Phone Companion on the Mac.")
                else -> PairStatus.Failed(
                    "Couldn't reach ${pairing.host}:${pairing.port}. Check your phone is on the same Wi-Fi as the Mac and Phone Companion is on."
                )
            }
        }
    }

    fun clearPairError() {
        if (_pairStatus.value is PairStatus.Failed) _pairStatus.value = PairStatus.Idle
    }

    /** Forgets the Mac and goes back to the pair screen */
    fun unpair() {
        _controller.value?.stop()
        _controller.value = null
        store.save(null)
    }

    override fun onCleared() {
        client.close()
    }
}
