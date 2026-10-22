package com.bluewave.remote

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImeWebSocketTest {
    private class Store : PairingStore {
        override fun token(ip: String): String? = null

        override fun token(ip: String, value: String) {}

        override fun pin(ip: String): String? = null

        override fun save(tv: Tv) {}
    }

    private class Events : TvConnection.Listener {
        val authorized = CountDownLatch(1)
        val started = CountDownLatch(1)
        val allIme = CountDownLatch(4)
        val events = CopyOnWriteArrayList<TvImeEvent>()

        override fun status(text: String, connected: Boolean) {}

        override fun authorized(tv: Tv) {
            authorized.countDown()
        }

        override fun apps(apps: List<TvApp>) {}

        override fun trustRequired(ip: String, fingerprint: String) {}

        override fun disconnected(retry: Boolean) {}

        override fun ime(event: TvImeEvent) {
            events.add(event)
            allIme.countDown()
            if (event.kind == ImeKind.START) started.countDown()
        }
    }

    @Test
    fun actualSocketCarriesUnicodeImeAndEditingMessagesOnlyAfterAuthorization() {
        val server = MockWebServer()
        val peer = AtomicReference<WebSocket>()
        val outgoing = LinkedBlockingQueue<JSONObject>()
        val received = CountDownLatch(7)
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            peer.set(webSocket)
                            webSocket.send(
                                """{"event":"ms.remote.imeStart","data":{"text":"must be ignored"}}"""
                            )
                            webSocket.send("""{"event":"ms.channel.connect","data":{}}""")
                            webSocket.send(
                                """{"event":"ms.remote.imeStart","data":{"text":"مرحبا"}}"""
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            outgoing.add(JSONObject(text))
                            received.countDown()
                        }
                    }
                )
        )
        server.start(8001)
        val events = Events()
        val connection = TvConnection(Store(), events, { it == "127.0.0.1" })
        try {
            assertFalse(connection.sendText("not authorized"))
            connection.connect(Tv("127.0.0.1", secure = false))
            assertTrue(events.authorized.await(5, TimeUnit.SECONDS))
            assertTrue(events.started.await(5, TimeUnit.SECONDS))
            assertEquals(1, events.events.size)
            assertEquals("مرحبا", events.events[0].text)
            assertTrue(connection.sendText("مرحبا English 123 🎬"))
            assertTrue(connection.sendText("second fixture"))
            assertTrue(connection.endText())
            assertTrue(connection.send("KEY_BACKSPACE"))
            assertTrue(connection.send("KEY_ENTER"))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            val messages = outgoing.toList()
            val inputs =
                messages.filter {
                    it.optJSONObject("params")?.optString("TypeOfRemote") == "SendInputString"
                }
            assertEquals(
                listOf("مرحبا English 123 🎬", "second fixture"),
                inputs.map {
                    String(
                        Base64.getDecoder().decode(it.getJSONObject("params").getString("Cmd")),
                        Charsets.UTF_8,
                    )
                },
            )
            assertEquals(
                1,
                messages.count {
                    it.optJSONObject("params")?.optString("event") == "custom.remote.textReceived"
                },
            )
            assertEquals(
                1,
                messages.count {
                    it.optJSONObject("params")?.optString("TypeOfRemote") == "SendInputEnd"
                },
            )
            assertEquals(
                listOf("KEY_BACKSPACE", "KEY_ENTER"),
                messages
                    .filter {
                        it.optJSONObject("params")?.optString("TypeOfRemote") == "SendRemoteKey"
                    }
                    .map { it.getJSONObject("params").getString("DataOfCmd") },
            )
            peer.get().send("""{"event":"ms.remote.imeUpdate","data":{"text":"TV fixture"}}""")
            peer.get().send("""{"event":"ms.remote.imeDone"}""")
            peer.get().send("""{"event":"ms.remote.imeEnd"}""")
            assertTrue(events.allIme.await(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(ImeKind.START, ImeKind.UPDATE, ImeKind.DONE, ImeKind.END),
                events.events.map { it.kind },
            )
        } finally {
            connection.disconnect()
            server.close()
        }
        assertFalse(connection.sendText("closed"))
        assertFalse(connection.endText())
    }

    @Test(expected = IllegalArgumentException::class)
    fun remoteProfileRejectsPlaintextBeforeSocketCreation() {
        TvConnection(Store(), Events(), { it == "127.0.0.1" })
            .connect(Tv("127.0.0.1", secure = false, remote = true))
    }
}
