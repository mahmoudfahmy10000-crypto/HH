package com.bluewave.remote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class Discovery(context: Context) {
    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val http =
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    suspend fun scan(found: (Tv) -> Unit) =
        withContext(Dispatchers.IO) {
            val lock =
                wifi.createMulticastLock("samsung-discovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 500
                    val query =
                        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
                            .toByteArray()
                    repeat(2) {
                        socket.send(
                            DatagramPacket(
                                query,
                                query.size,
                                InetAddress.getByName("239.255.255.250"),
                                1900,
                            )
                        )
                    }
                    val seen = mutableSetOf<String>()
                    val candidates = mutableListOf<String>()
                    val end = System.currentTimeMillis() + 4500
                    while (System.currentTimeMillis() < end && isActive) {
                        val p = DatagramPacket(ByteArray(8192), 8192)
                        try {
                            socket.receive(p)
                            val ip = p.address.hostAddress ?: continue
                            if (Protocol.localIp(ip) && seen.add(ip)) candidates.add(ip)
                        } catch (_: SocketTimeoutException) {}
                    }
                    candidates.take(64).map { ip -> async { inspect(ip)?.let(found) } }.awaitAll()
                }
            } finally {
                lock.release()
            }
        }

    fun inspect(ip: String): Tv? =
        runCatching {
                require(Protocol.localIp(ip))
                http
                    .newCall(Request.Builder().url("http://$ip:8001/api/v2/").build())
                    .execute()
                    .use { res ->
                        check(res.isSuccessful)
                        val body = requireNotNull(res.body)
                        require(body.contentLength() <= 262144) { "TV metadata too large" }
                        val source = body.source()
                        require(!source.request(262145)) { "TV metadata too large" }
                        val root = JSONObject(source.readUtf8())
                        val device = root.getJSONObject("device")
                        check(
                            device.toString().contains("samsung", true) ||
                                root.optString("type").contains("Samsung", true)
                        )
                        Tv(ip, device.optString("name", "Samsung TV"), device.optString("wifiMac"))
                    }
            }
            .getOrNull()

    companion object {
        fun wake(mac: String) {
            val data = Protocol.magicPacket(mac)
            DatagramSocket().use { s ->
                s.broadcast = true
                repeat(3) {
                    s.send(
                        DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), 9)
                    )
                }
            }
        }
    }
}
