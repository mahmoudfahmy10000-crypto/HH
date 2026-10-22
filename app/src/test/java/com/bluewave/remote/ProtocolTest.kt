package com.bluewave.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun samsungPowerHasCorrectWireBits() {
        val pattern = Protocol.irPattern("KEY_POWER")
        assertEquals(67, pattern.size)
        assertArrayEquals(intArrayOf(4500, 4500), pattern.take(2).toIntArray())
        val decoded =
            (0..3).map { b ->
                (0..7).fold(0) { v, i ->
                    (v shl 1) or if (pattern[3 + (b * 8 + i) * 2] == 1690) 1 else 0
                }
            }
        assertEquals(listOf(0xE0, 0xE0, 0x40, 0xBF), decoded)
        assertEquals(560, pattern.last())
    }

    @Test
    fun allIrCommandsHaveSafeDurationsAndComplement() {
        Protocol.irCodes.keys.forEach { key ->
            val p = Protocol.irPattern(key)
            assertTrue(p.all { it > 0 })
            assertTrue(p.sum() < 200000)
            val c = (16..31).map { if (p[3 + it * 2] == 1690) 1 else 0 }
            for (i in 0..7) assertNotEquals(c[i], c[i + 8])
        }
        assertEquals(9000, Protocol.irPattern("KEY_POWER", true)[0])
    }

    @Test
    fun websocketCommandsUseSamsungProtocol() {
        val j = JSONObject(Protocol.key("KEY_VOLUP"))
        assertEquals("ms.remote.control", j.getString("method"))
        assertEquals("KEY_VOLUP", j.getJSONObject("params").getString("DataOfCmd"))
        assertEquals("Click", j.getJSONObject("params").getString("Cmd"))
        val app = JSONObject(Protocol.launch("123\"test"))
        assertEquals(
            "123\"test",
            app.getJSONObject("params").getJSONObject("data").getString("appId"),
        )
        assertEquals(
            "ed.installedApp.get",
            JSONObject(Protocol.appList()).getJSONObject("params").getString("event"),
        )
    }

    @Test
    fun rejectsInvalidHosts() {
        listOf(
                "localhost",
                "127.0.0.1",
                "192.168.1.999",
                "1.2.3",
                "1.2.3.4/evil",
                "224.0.0.1",
                "0.0.0.0",
            )
            .forEach { assertFalse(it, Protocol.validIp(it)) }
        assertTrue(Protocol.validIp("192.168.1.20"))
    }

    @Test
    fun wakePacketContainsSixteenMacCopies() {
        val p = Protocol.magicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xFF.toByte() })
        repeat(16) {
            assertArrayEquals(
                byteArrayOf(
                    0xAA.toByte(),
                    0xBB.toByte(),
                    0xCC.toByte(),
                    0xDD.toByte(),
                    0xEE.toByte(),
                    0xFF.toByte(),
                ),
                p.copyOfRange(6 + it * 6, 12 + it * 6),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownKey() {
        Protocol.key("INJECT")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidMac() {
        Protocol.magicPacket("invalid")
    }
}
