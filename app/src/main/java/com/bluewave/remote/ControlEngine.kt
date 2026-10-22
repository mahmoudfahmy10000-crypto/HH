package com.bluewave.remote

/** One registry for UI validation, serialization, transport dispatch and diagnostic inventory. */
data class ControlSpec(
    val key: String,
    val label: String,
    val irCode: Int?,
    val power: Boolean = false,
)

object ControlCatalog {
    val all: List<ControlSpec> by lazy {
        val labels =
            mapOf(
                "KEY_POWER" to "Power Toggle",
                "KEY_POWERON" to "Power ON",
                "KEY_POWEROFF" to "Power OFF",
                "KEY_RETURN" to "Back",
                "KEY_ENTER" to "OK / Enter",
                "KEY_VOLUP" to "Volume Up",
                "KEY_VOLDOWN" to "Volume Down",
                "KEY_CHUP" to "Channel Up",
                "KEY_CHDOWN" to "Channel Down",
                "KEY_FF" to "Fast Forward",
                "KEY_REWIND" to "Rewind",
                "KEY_SOURCE" to "Source / Input",
                "KEY_BACKSPACE" to "TV Backspace",
            )
        val keys =
            Protocol.irCodes.keys +
                setOf("KEY_POWERON", "KEY_POWEROFF", "KEY_BACKSPACE", "KEY_GUIDE", "KEY_INFO")
        keys.map {
            ControlSpec(
                it,
                labels[it] ?: it.removePrefix("KEY_").lowercase().replaceFirstChar(Char::uppercase),
                Protocol.irCodes[it],
                it in setOf("KEY_POWER", "KEY_POWERON", "KEY_POWEROFF"),
            )
        }
    }

    fun find(key: String) = all.find { it.key == key }
}

enum class DeliveryStatus(val title: String) {
    COMMAND_SENT("Command Sent"),
    CONFIRMED_RESPONSE("Confirmed Response"),
    REQUIRES_MANUAL_VERIFICATION("Requires Manual Verification"),
    FAILED("Failed"),
    UNSUPPORTED("Unsupported"),
}

enum class Transport {
    WIFI,
    IR,
    WAKE,
    NONE,
}

data class DispatchResult(
    val status: DeliveryStatus,
    val transport: Transport,
    val detail: String,
) {
    val sent
        get() = status == DeliveryStatus.COMMAND_SENT
}

object CommandDispatcher {
    fun send(
        key: String,
        ir: Boolean,
        hasIr: Boolean,
        networkAllowed: Boolean,
        nec: Boolean,
        network: (String) -> Boolean,
        emitter: (IntArray) -> Unit,
    ): DispatchResult {
        val spec =
            ControlCatalog.find(key)
                ?: return DispatchResult(
                    DeliveryStatus.UNSUPPORTED,
                    Transport.NONE,
                    "Unknown command",
                )
        if (ir && (!hasIr || spec.irCode == null))
            return DispatchResult(
                DeliveryStatus.UNSUPPORTED,
                Transport.IR,
                if (!hasIr) "No Consumer IR transmitter"
                else "No verified IR mapping for ${spec.label}",
            )
        if (!ir && !networkAllowed)
            return DispatchResult(
                DeliveryStatus.FAILED,
                Transport.WIFI,
                "No authorized network connection",
            )
        return try {
            if (ir) {
                emitter(Protocol.irPattern(key, nec))
                DispatchResult(
                    DeliveryStatus.COMMAND_SENT,
                    Transport.IR,
                    "Emitter accepted one IR frame; TV response is unconfirmed",
                )
            } else if (network(key))
                DispatchResult(
                    DeliveryStatus.COMMAND_SENT,
                    Transport.WIFI,
                    "WebSocket queued one key; TV execution is unconfirmed",
                )
            else
                DispatchResult(
                    DeliveryStatus.FAILED,
                    Transport.WIFI,
                    "WebSocket did not queue the key",
                )
        } catch (_: Exception) {
            DispatchResult(
                DeliveryStatus.FAILED,
                if (ir) Transport.IR else Transport.WIFI,
                "Transport rejected the command; nothing is confirmed",
            )
        }
    }
}

enum class PowerIntent {
    ON,
    OFF,
    TOGGLE,
}

enum class PhysicalState {
    UNKNOWN,
    ON,
    STANDBY,
}

enum class PowerState(val title: String) {
    CONNECTED("Connected"),
    STANDBY_SUSPECTED("Standby Suspected"),
    OFFLINE("Offline"),
    UNKNOWN("Unknown"),
}

