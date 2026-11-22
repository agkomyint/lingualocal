package com.example.lingualocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import kotlinx.coroutines.flow.first
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.lingualocal.audio.People
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.Config
import com.example.lingualocal.Project
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.ai.AskAssistant
import com.example.lingualocal.ai.HybridAssistant
import com.example.lingualocal.ai.OfflineTranslator
import com.example.lingualocal.audio.PlayAll
import com.example.lingualocal.audio.PlayMode
import com.example.lingualocal.audio.SegmentAudio
import com.example.lingualocal.data.Lang
import com.example.lingualocal.data.ProjectStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row of a project's transcript. */
private sealed interface TimelineRow {
    val key: Any
    data class Session(val number: Int, val start: Long, val items: List<Caption>) : TimelineRow { override val key get() = "s-$start" }
    data class Gap(val id: Long, val ms: Long) : TimelineRow { override val key get() = "gap-$id" }
    data class Piece(val caption: Caption) : TimelineRow { override val key get() = caption.id }
}

private fun timeline(captions: List<Caption>): List<TimelineRow> = buildList {
    val bySession = captions.groupBy { it.session }
    var prev: Caption? = null
    var number = 0
    for (c in captions) {
        if (prev == null || prev.session != c.session) {
            add(TimelineRow.Session(++number, c.session, bySession[c.session].orEmpty()))
        } else {
            // Inside a session the transcript also marks the quiet stretches between pieces of speech.
            val gap = c.time - (prev.time + prev.durationMs)
            if (gap >= SILENCE_GAP_MS) add(TimelineRow.Gap(c.id, gap))
        }
        add(TimelineRow.Piece(c))
        prev = c
    }
}

private const val SILENCE_GAP_MS = 2_000L
private val ASK_WIDTH = 380.dp
private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The project on screen, for the pieces of the conversation screen. */
private val LocalSpace = androidx.compose.runtime.staticCompositionLocalOf<com.example.lingualocal.ProjectSpace> { error("no project") }

/** Which piece the per-bubble play button started, so only that bubble shows "stop". */
private var replayingId by mutableStateOf<Long?>(null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(space: com.example.lingualocal.ProjectSpace, nav: Nav, showBack: Boolean) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSpace provides space) { ConversationContent(space, nav, showBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationContent(space: com.example.lingualocal.ProjectSpace, nav: Nav, showBack: Boolean) {
    val project by space.project.collectAsStateWithLifecycle()
    val anyRunning by AppState.running.collectAsStateWithLifecycle()
    val live by AppState.live.collectAsStateWithLifecycle()
    // Listening into this project. Listening into another one goes on in the background (bar at the top).
    val running = anyRunning && live?.id == space.id
    val elsewhere = live.takeIf { anyRunning && it != null && it.id != space.id }
    val captions by space.captions.collectAsStateWithLifecycle()
    val playing by PlayAll.current.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val rows = remember(captions) { timeline(captions) }
    var renaming by remember { mutableStateOf(false) }
    var engines by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    // Find & jump (long meetings): search, one person's pieces, or a time.
    var finding by remember(project.id) { mutableStateOf(false) }
    var query by remember(project.id) { mutableStateOf("") }
    var person by remember(project.id) { mutableStateOf<Int?>(null) }
    var mineOnly by remember(project.id) { mutableStateOf(false) }
    val clock = remember(captions.size, query) { clockHits(captions, query) }
    val words = remember(query, clock) { if (clock != null) emptyList() else searchWords(query) }
    val filtering = finding && (words.isNotEmpty() || person != null || mineOnly)
    val results = remember(captions, words, person, mineOnly, filtering) {
        if (filtering) captions.filter { it.status != SegmentStatus.RECORDING && matches(it, words, person, mineOnly) } else emptyList()
    }
    val marks = remember(captions.size, finding) { if (finding) timeMarks(captions) else emptyList() }
    val people = remember(captions.size, finding) { if (finding) peopleIn(captions) else emptyList() }
    val hasMine = remember(captions.size, finding) { finding && captions.any { it.mine } }
    val resultsState = rememberLazyListState()
    /** Piece to bring into view once the whole conversation is showing again. */
    var jumpTo by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(project.id) { AskAssistant.forProject(project.id) }
    LaunchedEffect(project.id) {
        val total = androidx.compose.runtime.snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(total - 1)
    }
    if (showBack) BackHandler(onBack = nav::close)
    if (finding) BackHandler { finding = false }

    LaunchedEffect(captions.size) {
        val total = listState.layoutInfo.totalItemsCount
        // Follow new pieces only when already at the end, so reading back in a long meeting isn't interrupted.
        val lastSeen = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (captions.isNotEmpty() && total > 0 && playing == null && !filtering && (lastSeen >= total - 4 || total <= 2)) {
            listState.animateScrollToItem(total - 1)
        }
    }
    LaunchedEffect(jumpTo, filtering) {
        val id = jumpTo ?: return@LaunchedEffect
        if (filtering) return@LaunchedEffect
        val i = rows.indexOfFirst { it is TimelineRow.Piece && it.caption.id == id }
        if (i >= 0) listState.scrollToItem((i - 1).coerceAtLeast(0))
        jumpTo = null
    }
    fun jump(m: TimeMark) {
        val i = if (m.time == Long.MAX_VALUE) rows.lastIndex else {
            rows.indexOfFirst { (it is TimelineRow.Piece && it.caption.time >= m.time) || (m.session && it is TimelineRow.Session && it.start == m.time) }
        }
        if (i >= 0) scope.launch { listState.scrollToItem(if (m.session) i else (i - 1).coerceAtLeast(0)) }
    }
    // Play all: keep the piece being played on screen.
    LaunchedEffect(playing) {
        val i = rows.indexOfFirst { it is TimelineRow.Piece && it.caption.id == playing }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(LL.Bg).imePadding()) {
        // Ask sits beside the transcript when there's room, otherwise it's a sheet over it.
        val askSide = maxWidth >= 900.dp
        val wide = (if (asking && askSide) maxWidth - ASK_WIDTH else maxWidth) >= 640.dp
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxSize()) {
            if (elsewhere != null) RecordingElsewhere(elsewhere) { nav.open(elsewhere.project.value) }
            ConversationHeader(project, running, showBack, nav, onRename = { renaming = true }, onEngines = { engines = true }, captions = captions, playing = playing != null, asking = asking, onAsk = { asking = !asking }, finding = finding, onFind = { finding = !finding })
            if (finding) FindBar(
                query = query, onQuery = { query = it },
                person = person, onPerson = { person = it },
                mine = mineOnly, onMine = { mineOnly = it },
                people = people, hasMine = hasMine,
                marks = marks, onJump = ::jump,
                results = if (filtering) results.size else null,
                onClose = { finding = false },
                clock = clock,
                onClockGo = { hit -> finding = false; jumpTo = pieceAt(captions, hit.time)?.id },
                onClockPlay = { hit ->
                    val p = pieceAt(captions, hit.time)
                    finding = false
                    if (p != null) { jumpTo = p.id; PlayAll.start(captions, fromId = p.id) }
                },
            )
            BufferBanner()
            BatteryBanner(running)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = 860.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = if (wide) 24.dp else 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (captions.isEmpty()) item { EmptyTranscript(running) }
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is TimelineRow.Session -> SessionDivider(row)
                            is TimelineRow.Gap -> GapDivider(row.ms)
                            is TimelineRow.Piece -> Bubble(row.caption, playing = row.caption.id == playing, wide = wide)
                        }
                    }
                }
                if (filtering) Box(Modifier.fillMaxSize().background(LL.Bg), contentAlignment = Alignment.TopCenter) { LazyColumn(
                    state = resultsState,
                    modifier = Modifier.widthIn(max = 860.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = if (wide) 24.dp else 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.id }) { c ->
                        Column {
                            GoToRow(c.time) { jumpTo = c.id; finding = false }
                            Bubble(c, playing = c.id == playing, wide = wide)
                        }
                    }
                } }
            }
            if (!filtering) LatestButton(listState, rows.size)
            if (playing != null) PlayerBar(captions, wide)
            InputBar(running, elsewhere, nav::toggleListening, wide)
        }
        if (asking && askSide) {
            Box(Modifier.width(1.dp).fillMaxSize().background(LL.Line))
            AskPanel(onClose = { asking = false }, modifier = Modifier.width(ASK_WIDTH).fillMaxSize())
        }
        }
        if (asking && !askSide) {
            ModalBottomSheet(
                onDismissRequest = { asking = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = LL.Surface,
                dragHandle = null,
            ) { AskPanel(onClose = { asking = false }, modifier = Modifier.fillMaxWidth().fillMaxSize(0.92f).imePadding()) }
        }
    }

    if (renaming) NameDialog("Rename project", project.name, onDismiss = { renaming = false }) {
        ProjectStore.rename(project, it)
        renaming = false
    }
    if (engines) {
        ModalBottomSheet(
            onDismissRequest = { engines = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LL.Surface,
        ) { EnginePanel() }
    }
}

