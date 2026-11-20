package com.example.lingualocal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.ai.HybridAssistant
import com.example.lingualocal.ai.OfflineTranslator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Accent = Color(0xFF4FD1A5)
private val Muted = Color(0xFF8A93A3)
private val Surface = Color(0xFF181C23)

private val scheme: ColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00281C),
    background = Color(0xFF0F1115),
    surface = Surface,
    onSurface = Color(0xFFE8ECF2),
)

@Composable
fun LinguaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
fun LinguaScreen(onToggleListening: () -> Unit) {
    val running by AppState.running.collectAsStateWithLifecycle()
    val captions by AppState.captions.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(captions.size) {
        if (captions.isNotEmpty()) listState.animateScrollToItem(captions.lastIndex)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LinguaLocal", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("Thai ⇄ English, always listening", color = Muted, fontSize = 14.sp)
            }
            Button(
                onClick = onToggleListening,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFE5484D) else Accent,
                    contentColor = if (running) Color.White else Color(0xFF00281C),
                ),
            ) {
                Icon(if (running) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (running) "Stop" else "Start listening")
            }
        }

        Spacer(Modifier.height(8.dp))
        SpeakToggle()
        Spacer(Modifier.height(8.dp))
        RuntimePanel()
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (captions.isEmpty()) {
                item {
                    Text(
                        if (running) "Listening… speak Thai or English nearby." else "Tap Start listening. Translations appear here and are spoken aloud.",
                        color = Muted, modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(captions, key = { it.id }) { CaptionCard(it) }
        }

        Spacer(Modifier.height(8.dp))
        TypeToTranslate()
    }
}

@Composable
private fun SpeakToggle() {
    val speak by AppState.speakAloud.collectAsStateWithLifecycle()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface)
            .clickable { AppState.speakAloud.value = !speak }.padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Speak translations aloud", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
            Text(
                if (speak) "On · mic pauses while the app speaks (unless headphones are connected)"
                else "Off · text only, the mic never pauses",
                color = Muted, fontSize = 12.sp,
            )
        }
        Switch(
            checked = speak,
            onCheckedChange = { AppState.speakAloud.value = it },
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
private fun RuntimePanel() {
    val live by AppState.liveStatus.collectAsStateWithLifecycle()
    val vad by AppState.vadStatus.collectAsStateWithLifecycle()
    val nano by AppState.nanoStatus.collectAsStateWithLifecycle()
    val offline by AppState.offlineStatus.collectAsStateWithLifecycle()
    val prob by AppState.vadProbability.collectAsStateWithLifecycle()
    val level by AppState.micLevel.collectAsStateWithLifecycle()
    val speech by AppState.speechActive.collectAsStateWithLifecycle()
    val echo by AppState.echoGuard.collectAsStateWithLifecycle()
    val animProb by animateFloatAsState(prob, label = "vad")
    val animLevel by animateFloatAsState(level, label = "level")

    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (echo) Color(0xFFF5A524) else if (speech) Accent else Color(0xFF3A404C))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        echo -> "Paused while translation plays"
                        speech -> "Speech detected"
                        else -> "Silence"
                    },
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp,
                )
            }
            Meter("Mic", animLevel)
            Meter("Voice (Silero VAD)", animProb)
            StatusRow("Voice detector", vad)
            StatusRow("Interpreter · Gemini Live", live)
            StatusRow("Hybrid · Firebase AI Logic", nano)
            StatusRow("Offline translator", offline)
        }
    }
}

@Composable
private fun Meter(label: String, value: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(130.dp))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = Accent, trackColor = Color(0xFF2A303A),
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.width(170.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun CaptionCard(caption: Caption) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                timeFormat.format(Date(caption.time)) + if (caption.done) "" else " · live",
                color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            if (caption.heard.isNotBlank()) Text(caption.heard.trim(), color = Muted, fontSize = 15.sp)
            if (caption.translation.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(caption.translation.trim(), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            caption.explanation?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFC9D1DC), fontSize = 14.sp)
                Text(caption.explainSource.orEmpty(), color = Accent, fontSize = 11.sp)
            }
            if (caption.done && caption.explanation == null && caption.translation.isNotBlank()) {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        scope.launch {
                            val answer = runCatching { HybridAssistant.explain(caption.heard, caption.translation) }
                            AppState.updateCaption(caption.id) {
                                answer.fold(
                                    { a -> it.copy(explanation = a.text, explainSource = a.source) },
                                    { e -> it.copy(explanation = "Couldn't explain: ${e.message?.take(100)}", explainSource = "") },
                                )
                            }
                            loading = false
                        }
                    },
                ) { Text(if (loading) "Thinking…" else "What did they mean?") }
            }
        }
    }
}

@Composable
private fun TypeToTranslate() {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Column {
        if (result.isNotBlank()) {
            Text(
                result, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface)
                    .clickable { result = "" }.padding(12.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Type Thai or English (works offline)") },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val input = text
                    scope.launch {
                        result = runCatching { OfflineTranslator.translate(input) }
                            .getOrElse { "Offline translation failed: ${it.message}" }
                    }
                },
            ) { Text("Translate") }
        }
    }
}
