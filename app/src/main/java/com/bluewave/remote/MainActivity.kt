package com.bluewave.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs

private val Blue = Color(0xFF4D9FFF)
private val Surface = Color(0xFF151923)
private val RemoteColors =
    darkColorScheme(
        primary = Blue,
        background = Color(0xFF050609),
        surface = Surface,
        secondary = Color(0xFFA9CFFF),
    )

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = RemoteColors) { RemoteApp() } }
    }
}

@Composable
private fun RemoteApp(vm: RemoteModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = RemoteColors.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1017)) {
                listOf(
                        "Remote" to Icons.Rounded.SettingsRemote,
                        "Apps" to Icons.Rounded.Apps,
                        "Devices" to Icons.Rounded.Tv,
                        "Settings" to Icons.Rounded.Tune,
                    )
                    .forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = page == i,
                            onClick = { page = i },
                            icon = { Icon(icon, label) },
                            label = { Text(label) },
                        )
                    }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .widthIn(max = 620.dp)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "BLUEWAVE",
                        color = Blue,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Samsung Remote",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Icon(
                    Icons.Rounded.SettingsRemote,
                    null,
                    tint = Blue,
                    modifier = Modifier.size(34.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = !s.ir,
                    onClick = { vm.mode(false) },
                    label = { Text("Wi-Fi remote") },
                    leadingIcon = { Icon(Icons.Rounded.Wifi, null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = s.ir,
                    onClick = { vm.mode(true) },
                    label = { Text("Infrared") },
                    leadingIcon = { Icon(Icons.Rounded.Sensors, null, Modifier.size(18.dp)) },
                )
            }
            val color by
                animateColorAsState(
                    if (s.ir && s.hasIr || !s.ir && s.connected) Color(0xFF52E0B2)
                    else Color(0xFFFFC16E),
                    label = "connection",
                )
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (s.ir)
                            if (s.hasIr) "IR transmitter available • 38 kHz"
                            else "No IR transmitter detected"
                        else s.status,
                        color = color,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (s.feedback.isNotBlank())
                Text(
                    s.feedback,
                    color = Color(0xFFB4C5DD),
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(Color(0xFF101B2C), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                )
            when (page) {
                0 -> RemoteControls(s, vm)
                1 -> AppsScreen(s, vm)
                2 -> DevicesScreen(s, vm)
                3 -> SettingsScreen(s, vm)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (s.trustIp != null)
        AlertDialog(
            onDismissRequest = { vm.trust(false) },
            title = { Text("Trust this TV certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Samsung TVs often use self-signed TLS certificates. Only approve on a trusted home network after checking that ${s.trustIp} belongs to your TV. A changed certificate may indicate a different device."
                    )
                    Text("SHA-256\n${s.fingerprint}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { vm.trust(true) }) { Text("Trust and pair") } },
            dismissButton = { TextButton(onClick = { vm.trust(false) }) { Text("Cancel") } },
        )
}

@Composable
private fun KeyButton(
    label: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = if (accent) Color(0xFF123B6D) else Surface,
                contentColor = if (accent) Blue else Color(0xFFE4ECFA),
            ),
        contentPadding = PaddingValues(10.dp),
    ) {
        if (icon != null) Icon(icon, label, Modifier.size(24.dp))
        else Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RemoteControls(s: RemoteState, vm: RemoteModel) {
    var numbers by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(false) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyButton("Power", Icons.Rounded.PowerSettingsNew, Modifier.weight(1f), true) {
            vm.send("KEY_POWER")
        }
        if (!s.ir) KeyButton("Wake", Icons.Rounded.Power, Modifier.weight(1f)) { vm.wake() }
        KeyButton("Source", Icons.Rounded.Input, Modifier.weight(1f)) { vm.send("KEY_SOURCE") }
        KeyButton("Mute", Icons.Rounded.VolumeOff, Modifier.weight(1f)) { vm.send("KEY_MUTE") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyButton("Volume up", Icons.Rounded.Add, Modifier.width(75.dp)) {
                vm.send("KEY_VOLUP")
            }
            Text("VOL", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            KeyButton("Volume down", Icons.Rounded.Remove, Modifier.width(75.dp)) {
                vm.send("KEY_VOLDOWN")
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyButton("Home", Icons.Rounded.Home, Modifier.width(75.dp), true) {
                vm.send("KEY_HOME")
            }
            Text("SMART TV", color = Blue, style = MaterialTheme.typography.labelSmall)
            KeyButton("Back", Icons.Rounded.KeyboardReturn, Modifier.width(75.dp)) {
                vm.send("KEY_RETURN")
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyButton("Channel up", Icons.Rounded.KeyboardArrowUp, Modifier.width(75.dp)) {
                vm.send("KEY_CHUP")
            }
            Text("CH", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            KeyButton("Channel down", Icons.Rounded.KeyboardArrowDown, Modifier.width(75.dp)) {
                vm.send("KEY_CHDOWN")
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(286.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF1B2B43), Color(0xFF10141C)))),
            contentAlignment = Alignment.Center,
        ) {
            @Composable
            fun arrow(label: String, icon: ImageVector, alignment: Alignment, key: String) {
                IconButton(
                    onClick = { vm.send(key) },
                    modifier = Modifier.align(alignment).size(84.dp),
                ) {
                    Icon(icon, label, tint = Color(0xFFD7E6FA), modifier = Modifier.size(36.dp))
                }
            }
            arrow("Up", Icons.Rounded.KeyboardArrowUp, Alignment.TopCenter, "KEY_UP")
            arrow("Down", Icons.Rounded.KeyboardArrowDown, Alignment.BottomCenter, "KEY_DOWN")
            arrow("Left", Icons.Rounded.KeyboardArrowLeft, Alignment.CenterStart, "KEY_LEFT")
            arrow("Right", Icons.Rounded.KeyboardArrowRight, Alignment.CenterEnd, "KEY_RIGHT")
            Button(
                onClick = { vm.send("KEY_ENTER") },
                shape = CircleShape,
                modifier = Modifier.size(94.dp),
                colors =
                    ButtonDefaults.buttonColors(containerColor = Blue, contentColor = Color.Black),
            ) {
                Text(
                    "OK",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KeyButton("Menu", Icons.Rounded.Menu, Modifier.weight(1f)) { vm.send("KEY_MENU") }
        KeyButton("Settings", Icons.Rounded.Settings, Modifier.weight(1f)) {
            vm.send("KEY_SETTINGS")
        }
        KeyButton("Keypad", Icons.Rounded.Dialpad, Modifier.weight(1f)) { numbers = !numbers }
        KeyButton("Swipe pad", Icons.Rounded.TouchApp, Modifier.weight(1f)) { touch = !touch }
    }
    if (touch) {
        Text(
            "Swipe navigation • sends direction keys, not a TV mouse pointer",
            color = Color.Gray,
            style = MaterialTheme.typography.bodySmall,
        )
        Box(
            Modifier.fillMaxWidth()
                .height(130.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surface)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dx = 0f
                            dy = 0f
                        },
                        onDragEnd = {
                            if (abs(dx) > 40 || abs(dy) > 40)
                                vm.send(
                                    if (abs(dx) > abs(dy)) if (dx > 0) "KEY_RIGHT" else "KEY_LEFT"
                                    else if (dy > 0) "KEY_DOWN" else "KEY_UP"
                                )
                        },
                    ) { change, drag ->
                        change.consume()
                        dx += drag.x
                        dy += drag.y
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("Swipe to navigate", color = Blue)
        }
    }
    if (numbers) {
        for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(0))) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { n ->
                    KeyButton(n.toString(), modifier = Modifier.weight(1f)) { vm.send("KEY_$n") }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KeyButton("Rewind", Icons.Rounded.FastRewind, Modifier.weight(1f)) { vm.send("KEY_REWIND") }
        KeyButton("Play", Icons.Rounded.PlayArrow, Modifier.weight(1f)) { vm.send("KEY_PLAY") }
        KeyButton("Pause", Icons.Rounded.Pause, Modifier.weight(1f)) { vm.send("KEY_PAUSE") }
        KeyButton("Fast forward", Icons.Rounded.FastForward, Modifier.weight(1f)) {
            vm.send("KEY_FF")
        }
    }
    Text("STREAMING", style = MaterialTheme.typography.labelMedium, color = Blue)
    AppGrid(s, vm)
}

private val brands = listOf("YouTube", "Yango Play", "WATCH IT", "Shahid", "Netflix", "Prime Video")

@Composable
private fun AppGrid(s: RemoteState, vm: RemoteModel) {
    val apps =
        (brands.map { name -> s.apps.find { it.name.contains(name, true) } ?: TvApp("", name) } +
                s.shortcuts)
            .distinctBy { it.name }
    apps.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { app ->
                val colors =
                    listOf(
                        Color(0xFFEF5350),
                        Color(0xFF8C74FF),
                        Color(0xFFFFB74D),
                        Color(0xFF50D6B4),
                        Color(0xFFED4050),
                        Color(0xFF52AEFF),
                    )
                OutlinedButton(
                    onClick = {
                        if (app.id.isNotBlank() && s.shortcuts.any { it.id == app.id })
                            vm.launchCustom(app)
                        else vm.launch(app)
                    },
                    modifier = Modifier.weight(1f).height(62.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        app.name,
                        color = colors[brands.indexOf(app.name).coerceAtLeast(0) % 6],
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppsScreen(s: RemoteState, vm: RemoteModel) {
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    Text(
        "Your TV, your entertainment",
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
    )
    AppGrid(s, vm)
    Text(
        "Direct launching depends on the TV exposing an app ID and supporting ed.apps.launch. A sent request does not confirm launch. IR users can open Home and navigate.",
        color = Color.Gray,
    )
    Button(onClick = { vm.refreshApps() }) {
        Icon(Icons.Rounded.Refresh, null)
        Text(" Refresh installed apps")
    }
    s.apps.forEach { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.launch(app) }, modifier = Modifier.weight(1f)) {
                Text(app.name)
            }
            TextButton(onClick = { vm.toggleShortcut(app) }) {
                Text(if (s.shortcuts.any { it.id == app.id }) "Unpin" else "Pin")
            }
        }
    }
    Text("Custom shortcut", color = Blue)
    OutlinedTextField(
        name,
        { name = it },
        label = { Text("App name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        id,
        { id = it },
        label = { Text("Samsung TV app ID") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        enabled = name.isNotBlank() && id.isNotBlank(),
        onClick = {
            vm.toggleShortcut(TvApp(id.trim(), name.trim()))
            name = ""
            id = ""
        },
    ) {
        Text("Save shortcut")
    }
    s.shortcuts.forEach { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(app.name, Modifier.weight(1f), color = Color.White)
            TextButton(onClick = { vm.toggleShortcut(app) }) { Text("Remove") }
        }
    }
}

@Composable
private fun DevicesScreen(s: RemoteState, vm: RemoteModel) {
    var ip by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(true) }
    Text(
        "Connect your Samsung TV",
        style = MaterialTheme.typography.titleLarge,
        color = Color.White,
    )
    Text(
        "Use the same home Wi-Fi as your TV. Turn it on, then approve Bluewave Remote on the TV. Guest networks and client isolation can block discovery.",
        color = Color.Gray,
    )
    Button(onClick = { vm.scan() }, enabled = !s.scanning) {
        if (s.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(if (s.scanning) " Searching…" else "Discover TVs")
    }
    s.discovered.forEach { tv -> TvRow(tv, { vm.connect(tv) }) }
    Text("Manual connection", color = Blue)
    OutlinedTextField(
        ip,
        { ip = it.trim() },
        label = { Text("TV IPv4 address") },
        placeholder = { Text("192.168.1.20") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        mac,
        { mac = it.trim() },
        label = { Text("TV MAC • optional for Wake-on-LAN") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(secure, { secure = it })
        Spacer(Modifier.width(12.dp))
        Text(
            if (secure) "TLS / port 8002 • certificate pinning"
            else "Legacy / port 8001 • unencrypted",
            color = Color.White,
        )
    }
    if (!secure)
        Text(
            "Legacy mode sends commands and pairing tokens unencrypted. Use only on a trusted home network.",
            color = Color(0xFFFFC16E),
        )
    Button(onClick = { vm.manual(ip, mac, secure) }, enabled = ip.isNotBlank()) {
        Text("Connect and pair")
    }
    Text("Saved televisions", color = Blue)
    s.saved.forEach { tv -> TvRow(tv, { vm.connect(tv) }, { vm.forget(tv) }) }
    if (s.saved.isEmpty())
        Text("TVs are saved only after successful authorization.", color = Color.Gray)
    OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
}

@Composable
private fun TvRow(tv: Tv, connect: () -> Unit, forget: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tv.name, color = Color.White)
                Text(tv.ip, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = connect) { Text("Connect") }
            if (forget != null)
                IconButton(onClick = forget) { Icon(Icons.Rounded.Delete, "Forget TV") }
        }
    }
}

@Composable
private fun SettingsScreen(s: RemoteState, vm: RemoteModel) {
    Text("IR configuration", style = MaterialTheme.typography.titleLarge, color = Color.White)
    Text(
        if (s.hasIr)
            "Android reports an infrared emitter. Point the top of your phone at the TV with a clear line of sight."
        else
            "No ConsumerIrManager emitter is available. Redmi Note 14 hardware varies by region; Wi-Fi mode remains available.",
        color = Color.Gray,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(s.nec, { vm.profile(it) })
        Spacer(Modifier.width(12.dp))
        Text(
            if (s.nec) "Samsung legacy • NEC 9 ms header" else "Samsung32 • 4.5 ms header",
            color = Color.White,
        )
    }
    Text(
        "Both profiles use 38 kHz Samsung TV command codes. Try Samsung32 first. TV response is not detectable over IR.",
        color = Color.Gray,
    )
    Button(
        onClick = {
            vm.mode(true)
            vm.send("KEY_VOLUP")
        },
        enabled = s.hasIr,
    ) {
        Text("Test IR • volume +")
    }
    Text("Compatibility & privacy", color = Blue)
    Text(
        "Wi-Fi supports Samsung TVs exposing the Tizen remote WebSocket API (usually 2016+). Older PIN/encrypted protocols are unsupported. Settings uses KEY_SETTINGS on Wi-Fi and the Samsung menu IR code; availability varies by model. Native pointer control is not exposed consistently; swipe pad sends direction keys. Network power-on needs a TV MAC, enabled network standby, and supported Wake-on-LAN. IR is a power toggle.",
        color = Color.Gray,
    )
    Text(
        "No account, cloud service, analytics, or internet connection is required by the app. Pairing tokens are encrypted with an Android Keystore key; backups are disabled. Forget a TV to remove its token and certificate pin. Connections retry up to three times while the app process is running.",
        color = Color.Gray,
    )
    Text(
        "Version 1.0 • independent app, not affiliated with Samsung",
        style = MaterialTheme.typography.bodySmall,
        color = Blue,
    )
}