// ---------- Header ----------

@Composable
private fun ConversationHeader(
    project: Project, running: Boolean, showBack: Boolean, nav: Nav,
    onRename: () -> Unit, onEngines: () -> Unit,
    captions: List<Caption>, playing: Boolean, asking: Boolean, onAsk: () -> Unit,
    finding: Boolean, onFind: () -> Unit,
) {
    val playable = captions.count { it.done && (it.heard.isNotBlank() || it.turns.isNotEmpty()) }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (showBack) 4.dp else 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (showBack) {
                IconButton(onClick = nav::close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "All projects", tint = LL.Text) }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(project.name, color = LL.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (running) LL.Live else LL.Idle))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "Listening · ${elapsed(running)}" else "Not listening", color = if (running) LL.Muted else LL.Faint, fontSize = 12.sp)
                }
            }
            HeaderIcon(androidx.compose.material.icons.Icons.Outlined.Search, "Find and jump to a time", active = finding, onClick = onFind)
            Spacer(Modifier.width(8.dp))
            HeaderIcon(Icons.Outlined.Tune, "Engines and diagnostics", onClick = onEngines)
            Spacer(Modifier.width(8.dp))
            PairSwitch(project)
            Spacer(Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (asking) LL.Selected else LL.Raised)
                    .border(1.dp, if (asking) LL.MineLine else LL.LineStrong, RoundedCornerShape(20.dp)).clickable(onClick = onAsk)
                    .padding(start = 10.dp, end = 14.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LL.English, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ask", color = LL.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (playable > 0 && !playing) {
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(LL.Raised)
                        .border(1.dp, LL.LineStrong, RoundedCornerShape(20.dp)).clickable { PlayAll.start(captions) }
                        .padding(start = 10.dp, end = 14.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = LL.Text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play all", color = LL.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LL.Line))
    }
}

