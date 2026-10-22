package com.bluewave.remote

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImeProtocolTest {
    @Test
    fun utf8Base64RoundTripsArabicEnglishAndMixedText() {
        for (text in
            listOf(
                "Samsung AU7000",
                "مرحبا بالعالم",
                "ابحث عن Shahid 2021",
                "١٢٣ 123 !@#$%&?",
                "أَهْلًا",
                "العربية English 🎬",
                "line one\nسطر ثان",
                "\"quoted\" \\ symbols",
            )) {
            val payload = JSONObject(ImeProtocol.text(text))
            assertEquals("ms.remote.control", payload.getString("method"))
            val p = payload.getJSONObject("params")
            assertEquals("base64", p.getString("DataOfCmd"))
            assertEquals("SendInputString", p.getString("TypeOfRemote"))
            assertEquals(
                text,
                String(Base64.getDecoder().decode(p.getString("Cmd")), Charsets.UTF_8),
            )
        }
    }

    @Test
    fun inputEndAndCompatibilityAnnouncementHaveKnownShapes() {
        val end = JSONObject(ImeProtocol.end())
        assertEquals("ms.remote.control", end.getString("method"))
        assertEquals(1, end.getJSONObject("params").length())
        assertEquals("SendInputEnd", end.getJSONObject("params").getString("TypeOfRemote"))
        val announce = JSONObject(ImeProtocol.announce())
        assertEquals("ms.channel.emit", announce.getString("method"))
        assertEquals(
            "custom.remote.textReceived",
            announce.getJSONObject("params").getString("event"),
        )
    }

    @Test
    fun rejectsOversizeNulAndMalformedSurrogateInput() {
        for (text in listOf("ع".repeat(2049), "x\u0000y", "\uD800", "")) {
            try {
                ImeProtocol.text(text)
                fail("Invalid text accepted")
            } catch (_: Exception) {}
        }
        assertNotNull(ImeProtocol.text("ع".repeat(2048)))
    }

    @Test
    fun recognizesAllFourEventsAndIgnoresUnknownEvents() {
        listOf(
                "Start" to ImeKind.START,
                "Update" to ImeKind.UPDATE,
                "Done" to ImeKind.DONE,
                "End" to ImeKind.END,
            )
            .forEach { (suffix, kind) ->
                assertEquals(
                    kind,
                    ImeProtocol.parse(JSONObject().put("event", "ms.remote.ime$suffix"))!!.kind,
                )
            }
        assertNull(ImeProtocol.parse(JSONObject().put("event", "unknown")))
    }

    @Test
    fun onlyDecodesExplicitBase64AndRejectsMalformedUpdates() {
        fun message(text: String, encoding: String = "") =
            JSONObject()
                .put("event", "ms.remote.imeUpdate")
                .put("data", JSONObject().put("text", text).put("encoding", encoding))
        assertEquals("YWJj", ImeProtocol.parse(message("YWJj"))!!.text)
        val arabic = "مرحبا English"
        assertEquals(
            arabic,
            ImeProtocol.parse(
                    message(
                        Base64.getEncoder().encodeToString(arabic.toByteArray(Charsets.UTF_8)),
                        "base64",
                    )
                )!!
                .text,
        )
        assertNull(ImeProtocol.parse(message("***", "base64"))!!.text)
        assertNull(ImeProtocol.parse(message("/w==", "base64"))!!.text)
        assertNull(ImeProtocol.parse(message("x".repeat(4097)))!!.text)
    }

    @Test
    fun passwordHintsSuppressTextAndKeyboard() {
        val event =
            ImeProtocol.parse(
                JSONObject()
                    .put("event", "ms.remote.imeStart")
                    .put(
                        "data",
                        JSONObject().put("inputType", "password").put("text", "fixture-password"),
                    )
            )!!
        assertTrue(event.sensitive)
        assertNull(event.text)
        val state = KeyboardState(visible = true, draft = "private draft").event(event, true)
        assertFalse(state.visible)
        assertFalse(state.active)
        assertEquals("", state.draft)
    }

    @Test
    fun automaticActivationRequiresForegroundAndUserPreference() {
        val start = TvImeEvent(ImeKind.START)
        assertTrue(KeyboardState().event(start, true).visible)
        assertFalse(KeyboardState().event(start, false).visible)
        assertFalse(KeyboardState(automatic = false).event(start, true).visible)
        assertTrue(KeyboardState(automatic = false, visible = true).event(start, true).visible)
        assertFalse(KeyboardState(automatic = false, visible = true).event(start, false).visible)
        assertNull(KeyboardState().event(TvImeEvent(ImeKind.START, "private"), false).offeredText)
    }

    @Test
    fun tvUpdatesDoNotOverwriteDirtyDraftOrEchoText() {
        val state =
            KeyboardState(
                active = true,
                visible = true,
                draft = "phone edit",
                dirty = true,
                sentDraft = "old",
            )
        val updated = state.event(TvImeEvent(ImeKind.UPDATE, "TV value"), true)
        assertEquals("phone edit", updated.draft)
        assertTrue(updated.dirty)
        assertEquals("TV value", updated.offeredText)
        assertEquals("old", updated.sentDraft)
        assertNull(updated.event(TvImeEvent(ImeKind.UPDATE, "private"), false).offeredText)
    }

    @Test
    fun doneAndCancellationClearTransientText() {
        for (kind in listOf(ImeKind.DONE, ImeKind.END)) {
            val state =
                KeyboardState(
                        active = true,
                        visible = true,
                        draft = "draft",
                        sentDraft = "sent",
                        offeredText = "offered",
                        fieldConfirmed = true,
                    )
                    .event(TvImeEvent(kind), true)
            assertFalse(state.active)
            assertFalse(state.visible)
            assertFalse(state.fieldConfirmed)
            assertEquals("", state.draft)
            assertNull(state.sentDraft)
            assertNull(state.offeredText)
        }
    }
}
