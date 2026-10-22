package com.bluewave.remote

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

enum class ImeKind {
    START,
    UPDATE,
    DONE,
    END,
}

data class TvImeEvent(val kind: ImeKind, val text: String? = null, val sensitive: Boolean = false)

object ImeProtocol {
    const val MAX_TEXT_BYTES = 4096

    fun validate(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) {
            "Text exceeds 4096 UTF-8 bytes"
        }
        require(text.none { it == '\u0000' }) { "NUL characters are not supported" }
        // Reject unpaired UTF-16 surrogates rather than silently changing the user's text.
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
    }

    fun text(text: String): String {
        validate(text)
        require(text.isNotEmpty()) {
            "Use Backspace to delete; empty input is not a TV clear command"
        }
        return JSONObject()
            .put("method", "ms.remote.control")
            .put(
                "params",
                JSONObject()
                    .put(
                        "Cmd",
                        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)),
                    )
                    .put("DataOfCmd", "base64")
                    .put("TypeOfRemote", "SendInputString"),
            )
            .toString()
    }

    fun end(): String =
        JSONObject()
            .put("method", "ms.remote.control")
            .put("params", JSONObject().put("TypeOfRemote", "SendInputEnd"))
            .toString()

    fun announce(): String =
        JSONObject()
            .put("method", "ms.channel.emit")
            .put(
                "params",
                JSONObject().put("event", "custom.remote.textReceived").put("to", "broadcast"),
            )
            .toString()

    /** Optional update schemas vary by firmware; never guess that unmarked text is Base64. */
    fun parse(message: JSONObject): TvImeEvent? {
        val kind =
            when (message.optString("event")) {
                "ms.remote.imeStart" -> ImeKind.START
                "ms.remote.imeUpdate" -> ImeKind.UPDATE
                "ms.remote.imeDone" -> ImeKind.DONE
                "ms.remote.imeEnd" -> ImeKind.END
                else -> return null
            }
        val data = message.optJSONObject("data")
        val sensitive =
            data?.optBoolean("isPassword", false) == true ||
                listOf("inputType", "imeType", "type").any {
                    data?.optString(it)?.contains("password", true) == true
                }
        if (sensitive) return TvImeEvent(kind, sensitive = true)
        val raw = listOf("text", "value").firstNotNullOfOrNull { key -> data?.opt(key) as? String }
        val text =
            raw?.let { value ->
                runCatching {
                        val decoded =
                            if (data?.optString("encoding")?.equals("base64", true) == true) {
                                require(value.length <= MAX_TEXT_BYTES * 2)
                                val bytes = Base64.getDecoder().decode(value)
                                Charsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(bytes))
                                    .toString()
                            } else value
                        validate(decoded)
                        decoded
                    }
                    .getOrNull()
            }
        return TvImeEvent(kind, text)
    }
}

/** No guessed insertion/deletion replay: remote values are offered for explicit adoption. */
data class KeyboardState(
    val visible: Boolean = false,
    val active: Boolean = false,
    val draft: String = "",
    val dirty: Boolean = false,
    val fieldConfirmed: Boolean = false,
    val resendConfirmed: Boolean = false,
    val offeredText: String? = null,
    val automatic: Boolean = true,
    val session: Long = 0,
    val sentDraft: String? = null,
    val message: String =
        "Select a TV text field first. Manual Send requests insertion; TV behavior varies.",
) {
    fun event(event: TvImeEvent, foreground: Boolean): KeyboardState =
        when {
            event.sensitive ->
                copy(
                    visible = false,
                    active = false,
                    draft = "",
                    dirty = false,
                    fieldConfirmed = false,
                    resendConfirmed = false,
                    offeredText = null,
                    sentDraft = null,
                    message = "Password IME is unsupported. Use the TV's own input controls.",
                )
            event.kind == ImeKind.DONE || event.kind == ImeKind.END ->
                copy(
                    visible = false,
                    active = false,
                    draft = "",
                    dirty = false,
                    fieldConfirmed = false,
                    resendConfirmed = false,
                    offeredText = null,
                    sentDraft = null,
                    message =
                        "TV ended the IME session. Execution of earlier text is not confirmed.",
                )
            event.kind == ImeKind.START ->
                copy(
                    active = true,
                    visible = foreground && (automatic || visible),
                    draft = "",
                    dirty = false,
                    fieldConfirmed = false,
                    resendConfirmed = false,
                    offeredText = if (foreground) event.text else null,
                    sentDraft = null,
                    session = session + 1,
                    message =
                        "TV IME session detected. Select and verify the TV field before sending.",
                )
            else ->
                copy(
                    offeredText = if (foreground && active) event.text else null,
                    message =
                        "IME update received. Adopt offered TV text explicitly to avoid overwriting local edits.",
                )
        }
}
