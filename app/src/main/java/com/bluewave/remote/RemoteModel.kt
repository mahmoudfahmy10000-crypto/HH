package com.bluewave.remote

import android.app.Application
import android.hardware.ConsumerIrManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteState(
    val ir: Boolean = false,
    val hasIr: Boolean = false,
    val nec: Boolean = false,
    val connected: Boolean = false,
    val status: String = "Select a TV to connect",
    val feedback: String = "",
    val saved: List<Tv> = emptyList(),
    val discovered: List<Tv> = emptyList(),
    val scanning: Boolean = false,
    val selected: Tv? = null,
    val apps: List<TvApp> = emptyList(),
    val shortcuts: List<TvApp> = emptyList(),
    val trustIp: String? = null,
    val fingerprint: String? = null,
)

class RemoteModel(app: Application) : AndroidViewModel(app), TvConnection.Listener {
    private val store = SecureStore(app)
    private val irManager = app.getSystemService(ConsumerIrManager::class.java)
    private val discovery = Discovery(app)
    private val _state =
        MutableStateFlow(
            RemoteState(
                hasIr = irManager?.hasIrEmitter() == true,
                saved = store.saved(),
                shortcuts = store.shortcuts(),
            )
        )
    val state = _state.asStateFlow()
    private val connection = TvConnection(store, this)
    private var retry: Job? = null
    private var timeout: Job? = null
    private var scanJob: Job? = null
    private var attempts = 0
    private var allowReconnect = true
    private val irMutex = Mutex()

    init {
        store.last()?.let { connect(it) }
    }

    fun mode(ir: Boolean) {
        if (ir) {
            allowReconnect = false
            retry?.cancel()
            timeout?.cancel()
            connection.disconnect()
        }
        _state.update {
            it.copy(ir = ir, connected = if (ir) false else it.connected, feedback = "")
        }
        if (!ir) _state.value.selected?.let { connect(it) }
    }

    fun profile(nec: Boolean) {
        _state.update { it.copy(nec = nec) }
    }