/** The project's language pair; tap to switch. New pieces use the new pair. */
@Composable
private fun PairSwitch(project: Project) {
    val pair = com.example.lingualocal.data.LangPair.of(project.pair)
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(LL.Raised)
                .border(1.dp, LL.LineStrong, RoundedCornerShape(20.dp)).clickable { open = true }.padding(horizontal = 12.dp),
        ) {
            Text(pair.a.uppercase(), color = LL.lang(pair.a), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (!pair.transcribeOnly) {
                Text(" ⇄ ", color = LL.Faint, fontSize = 13.sp)
                Text(pair.b.uppercase(), color = LL.lang(pair.b), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LL.Raised) {
            com.example.lingualocal.data.LangPair.entries.forEach { p ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.label, color = LL.Text, fontSize = 15.sp, fontWeight = if (p == pair) FontWeight.SemiBold else FontWeight.Normal)
                            Text(
                                when { p.transcribeOnly -> "No translation · needs internet"; p.worksOffline -> "Works offline too"; else -> "Needs internet" },
                                color = LL.Faint, fontSize = 12.sp,
                            )
                        }
                    },
                    trailingIcon = { if (p == pair) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = LL.English, modifier = Modifier.size(18.dp)) },
                    onClick = { open = false; if (p != pair) com.example.lingualocal.data.ProjectStore.setPair(project, p) },
                )
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (active) LL.Selected else LL.Surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (active) LL.English else LL.Muted, modifier = Modifier.size(20.dp)) }
}

/** Listening goes on off screen (another project or the projects home): where, for how long, and the way back. */
@Composable
internal fun RecordingElsewhere(live: com.example.lingualocal.ProjectSpace, onGoBack: () -> Unit) {
    val p by live.project.collectAsStateWithLifecycle()
    val name = if (p.name.startsWith("Untitled · ")) "Untitled" else p.name
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(LL.Surface).clickable(onClick = onGoBack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(LL.Live))
        Spacer(Modifier.width(10.dp))
        Text(
            "Recording in $name · ${elapsed(true)}", color = LL.Text, fontSize = 14.sp,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text("Go back", color = LL.English, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(LL.Line))
}

/** mm:ss since the current listening session started, ticking once a second. */
@Composable
private fun elapsed(running: Boolean): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val start = AppState.session
    if (!running || start <= 0) return "0:00"
    val s = ((now - start) / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Speech saved while Gemini is unreachable, so nothing disappears silently. */
@Composable
private fun BufferBanner() {
    val captions by LocalSpace.current.captions.collectAsStateWithLifecycle()
    val buffered by AppState.bufferedBytes.collectAsStateWithLifecycle()
    val live by AppState.liveStatus.collectAsStateWithLifecycle()
    val waiting = captions.count { it.status == SegmentStatus.QUEUED || it.status == SegmentStatus.TRANSLATING }
    val bufferedSec = buffered / (2f * Config.MIC_SAMPLE_RATE)
    val offline = !live.startsWith("Live")
    if (bufferedSec < 1f && !(offline && waiting > 0)) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(LL.Thai.copy(alpha = 0.10f)).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = LL.Thai, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "%.0f s of speech is saved and waiting. It's sent as soon as Gemini reconnects.".format(bufferedSec),
            color = LL.Thai, fontSize = 13.sp,
        )
    }
}

/** The piece being spoken at [time], or the first one after it. */
private fun pieceAt(captions: List<Caption>, time: Long): Caption? =
    captions.lastOrNull { it.time <= time && time < it.time + it.durationMs + 1_000 } ?: captions.firstOrNull { it.time >= time }

/** Shown when reading back in a long conversation: one tap back to the newest piece. */
@Composable
private fun LatestButton(state: androidx.compose.foundation.lazy.LazyListState, total: Int) {
    val behind by remember(total) {
        androidx.compose.runtime.derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            total > 0 && last < total - 3
        }
    }
    if (!behind) return
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(LL.Raised)
                .border(1.dp, LL.LineStrong, RoundedCornerShape(18.dp))
                .clickable { scope.launch { state.scrollToItem(total - 1) } }.padding(horizontal = 14.dp),
        ) {
            Text("↓  Latest", color = LL.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Hidden for the rest of the app run once dismissed. */
private var batteryHintDismissed by mutableStateOf(false)

/**
 * While listening: if the system may stop the app in the background (battery saver; Xiaomi is strict),
 * offers to turn that off so a long meeting isn't cut. Shown until allowed or dismissed.
 */
@Composable
private fun BatteryBanner(running: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pm = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var allowed by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(running) {
        allowed = pm?.isIgnoringBatteryOptimizations(context.packageName) != false
        onPauseOrDispose { }
    }
    if (!running || allowed || batteryHintDismissed) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(LL.Thai.copy(alpha = 0.10f)).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = LL.Thai, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "For long meetings, let LinguaLocal run without battery limits so the phone doesn't stop it.",
            color = LL.Thai, fontSize = 13.sp, modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = {
            runCatching {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                )
            }
        }) { Text("Allow", color = LL.Thai, fontWeight = FontWeight.SemiBold) }
        androidx.compose.material3.TextButton(onClick = { batteryHintDismissed = true }) { Text("Not now", color = LL.Muted) }
    }
}

@Composable
private fun EmptyTranscript(running: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMark(56.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (running) "Listening… speak ${LocalSpace.current.pair.label.replace(" ⇄ ", " or ").removeSuffix(" only")} nearby." else "Tap listen, or type below.",
            color = LL.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text("Everything heard or typed here is saved in this project.", color = LL.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ---------- Dividers ----------

@Composable
private fun SessionDivider(row: TimelineRow.Session) {
    val end = row.items.maxOfOrNull { it.time + it.durationMs } ?: row.start
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.weight(1f).height(1.dp).background(LL.LineStrong))
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Session ${row.number}", color = LL.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${dayLabel(row.start)} – ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(end))} · ${duration(end - row.start)}",
                color = LL.Muted, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(LL.Raised)
                .clickable { PlayAll.start(row.items) }.padding(start = 6.dp, end = 10.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = LL.Text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(2.dp))
            Text("Play", color = LL.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(LL.LineStrong))
    }
}

@Composable
private fun GapDivider(ms: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        DashedLine(Modifier.weight(1f))
        Text(
            "  ${if (ms < 60_000) "${ms / 1000} s" else duration(ms)} quiet  ",
            color = LL.Faint, fontSize = 12.sp,
        )
        DashedLine(Modifier.weight(1f))
    }
}

