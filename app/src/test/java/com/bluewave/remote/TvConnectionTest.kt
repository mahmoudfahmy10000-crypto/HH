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
        var value: String? = null
        var saved: Tv? = null

        override fun token(ip: String) = value

        override fun token(ip: String, value: String) {
            this.value = value
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
        val connection = TvConnection(store, events)
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
        val connection = TvConnection(store, events)
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
}
