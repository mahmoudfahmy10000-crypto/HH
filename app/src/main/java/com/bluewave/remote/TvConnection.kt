package com.bluewave.remote

import java.net.Proxy
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import okhttp3.*
import org.json.JSONObject

class TvConnection(private val store: PairingStore, private val listener: Listener) {
    interface Listener {
        fun status(text: String, connected: Boolean)

        fun authorized(tv: Tv)

        fun apps(apps: List<TvApp>)

        fun trustRequired(ip: String, fingerprint: String)

        fun disconnected(retry: Boolean)
    }

    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    @Volatile private var generation = 0
    @Volatile
    var ready = false
        private set

    fun disconnect() {
        generation++
        ready = false
        socket?.cancel()
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
    }

    fun connect(tv: Tv) {
        disconnect()
        val current = generation
        listener.status("Connecting to ${tv.ip}…", false)
        val builder =
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(7, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
        if (tv.secure) {
            val trust =
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>,
                        authType: String,
                    ) {
                        throw java.security.cert.CertificateException("Not a client")
                    }

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>,
                        authType: String,
                    ) {
                        if (chain.isEmpty())
                            throw java.security.cert.CertificateException("Missing TV certificate")
                        val fp =
                            MessageDigest.getInstance("SHA-256")
                                .digest(chain[0].encoded)
                                .joinToString(":") { "%02X".format(it) }
                        if (store.pin(tv.ip) != fp) {
                            if (current == generation) listener.trustRequired(tv.ip, fp)
                            throw java.security.cert.CertificateException(
                                "TV certificate requires approval"
                            )
                        }
                    }
                }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            // Samsung certificates are usually self-signed and lack IP SANs. Exact user-approved
            // certificate pin is checked above.
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { host, _ ->
                host == tv.ip
            }
        }
        client = builder.build()
        val name = Base64.getEncoder().encodeToString("Bluewave Remote".toByteArray())
        val url =
            HttpUrl.Builder()
                .scheme(if (tv.secure) "https" else "http")
                .host(tv.ip)
                .port(if (tv.secure) 8002 else 8001)
                .addPathSegments("api/v2/channels/samsung.remote.control")
                .addQueryParameter("name", name)
                .apply { store.token(tv.ip)?.let { addQueryParameter("token", it) } }
                .build()
        socket =
            client!!.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (current == generation)
                            listener.status(
                                "Awaiting TV authorization. Select Allow on the TV.",
                                false,
                            )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (current != generation) return
                        runCatching {
                                val msg = JSONObject(text)
                                when (msg.optString("event")) {
                                    "ms.channel.connect" -> {
                                        msg.optJSONObject("data")
                                            ?.optString("token")
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { store.token(tv.ip, it) }
                                        ready = true
                                        store.save(tv)
                                        listener.authorized(tv)
                                        listener.status("Connected • ${tv.name}", true)
                                        webSocket.send(Protocol.appList())
                                    }
                                    "ms.channel.unauthorized" -> {
                                        ready = false
                                        listener.status(
                                            "TV denied authorization. Allow this remote in TV Device Connection Manager, then reconnect.",
                                            false,
                                        )
                                        listener.disconnected(false)
                                    }
                                    "ms.channel.timeOut" -> {
                                        ready = false
                                        listener.status(
                                            "Pairing timed out. Check the TV authorization prompt.",
                                            false,
                                        )
                                        listener.disconnected(false)
                                    }
                                    "ed.installedApp.get" -> {
                                        val data =
                                            msg.optJSONArray("data")
                                                ?: msg.optJSONObject("data")?.optJSONArray("data")
                                        val apps =
                                            if (data == null) emptyList()
                                            else
                                                (0 until data.length()).mapNotNull { i ->
                                                    data.optJSONObject(i)?.let { a ->
                                                        val id = a.optString("appId")
                                                        if (id.isBlank()) null
                                                        else TvApp(id, a.optString("name", id))
                                                    }
                                                }
                                        listener.apps(apps)
                                    }
                                    "ms.error" ->
                                        listener.status(
                                            "TV API error: ${msg.optJSONObject("data")?.optString("message") ?: "command unsupported"}",
                                            ready,
                                        )
                                }
                            }
                            .onFailure {
                                listener.status(
                                    "Unrecognized TV response; connection retained.",
                                    ready,
                                )
                            }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        if (current != generation) return
                        ready = false
                        val certificate =
                            t is SSLHandshakeException || t is SSLPeerUnverifiedException
                        listener.status(
                            if (certificate)
                                "Secure connection stopped. Review the TV certificate or reconnect after trusting it."
                            else
                                "Disconnected: ${t.javaClass.simpleName}. Check TV power, IP, network, and model compatibility.",
                            false,
                        )
                        listener.disconnected(!certificate)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        if (current != generation) return
                        ready = false
                        listener.status("TV is closing the connection ($code)", false)
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (current == generation) {
                            ready = false
                            listener.status("TV disconnected ($code)", false)
                            listener.disconnected(true)
                        }
                    }
                },
            )
    }

    fun send(key: String): Boolean = ready && socket?.send(Protocol.key(key)) == true

    fun launch(id: String): Boolean = ready && socket?.send(Protocol.launch(id)) == true

    fun requestApps(): Boolean = ready && socket?.send(Protocol.appList()) == true
}
