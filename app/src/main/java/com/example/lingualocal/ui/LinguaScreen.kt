package com.example.lingualocal.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingualocal.AppState
import com.example.lingualocal.Project
import com.example.lingualocal.R
import com.example.lingualocal.Startup
import com.example.lingualocal.audio.PlayAll
import com.example.lingualocal.audio.SegmentAudio
import com.example.lingualocal.audio.TranslationVoice
import com.example.lingualocal.audio.VoiceEngine
import com.example.lingualocal.audio.VoiceRules
import com.example.lingualocal.data.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Root of the app. Wide landscape screens (tablet) get the sidebar with every project next to the open
 * conversation; phones and portrait tablets show the projects home, then the conversation full screen.
 */
@Composable
fun LinguaScreen(onToggleListening: () -> Unit) {
    val view by AppState.view.collectAsStateWithLifecycle()
    val dialogs = remember { ProjectDialogs() }
    val scope = rememberCoroutineScope()
    val nav = remember(onToggleListening, scope) { Nav(onToggleListening, scope) }

    BoxWithConstraints(Modifier.fillMaxSize().background(LL.Bg).safeDrawingPadding()) {
        val sidebar = maxWidth >= 1200.dp || (maxWidth >= 840.dp && maxWidth > maxHeight)
        if (sidebar) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(view?.id, nav, dialogs, Modifier.width(300.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(LL.Line))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val s = view
                    if (s == null) EmptyConversation(nav) else ConversationScreen(s, nav, showBack = false)
                }
            }
        } else {
            val s = view
            if (s == null) ProjectsHome(nav, dialogs) else ConversationScreen(s, nav, showBack = true)
        }
    }
    ProjectDialogsHost(dialogs, nav)
}

/**
 * Moving between projects. Listening keeps recording into its own project while the user looks at,
 * plays or asks about others; only the mic button or Quick start ends or moves a session.
 */
internal class Nav(private val startStop: () -> Unit, private val scope: CoroutineScope) {
    /** Playback belongs to the project on screen. */
    private fun leaveView() {
        PlayAll.stop()
        SegmentAudio.stop()
    }

    fun open(p: Project) {
        if (AppState.view.value?.id == p.id) return
        leaveView()
        ProjectStore.open(p)
    }

    fun close() {
        leaveView()
        ProjectStore.close()
    }

    /** The mic button of the project on screen: stops listening here, or starts here (ending a session in another project first). */
    fun toggleListening() {
        val here = AppState.view.value?.id ?: return
        if (AppState.liveId() == here) { startStop(); return }
        scope.launch {
            if (AppState.running.value) {
                startStop()
                withTimeoutOrNull(3_000) { AppState.running.first { !it } }
            }
            if (!AppState.running.value && AppState.view.value?.id == here) startStop()
        }
    }

    /** Stops listening wherever it is. */
    fun stopListening() {
        if (AppState.running.value) startStop()
    }

    /** One tap: a new project named by date, already listening. */
    fun quickStart() {
        leaveView()
        ProjectStore.open(ProjectStore.create(null))
        toggleListening()
    }
}

internal class ProjectDialogs {
    var naming by mutableStateOf(false)
    var renaming by mutableStateOf<Project?>(null)
    var deleting by mutableStateOf<Project?>(null)
}

@Composable
private fun ProjectDialogsHost(d: ProjectDialogs, nav: Nav) {
    if (d.naming) NameDialog("New project", "", onDismiss = { d.naming = false }) {
        d.naming = false
        nav.open(ProjectStore.create(it))
    }
    d.renaming?.let { p ->
        NameDialog("Rename project", p.name, onDismiss = { d.renaming = null }) {
            ProjectStore.rename(p, it)
            d.renaming = null
        }
    }
    d.deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { d.deleting = null },
            containerColor = LL.Surface,
            title = { Text("Delete \"${p.name}\"?", color = LL.Text) },
            text = { Text("All ${p.items} recordings and translations in this project are deleted from this device. This can’t be undone.", color = LL.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    // Listening in it stops first; listening in another project carries on.
                    if (AppState.liveId() == p.id) nav.stopListening()
                    if (AppState.view.value?.id == p.id) nav.close()
                    ProjectStore.delete(p)
                    d.deleting = null
                }) { Text("Delete", color = LL.Danger) }
            },
            dismissButton = { TextButton(onClick = { d.deleting = null }) { Text("Cancel", color = LL.Text) } },
        )
    }
}