@Composable
private fun DashedLine(modifier: Modifier) {
    Canvas(modifier.height(1.dp)) {
        drawLine(LL.LineStrong, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = size.height.coerceAtLeast(1f),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
}

// ---------- Bubble ----------

@Composable
private fun Bubble(caption: Caption, playing: Boolean, wide: Boolean) {
    val heardLang = caption.lang.ifBlank { Lang.of(caption.heard.ifBlank { caption.preview }) }
    val silent = caption.status == SegmentStatus.NO_SPEECH || caption.status == SegmentStatus.SKIPPED

    if (silent) {
        // Pieces with no words stay in the record, but quietly.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(clock.format(Date(caption.time)), color = LL.Faint, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (caption.status == SegmentStatus.NO_SPEECH) "No words · %.0f s".format(caption.durationMs / 1000f)
                else caption.note ?: "Noise · skipped on device",
                color = LL.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    // Everything reads top to bottom in one column, like a transcript. Your own pieces get a thin blue edge.
    val mine = caption.mine
    Column(Modifier.fillMaxWidth()) {
        BubbleHeader(caption, heardLang)
        Spacer(Modifier.height(6.dp))
        val shape = RoundedCornerShape(16.dp)
        val ring = when {
            playing -> LL.English
            caption.status == SegmentStatus.RECORDING -> LL.Thai.copy(alpha = 0.5f)
            else -> LL.Line
        }
        val edge = LL.English
        Column(
            Modifier.fillMaxWidth().clip(shape).background(LL.Surface)
                .drawBehind { if (mine) drawRect(edge, size = Size(3.dp.toPx(), size.height)) }
                .border(BorderStroke(if (playing) 2.dp else 1.dp, ring), shape)
                .padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
        ) { BubbleBody(caption, heardLang, playing) }
    }
}

@Composable
private fun BubbleHeader(caption: Caption, heardLang: String) {
    val names by People.names.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val person = caption.speaker.takeIf { !caption.mine }
    if (renaming && person != null) PersonDialog(person) { renaming = false }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        val (avatar, color) = when {
            caption.mine -> "" to LL.English
            person != null -> (names[person]?.takeIf { it.isNotBlank() }?.take(1)?.uppercase() ?: "$person") to LL.Thai
            else -> "•" to LL.Muted
        }
        Box(Modifier.size(28.dp).clip(CircleShape).background(LL.Raised), contentAlignment = Alignment.Center) {
            // You: keyboard when typed, mic when spoken.
            if (caption.mine) Icon(if (caption.audioFile == null) Icons.Outlined.Keyboard else Icons.Outlined.Mic, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
            else Text(avatar, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when { caption.mine -> "You"; person != null -> People.label(person, names); else -> "Voice" },
            color = LL.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            // Tap a person to give them a name; it shows on all their cards in this project.
            modifier = if (person != null) Modifier.clickable { renaming = true } else Modifier,
        )
        Spacer(Modifier.width(8.dp))
        Text(clock.format(Date(caption.time)), color = LL.Faint, fontSize = 12.sp)
        if (heardLang.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            val pair = LocalSpace.current.pair
            val to = when {
                caption.textOnly || (!caption.done && pair.transcribeOnly) -> ""
                caption.done -> Lang.target(heardLang, caption.translation.ifBlank { caption.turns.firstOrNull()?.translation.orEmpty() })
                else -> Lang.other(heardLang, pair)
            }
            LangPill(heardLang, to)
        }
    }
}

/** Who said this turn: the project's person (tap to name them), or Gemini's A/B when not matched by voice. */
@Composable
private fun TurnLabel(t: com.example.lingualocal.Turn) {
    val names by People.names.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val p = t.person
    Text(
        if (p != null) People.label(p, names) else t.speaker,
        color = LL.Thai, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = if (p != null) Modifier.clickable { renaming = true } else Modifier,
    )
    if (renaming && p != null) PersonDialog(p) { renaming = false }
}

@Composable
private fun PersonDialog(n: Int, onDismiss: () -> Unit) {
    NameDialog(
        "Name ${People.label(n)}", People.names.value[n].orEmpty(), onDismiss,
        placeholder = "e.g. Khun Somchai", hint = "Shown on all of this person's cards in this project",
    ) { People.rename(n, it); onDismiss() }
}

/** Reads [text] (a translation, or the words of a piece without one) aloud in its language. Tap again to stop. */
@Composable
private fun SayButton(text: String, key: String, label: Boolean = true, saved: Boolean = false, own: Boolean = false) {
    val saying by PlayAll.saying.collectAsStateWithLifecycle()
    val on = saying == key
    val lang = Lang.of(text)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) LL.lang(lang).copy(alpha = 0.2f) else LL.Control)
            .clickable { PlayAll.say(text, key) }.padding(start = 8.dp, end = if (label) 10.dp else 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(
            if (on) Icons.Filled.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = when {
                on -> "Stop"
                own -> "Read the ${Lang.name(lang)} text aloud"
                else -> "Speak the ${Lang.name(lang)} translation"
            },
            tint = LL.lang(lang), modifier = Modifier.size(18.dp),
        )
        if (label) {
            Spacer(Modifier.width(4.dp))
            Text(lang.uppercase(), color = LL.lang(lang), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (saved && !on) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.DownloadDone, contentDescription = "Voice saved, plays offline", tint = LL.Faint, modifier = Modifier.size(14.dp))
        }
    }
}

/** Copies the words of a piece that has no translation. */
@Composable
private fun CopyButton(caption: Caption) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val copy = {
        val text = if (caption.turns.isNotEmpty()) caption.turns.joinToString("\n") { it.heard.trim() } else caption.heard.trim()
        val copied = runCatching {
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("LinguaLocal", text))
        }.isSuccess
        // Android 13+ confirms a copy by itself.
        if (!copied || android.os.Build.VERSION.SDK_INT < 33) {
            android.widget.Toast.makeText(context, if (copied) "Copied" else "Couldn't copy", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(LL.Control).clickable { copy() }
            .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy the text", tint = LL.Text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Copy", color = LL.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LangPill(from: String, to: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(LL.Raised).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(from.uppercase(), color = LL.lang(from), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (to.isNotBlank()) {
            Text(" → ", color = LL.Faint, fontSize = 11.sp)
            Text(to.uppercase(), color = LL.lang(to), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BubbleBody(caption: Caption, heardLang: String, playing: Boolean) {
    val space = LocalSpace.current
    val scope = rememberCoroutineScope()
    var explaining by remember { mutableStateOf(false) }
    val own = caption.textOnly
    val writing = space.pair.transcribeOnly

    if (caption.turns.isNotEmpty()) {
        // More than one voice inside this piece: who said what (A, B are per piece).
        caption.turns.forEachIndexed { i, t ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Column {
                TurnLabel(t)
                Column {
                    if (own) Text(t.heard.trim(), color = LL.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 30.sp)
                    else Text(t.heard.trim(), color = LL.Muted, fontSize = 15.sp, lineHeight = 22.sp)
                    if (t.translation.isNotBlank()) Text(t.translation.trim(), color = LL.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp)
                }
            }
        }
    } else if (own) {
        // Burmese stacks above and below the line, so it gets more height than a translation.
        Text(caption.heard.trim(), color = LL.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 31.sp)
    } else {
        val original = caption.heard.trim().ifBlank { if (!caption.done) caption.preview.trim() else "" }
        if (original.isNotBlank()) {
            Text(original, color = LL.Muted, fontSize = 15.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (caption.mine) LL.MineLine else LL.Line))
            Spacer(Modifier.height(10.dp))
        }
        when {
            caption.translation.isNotBlank() ->
                Text(caption.translation.trim(), color = LL.Text, fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp)
            caption.status == SegmentStatus.QUEUED -> PendingLine(if (writing) "Waiting to write down" else "Waiting to translate", LL.Thai)
            caption.status == SegmentStatus.RECORDING -> PendingLine("Recording", LL.Thai)
            else -> PendingLine(if (writing) "Writing" else "Translating", LL.English)
        }
    }

    caption.note?.takeIf { !caption.done }?.let {
        Spacer(Modifier.height(10.dp))
        NoteBox(it, Icons.Outlined.Info, LL.Thai)
    }
    caption.correction?.let { CorrectionBox(caption.id, it) }
    caption.explanation?.let {
        Spacer(Modifier.height(10.dp))
        NoteBox(it + caption.explainSource?.takeIf { s -> s.isNotBlank() }?.let { s -> "\n— $s" }.orEmpty(), Icons.Outlined.AutoAwesome, LL.English)
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val replaying by AppState.replaying.collectAsStateWithLifecycle()
        caption.audioFile?.let { path ->
            val mineIsPlaying = (replaying && replayingId == caption.id) || playing
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (mineIsPlaying) LL.English else LL.Control).clickable {
                    if (replaying && replayingId == caption.id) SegmentAudio.stop()
                    else { replayingId = caption.id; SegmentAudio.play(path, caption.heard) }
                },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (mineIsPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = "Play original audio", tint = if (mineIsPlaying) LL.Bg else LL.Text, modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        if (caption.audioFile != null || caption.durationMs > 0) Text("%.1f s".format(caption.durationMs / 1000f), color = LL.Faint, fontSize = 12.sp)
        val spoken = caption.spokenText
        if (caption.done && spoken.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            SayButton(spoken, caption.id.toString(), saved = com.example.lingualocal.audio.TranslationVoice.saved(caption, spoken) != null, own = own)
        }
        if (own) {
            Spacer(Modifier.width(6.dp))
            CopyButton(caption)
        }
        if (caption.done && caption.explanation == null && caption.translation.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            Text(
                if (explaining) "Thinking…" else "What did they mean?",
                color = LL.English, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !explaining) {
                    explaining = true
                    scope.launch {
                        val answer = runCatching { HybridAssistant.explain(caption.heard, caption.translation) }
                        space.updateCaption(caption.id) {
                            answer.fold(
                                { a -> it.copy(explanation = a.text, explainSource = a.source) },
                                { e -> it.copy(explanation = "Couldn't explain: ${e.message?.take(100)}", explainSource = "") },
                            )
                        }
                        explaining = false
                    }
                }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(engineLabel(caption), color = LL.Faint, fontSize = 12.sp, maxLines = 1)
    }
}

private fun engineLabel(c: Caption): String = when {
    c.engine.startsWith("cloud:") -> "Gemini"
    c.engine.startsWith("device:") || c.offline -> "On device"
    else -> ""
}

@Composable
private fun PendingLine(text: String, color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1200)), label = "phase")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(Modifier.padding(end = 4.dp).size(6.dp).clip(CircleShape).background(color.copy(alpha = if (phase.toInt() == i) 1f else 0.3f)))
        }
        Spacer(Modifier.width(6.dp))
        Text("$text…", color = LL.Muted, fontSize = 15.sp)
    }
}

/** Ask fixed this piece after re-listening: what it said before, why, and a way back. */
@Composable
private fun CorrectionBox(id: Long, k: com.example.lingualocal.Revision) {
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LL.Bg)
            .border(1.dp, LL.Ok.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).clickable { open = !open }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = LL.Ok, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Corrected by Gemini · ${k.reason}", color = LL.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            Text(
                "Undo", color = LL.English, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AskAssistant.undoCorrection(id) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (open) {
            val before = k.heard.ifBlank { k.turns.joinToString(" / ") { "${it.speaker}: ${it.heard}" } }
            val beforeTr = k.translation.ifBlank {
                k.turns.filter { it.translation.isNotBlank() }.joinToString(" / ") { "${it.speaker}: ${it.translation}" }
            }
            Spacer(Modifier.height(8.dp))
            Text("Before", color = LL.Faint, fontSize = 12.sp)
            Text(before, color = LL.Faint, fontSize = 14.sp, lineHeight = 20.sp)
            if (beforeTr.isNotBlank()) Text(beforeTr, color = LL.Faint, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun NoteBox(text: String, icon: ImageVector, tint: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LL.Bg).padding(12.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = LL.Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

// ---------- Play all player ----------

/** Plays the project in order: original voices, or the translations spoken. The mic keeps listening. */
@Composable
private fun PlayerBar(captions: List<Caption>, wide: Boolean) {
    val mode by PlayAll.mode.collectAsStateWithLifecycle()
    val fast by PlayAll.fast.collectAsStateWithLifecycle()
    val position by PlayAll.position.collectAsStateWithLifecycle()
    val progress by animateFloatAsState(if (position.second > 0) position.first / position.second.toFloat() else 0f, label = "play")

    val shape = if (wide) RoundedCornerShape(24.dp) else RoundedCornerShape(24.dp, 24.dp, 0.dp, 0.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = if (wide) 24.dp else 0.dp).padding(bottom = if (wide) 10.dp else 0.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 860.dp).fillMaxWidth().clip(shape).background(LL.Surface).border(1.dp, LL.LineStrong, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = LL.English, trackColor = LL.Line, drawStopIndicator = {},
            )
            Spacer(Modifier.height(10.dp))
            val transport: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PlayAll.previous() }) { Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", tint = LL.Text) }
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(LL.English).clickable { PlayAll.stop() },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Stop, contentDescription = "Stop playing", tint = LL.Bg, modifier = Modifier.size(28.dp)) }
                    IconButton(onClick = { PlayAll.next() }) { Icon(Icons.Filled.SkipNext, contentDescription = "Next", tint = LL.Text) }
                }
            }
            val label = "Playing ${position.first} of ${position.second}"
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    transport()
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = LL.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    ModeSegments(mode)
                    Spacer(Modifier.width(8.dp))
                    SpeedPill(fast)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = LL.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    SpeedPill(fast)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ModeSegments(mode, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    transport()
                }
            }
        }
    }
}

