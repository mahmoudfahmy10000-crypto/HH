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
    val remoteMode: Boolean = false,
    val network: NetworkState = NetworkState(),
    val keyboard: KeyboardState = KeyboardState(),
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
                selected = store.last(),
                remoteMode = store.last()?.remote == true,
                keyboard = KeyboardState(automatic = store.automaticKeyboard()),
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

    @Volatile private var foreground = false
    private val monitor =
        NetworkMonitor(app) { network -> viewModelScope.launch { networkChanged(network) } }

    init {
        monitor.start()
    }

    private fun networkError(tv: Tv): String? =
        AccessPolicy.error(tv.remote, tv.secure, monitor.snapshot())

    private fun canCommand(): Boolean {
        if (!foreground) {
            feedback("Open the app before sending remote commands.")
            return false
        }
        val tv = _state.value.selected ?: return false
        val error = networkError(tv)
        if (error != null) {
            suspendConnection(error)
            feedback(error)
            return false
        }
        return true
    }

    private fun suspendConnection(reason: String) {
        retry?.cancel()
        timeout?.cancel()
        connection.disconnect()
        _state.update {
            it.copy(
                connected = false,
                status = reason,
                trustIp = null,
                fingerprint = null,
                keyboard = KeyboardState(automatic = it.keyboard.automatic),
            )
        }
    }

    private fun networkChanged(network: NetworkState) {
        val changed = network != _state.value.network
        _state.update { it.copy(network = network) }
        if (!changed || !foreground) return
        val tv = _state.value.selected
        if (tv != null && !_state.value.ir) {
            suspendConnection(networkError(tv) ?: "Network changed; reconnecting…")
            if (allowReconnect && networkError(tv) == null) {
                attempts = 0
                startConnect(tv)
            }
        } else if (tv == null && !network.vpn && (network.wifi || network.ethernet)) scan()
    }

    fun setForeground(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (!active) {
            scanJob?.cancel()
            suspendConnection("Paused while app is not active")
            return
        }
        _state.update { it.copy(network = monitor.snapshot()) }
        val tv = _state.value.selected
        if (allowReconnect && tv != null && !_state.value.ir) {
            attempts = 0
            startConnect(tv)
        } else if (
            tv == null &&
                !_state.value.network.vpn &&
                (_state.value.network.wifi || _state.value.network.ethernet)
        )
            scan()
    }

    fun remoteMode(enabled: Boolean) {
        _state.update { it.copy(remoteMode = enabled) }
        _state.value.selected?.let {
            connect(it.copy(remote = enabled, secure = if (enabled) true else it.secure))
        }
    }

    fun automaticKeyboard(enabled: Boolean) {
        store.automaticKeyboard(enabled)
        _state.update { it.copy(keyboard = it.keyboard.copy(automatic = enabled)) }
    }

    fun openKeyboard() {
        if (_state.value.ir) {
            feedback("Text entry requires Wi-Fi/VPN WebSocket; IR keyboard is unsupported.")
            return
        }
        if (!canCommand() || !connection.ready) {
            feedback("Connect and authorize the TV before opening the keyboard.")
            return
        }
        _state.update {
            it.copy(
                keyboard =
                    it.keyboard.copy(
                        visible = true,
                        fieldConfirmed = false,
                        message =
                            "Open a TV text/search field, then confirm below. Gboard controls the phone draft only.",
                    )
            )
        }
    }

    fun editDraft(text: String) {
        try {
            ImeProtocol.validate(text)
        } catch (_: Exception) {
            feedback("Text must be valid Unicode and at most 4096 UTF-8 bytes.")
            return
        }
        _state.update {
            it.copy(
                keyboard = it.keyboard.copy(draft = text, dirty = true, resendConfirmed = false)
            )
        }
    }

    fun confirmField(confirmed: Boolean) {
        _state.update { it.copy(keyboard = it.keyboard.copy(fieldConfirmed = confirmed)) }
    }

    fun confirmResend(confirmed: Boolean) {
        _state.update { it.copy(keyboard = it.keyboard.copy(resendConfirmed = confirmed)) }
    }

    fun adoptTvText() {
        _state.update { s ->
            val text = s.keyboard.offeredText ?: return@update s
            s.copy(
                keyboard =
                    s.keyboard.copy(
                        draft = text,
                        dirty = false,
                        offeredText = null,
                        resendConfirmed = false,
                        message =
                            "Adopted TV-reported text locally. Editing does not automatically modify the TV.",
                    )
            )
        }
    }

    private fun keyboardReady(): Boolean {
        if (_state.value.ir || !canCommand() || !connection.ready) {
            feedback("Keyboard needs an authorized network connection.")
            return false
        }
        if (!_state.value.keyboard.fieldConfirmed) {
            feedback("Select a TV text field and confirm it in the keyboard panel first.")
            return false
        }
        return true
    }

    fun sendDraft() {
        if (!keyboardReady()) return
        val k = _state.value.keyboard
        if (k.draft.isEmpty()) {
            feedback("Empty draft is not a TV clear command. Use TV Backspace if supported.")
            return
        }
        if (k.sentDraft != null && !k.resendConfirmed) {
            feedback(
                "Text was already sent in this session. Confirm possible append/replace behavior before sending again."
            )
            return
        }
        val sent = connection.sendText(k.draft)
        _state.update {
            it.copy(
                keyboard =
                    it.keyboard.copy(
                        sentDraft = if (sent) k.draft else k.sentDraft,
                        resendConfirmed = false,
                        message =
                            if (sent)
                                "Text queued, not confirmed by TV. Check the TV field; unsupported apps may ignore it."
                            else "Text was not queued; connection unavailable.",
                    )
            )
        }
    }

    fun keyboardEnter() {
        if (!keyboardReady()) return
        val ended = connection.endText()
        val entered = ended && connection.send("KEY_ENTER")
        _state.update {
            it.copy(
                keyboard =
                    it.keyboard.copy(
                        fieldConfirmed = false,
                        message =
                            if (entered)
                                "SendInputEnd and Enter queued; TV completion is unconfirmed."
                            else "Could not queue the complete Enter request.",
                    )
            )
        }
    }

    fun keyboardBackspace() {
        if (!keyboardReady()) return
        val sent = connection.send("KEY_BACKSPACE")
        _state.update {
            it.copy(
                keyboard =
                    it.keyboard.copy(
                        message =
                            if (sent)
                                "TV Backspace queued. Some apps ignore this key. Edit the phone draft separately with Gboard."
                            else "Backspace was not queued."
                    )
            )
        }
    }

    fun clearDraft() {
        _state.update {
            it.copy(
                keyboard =
                    it.keyboard.copy(
                        draft = "",
                        dirty = true,
                        resendConfirmed = false,
                        message = "Phone draft cleared only. TV field was not cleared.",
                    )
            )
        }
    }

    fun closeKeyboard() {
        val k = _state.value.keyboard
        if (foreground && !_state.value.ir && (k.active || k.fieldConfirmed) && canCommand())
            connection.endText()
        _state.update { it.copy(keyboard = KeyboardState(automatic = it.keyboard.automatic)) }
    }

    override fun ime(event: TvImeEvent) {
        viewModelScope.launch {
            if (!connection.ready || _state.value.ir) return@launch
            _state.update { it.copy(keyboard = it.keyboard.event(event, foreground)) }
            if (event.sensitive) feedback("Password IME is unsupported. Use the TV's own controls.")
        }
    }

    fun mode(ir: Boolean) {
        if (ir) {
            allowReconnect = false
            retry?.cancel()
            timeout?.cancel()
            connection.disconnect()
        }
        _state.update {
            it.copy(
                ir = ir,
                connected = if (ir) false else it.connected,
                feedback = "",
                keyboard = KeyboardState(automatic = it.keyboard.automatic),
            )
        }
        if (!ir) _state.value.selected?.let { connect(it) }
    }

    fun profile(nec: Boolean) {
        _state.update { it.copy(nec = nec) }
    }

    fun scan() {
        if (_state.value.scanning || !foreground) return
        val error = AccessPolicy.error(false, true, monitor.snapshot())
        if (error != null) {
            feedback(
                "Discovery needs local Wi-Fi without VPN. Over VPN, use the saved TV private IP. $error"
            )
            return
        }
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
            val remote = _state.value.remoteMode
            val tv = if (remote) null else withContext(Dispatchers.IO) { discovery.inspect(ip) }
            connect(
                Tv(
                    ip,
                    tv?.name ?: "Samsung TV ($ip)",
                    mac.ifBlank { tv?.mac.orEmpty() },
                    if (remote) true else secure,
                    remote,
                )
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
                remoteMode = tv.remote,
                keyboard = KeyboardState(automatic = it.keyboard.automatic),
                apps = emptyList(),
                connected = false,
                trustIp = null,
                fingerprint = null,
            )
        }
        startConnect(tv)
    }

    private fun startConnect(tv: Tv) {
        if (!foreground) return
        val error = networkError(tv)
        if (error != null) {
            suspendConnection(error)
            return
        }
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
        _state.update { it.copy(keyboard = KeyboardState(automatic = it.keyboard.automatic)) }
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
        } else if (canCommand())
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
        if (!canCommand()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Discovery.wake(tv.mac, if (tv.remote) tv.ip else null) }
                .onSuccess {
                    feedback(
                        "Wake packet sent ${if(tv.remote) "to TV IP through VPN" else "on LAN"}. Standby/ARP support is required; power-on is unconfirmed."
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
        if (!canCommand()) return
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
        if (!canCommand()) return
        feedback(
            if (connection.launch(app.id))
                "Requested ${app.name} using your app ID; launch is unconfirmed."
            else "Not connected"
        )
    }

    fun refreshApps() {
        if (!canCommand()) return
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
        _state.update {
            it.copy(
                feedback = text,
                keyboard =
                    if (it.keyboard.visible) it.keyboard.copy(message = text) else it.keyboard,
            )
        }
    }

    override fun status(text: String, connected: Boolean) {
        _state.update {
            it.copy(
                status = text,
                connected = connected,
                keyboard =
                    if (connected) {
                        if (text.startsWith("TV reported ms.error"))
                            it.keyboard.copy(message = text)
                        else it.keyboard
                    } else KeyboardState(automatic = it.keyboard.automatic),
            )
        }
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
        if (
            !allowReconnect ||
                !foreground ||
                attempts >= ReconnectPolicy.MAX_ATTEMPTS ||
                _state.value.ir
        )
            return
        val tv = _state.value.selected ?: return
        attempts++
        val retryDelay = ReconnectPolicy.delayMillis(attempts)
        this.retry?.cancel()
        this.retry =
            viewModelScope.launch {
                delay(retryDelay)
                if (allowReconnect) startConnect(tv)
            }
    }

    override fun onCleared() {
        monitor.stop()
        connection.disconnect()
        super.onCleared()
    }
}
