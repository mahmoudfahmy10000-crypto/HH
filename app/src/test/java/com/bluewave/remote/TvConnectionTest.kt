package com.bluewave.remote

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvConnectionTest {
    private class Store : PairingStore {
        val tokens = mutableMapOf<String, String>()
        var value: String? = null
        var saved: Tv? = null

        override fun token(ip: String) = tokens[ip]

        override fun token(ip: String, value: String) {
            this.value = value
            tokens[ip] = value
        }

        override fun pin(ip: String): String? = null

        override fun save(tv: Tv) {
            saved = tv
        }
    }

    private open class Events : TvConnection.Listener {
        val connected = CountDownLatch(1)
        val denied = CountDownLatch(1)
        val appList = CountDownLatch(1)
        var apps: List<TvApp> = emptyList()

        override fun status(text: String, connected: Boolean) {}

        override fun authorized(tv: Tv) {
            connected.countDown()
        }

        override fun apps(apps: List<TvApp>) {
            this.apps = apps
            appList.countDown()
        }

        override fun trustRequired(ip: String, fingerprint: String) {}

        override fun disconnected(retry: Boolean) {
            if (!retry) denied.countDown()
        }
    }

    @Test
    fun waitsForAuthorizationThenPersistsTokenAndSendsCommands() {
        val server = MockWebServer()
        val store = Store()
        val events = Events()
        val key = CountDownLatch(1)
        val appsRequested = CountDownLatch(1)
        val peer = java.util.concurrent.atomic.AtomicReference<WebSocket?>(null)
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            peer.set(webSocket)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val j = JSONObject(text)
                            if (j.optString("method") == "ms.remote.control") key.countDown()
                            else {
                                appsRequested.countDown()
                                webSocket.send(
                                    """{"event":"ed.installedApp.get","data":[{"appId":"123","name":"YouTube"}]}"""
                                )
                            }
                        }
                    }
                )
        )
        server.start(8001)
        val connection = TvConnection(store, events, { it == "127.0.0.1" })
        try {
            connection.connect(Tv("127.0.0.1", secure = false))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(request.path!!.startsWith("/api/v2/channels/samsung.remote.control?name="))
            assertFalse(connection.ready)
            assertFalse(connection.send("KEY_VOLUP"))
            assertNull(store.saved)
            // onOpen callback may be scheduled just after handshake request is recorded.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (peer.get() == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(peer.get())
            peer.get()!!.send("""{"event":"ms.channel.connect","data":{"token":"test-pairing"}}""")
            assertTrue(events.connected.await(5, TimeUnit.SECONDS))
            assertTrue(connection.ready)
            assertEquals("test-pairing", store.value)
            assertNotNull(store.saved)
            assertTrue(connection.send("KEY_VOLUP"))
            assertTrue(key.await(5, TimeUnit.SECONDS))
            assertTrue(appsRequested.await(5, TimeUnit.SECONDS))
            assertTrue(events.appList.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(TvApp("123", "YouTube")), events.apps)
        } finally {
            connection.disconnect()
            server.close()
        }
    }

    @Test
    fun unauthorizedTvNeverBecomesConnected() {
        val server = MockWebServer()
        val store = Store()
        val events = Events()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"event":"ms.channel.unauthorized"}""")
                        }
                    }
                )
        )
        server.start(8001)
        val connection = TvConnection(store, events, { it == "127.0.0.1" })
        try {
            connection.connect(Tv("127.0.0.1", secure = false))
            assertTrue(events.denied.await(5, TimeUnit.SECONDS))
            assertFalse(connection.ready)
            assertNull(store.saved)
            assertFalse(connection.send("KEY_POWER"))
        } finally {
            connection.disconnect()
            server.close()
        }
    }

    @Test
    fun plaintextEndpointNeverReceivesTlsPairingToken() {
        for (legacyToken in listOf(null, "plain-fixture-token")) {
            val server = MockWebServer()
            val store =
                Store().apply {
                    tokens["tls:127.0.0.1"] = "tls-fixture-token"
                    if (legacyToken != null) tokens["plain:127.0.0.1"] = legacyToken
                }
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
            server.start(8001)
            val connection = TvConnection(store, Events(), { it == "127.0.0.1" })
            try {
                connection.connect(Tv("127.0.0.1", secure = false))
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals(legacyToken, request.requestUrl!!.queryParameter("token"))
                assertFalse(request.path!!.contains("tls-fixture-token"))
            } finally {
                connection.disconnect()
                server.close()
            }
        }
    }

    @Test
    fun redirectsCannotForwardPairingCredentials() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://127.0.0.1:8001/unexpected")
        )
        server.enqueue(MockResponse().setResponseCode(401))
        server.start(8001)
        val failure = CountDownLatch(1)
        val connection =
            TvConnection(
                Store(),
                object : Events() {
                    override fun disconnected(retry: Boolean) {
                        failure.countDown()
                    }
                },
                { it == "127.0.0.1" },
            )
        try {
            connection.connect(Tv("127.0.0.1", secure = false))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertTrue(failure.await(5, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
            assertFalse(connection.ready)
        } finally {
            connection.disconnect()
            server.close()
        }
    }

    @Test
    fun everyCatalogKeyAndLaunchTypeTraversesMockSocketOnceAndErrorsRemainUnconfirmed() {
        val server = MockWebServer()
        val queue = java.util.concurrent.LinkedBlockingQueue<String>()
        val error = CountDownLatch(1)
        val events =
            object : Events() {
                override fun commandError() {
                    error.countDown()
                }
            }
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"event":"ms.channel.connect"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val j = JSONObject(text)
                            if (
                                j.optString("method") == "ms.remote.control" ||
                                    j.optJSONObject("params")?.optString("event") ==
                                        "ed.apps.launch"
                            ) {
                                queue.put(text)
                                webSocket.send(
                                    """{"event":"ms.error","data":{"message":"unsupported fixture"}}"""
                                )
                            }
                        }
                    }
                )
        )
        server.start(8001)
        val c = TvConnection(Store(), events, { it == "127.0.0.1" })
        try {
            assertFalse(c.send("KEY_HOME"))
            c.connect(Tv("127.0.0.1", secure = false))
            assertTrue(events.connected.await(5, TimeUnit.SECONDS))
            ControlCatalog.all.forEach { spec ->
                assertTrue(c.send(spec.key))
                assertEquals(
                    spec.key,
                    JSONObject(queue.poll(5, TimeUnit.SECONDS)!!)
                        .getJSONObject("params")
                        .getString("DataOfCmd"),
                )
            }
            LaunchType.entries.forEach { type ->
                assertTrue(c.launch("fixture-region-id", type))
                assertEquals(
                    type.name,
                    JSONObject(queue.poll(5, TimeUnit.SECONDS)!!)
                        .getJSONObject("params")
                        .getJSONObject("data")
                        .getString("action_type"),
                )
            }
            assertTrue(error.await(5, TimeUnit.SECONDS))
            assertNull(queue.poll(150, TimeUnit.MILLISECONDS))
            c.disconnect()
            assertFalse(c.send("KEY_POWER"))
            assertFalse(c.launch("fixture"))
            assertNull(queue.poll(150, TimeUnit.MILLISECONDS))
        } finally {
            c.disconnect()
            server.close()
        }
    }

    @Test
    fun serverPairingTimeoutCannotAuthorizeOrPersistCredentials() {
        val server = MockWebServer()
        val events = Events()
        val store = Store()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"event":"ms.channel.timeOut"}""")
                        }
                    }
                )
        )
        server.start(8001)
        val c = TvConnection(store, events, { it == "127.0.0.1" })
        try {
            c.connect(Tv("127.0.0.1", secure = false))
            assertTrue(events.denied.await(5, TimeUnit.SECONDS))
            assertFalse(c.ready)
            assertNull(store.saved)
            assertFalse(c.send("KEY_POWER"))
        } finally {
            c.disconnect()
            server.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun productionConnectionRejectsPublicDestinationBeforeOpeningSocket() {
        TvConnection(Store(), Events()).connect(Tv("8.8.8.8"))
    }
}