@Composable
private fun ModeSegments(mode: PlayMode, modifier: Modifier = Modifier) {
    val project by LocalSpace.current.project.collectAsStateWithLifecycle()
    val spokenLabel = if (com.example.lingualocal.data.LangPair.of(project.pair).transcribeOnly) "Text read aloud" else "Translations spoken"
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(LL.Bg).padding(3.dp)) {
        listOf(PlayMode.ORIGINAL to "Original voices", PlayMode.TRANSLATION to spokenLabel).forEach { (m, text) ->
            val sel = m == mode
            Text(
                text, color = if (sel) LL.Text else LL.Muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(if (sel) LL.Selected else Color.Transparent)
                    .clickable { PlayAll.setMode(m) }.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SpeedPill(fast: Boolean) {
    Text(
        if (fast) "1.5×" else "1×", color = LL.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(LL.Raised).clickable { PlayAll.toggleSpeed() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

// ---------- Live level ----------

/** Live mic level, last few seconds. Blue = speech being recorded, amber = mic muted for our own audio. */
@Composable
private fun WaveBars(running: Boolean, modifier: Modifier) {
    val bars by AppState.waveform.collectAsStateWithLifecycle()
    Canvas(modifier) {
        val barW = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val n = (size.width / (barW + gap)).toInt().coerceAtLeast(1)
        val shown = bars.takeLast(n)
        val offset = n - shown.size
        val mid = size.height / 2
        for (i in 0 until n) {
            val b = shown.getOrNull(i - offset)
            val level = if (running && b != null) b.level else 0f
            val h = (level.coerceAtLeast(0.06f) * size.height).coerceAtMost(size.height)
            val color = when {
                !running || b == null -> LL.Idle
                b.muted -> LL.Thai
                b.speech -> LL.English
                else -> LL.Idle
            }
            drawRoundRect(color, Offset(i * (barW + gap), mid - h / 2), Size(barW, h), CornerRadius(barW / 2))
        }
    }
}

@Composable
private fun statusLine(running: Boolean): String {
    val held by AppState.pushToTalk.collectAsStateWithLifecycle()
    val ptt by AppState.pushToTalkStatus.collectAsStateWithLifecycle()
    val project by LocalSpace.current.project.collectAsStateWithLifecycle()
    val pair = com.example.lingualocal.data.LangPair.of(project.pair)
    return when {
        held && pair.transcribeOnly -> "Speak now… release to write it down"
        held && pair != com.example.lingualocal.data.LangPair.TH_EN -> "Speak now… release to translate"
        held -> ptt.ifBlank { "Speak English now… release to translate" }
        running && AppState.runningSource.value == com.example.lingualocal.AudioSource.PHONE -> "Phone audio · ${pair.label} · ${elapsed(running)}"
        running -> "Listening · ${pair.label}${if (pair.transcribeOnly) "" else " auto-detected"} · ${elapsed(running)}"
        else -> "Tap the mic to start listening"
    }
}

/** Hold-to-speak: your side, translated and read out on release. Only while listening to the mic. */
private fun Modifier.holdToSpeak(running: Boolean) = pointerInput(running) {
    if (!running || AppState.runningSource.value == com.example.lingualocal.AudioSource.PHONE) return@pointerInput
    detectTapGestures(onPress = {
        AppState.pushToTalk.value = true
        try { tryAwaitRelease() } finally { AppState.pushToTalk.value = false }
    })
}

// ---------- Input bar ----------

/**
 * The one input. Left: listen to the people around (or phone audio), live. Middle: type your side.
 * Right: hold to speak your side, or send what you typed. Everything lands in the same transcript.
 */
@Composable
private fun InputBar(running: Boolean, elsewhere: com.example.lingualocal.ProjectSpace?, onToggle: () -> Unit, wide: Boolean) {
    val space = LocalSpace.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val held by AppState.pushToTalk.collectAsStateWithLifecycle()
    val speak by AppState.speakAloud.collectAsStateWithLifecycle()
    val source by AppState.runningSource.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val send = {
        val input = text.trim()
        if (input.isNotBlank()) { text = ""; sendTyped(space, input, scope) }
    }
    val canHold = running && source != com.example.lingualocal.AudioSource.PHONE
    val status = statusLine(running)
    val project by space.project.collectAsStateWithLifecycle()
    val writing = com.example.lingualocal.data.LangPair.of(project.pair).transcribeOnly
    val typeLabel = if (writing) "Type to hear it" else "Type to translate"
    val hint = if (held || running) status else "$typeLabel, or tap to listen"
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = if (wide) 24.dp else 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(30.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth().clip(shape).background(LL.Surface)
                .border(1.dp, if (running) LL.English.copy(alpha = 0.45f) else LL.LineStrong, shape)
                .padding(6.dp),
        ) {
            ListenButton(running, onToggle)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (running && text.isEmpty()) WaveBars(running, Modifier.fillMaxWidth().height(26.dp).alpha(0.3f))
                if (text.isEmpty() && elsewhere != null) ElsewhereHint(elsewhere, wide, typeLabel)
                else if (text.isEmpty()) Text(
                    hint, color = if (held) LL.Text else if (running) LL.Muted else LL.Faint,
                    fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicTextField(
                    value = text, maxLines = 4,
                    // Enter sends (hardware keyboards and IMEs that insert a newline).
                    onValueChange = { v -> if (v.contains('\n')) { text = v.replace("\n", " "); send() } else text = v },
                    textStyle = TextStyle(color = LL.Text, fontSize = 16.sp), cursorBrush = SolidColor(LL.English),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
            if (text.isBlank()) {
                SourceToggle(running, onToggle)
                // Transcripts are never read out by themselves, so the switch would do nothing here.
                if (!writing) BarIcon(
                    if (speak) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                    if (speak) "Translations are read aloud" else "Captions only", active = speak,
                ) { AppState.speakAloud.value = !speak }
            }
            Spacer(Modifier.width(4.dp))
            if (text.isNotBlank()) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(LL.English).clickable { send() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = if (writing) "Read aloud" else "Translate", tint = LL.Bg, modifier = Modifier.size(20.dp)) }
            } else {
                // Your voice. Needs listening on (mic), because it goes through the same pipeline.
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (held) LL.Live else LL.Raised)
                        .then(
                            if (canHold) Modifier.holdToSpeak(running)
                            else Modifier.clickable {
                                android.widget.Toast.makeText(
                                    context,
                                    when {
                                        running -> "Hold to speak works with the mic, not phone audio"
                                        elsewhere != null -> "Recording in another project. Move it here to hold and speak"
                                        else -> "Tap listen first, then hold to speak"
                                    },
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Mic, contentDescription = "Hold to speak",
                        tint = if (held) LL.Bg else if (canHold) LL.Text else LL.Faint, modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** The empty input while another project records: says so, and that the listen button moves the recording here. */
@Composable
private fun ElsewhereHint(live: com.example.lingualocal.ProjectSpace, wide: Boolean, typeLabel: String) {
    val p by live.project.collectAsStateWithLifecycle()
    val name = if (p.name.startsWith("Untitled · ")) "Untitled" else p.name
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(LL.Live))
            Spacer(Modifier.width(6.dp))
            Text("Recording in $name", color = LL.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (wide) "$typeLabel, or tap to record here instead" else "Type, or tap to switch",
            color = LL.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Starts or stops live listening. Ear when idle; a stop square with a soft pulse while live. */
@Composable
private fun ListenButton(running: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.22f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (running) Box(Modifier.size(48.dp).scale(pulse).clip(CircleShape).background(LL.English.copy(alpha = 0.16f)))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (running) LL.English else LL.Raised).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (running) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(LL.Bg))
            else Icon(Icons.Outlined.Hearing, contentDescription = "Start listening", tint = LL.Text, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = if (active) LL.English else LL.Faint, modifier = Modifier.size(20.dp))
    }
}

/**
 * What listen hears: people in the room, or phone audio (YouTube, videos, other apps). Tapping it while listening
 * restarts on the other one (phone audio asks Android's permission first).
 */
@Composable
private fun SourceToggle(running: Boolean, toggle: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT < 29) return // phone audio needs Android 10
    val chosen by AppState.source.collectAsStateWithLifecycle()
    val now by AppState.runningSource.collectAsStateWithLifecycle()
    val phone = (if (running) now else chosen) == com.example.lingualocal.AudioSource.PHONE
    val scope = rememberCoroutineScope()
    BarIcon(
        if (phone) androidx.compose.material.icons.Icons.Outlined.PhoneAndroid else androidx.compose.material.icons.Icons.Outlined.Groups,
        if (phone) "Listening to phone audio" else "Listening to people in the room", active = phone,
    ) {
        AppState.source.value = if (phone) com.example.lingualocal.AudioSource.MIC else com.example.lingualocal.AudioSource.PHONE
        if (running) scope.launch {
            toggle() // stop
            kotlinx.coroutines.withTimeoutOrNull(3_000) { AppState.running.first { r -> !r } }
            toggle() // start on the new source
        }
    }
}

/**
 * A typed message becomes a piece of the project ("You"), translated and saved like spoken ones.
 * In a one-language project it is read aloud as typed instead.
 */
private fun sendTyped(space: com.example.lingualocal.ProjectSpace, input: String, scope: kotlinx.coroutines.CoroutineScope) {
    val pair = space.pair
    val listeningHere = AppState.liveId() == space.id
    val id = space.newCaption(mine = true, source = com.example.lingualocal.Caption.SOURCE_TYPED)
    // Not listening here: keep typed pieces in the latest session instead of starting one per message.
    val lastSession = space.captions.value.lastOrNull { it.id != id }?.session
    space.updateCaption(id) {
        it.copy(
            heard = input, lang = Lang.of(input),
            status = if (pair.transcribeOnly) SegmentStatus.DONE else SegmentStatus.TRANSLATING,
            session = if (listeningHere || lastSession == null) it.session else lastSession,
        )
    }
    if (pair.transcribeOnly) {
        // Typed here to be heard, so it plays whatever the speaker switch says; the voice is saved with the piece.
        PlayAll.say(input, id.toString())
        return
    }
    scope.launch(kotlinx.coroutines.NonCancellable) {
        runCatching {
            if (pair.worksOffline) OfflineTranslator.translate(input) else com.example.lingualocal.ai.CloudText.translate(input, pair)
        }.onSuccess { t ->
            space.updateCaption(id) {
                it.copy(
                    translation = t.trim(), status = SegmentStatus.DONE, note = null, offline = pair.worksOffline,
                    engine = if (pair.worksOffline) Lang.DEVICE_ENGINE else Lang.cloudEngine(Config.CLOUD_TEXT_MODEL),
                )
            }
            if (AppState.speakAloud.value) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { PlayAll.say(t.trim(), id.toString()) }
        }.onFailure { e ->
            space.updateCaption(id) {
                it.copy(status = SegmentStatus.QUEUED, note = if (pair.worksOffline) "Offline translation failed: ${e.message?.take(60)}" else "Couldn't translate (needs internet)")
            }
        }
    }
}

// ---------- Engines sheet (diagnostics) ----------

@Composable
private fun EnginePanel() {
    val live by AppState.liveStatus.collectAsStateWithLifecycle()
    val vad by AppState.vadStatus.collectAsStateWithLifecycle()
    val nano by AppState.nanoStatus.collectAsStateWithLifecycle()
    val gemma by AppState.gemmaStatus.collectAsStateWithLifecycle()
    val offline by AppState.offlineStatus.collectAsStateWithLifecycle()
    val offlineSpeech by AppState.offlineSpeechStatus.collectAsStateWithLifecycle()
    val speakerModel by AppState.speakerStatus.collectAsStateWithLifecycle()
    val prob by AppState.vadProbability.collectAsStateWithLifecycle()
    val speech by AppState.speechActive.collectAsStateWithLifecycle()
    val echo by AppState.echoGuard.collectAsStateWithLifecycle()
    val running by AppState.running.collectAsStateWithLifecycle()
    val captions by LocalSpace.current.captions.collectAsStateWithLifecycle()
    val gain by AppState.inputGain.collectAsStateWithLifecycle()
    val animProb by animateFloatAsState(prob, label = "vad")

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Engines", color = LL.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (echo) LL.Thai else if (speech) LL.English else LL.Idle))
            Spacer(Modifier.width(8.dp))
            Text(
                when { echo -> "Mic muted while the app plays audio"; speech -> "Speech detected"; else -> "Silence" },
                color = LL.Text, fontSize = 14.sp,
            )
        }
        WaveBars(running, Modifier.fillMaxWidth().height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice (Silero VAD)", color = LL.Muted, fontSize = 12.sp, modifier = Modifier.width(150.dp))
            LinearProgressIndicator(
                progress = { animProb.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = LL.English, trackColor = LL.Line, drawStopIndicator = {},
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Recording ${captions.count { it.status == SegmentStatus.RECORDING }}", LL.Live)
            Chip("Queued ${captions.count { it.status == SegmentStatus.QUEUED }}", LL.Thai)
            Chip("Translating ${captions.count { it.status == SegmentStatus.TRANSLATING }}", LL.English)
            Chip("Done ${captions.count { it.status == SegmentStatus.DONE }}", LL.Ok)
            Chip("Boost ×%.1f".format(gain), LL.Muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LL.Line))
        KeyValue("Voice detector", vad)
        KeyValue("Interpreter · Gemini Live", live)
        KeyValue("Hybrid · Firebase AI Logic", nano)
        KeyValue("Offline explain · Gemma", gemma)
        KeyValue("Offline translator", offline)
        KeyValue("Offline Thai speech", offlineSpeech)
        KeyValue("Who is speaking", speakerModel)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
