package com.example.lingualocal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lingualocal.Caption
import com.example.lingualocal.audio.People
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A place in the transcript to jump to: a session start or a quarter hour inside a session. */
internal data class TimeMark(val label: String, val time: Long, val session: Boolean)

private const val MARK_EVERY_MS = 15 * 60_000L

/** Session starts, then every quarter hour of long sessions (09:00, 09:15, 09:30…). */
internal fun timeMarks(captions: List<Caption>): List<TimeMark> {
    if (captions.isEmpty()) return emptyList()
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val day = SimpleDateFormat("d MMM", Locale.getDefault())
    val sessions = captions.groupBy { it.session }.values.toList()
    val days = sessions.map { day.format(Date(it.first().time)) }.distinct().size
    return buildList {
        sessions.forEachIndexed { i, s ->
            val start = s.first().time
            val end = s.last().time
            val label = (if (days > 1) day.format(Date(start)) + " " else "") + clock.format(Date(start))
            add(TimeMark(if (sessions.size > 1) "Session ${i + 1} · $label" else "Start · $label", start, true))
            var t = (start / MARK_EVERY_MS + 1) * MARK_EVERY_MS
            while (t < end) {
                add(TimeMark(clock.format(Date(t)), t, false))
                t += MARK_EVERY_MS
            }
        }
    }
}

/** People heard in the project (person numbers on cards and turns). */
internal fun peopleIn(captions: List<Caption>): List<Int> =
    captions.flatMapTo(sortedSetOf()) { c -> listOfNotNull(c.speaker) + c.turns.mapNotNull { it.person } }.toList()

/** Does the piece match the search words (all of them) and the chosen person? */
internal fun matches(c: Caption, words: List<String>, person: Int?, mine: Boolean): Boolean {
    if (mine && !c.mine) return false
    if (person != null && c.speaker != person && c.turns.none { it.person == person }) return false
    if (words.isEmpty()) return true
    val text = com.example.lingualocal.data.ProjectStore.searchText(c).lowercase()
    return words.all { text.contains(it) }
}

/** A moment the user typed ("8:35", "20:35", "8:35 pm") found inside a recorded session. */
internal data class ClockHit(val time: Long, val session: Int, val sessionStart: Long)

private val CLOCK = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a|p)?$""")

/**
 * If [query] is a time of day, the places in the recording at that time (one per session/day that covers it),
 * else null. Without am/pm, both 8:35 and 20:35 are tried.
 */
internal fun clockHits(captions: List<Caption>, query: String): List<ClockHit>? {
    val m = CLOCK.matchEntire(query.trim().lowercase()) ?: return null
    val (hs, ms, ampm) = m.destructured
    if (ms.isEmpty() && ampm.isEmpty()) return null // a lone number is a search word, not a time
    val h = hs.toInt(); val min = ms.ifEmpty { "0" }.toInt()
    if (h > 23 || min > 59) return null
    val hours = when {
        ampm.startsWith("p") -> if (h in 1..11) listOf(h + 12) else if (h == 12) listOf(12) else return null
        ampm.startsWith("a") -> if (h == 12) listOf(0) else if (h in 1..11) listOf(h) else return null
        h in 1..11 -> listOf(h, h + 12)
        else -> listOf(h)
    }
    val sessions = captions.groupBy { it.session }.values.toList()
    return buildList {
        sessions.forEachIndexed { i, s ->
            val from = s.first().time
            val to = s.maxOf { it.time + it.durationMs }
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = from }
            // Every day the session touches (a session can run past midnight).
            while (true) {
                for (hour in hours) {
                    val c = (cal.clone() as java.util.Calendar).apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, min)
                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    }
                    if (c.timeInMillis in from..to) add(ClockHit(c.timeInMillis, i + 1, from))
                }
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                if (cal.timeInMillis > to) break
            }
        }
    }.sortedBy { it.time }
}

internal fun searchWords(query: String) = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

/**
 * Find & jump: search what was said, show one person's pieces, or jump to a time, so long meetings don't
 * need scrolling.
 */
@Composable
internal fun FindBar(
    query: String, onQuery: (String) -> Unit,
    person: Int?, onPerson: (Int?) -> Unit,
    mine: Boolean, onMine: (Boolean) -> Unit,
    people: List<Int>, hasMine: Boolean,
    marks: List<TimeMark>, onJump: (TimeMark) -> Unit,
    results: Int?, onClose: () -> Unit,
    clock: List<ClockHit>? = null, onClockGo: (ClockHit) -> Unit = {}, onClockPlay: (ClockHit) -> Unit = {},
) {
    val names by People.names.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().background(LL.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(LL.Raised)
                    .border(1.dp, LL.LineStrong, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = LL.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search words, or type a time like 8:35", color = LL.Faint, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = onQuery, singleLine = true,
                        textStyle = TextStyle(color = LL.Text, fontSize = 15.sp),
                        cursorBrush = SolidColor(LL.English),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, contentDescription = "Clear", tint = LL.Muted,
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onQuery("") },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = "Close find", tint = LL.Text, modifier = Modifier.size(22.dp)) }
        }
        if (people.isNotEmpty() || hasMine) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FindChip("Everyone", person == null && !mine) { onPerson(null); onMine(false) }
                people.forEach { n -> FindChip(People.label(n, names), person == n) { onMine(false); onPerson(if (person == n) null else n) } }
                if (hasMine) FindChip("Me (push-to-talk)", mine) { onPerson(null); onMine(!mine) }
            }
        }
        if (clock != null) {
            Spacer(Modifier.height(10.dp))
            if (clock.isEmpty()) Text("Nothing was recorded at that time", color = LL.Muted, fontSize = 13.sp)
            val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
            clock.forEach { hit ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(fmt.format(Date(hit.time)), color = LL.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Session ${hit.session} · started ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(hit.sessionStart))}",
                            color = LL.Muted, fontSize = 12.sp,
                        )
                    }
                    FindChip("Go to", false, strong = true) { onClockGo(hit) }
                    Spacer(Modifier.width(8.dp))
                    FindChip("▶  Play from here", true) { onClockPlay(hit) }
                }
            }
        } else if (results != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (results == 0) "Nothing found" else "$results ${if (results == 1) "piece" else "pieces"} · tap “Go to” to see it in the conversation",
                color = LL.Muted, fontSize = 13.sp,
            )
        } else if (marks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("JUMP TO", color = LL.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                marks.forEach { m -> FindChip(m.label, false, strong = m.session) { onJump(m) } }
                FindChip("Latest", false, strong = true) { onJump(TimeMark("Latest", Long.MAX_VALUE, true)) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(LL.Line))
}

@Composable
private fun FindChip(text: String, selected: Boolean, strong: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.height(32.dp).clip(shape).background(if (selected) LL.Selected else LL.Raised)
            .border(1.dp, if (selected) LL.MineLine else LL.LineStrong, shape).clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (selected) LL.English else if (strong) LL.Text else LL.Muted, fontSize = 13.sp,
            fontWeight = if (selected || strong) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Above a search result: its time, and a button to see it in the whole conversation. */
@Composable
internal fun GoToRow(time: Long, onGo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            SimpleDateFormat("d MMM · HH:mm:ss", Locale.getDefault()).format(Date(time)),
            color = LL.Faint, fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        Text(
            "Go to →", color = LL.English, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onGo).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

