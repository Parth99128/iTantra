package com.itantra.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itantra.app.core.*
import com.itantra.app.ui.theme.ITantraTheme

class MainActivity : ComponentActivity() {
    private val viewModel: WalkieTalkieViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it == false }) viewModel.setPermissionWarning("Bluetooth/microphone permission is required for the selected feature.")
    }
    private val modelPackPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installModelPack)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.attachActivity(this)
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
        setContent {
            ITantraTheme {
                Surface(Modifier.fillMaxSize()) {
                    WalkieTalkieScreen(viewModel) {
                        modelPackPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun WalkieTalkieScreen(viewModel: WalkieTalkieViewModel, onPickModelPack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var langExpanded by remember { mutableStateOf(false) }
    var peerExpanded by remember { mutableStateOf(false) }
    var ttsText by remember { mutableStateOf("आपातकालीन संदेश: कृपया तुरंत सहायता भेजें।") }

    LazyColumn(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Text("iTantra", style = MaterialTheme.typography.headlineMedium)
            Text("Offline Neural Transceiver Radio")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onPickModelPack, enabled = !state.installInProgress) {
                Text(if (state.installInProgress) "Installing…" else "Install Offline Model Pack")
            }
            if (state.installInProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.installMessage.isNotBlank()) Text(state.installMessage, style = MaterialTheme.typography.bodySmall)
            state.permissionWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Models stay on-device; inference does not use the internet.", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Walkie-Talkie Mode")
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.mode == OperatingMode.WALKIE_TALKIE, onCheckedChange = { viewModel.setMode(if (it) OperatingMode.NORMAL_PHONE else OperatingMode.WALKIE_TALKIE) })
            }
            Spacer(Modifier.height(8.dp))
            Box {
                OutlinedButton(onClick = { langExpanded = true }) { Text(state.language.displayName) }
                DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                    SupportedLanguage.values().forEach { lang ->
                        DropdownMenuItem(text = { Text(lang.displayName) }, onClick = { viewModel.loadModels(lang); langExpanded = false })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Bluetooth: ${state.btState}", style = MaterialTheme.typography.labelLarge)
            Row {
                OutlinedButton(onClick = { viewModel.startDiscovery() }) { Text("Scan") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { viewModel.startAsHost() }) { Text("Host") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { viewModel.disconnectBluetooth() }) { Text("Disconnect") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { peerExpanded = !peerExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.bluetoothDevices.isEmpty()) "Select paired/discovered phone" else "Select phone (${state.bluetoothDevices.size})")
            }
            DropdownMenu(expanded = peerExpanded, onDismissRequest = { peerExpanded = false }) {
                state.bluetoothDevices.forEach { peer ->
                    DropdownMenuItem(text = { Text("${peer.name}\n${peer.address}") }, onClick = { viewModel.connectToPeer(peer); peerExpanded = false })
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Offline TTS", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.language == SupportedLanguage.MARATHI) "Marathi is TTS-only in the current model matrix." else "Speak text locally without Bluetooth.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = ttsText,
                        onValueChange = { ttsText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Text to speak") },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { viewModel.testTts(ttsText) }, enabled = ttsText.isNotBlank() && !state.installInProgress) { Text("Speak Offline") }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        item {
            val ptt = Modifier.size(170.dp).pointerInteropFilter { event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> viewModel.onPushToTalkStart()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> viewModel.onPushToTalkEnd()
                }
                true
            }
            Button(onClick = {}, modifier = ptt, shape = MaterialTheme.shapes.extraLarge, enabled = state.mode == OperatingMode.WALKIE_TALKIE && !state.installInProgress) {
                Text(if (state.talkState == TalkState.LISTENING_FOR_SPEECH) "LISTENING…" else "HOLD TO TALK")
            }
            Spacer(Modifier.height(16.dp))
            Text("State: ${state.talkState}", style = MaterialTheme.typography.labelLarge)
            state.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.partialTranscript.isNotBlank()) Text("Live: ${state.partialTranscript}")
            Spacer(Modifier.height(8.dp))
            TranscriptCard("You said:", state.lastFinalTranscript)
            TranscriptCard("Received:", state.lastReceivedText)
            Spacer(Modifier.height(8.dp))
            Text("STT: ${state.lastSttLatencyMs}ms | TTS: ${state.lastTtsLatencyMs}ms | E2E: ${state.lastEndToEndMs}ms", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun TranscriptCard(label: String, text: String) {
    if (text.isBlank()) return
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
