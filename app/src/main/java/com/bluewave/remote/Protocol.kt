package com.bluewave.remote

import org.json.JSONObject

object Protocol {
    val irCodes =
        mapOf(
            "KEY_POWER" to 0x40,
            "KEY_VOLUP" to 0xE0,
            "KEY_VOLDOWN" to 0xD0,
            "KEY_CHUP" to 0x48,
            "KEY_CHDOWN" to 0x08,
            "KEY_MUTE" to 0xF0,
            "KEY_HOME" to 0x9E,
            "KEY_RETURN" to 0x1A,
            "KEY_MENU" to 0x58,
            "KEY_SETTINGS" to 0x58,
            "KEY_TOOLS" to 0xD2,
            "KEY_SOURCE" to 0x80,
            "KEY_UP" to 0x06,
            "KEY_DOWN" to 0x86,
            "KEY_LEFT" to 0xA6,
            "KEY_RIGHT" to 0x46,
            "KEY_ENTER" to 0x16,
            "KEY_PLAY" to 0xE2,
            "KEY_PAUSE" to 0x52,
            "KEY_FF" to 0x12,
            "KEY_REWIND" to 0xA2,
            "KEY_0" to 0x88,
            "KEY_1" to 0x20,
            "KEY_2" to 0xA0,
            "KEY_3" to 0x60,
            "KEY_4" to 0x10,
            "KEY_5" to 0x90,
            "KEY_6" to 0x50,
            "KEY_7" to 0x30,
            "KEY_8" to 0xB0,
            "KEY_9" to 0x70,
        )

    // Samsung32 wire order: E0 E0 command inverse, MSB first. NEC uses a longer header.
    fun irPattern(key: String, necHeader: Boolean = false): IntArray {
        val code = requireNotNull(irCodes[key]) { "No verified IR code for $key" }
        val bytes = intArrayOf(0xE0, 0xE0, code, code xor 0xFF)
        val out = mutableListOf(if (necHeader) 9000 else 4500, 4500)
        bytes.forEach { b ->
            for (bit in 7 downTo 0) {
                out.add(560)
                out.add(if ((b shr bit) and 1 == 1) 1690 else 560)
            }
        }
        out.add(560)
        return out.toIntArray()
    }

    fun key(key: String): String {
        require(
            key in irCodes ||
                key == "KEY_POWERON" ||
                key == "KEY_POWEROFF" ||
                key == "KEY_BACKSPACE"
        )
        return JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params",
                JSONObject()
                    .put("Cmd", "Click")
                    .put("DataOfCmd", key)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey"),
            )
            .toString()
    }

    fun launch(id: String) =
        JSONObject()
            .put("method", "ms.channel.emit")
            .put(
                "params",
                JSONObject()
                    .put("event", "ed.apps.launch")
                    .put("to", "host")
                    .put(
                        "data",
                        JSONObject()
                            .put("appId", id)
                            .put("action_type", "DEEP_LINK")
                            .put("metaTag", ""),
                    ),
            )
            .toString()

    fun appList() =
        JSONObject()
            .put("method", "ms.channel.emit")
            .put("params", JSONObject().put("event", "ed.installedApp.get").put("to", "host"))
            .toString()

    fun validIp(ip: String): Boolean =
        ip.split('.').let { p ->
            p.size == 4 &&
                p.all {
                    it.isNotEmpty() &&
                        it.length <= 3 &&
                        it.all { c -> c in '0'..'9' } &&
                        (it.length == 1 || it[0] != '0') &&
                        (it.toIntOrNull() ?: -1) in 0..255
                } &&
                p[0].toInt() in 1..223 &&
                p[0].toInt() != 127
        }

    // Restrict real discovery/manual/saved-device flows to local IPv4 destinations.
    // Static Android cleartext domain rules cannot describe dynamically discovered LAN IPs.
    fun localIp(ip: String): Boolean {
        if (!validIp(ip)) return false
        val p = ip.split('.').map(String::toInt)
        return p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254)
    }

    fun magicPacket(mac: String): ByteArray {
        require(mac.matches(Regex("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")))
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }
        return ByteArray(102) { i -> if (i < 6) 0xFF.toByte() else bytes[(i - 6) % 6] }
    }
}
