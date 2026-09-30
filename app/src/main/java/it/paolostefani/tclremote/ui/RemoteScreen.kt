package it.paolostefani.tclremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.paolostefani.tclremote.TclRemoteViewModel

@Composable
fun RemoteScreen(state: TclRemoteViewModel.UiState, viewModel: TclRemoteViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.disconnect() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Disconnect")
            }
            Column(Modifier.weight(1f)) {
                Text(state.deviceName ?: "TV", style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(state.model, state.isOn?.let { if (it) "on" else "off" })
                        .joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Volume
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(onClick = { viewModel.sendKey("VOLUME_DOWN") }) {
                Icon(Icons.Default.VolumeDown, contentDescription = "Volume down")
            }
            val volumeLabel = buildString {
                append(state.volume?.let { "Vol ${it}" } ?: "Vol —")
                if (state.muted == true) append("  muted")
            }
            Text(volumeLabel, style = MaterialTheme.typography.titleMedium)
            FilledIconButton(onClick = { viewModel.sendKey("VOLUME_UP") }) {
                Icon(Icons.Default.VolumeUp, contentDescription = "Volume up")
            }
            IconButton(onClick = { viewModel.toggleMute() }) {
                Icon(Icons.Default.VolumeMute, contentDescription = "Mute")
            }
        }
        Spacer(Modifier.height(12.dp))

        // D-pad
        DPad(viewModel)
        Spacer(Modifier.height(12.dp))

        // Power / home / back
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { viewModel.power() }) { Icon(Icons.Default.PowerSettingsNew, null) }
            IconButton(onClick = { viewModel.sendKey("home") }) { Icon(Icons.Default.Home, null) }
            IconButton(onClick = { viewModel.sendKey("back") }) { Icon(Icons.Default.ArrowBack, null) }
        }
        Spacer(Modifier.height(16.dp))

        // Text input
        TextInput(viewModel)
        Spacer(Modifier.height(16.dp))

        // Voice push-to-talk
        VoiceButton(active = state.voiceActive, viewModel = viewModel)
        Spacer(Modifier.height(20.dp))

        // Apps
        Text("Apps", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(400.dp)
        ) {
            items(state.apps) { app ->
                AppTile(app.label, app.color) { viewModel.launchApp(app.id) }
            }
        }
    }
}

@Composable
private fun DPad(viewModel: TclRemoteViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { viewModel.sendKey("up") }) { Icon(Icons.Default.ArrowUpward, "Up") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.sendKey("left") }) { Icon(Icons.Default.ArrowBack, "Left") }
            Box(
                Modifier.size(72.dp).padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(onClick = { viewModel.sendKey("ok") }, modifier = Modifier.size(64.dp)) {
                    Text("OK")
                }
            }
            IconButton(onClick = { viewModel.sendKey("right") }) { Icon(Icons.Default.ArrowForward, "Right") }
        }
        IconButton(onClick = { viewModel.sendKey("down") }) { Icon(Icons.Default.ArrowDownward, "Down") }
    }
}

@Composable
private fun TextInput(viewModel: TclRemoteViewModel) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Send text to TV") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { viewModel.sendText(text); text = "" },
            enabled = text.isNotEmpty()
        ) {
            Text("Send")
        }
    }
}

@Composable
private fun VoiceButton(active: Boolean, viewModel: TclRemoteViewModel) {
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        viewModel.startVoice()
                        tryAwaitRelease()
                        viewModel.stopVoice()
                    }
                )
            },
        onClick = {}
    ) {
        Icon(Icons.Default.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (active) "Listening..." else "Hold to speak")
    }
}

@Composable
private fun AppTile(label: String, color: Long, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val initial = label.take(1).uppercase()
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        Color(color),
                        MaterialTheme.shapes.medium
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(initial, color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
