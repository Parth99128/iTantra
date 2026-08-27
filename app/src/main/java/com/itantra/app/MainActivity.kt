package com.itantra.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.itantra.app.core.*
import com.itantra.app.ui.theme.ITantraTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WalkieTalkieViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handle denials with a rationale dialog in production */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )

        viewModel.loadModels(SupportedLanguage.HINDI)

        setContent {
            ITantraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WalkieTalkieScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieScreen(viewModel: WalkieTalkieViewModel) {
    val state by viewModel.uiState.collectAsState()
    var langMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("iTantra", style = MaterialTheme.typography.headlineMedium)
        Text("Neural Transceiver Radio", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))

        // --- Mode toggle: WALKIE_TALKIE vs NORMAL_PHONE (required by PS) ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Walkie-Talkie Mode")
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.mode == OperatingMode.WALKIE_TALKIE,
                onCheckedChange = {
                    viewModel.setMode(if (it) OperatingMode.WALKIE_TALKIE else OperatingMode.NORMAL_PHONE)
                }
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- Language selector ---
        Box {
            OutlinedButton(onClick = { langMenuExpanded = true }) {
                Text(state.language.displayName)
            }
            DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                SupportedLanguage.values().forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(lang.displayName) },
                        onClick = {
                            viewModel.loadModels(lang)
                            langMenuExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        AssistChip(onClick = {}, label = { Text("BT: ${state.btState}") })

        Spacer(Modifier.height(16.dp))
        Row {
            Button(onClick = { viewModel.startAsHost() }) { Text("Host (Phone A)") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { /* launch system BT picker, then viewModel.connectAsClient(mac) */ }) {
                Text("Join (Phone B)")
            }
        }

        Spacer(Modifier.height(32.dp))

        // --- Push-to-talk button ---
        val ptt = Modifier
            .size(160.dp)
            .pointerInteropFilter { event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> viewModel.onPushToTalkStart()
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> viewModel.onPushToTalkEnd()
                }
                true
            }

        Button(
            onClick = { /* handled via pointerInteropFilter for press-and-hold */ },
            modifier = ptt,
            shape = MaterialTheme.shapes.extraLarge,
            enabled = state.mode == OperatingMode.WALKIE_TALKIE
        ) {
            Text(if (state.talkState == TalkState.LISTENING_FOR_SPEECH) "LISTENING..." else "HOLD TO TALK")
        }

        Spacer(Modifier.height(24.dp))
        Text("State: ${state.talkState}", style = MaterialTheme.typography.labelLarge)

        Spacer(Modifier.height(16.dp))
        TranscriptCard(label = "You said:", text = state.lastFinalTranscript)
        TranscriptCard(label = "Received:", text = state.lastReceivedText)

        Spacer(Modifier.height(16.dp))
        Text(
            "STT: ${state.lastSttLatencyMs}ms  |  TTS: ${state.lastTtsLatencyMs}ms  |  " +
            "End-to-end: ${state.lastEndToEndMs}ms",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun TranscriptCard(label: String, text: String) {
    if (text.isBlank()) return
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