@Composable
internal fun NameDialog(
    title: String, initial: String, onDismiss: () -> Unit,
    placeholder: String = "e.g. Landlord · lease renewal", hint: String = "Leave blank to name it by date",
    onDone: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LL.Surface,
        title = { Text(title, color = LL.Text) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                placeholder = { Text(placeholder, color = LL.Faint) },
                supportingText = { Text(hint, color = LL.Faint) },
                colors = fieldColors(),
                shape = RoundedCornerShape(14.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text("Save", color = LL.English, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = LL.Text) } },
    )
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LL.Text, unfocusedTextColor = LL.Text,
    focusedBorderColor = LL.English, unfocusedBorderColor = LL.LineStrong,
    cursorColor = LL.English,
    focusedContainerColor = LL.Bg, unfocusedContainerColor = LL.Bg,
)

/** The brand mark (two speech bubbles), cropped out of the adaptive-icon drawable. */
@Composable
internal fun LogoMark(size: Dp) {
    Box(Modifier.size(size).clipToBounds(), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 2.1f))
    }
}

// ---------- Projects home (phone, portrait tablet) ----------

@Composable
private fun ProjectsHome(nav: Nav, dialogs: ProjectDialogs) {
    val projects by AppState.projects.collectAsStateWithLifecycle()
    val running by AppState.running.collectAsStateWithLifecycle()
    val live by AppState.live.collectAsStateWithLifecycle()
    val recording = live.takeIf { running }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    if (settings) SettingsDialog { settings = false }
    val shown = projects.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        if (recording != null) RecordingElsewhere(recording) { nav.open(recording.project.value) }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)) {
                        LogoMark(32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("LinguaLocal", color = LL.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { searching = !searching; if (!searching) query = "" },
                            modifier = Modifier.clip(CircleShape).background(LL.Surface),
                        ) { Icon(Icons.Outlined.Search, contentDescription = "Search projects", tint = LL.Text) }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { settings = true }, modifier = Modifier.clip(CircleShape).background(LL.Surface)) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = LL.Text)
                        }
                    }
                }
                if (searching) item { SearchField(query) { query = it } }
                item { StartCard(onClick = nav::quickStart) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp, start = 4.dp)) {
                        SectionLabel("Recent", Modifier.weight(1f))
                        TextButton(onClick = { dialogs.naming = true }) {
                            Text("New project", color = LL.English, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (query.isNotBlank()) "No project matches \"$query\"."
                            else "No projects yet. A project keeps one situation together, like \"Landlord\" or \"Clinic visit\": every recording and translation, ready to play back.",
                            color = LL.Muted, fontSize = 14.sp, lineHeight = 21.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { p ->
                    SideRow(p, selected = false, live = p.id == recording?.id, onOpen = { nav.open(p) }, dialogs = dialogs)
                }
            }
        }
    }
}

