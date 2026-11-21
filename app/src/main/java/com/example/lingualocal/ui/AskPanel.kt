package com.example.lingualocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingualocal.ai.AskAssistant

private val suggestions = listOf(
    "Summarise this conversation",
    "What was their tone? Listen to the recordings",
    "Check the transcripts against the recordings and fix mistakes",
    "What should I reply in Thai?",
)

/** Chat with Gemini about the open project. It reads the transcript, re-listens to recordings and searches the web. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskPanel(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val messages by AskAssistant.messages.collectAsStateWithLifecycle()
    val busy by AskAssistant.busy.collectAsStateWithLifecycle()
    val activity by AskAssistant.activity.collectAsStateWithLifecycle()
    val liveSteps by AskAssistant.liveSteps.collectAsStateWithLifecycle()
    val online = remember(messages.size) { AskAssistant.online() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(messages.size, busy, liveSteps.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        AskAssistant.send(text)
        input = ""
    }

    Column(modifier.background(LL.Surface)) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(LL.Selected), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LL.English, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ask", color = LL.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (online) "Gemini · reads and listens to this project" else "Offline · needs internet",
                    color = if (online) LL.Faint else LL.Thai, fontSize = 12.sp,
                )
            }
            if (messages.isNotEmpty()) {
                IconButton(onClick = AskAssistant::clear, enabled = !busy) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "New chat", tint = LL.Muted)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close Ask", tint = LL.Muted) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LL.Line))

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column {
                        Text("Ask anything about this conversation", color = LL.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Gemini can read every piece, listen to the original voices again for tone and missed words, and look things up.",
                            color = LL.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            suggestions.forEach { s ->
                                Text(
                                    s, color = LL.EnglishSoft, fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(LL.Raised)
                                        .border(1.dp, LL.LineStrong, RoundedCornerShape(16.dp))
                                        .clickable(enabled = online && !busy) { send(s) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(messages.size) { i -> AskMessage(messages[i]) }
            if (busy) item {
                Column {
                    if (liveSteps.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            liveSteps.forEach { StepChip(it) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Activity(activity)
                }
            }
        }

        // Input
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(LL.Raised)
                    .border(1.dp, LL.LineStrong, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.isEmpty()) Text(if (online) "Ask about this conversation…" else "Needs internet", color = LL.Faint, fontSize = 15.sp)
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(color = LL.Text, fontSize = 15.sp),
                    cursorBrush = SolidColor(LL.English),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = input.isNotBlank() && !busy
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (canSend) LL.English else LL.Control)
                    .clickable(enabled = canSend) { send(input) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", tint = if (canSend) LL.Bg else LL.Faint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskMessage(m: AskAssistant.Msg) {
    if (m.role == AskAssistant.Role.USER) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text, color = LL.Text, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                    .background(LL.MineBubble).border(1.dp, LL.MineLine, RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        if (m.steps.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                m.steps.forEach { StepChip(it) }
            }
            Spacer(Modifier.height(8.dp))
        }
        SelectionContainer {
            Text(markdown(m.text), color = if (m.error) LL.Danger else LL.Text, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun StepChip(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(LL.Raised).padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(when { label.startsWith("Corrected") -> LL.Ok; label.startsWith("Listened") -> LL.Thai; else -> LL.English }))
        Spacer(Modifier.width(6.dp))
        Text(label, color = LL.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun Activity(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = LL.English, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(text.ifBlank { "Thinking…" }, color = LL.Muted, fontSize = 13.sp)
    }
}

/** Just enough Markdown for Gemini's answers: **bold**, bullets and headings. */
private fun markdown(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        val heading = line.trimStart().startsWith("#")
        if (heading) line = line.trimStart().trimStart('#').trimStart()
        val bullet = Regex("^(\\s*)[*-] ").find(line)
        if (bullet != null) line = bullet.groupValues[1] + "•  " + line.substring(bullet.range.last + 1)
        if (heading) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendBold(line) } else appendBold(line)
    }
}

private fun AnnotatedString.Builder.appendBold(line: String) {
    line.split("**").forEachIndexed { j, part ->
        if (j % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendItalic(part) } else appendItalic(part)
    }
}

private fun AnnotatedString.Builder.appendItalic(text: String) {
    text.split("*").forEachIndexed { j, part ->
        if (j % 2 == 1) withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(part) } else append(part)
    }
}
