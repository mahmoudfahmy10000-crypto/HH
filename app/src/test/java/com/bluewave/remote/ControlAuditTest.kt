package com.bluewave.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControlAuditTest {
    private val keys =
        (listOf(
                "POWER",
                "VOLUP",
                "VOLDOWN",
                "CHUP",
                "CHDOWN",
                "MUTE",
                "HOME",
                "RETURN",
                "MENU",
                "SETTINGS",
                "TOOLS",
                "SOURCE",
                "UP",
                "DOWN",
                "LEFT",
                "RIGHT",
                "ENTER",
                "PLAY",
                "PAUSE",
                "FF",
                "REWIND",
                "EXIT",
                "RED",
                "GREEN",
                "YELLOW",
                "BLUE",
                "POWERON",
                "POWEROFF",
                "BACKSPACE",
                "GUIDE",
                "INFO",
            ) + (0..9).map(Int::toString))
            .map { "KEY_$it" }
            .toSet()

    @Test
    fun everyControlSerializesExactlyOnceAndUsesOnlySelectedTransport() {
        assertEquals(keys, ControlCatalog.all.map { it.key }.toSet())
        keys.forEach { key ->
            var network = 0
            var ir = 0
            val result =
                CommandDispatcher.send(
                    key,
                    false,
                    true,
                    true,
                    false,
                    { actual ->
                        network++
                        val j = JSONObject(Protocol.key(actual))
                        assertEquals("ms.remote.control", j.getString("method"))
                        val p = j.getJSONObject("params")
                        assertEquals(key, p.getString("DataOfCmd"))
                        assertEquals("Click", p.getString("Cmd"))
                        assertEquals("SendRemoteKey", p.getString("TypeOfRemote"))
                        true
                    },
                    { ir++ },
                )
            assertTrue(result.sent)
            assertEquals(1, network)
            assertEquals(0, ir)
            network = 0
            val infrared =
                CommandDispatcher.send(
                    key,
                    true,
                    true,
                    true,
                    false,
                    {
                        network++
                        true
                    },
                    { ir++ },
                )
            assertEquals(0, network)
            assertEquals(if (ControlCatalog.find(key)!!.irCode != null) 1 else 0, ir)
            assertEquals(
                if (ir == 1) DeliveryStatus.COMMAND_SENT else DeliveryStatus.UNSUPPORTED,
                infrared.status,
            )
        }
    }

    @Test
    fun rejectedOrUnavailableTransportsNeverSend() {
        var count = 0
        listOf(
                CommandDispatcher.send(
                    "BAD",
                    false,
                    true,
                    true,
                    false,
                    {
                        count++
                        true
                    },
                    { count++ },
                ),
                CommandDispatcher.send(
                    "KEY_HOME",
                    false,
                    true,
                    false,
                    false,
                    {
                        count++
                        true
                    },
                    { count++ },
                ),
                CommandDispatcher.send(
                    "KEY_HOME",
                    true,
                    false,
                    true,
                    false,
                    {
                        count++
                        true
                    },
                    { count++ },
                ),
            )
            .forEach { assertFalse(it.sent) }
        assertEquals(0, count)
        assertEquals(
            DeliveryStatus.FAILED,
            CommandDispatcher.send("KEY_HOME", false, true, true, false, { false }, { fail() })
                .status,
        )
        assertEquals(
            DeliveryStatus.FAILED,
            CommandDispatcher.send(
                    "KEY_HOME",
                    true,
                    true,
                    false,
                    false,
                    {
                        fail()
                        false
                    },
                    { throw IllegalStateException() },
                )
                .status,
        )
    }

    @Test
    fun irRegressionFixturesMatchAllWireBytesIncludingCommunityExitAndColors() {
        val fixture =
            mapOf(
                "POWER" to 0x40,
                "VOLUP" to 0xE0,
                "VOLDOWN" to 0xD0,
                "MUTE" to 0xF0,
                "CHUP" to 0x48,
                "CHDOWN" to 0x08,
                "UP" to 0x06,
                "DOWN" to 0x86,
                "LEFT" to 0xA6,
                "RIGHT" to 0x46,
                "ENTER" to 0x16,
                "EXIT" to 0xB4,
                "RED" to 0x36,
                "GREEN" to 0x28,
                "YELLOW" to 0xA8,
                "BLUE" to 0x68,
                "HOME" to 0x9E,
                "RETURN" to 0x1A,
                "MENU" to 0x58,
                "SETTINGS" to 0x58,
                "TOOLS" to 0xD2,
                "SOURCE" to 0x80,
                "PLAY" to 0xE2,
                "PAUSE" to 0x52,
                "FF" to 0x12,
                "REWIND" to 0xA2,
                "0" to 0x88,
                "1" to 0x20,
                "2" to 0xA0,
                "3" to 0x60,
                "4" to 0x10,
                "5" to 0x90,
                "6" to 0x50,
                "7" to 0x30,
                "8" to 0xB0,
                "9" to 0x70,
            )
        fixture.forEach { (key, expected) ->
            val frame = Protocol.irPattern("KEY_$key")
            val bytes =
                (0..3).map { b ->
                    (0..7).fold(0) { v, i ->
                        (v shl 1) or if (frame[3 + (b * 8 + i) * 2] == 1690) 1 else 0
                    }
                }
            assertEquals(listOf(0xE0, 0xE0, expected, expected xor 255), bytes)
        }
    }

    private fun context(
        ir: Boolean = false,
        hasIr: Boolean = true,
        remote: Boolean = false,
        authorized: Boolean = false,
        mac: String = "10:22:33:44:55:66",
        wake: Boolean = false,
        physical: PhysicalState = PhysicalState.UNKNOWN,
    ) = PowerContext(ir, hasIr, remote, authorized, mac, wake, physical)

    @Test
    fun powerNeverBlindlyTogglesForAnOnOrOffIntent() {
        for (intent in listOf(PowerIntent.ON, PowerIntent.OFF)) {
            assertEquals(Transport.NONE, PowerPlanner.plan(intent, context(ir = true)).transport)
            assertEquals(
                Transport.NONE,
                PowerPlanner.plan(intent, context(remote = true, physical = PhysicalState.ON))
                    .transport,
            )
        }
        assertEquals(
            "KEY_POWEROFF",
            PowerPlanner.plan(PowerIntent.OFF, context(authorized = true)).key,
        )
        assertEquals(
            Transport.NONE,
            PowerPlanner.plan(PowerIntent.ON, context(authorized = true)).transport,
        )
        assertEquals(
            Transport.IR,
            PowerPlanner.plan(PowerIntent.ON, context(ir = true, physical = PhysicalState.STANDBY))
                .transport,
        )
        assertEquals(
            Transport.IR,
            PowerPlanner.plan(PowerIntent.OFF, context(ir = true, physical = PhysicalState.ON))
                .transport,
        )
        assertEquals(
            Transport.NONE,
            PowerPlanner.plan(PowerIntent.ON, context(wake = true, mac = "00:00:00:00:00:00"))
                .transport,
        )
        assertEquals(
            Transport.WAKE,
            PowerPlanner.plan(PowerIntent.ON, context(wake = true)).transport,
        )
        assertEquals(
            Transport.WAKE,
            PowerPlanner.plan(PowerIntent.ON, context(wake = true, remote = true)).transport,
        )
        assertEquals(Transport.NONE, PowerPlanner.plan(PowerIntent.TOGGLE, context()).transport)
        assertEquals("KEY_POWER", PowerPlanner.plan(PowerIntent.TOGGLE, context(ir = true)).key)
    }

    @Test
    fun powerGateBlocksDoubleTapsAndReverseIntentForFiveSeconds() {
        val gate = PowerGate()
        assertTrue(gate.acquire(100))
        assertFalse(gate.acquire(101))
        assertFalse(gate.acquire(5099))
        assertTrue(gate.acquire(5100))
    }

    @Test
    fun disconnectIsNeverProofOfPowerOff() {
        val p = PowerObservation().authorized()
        assertEquals(PowerState.CONNECTED, p.state)
        assertEquals(PowerState.UNKNOWN, p.disconnected(100).state)
        assertEquals(PowerState.STANDBY_SUSPECTED, p.offRequested(100).disconnected(200).state)
        assertEquals(PowerState.UNKNOWN, p.offRequested(100).disconnected(30101).state)
        assertEquals(PowerState.OFFLINE, p.exhausted(500).state)
    }

    @Test
    fun wakeRejectsMulticastZeroAndBroadcastMacs() {
        listOf("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01", "bad").forEach {
            assertFalse(Protocol.validWakeMac(it))
        }
        assertTrue(Protocol.validWakeMac("10:22:33:44:55:66"))
    }
}
