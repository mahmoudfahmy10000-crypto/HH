package com.bluewave.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Composable
internal fun KeyboardDialog(state: KeyboardState, vm: RemoteModel) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val owner = LocalLifecycleOwner.current
    Dialog(
        onDismissRequest = vm::closeKeyboard,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
            ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(.95f).imePadding(),
        ) {
            Column(
                Modifier.heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Smart TV keyboard", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.active) "TV IME session detected"
                    else "Manual mode • TV field compatibility is unverified",
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Arabic • English • mixed text. Switch languages or use voice typing in Gboard. Do not enter passwords here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = vm::editDraft,
                    label = { Text("Phone draft") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle =
                        MaterialTheme.typography.bodyLarge.copy(
                            textDirection = TextDirection.Content
                        ),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            autoCorrectEnabled = false,
                        ),
                )
                Row {
                    Checkbox(state.fieldConfirmed, vm::confirmField)
                    Text(
                        "I selected the intended TV text/search field",
                        Modifier.padding(top = 12.dp),
                    )
                }
                Text(
                    state.message,
                    color = Color(0xFFA9CFFF),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.offeredText != null) {
                    Text(
                        "The TV reported text. Using it replaces only this phone draft.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::adoptTvText) { Text("Use TV-reported text") }
                }
                if (state.sentDraft != null)
                    Row {
                        Checkbox(state.resendConfirmed, vm::confirmResend)
                        Text(
                            "Allow another Send; this TV/app may append or replace the text",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::sendDraft,
                        enabled =
                            state.fieldConfirmed &&
                                state.draft.isNotEmpty() &&
                                (state.sentDraft == null || state.resendConfirmed),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Send")
                    }
                    OutlinedButton(
                        onClick = vm::keyboardEnter,
                        enabled = state.fieldConfirmed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Enter")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::keyboardBackspace,
                        enabled = state.fieldConfirmed,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("TV Backspace")
                    }
                    OutlinedButton(onClick = vm::clearDraft, modifier = Modifier.weight(1f)) {
                        Text("Clear draft")
                    }
                }
                Text(
                    "Clear affects the phone only. Gboard edits the draft locally. Enter finishes IME then requests OK; TV Backspace may be unsupported. None of these requests confirms TV execution.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = vm::closeKeyboard, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
    LaunchedEffect(state.session) {
        delay(150)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
}