    fun scan() {
        if (_state.value.scanning) return
        scanJob =
            viewModelScope.launch {
                _state.update {
                    it.copy(
                        scanning = true,
                        discovered = emptyList(),
                        feedback = "Searching local network…",
                    )
                }
                try {
                    discovery.scan { tv ->
                        _state.update { s ->
                            s.copy(discovered = (s.discovered + tv).distinctBy { it.ip })
                        }
                    }
                    _state.update {
                        it.copy(
                            feedback =
                                if (it.discovered.isEmpty())
                                    "No Samsung TV found. Turn on the TV, use the same Wi-Fi, or enter its IP."
                                else "Discovery finished"
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    feedback("Discovery failed: ${e.javaClass.simpleName}. Use manual IP.")
                } finally {
                    _state.update { it.copy(scanning = false) }
                }
            }
    }

    fun manual(ip: String, mac: String, secure: Boolean) {
        if (!Protocol.localIp(ip)) {
            feedback("Enter a private LAN IPv4 address (for example 192.168.1.20).")
            return
        }
        if (mac.isNotBlank() && runCatching { Protocol.magicPacket(mac) }.isFailure) {
            feedback("MAC must look like AA:BB:CC:DD:EE:FF")
            return
        }
        viewModelScope.launch {
            val tv = withContext(Dispatchers.IO) { discovery.inspect(ip) }
            connect(
                Tv(ip, tv?.name ?: "Samsung TV ($ip)", mac.ifBlank { tv?.mac.orEmpty() }, secure)
            )
        }
    }

    fun connect(tv: Tv) {
        if (!Protocol.localIp(tv.ip)) {
            feedback("Only private LAN IPv4 TV addresses are supported.")
            return
        }
        retry?.cancel()
        timeout?.cancel()
        attempts = 0
        allowReconnect = true
        _state.update {
            it.copy(
                ir = false,
                selected = tv,
                apps = emptyList(),
                connected = false,
                trustIp = null,
                fingerprint = null,
            )
        }
        startConnect(tv)
    }

    private fun startConnect(tv: Tv) {
        connection.connect(tv)
        timeout?.cancel()
        timeout =
            viewModelScope.launch {
                delay(35000)
                if (!connection.ready) {
                    connection.disconnect()
                    status(
                        "Authorization timed out. Check TV prompt or unsupported model. Reconnect to retry.",
                        false,
                    )
                }
            }
    }

    fun disconnect() {
        allowReconnect = false
        retry?.cancel()
        timeout?.cancel()
        connection.disconnect()
        status("Disconnected", false)
    }

    fun forget(tv: Tv) {
        if (_state.value.selected?.ip == tv.ip) {
            disconnect()
            _state.update { it.copy(selected = null) }
        }
        store.forget(tv.ip)
        _state.update { it.copy(saved = store.saved()) }
    }

    fun trust(accept: Boolean) {
        val s = _state.value
        _state.update { it.copy(trustIp = null, fingerprint = null) }
        if (accept && s.trustIp != null && s.fingerprint != null) {
            store.pin(s.trustIp, s.fingerprint)
            s.selected?.let { connect(it) }
        } else disconnect()
    }

    fun send(key: String) {
        val s = _state.value
        if (s.ir) {
            if (!s.hasIr) {
                feedback("This phone reports no Consumer IR transmitter.")
                return
            }
            viewModelScope.launch(Dispatchers.IO) {
                irMutex.withLock {
                    runCatching {
                            val ranges = irManager.carrierFrequencies
                            check(
                                ranges == null ||
                                    ranges.any { 38000 in it.minFrequency..it.maxFrequency }
                            ) {
                                "38 kHz not supported by this transmitter"
                            }
                            irManager.transmit(38000, Protocol.irPattern(key, s.nec))
                        }
                        .onSuccess {
                            feedback(
                                "IR transmitted • point at TV. TV response cannot be confirmed."
                            )
                        }
                        .onFailure { feedback("IR failed: ${it.message}") }
                }
            }
        } else
            feedback(
                if (connection.send(key)) "Command queued to TV • TV execution is not acknowledged."
                else "Not connected. Connect to a compatible Samsung TV first."
            )
    }

    fun wake() {
        val tv = _state.value.selected
        if (tv == null || tv.mac.isBlank()) {
            feedback("Configure the TV MAC address for Wake-on-LAN. Otherwise use IR power.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Discovery.wake(tv.mac) }
                .onSuccess {
                    feedback(
                        "Wake packet sent. TV must support network standby; power-on is not confirmed."
                    )
                }
                .onFailure { feedback("Wake failed: ${it.message}") }
        }
    }

    fun launch(app: TvApp) {
        if (_state.value.ir) {
            feedback("IR cannot launch apps directly. Use Home and navigation.")
            return
        }
        val installed = _state.value.apps.find { it.id == app.id || it.name.equals(app.name, true) }
        if (installed == null) {
            feedback(
                "${app.name}: no verified installed app ID. Refresh TV apps, add its ID, or use Home and navigation."
            )
            return
        }
        feedback(
            if (connection.launch(installed.id))
                "Launch request sent for ${installed.name}. TV does not confirm execution; use Home if unavailable."
            else "TV disconnected; app was not requested."
        )
    }

    fun launchCustom(app: TvApp) {
        if (_state.value.ir) {
            feedback("Direct app launching is unavailable over IR.")
            return
        }
        feedback(
            if (connection.launch(app.id))
                "Requested ${app.name} using your app ID; launch is unconfirmed."
            else "Not connected"
        )
    }

    fun refreshApps() {
        feedback(
            if (connection.requestApps())
                "Requested app list. Some TVs do not expose it; use Home if no apps appear."
            else "Connect to a TV first."
        )
    }

    fun toggleShortcut(app: TvApp) {
        val list =
            _state.value.shortcuts.let {
                if (it.any { a -> a.id == app.id }) it.filterNot { a -> a.id == app.id }
                else it + app
            }
        store.shortcuts(list)
        _state.update { it.copy(shortcuts = list) }
    }

    fun feedback(text: String) {
        _state.update { it.copy(feedback = text) }
    }

    override fun status(text: String, connected: Boolean) {
        _state.update { it.copy(status = text, connected = connected) }
    }

    override fun authorized(tv: Tv) {
        attempts = 0
        timeout?.cancel()
        _state.update { it.copy(saved = store.saved()) }
    }

    override fun apps(apps: List<TvApp>) {
        _state.update {
            it.copy(
                apps = apps,
                feedback =
                    if (apps.isEmpty())
                        "TV returned no installed apps. Use Home or configure an app ID."
                    else "${apps.size} installed TV apps received",
            )
        }
    }

    override fun trustRequired(ip: String, fingerprint: String) {
        timeout?.cancel()
        _state.update { it.copy(trustIp = ip, fingerprint = fingerprint) }
    }

    override fun disconnected(retry: Boolean) {
        timeout?.cancel()
        if (!retry) {
            connection.disconnect()
            return
        }
        if (!allowReconnect || attempts >= 3 || _state.value.ir) return
        val tv = _state.value.selected ?: return
        attempts++
        this.retry?.cancel()
        this.retry =
            viewModelScope.launch {
                delay(2000L shl (attempts - 1))
                if (allowReconnect) startConnect(tv)
            }
    }

    override fun onCleared() {
        connection.disconnect()
        super.onCleared()
    }
}