data class PowerObservation(
    val state: PowerState = PowerState.UNKNOWN,
    val reason: String = "No independent power observation",
    val offRequestedAt: Long? = null,
) {
    fun authorized() =
        PowerObservation(
            PowerState.CONNECTED,
            "Samsung authorized channel responded; display/panel state is not measured",
        )

    fun offRequested(now: Long) =
        PowerObservation(
            PowerState.STANDBY_SUSPECTED,
            "OFF request sent, not confirmed by the TV",
            now,
        )

    fun disconnected(now: Long): PowerObservation =
        if (offRequestedAt != null && now - offRequestedAt in 0..30000)
            copy(
                state = PowerState.STANDBY_SUSPECTED,
                reason = "Disconnected after recent OFF request; standby is only suspected",
            )
        else
            PowerObservation(
                PowerState.UNKNOWN,
                "WebSocket disconnected; TV power state is unknown",
            )

    fun exhausted(now: Long): PowerObservation =
        if (offRequestedAt != null && now - offRequestedAt in 0..30000) disconnected(now)
        else
            PowerObservation(
                PowerState.OFFLINE,
                "Control endpoint did not respond after bounded attempts; this does not prove TV OFF",
            )
}

data class PowerContext(
    val irMode: Boolean,
    val hasIr: Boolean,
    val remote: Boolean,
    val authorized: Boolean,
    val mac: String,
    val wakeEnabled: Boolean,
    val physical: PhysicalState = PhysicalState.UNKNOWN,
)

data class PowerPlan(val transport: Transport, val key: String? = null, val reason: String)

object PowerPlanner {
    fun plan(intent: PowerIntent, c: PowerContext): PowerPlan =
        when (intent) {
            PowerIntent.ON ->
                when {
                    !c.remote && c.hasIr && c.physical == PhysicalState.STANDBY ->
                        PowerPlan(
                            Transport.IR,
                            "KEY_POWER",
                            "One IR toggle, only because you explicitly confirmed the TV is in standby. Point the emitter at TV.",
                        )
                    c.authorized && !c.irMode ->
                        PowerPlan(
                            Transport.NONE,
                            reason =
                                "Authorized TV endpoint is already reachable; no power packet is needed. Panel state is unmeasured.",
                        )
                    c.irMode ->
                        PowerPlan(
                            Transport.NONE,
                            reason =
                                "IR has only a verified toggle code. Confirm standby first; no discrete ON code is assumed.",
                        )
                    !c.wakeEnabled ->
                        PowerPlan(
                            Transport.NONE,
                            reason =
                                "Enable TV network standby / Power On with Mobile and explicitly configure network wake first.",
                        )
                    !Protocol.validWakeMac(c.mac) ->
                        PowerPlan(
                            Transport.NONE,
                            reason =
                                "Configure the TV's active-interface unicast MAC; missing/zero/broadcast MAC cannot wake it.",
                        )
                    else ->
                        PowerPlan(
                            Transport.WAKE,
                            reason =
                                if (c.remote)
                                    "Best-effort unicast wake through VPN; home gateway/ARP/standby support is required."
                                else
                                    "Best-effort LAN wake; TV network standby and network delivery are required.",
                        )
                }
            PowerIntent.OFF ->
                when {
                    !c.irMode && c.authorized ->
                        PowerPlan(
                            Transport.WIFI,
                            "KEY_POWEROFF",
                            "One explicit OFF key; unsupported models do not get an automatic toggle fallback.",
                        )
                    !c.remote && c.hasIr && c.physical == PhysicalState.ON ->
                        PowerPlan(
                            Transport.IR,
                            "KEY_POWER",
                            "One IR toggle, only because you explicitly confirmed TV ON.",
                        )
                    else ->
                        PowerPlan(
                            Transport.NONE,
                            reason =
                                "OFF needs an authorized WebSocket or confirmed-on TV with a local IR emitter. No blind toggle is sent.",
                        )
                }
            PowerIntent.TOGGLE ->
                when {
                    c.irMode && c.hasIr && !c.remote ->
                        PowerPlan(
                            Transport.IR,
                            "KEY_POWER",
                            "Explicit single toggle; observe TV state yourself.",
                        )
                    !c.irMode && c.authorized ->
                        PowerPlan(
                            Transport.WIFI,
                            "KEY_POWER",
                            "Explicit single network toggle; never retried automatically.",
                        )
                    else ->
                        PowerPlan(
                            Transport.NONE,
                            reason = "Toggle requires local IR mode or an authorized WebSocket.",
                        )
                }
        }
}

class PowerGate(private val intervalMillis: Long = 5000) {
    private var last: Long? = null

    @Synchronized
    fun acquire(now: Long): Boolean {
        val previous = last
        if (previous != null && now - previous < intervalMillis) return false
        last = now
        return true
    }
}

data class DiagnosticResult(
    val sequence: Long,
    val label: String,
    val delivery: DeliveryStatus,
    val verification: DeliveryStatus,
    val transport: Transport,
    val detail: String,
    val manual: Boolean? = null,
)
