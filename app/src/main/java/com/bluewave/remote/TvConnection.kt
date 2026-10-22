package com.bluewave.remote

import java.net.Proxy
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import okhttp3.*
import org.json.JSONObject

class TvConnection
internal constructor(
    private val store: PairingStore,
    private val listener: Listener,
    // Tests supply a loopback-only predicate; production uses the private-LAN policy.
    private val destinationAllowed: (String) -> Boolean = Protocol::localIp,
) {
    interface Listener {
        fun status(text: String, connected: Boolean)

        fun authorized(tv: Tv)

        fun apps(apps: List<TvApp>)

        fun trustRequired(ip: String, fingerprint: String)

        fun ime(event: TvImeEvent) {}

        fun disconnected(retry: Boolean)
    }

    private var socket: WebSocket? = null
    @Volatile private var textAnnounced = false
    private var client: OkHttpClient? = null
    @Volatile private var generation = 0
    @Volatile
    var ready = false
        private set

    fun disconnect() {
        generation++
        ready = false
        textAnnounced = false
        socket?.cancel()
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
    }

    fun connect(tv: Tv) {
        disconnect()
        require(destinationAllowed(tv.ip)) { "Only private LAN TV addresses are supported" }
        require(!tv.remote || tv.secure) { "Remote access requires TLS" }
        val current = generation
        listener.status("Connecting to ${tv.ip}…", false)
        val builder =
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(7, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
        if (tv.secure) {
            val trust =
                TvTrustManager(
                    expectedFingerprint = { store.pin(tv.ip) },
                    untrustedCertificate = { fp ->
                        if (current == generation) listener.trustRequired(tv.ip, fp)
                    },
                )
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            // Samsung certificates are usually self-signed and lack IP SANs. Exact user-approved
            // certificate pin is checked above.
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { host, _ ->
                host == tv.ip
            }
        }
        client = builder.build()
        // Never reuse a TLS pairing credential on the optional plaintext endpoint.
        val tokenScope = "${if (tv.secure) "tls" else "plain"}:${tv.ip}"
        val name = Base64.getEncoder().encodeToString("Bluewave Remote".toByteArray())
        val url =
            HttpUrl.Builder()
                .scheme(if (tv.secure) "https" else "http")
                .host(tv.ip)
                .port(if (tv.secure) 8002 else 8001)
                .addPathSegments("api/v2/channels/samsung.remote.control")
                .addQueryParameter("name", name)
                .apply { store.token(tokenScope)?.let { addQueryParameter("token", it) } }
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
                        if (text.length > 262144) {
                            webSocket.cancel()
                            ready = false
                            listener.status(
                                "TV response exceeded the size limit; disconnected.",
                                false,
                            )
                            listener.disconnected(false)
                            return
                        }
                        runCatching {
                                val msg = JSONObject(text)
                                ImeProtocol.parse(msg)?.let { event ->
                                    if (event.kind != ImeKind.UPDATE) textAnnounced = false
                                    if (ready) listener.ime(event)
                                    return
                                }
                                when (msg.optString("event")) {
                                    "ms.channel.connect" -> {
                                        msg.optJSONObject("data")
                                            ?.optString("token")
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { store.token(tokenScope, it) }
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
                                            "TV reported ms.error; command or text may be unsupported. Check the TV field and model.",
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

    @Synchronized
    fun sendText(text: String): Boolean {
        val payload = ImeProtocol.text(text)
        if (!ready) return false
        if (!textAnnounced) {
            if (socket?.send(ImeProtocol.announce()) != true) return false
            textAnnounced = true
        }
        return socket?.send(payload) == true
    }

    @Synchronized
    fun endText(): Boolean {
        if (!ready) return false
        textAnnounced = false
        return socket?.send(ImeProtocol.end()) == true
    }
}