@Composable
private fun StartCard(onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(LL.Surface)
            .border(1.dp, LL.LineStrong, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick).padding(top = 28.dp, bottom = 24.dp, start = 20.dp, end = 20.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
            Box(Modifier.size(128.dp).clip(CircleShape).background(LL.English.copy(alpha = 0.05f)))
            Box(Modifier.size(108.dp).clip(CircleShape).background(LL.English.copy(alpha = 0.12f)))
            Box(Modifier.size(88.dp).clip(CircleShape).background(LL.English), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = LL.Bg, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Start listening", color = LL.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Spacer(Modifier.height(16.dp))
        PairPicker()
    }
}

/** The language pair for the next new project. Both directions are detected on their own. */
@Composable
private fun PairPicker() {
    val picked by AppState.nextPair.collectAsStateWithLifecycle()
    val projects by AppState.projects.collectAsStateWithLifecycle()
    val current = picked ?: com.example.lingualocal.data.LangPair.of(projects.firstOrNull()?.pair)
    val (single, pairs) = com.example.lingualocal.data.LangPair.entries.partition { it.transcribeOnly }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(pairs, single).forEach { group ->
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(LL.Raised).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                group.forEach { p ->
                    val on = p == current
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                            .background(if (on) LL.Surface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { AppState.nextPair.value = p }.padding(horizontal = 14.dp),
                    ) {
                        if (p.transcribeOnly) {
                            Text(p.label, color = if (on) LL.lang(p.a) else LL.Faint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(" · no translation", color = LL.Faint, fontSize = 13.sp)
                        } else {
                            Text(p.a.uppercase(), color = if (on) LL.lang(p.a) else LL.Faint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(" ⇄ ", color = LL.Faint, fontSize = 13.sp)
                            Text(p.b.uppercase(), color = if (on) LL.lang(p.b) else LL.Faint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = LL.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, modifier = modifier)
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(LL.Surface).border(1.dp, LL.Line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = LL.Faint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search projects…", color = LL.Faint, fontSize = 14.sp)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = LL.Text, fontSize = 14.sp), cursorBrush = SolidColor(LL.English),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- Sidebar (tablet landscape) ----------

@Composable
private fun Sidebar(currentId: String?, nav: Nav, dialogs: ProjectDialogs, modifier: Modifier) {
    val projects by AppState.projects.collectAsStateWithLifecycle()
    val running by AppState.running.collectAsStateWithLifecycle()
    val live by AppState.live.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val shown = projects.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    val groups = shown.groupBy { ageGroup(it.created) }
    if (settings) SettingsDialog { settings = false }

    Column(modifier.background(LL.Side).padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            LogoMark(28.dp)
            Spacer(Modifier.width(10.dp))
            Text("LinguaLocal", color = LL.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { settings = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = LL.Muted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(LL.Surface)
                    .border(1.dp, LL.LineStrong, RoundedCornerShape(12.dp)).clickable { dialogs.naming = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, contentDescription = "New project", tint = LL.Text) }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(LL.English).clickable(onClick = nav::quickStart),
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = LL.Bg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Quick start", color = LL.Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        SearchField(query) { query = it }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            groups.forEach { (label, list) ->
                item(key = "h-$label") { SectionLabel(label, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)) }
                items(list, key = { it.id }) { p ->
                    val sel = p.id == currentId
                    SideRow(p, selected = sel, live = running && p.id == live?.id, onOpen = { nav.open(p) }, dialogs = dialogs)
                }
            }
            if (shown.isEmpty()) item {
                Text(if (query.isBlank()) "No projects yet." else "No match.", color = LL.Muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/** Recorded length: 04:12, or 1:04:12 past an hour. */
private fun length(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** A sidebar project: title, date and time underneath, length on the right. Long-press to rename or delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SideRow(p: Project, selected: Boolean, live: Boolean, onOpen: () -> Unit, dialogs: ProjectDialogs) {
    var menu by remember { mutableStateOf(false) }
    // Auto names carry their own date ("Untitled · 21 Nov 17:57"); the date line below already shows it.
    val title = if (p.name.startsWith("Untitled · ")) "Untitled" else p.name
    val date = remember(p.created) { java.text.SimpleDateFormat("d MMM yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(p.created)) }
    Box {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (selected) LL.Raised else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = LL.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = LL.Faint, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (live) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(LL.Live))
                    Spacer(Modifier.width(6.dp))
                    Text("Live", color = LL.Live, fontSize = 12.sp)
                    if (p.totalMs > 0) Text(" · ${length(p.totalMs)}", color = LL.Faint, fontSize = 12.sp)
                } else if (p.totalMs > 0) {
                    Text(length(p.totalMs), color = LL.Faint, fontSize = 12.sp)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LL.Raised) {
            DropdownMenuItem(text = { Text("Rename", color = LL.Text) }, onClick = { menu = false; dialogs.renaming = p })
            DropdownMenuItem(text = { Text("Delete", color = LL.Danger) }, onClick = { menu = false; dialogs.deleting = p })
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(LL.Line))
}

/** App settings: translation engines status and voice. */
@Composable
internal fun SettingsDialog(onClose: () -> Unit) {
    val speak by AppState.speakAloud.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LL.Surface)
                .border(1.dp, LL.LineStrong, RoundedCornerShape(24.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = LL.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = LL.Muted) }
            }
            SectionLabel("Translation")
            HybridStatusCard()
            SectionLabel("Voice")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speak translations", color = LL.Text, fontSize = 15.sp)
                    Text("Read each translation aloud as it arrives", color = LL.Muted, fontSize = 12.sp)
                }
                androidx.compose.material3.Switch(checked = speak, onCheckedChange = { AppState.speakAloud.value = it })
            }
            VoiceRules.DEFAULTS.keys.forEach { VoiceRow(it) }
            Column {
                Text("Device: free, instant", color = LL.Muted, fontSize = 12.sp)
                Text("Gemini: more natural, needs internet, paid", color = LL.Muted, fontSize = 12.sp)
            }
            if (version.isNotBlank()) Text("LinguaLocal $version", color = LL.Faint, fontSize = 12.sp)
        }
    }
}

/** Device or Gemini for one language. */
@Composable
private fun VoiceRow(lang: String) {
    val voices by AppState.voices.collectAsStateWithLifecycle()
    val device by androidx.compose.runtime.produceState<Boolean?>(null, lang) { value = TranslationVoice.phoneSpeaks(lang) }
    val noDevice = device == false
    val shown = if (noDevice) VoiceEngine.GEMINI else voices[lang]?.engine
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${com.example.lingualocal.data.Lang.name(lang)} voice", color = LL.Text, fontSize = 15.sp)
            if (noDevice) Text("No voice on this device", color = LL.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(LL.Raised).padding(3.dp).selectableGroup()) {
            VoiceEngine.entries.forEach { engine ->
                val on = shown == engine
                val enabled = engine == VoiceEngine.GEMINI || !noDevice
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(17.dp))
                        .background(if (on) LL.Control else LL.Raised)
                        .selectable(selected = on, enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton) {
                            AppState.voices.value = VoiceRules.pick(AppState.voices.value, lang, engine, System.currentTimeMillis())
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (engine == VoiceEngine.DEVICE) "Device" else "Gemini",
                        color = if (!enabled) LL.Faint.copy(alpha = 0.5f) else if (on) LL.Text else LL.Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(nav: Nav) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LogoMark(72.dp)
        Spacer(Modifier.height(16.dp))
        Text("Pick a project, or start listening", color = LL.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(LL.English).clickable(onClick = nav::quickStart).padding(horizontal = 20.dp),
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = null, tint = LL.Bg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Start listening", color = LL.Bg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        PairPicker()
    }
}

// ---------- Shared pieces ----------

/** Cloud (Gemini) vs on-device status, from the startup checks. */
@Composable
private fun hybridState(): Pair<Boolean, Boolean> {
    val steps by Startup.steps.collectAsStateWithLifecycle()
    val offline by AppState.offlineStatus.collectAsStateWithLifecycle()
    val online = steps.firstOrNull { it.id == "cloud" }?.detail?.startsWith("Online") == true
    return online to offline.startsWith("Ready")
}

@Composable
private fun HybridStatusCard() {
    val (online, device) = hybridState()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LL.Surface)
            .border(1.dp, LL.Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) LL.Ok else LL.Thai))
            Spacer(Modifier.width(8.dp))
            Text("Hybrid translation", color = LL.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        KeyValue("Cloud", if (online) "Gemini, online" else "Offline")
        KeyValue("Offline fallback", if (device) "On-device, ready" else "Preparing…")
    }
}

@Composable
internal fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, color = LL.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = LL.Text, fontSize = 12.sp, textAlign = TextAlign.End)
    }
}

internal fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

internal fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun dayStart(offsetDays: Int = 0): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_YEAR, offsetDays)
}.timeInMillis

private fun ageGroup(t: Long) = when {
    t >= dayStart() -> "Today"
    t >= dayStart(-6) -> "This week"
    else -> "Earlier"
}

private fun whenLabel(t: Long): String = when {
    t >= dayStart() -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    t >= dayStart(-6) -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
    else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
}

/** "Today 14:05", "Yesterday 09:12", "Sat 15 Nov 18:30". */
internal fun dayLabel(t: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    return when {
        t >= dayStart() -> "Today $time"
        t >= dayStart(-1) -> "Yesterday $time"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t)) + " $time"
    }
}
