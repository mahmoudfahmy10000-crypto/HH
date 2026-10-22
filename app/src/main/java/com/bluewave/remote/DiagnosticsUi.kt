package com.bluewave.remote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AuditDialogs(s: RemoteState, vm: RemoteModel) {
    s.pendingPower?.let { intent ->
        var physical by remember(intent) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = vm::dismissPower,
            title = { Text("Confirm power $intent") },
            text = {
                Column {
                    Text(vm.powerPreview(intent, physical).reason)
                    if (intent != PowerIntent.TOGGLE && s.hasIr && !s.remoteMode) {
                        Row {
                            Checkbox(physical, { physical = it })
                            Text(
                                if (intent == PowerIntent.ON)
                                    "I physically verified the TV is in standby"
                                else "I physically verified the TV is ON"
                            )
                        }
                    }
                    Text(
                        "One request only. No automatic toggle fallback. Observe the actual TV before marking a result."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmPower(physical) }) { Text("Send one request") }
            },
            dismissButton = { TextButton(onClick = vm::dismissPower) { Text("Cancel") } },
        )
    }
    s.pendingTest?.let { key ->
        AlertDialog(
            onDismissRequest = vm::dismissTest,
            title = { Text("Confirm diagnostic test") },
            text = {
                Text(
                    "Send one ${ControlCatalog.find(key)?.label ?: key} command? This may change volume, channel, focus or TV state. Observe the TV; no automated sweep runs."
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmTest) { Text("Run one test") } },
            dismissButton = { TextButton(onClick = vm::dismissTest) { Text("Cancel") } },
        )
    }
}

@Composable
fun PowerDiagnostics(s: RemoteState, vm: RemoteModel) {
    var mac by remember(s.selected?.ip) { mutableStateOf(vm.savedPowerMac()) }
    var enabled by remember(s.selected?.ip) { mutableStateOf(vm.savedWakeEnabled()) }
    Text("Power: ${s.power.state.title}", style = MaterialTheme.typography.titleLarge)
    Text(s.power.reason)
    Text(
        "A disconnected socket does not prove OFF. Network wake needs the TV's active network-interface MAC and enabled network standby / Power On with Mobile (if this firmware supports it). VPN wake also needs a reachable home gateway and appropriate ARP/routing. IR needs line of sight."
    )
    OutlinedTextField(
        mac,
        { mac = it },
        label = { Text("TV network-interface MAC") },
        singleLine = true,
    )
    Row {
        Checkbox(enabled, { enabled = it })
        Text("I enabled TV network standby; permit network wake")
    }
    Button(onClick = { vm.savePowerSettings(mac, enabled) }) { Text("Save wake configuration") }
    Row {
        PowerIntent.entries.forEach { intent ->
            TextButton(onClick = { vm.requestPower(intent) }) { Text(intent.name) }
        }
    }
}

@Composable
fun RemoteDiagnostics(s: RemoteState, vm: RemoteModel) {
    Text("Remote Diagnostics", style = MaterialTheme.typography.titleLarge)
    Text(
        "Delivery and TV behavior are separate. Confirmed channel/app-list responses prove API availability only. Yes/No below records your observation, not an automatic acknowledgement. History is in memory only; keyboard drafts and tokens are excluded."
    )
    Button(onClick = vm::diagnosticChecks) { Text("Check network, pairing, IR and app-list API") }
    ControlCatalog.all.chunked(3).forEach { row ->
        Row {
            row.forEach { spec ->
                TextButton(
                    onClick = { if (spec.power) vm.send(spec.key) else vm.requestTest(spec.key) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(spec.label)
                }
            }
        }
    }
    listOf("YouTube", "Shahid", "Yango Play", "Netflix", "WATCH IT", "MBC", "Prime Video")
        .chunked(2)
        .forEach { row ->
            Row {
                row.forEach { brand ->
                    TextButton(
                        onClick = { vm.requestTest("app:$brand") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Test $brand")
                    }
                }
            }
        }
    Button(onClick = { vm.requestTest("keyboard") }) { Text("Keyboard connection / field test") }
    Text(
        "Apps: use a named shortcut in Apps and observe the TV. Keyboard: open Keyboard, focus a real TV field, explicitly Send/Enter and verify the result. No background typing or automated app/power sweep."
    )
    s.diagnostics.forEach { result ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("#${result.sequence} ${result.label} • ${result.transport}")
                Text("${result.delivery.title} / ${result.verification.title}")
                Text(result.detail)
                if (result.delivery == DeliveryStatus.COMMAND_SENT && result.manual == null)
                    Row {
                        TextButton(onClick = { vm.markDiagnostic(result.sequence, true) }) {
                            Text("TV responded: Yes")
                        }
                        TextButton(onClick = { vm.markDiagnostic(result.sequence, false) }) {
                            Text("No response")
                        }
                    }
            }
        }
    }
}
