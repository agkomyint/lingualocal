package com.example.lingualocal.data

import android.content.Context
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.Project
import com.example.lingualocal.ProjectSpace
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.Revision
import com.example.lingualocal.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Projects live in the app's private storage, one folder each, kept until the user deletes them:
 *   files/projects/<id>/project.json   name, dates, totals
 *   files/projects/<id>/pieces.db      every piece: what was heard, translation, explanation, speaker… ([PieceDb])
 *   files/projects/<id>/people.json    voices and names of the people heard
 *   files/projects/<id>/audio/         the recordings (AAC .m4a) and saved translation voices
 * (Older versions kept the pieces in captions.json; it is moved into pieces.db the first time the project opens.)
 */
object ProjectStore {
    private const val SAVE_EVERY_MS = 1_000L
    /** Long enough for pieces still translating after listening stopped (or waiting for internet a while). */
    private const val RELEASE_AFTER_MS = 5 * 60_000L
    private const val UNTITLED = "Untitled · "
    private const val TAG = "ProjectStore"
    /** Version of the saved format. 2 = every piece has lang/target/engine. */
    const val SCHEMA = 2
    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun init(context: Context) {
        root = File(context.filesDir, "projects").apply { mkdirs() }
        refresh()
    }

    /** Projects in memory by id: the one on screen and the one being recorded (often the same). */
    private val open = java.util.concurrent.ConcurrentHashMap<String, ProjectSpace>()

    /** The project [p] in memory, loaded from disk the first time. One object per project, so nothing writes it twice. */
    private fun space(p: Project): ProjectSpace = open[p.id] ?: run {
        val t0 = System.currentTimeMillis()
        val s = ProjectSpace(readMeta(dir(p.id)) ?: p)
        s.fill(synchronized(lock) { loadCaptions(s) })
        Log.d(TAG, "loaded ${s.captions.value.size} pieces of ${p.id} in ${System.currentTimeMillis() - t0} ms")
        open[p.id] = s
        s.saver = scope.launch { saveOnChange(s) }
        s
    }

    /** Saves [s] shortly after anything in it changes, while it is open. */
    @OptIn(FlowPreview::class)
    private suspend fun saveOnChange(s: ProjectSpace) {
        combine(s.project, s.captions) { p, c -> p to c }
            .conflate()
            .collect { (p, c) ->
                save(s, p, c)
                delay(SAVE_EVERY_MS) // long meetings: at most one write every few seconds
            }
    }

    /** A project that is neither on screen nor being recorded is written and let go, after a wait for late translations. */
    private fun release(s: ProjectSpace?) {
        if (s == null || inUse(s)) return
        scope.launch {
            delay(RELEASE_AFTER_MS)
            closeSpace(s)
        }
    }

    private fun inUse(s: ProjectSpace) = AppState.view.value === s || AppState.live.value === s

    /** Lets go of [s] unless it came back on screen or into recording meanwhile ([force]: deleting). */
    private fun closeSpace(s: ProjectSpace, write: Boolean = true, force: Boolean = false) = synchronized(lock) {
        if (open[s.id] !== s || (!force && inUse(s))) return@synchronized
        s.saver?.cancel()
        if (write) save(s, s.project.value, s.captions.value)
        open.remove(s.id, s)
        runCatching { s.db?.close() }
        s.db = null
        s.saved.clear()
        Log.d(TAG, "let go of ${s.id}")
    }

    /**
     * Listening starts in the project on screen: from now on new pieces go into it, wherever the user looks.
     * Null when no project is open.
     */
    fun startRecording(): ProjectSpace? {
        val s = AppState.view.value ?: return null
        val old = AppState.live.value
        AppState.live.value = s
        if (old !== s) release(old)
        return s
    }

    /** Listening stopped: the recorded project is let go once it's off screen (after the wait for late translations). */
    fun stopRecording(s: ProjectSpace) {
        if (AppState.live.value === s) AppState.live.value = null
        release(s)
    }

    fun refresh() {
        // Newest first by creation, so projects never jump around when opened or recorded into.
        AppState.projects.value = root.listFiles()?.mapNotNull { readMeta(it) }?.sortedByDescending { it.created }.orEmpty()
    }

    /** New project; a blank name becomes "Untitled · 21 Nov 14:05". */
    fun create(name: String?): Project {
        val now = System.currentTimeMillis()
        val p = Project(
            id = "p$now",
            name = name?.trim()?.takeIf { it.isNotEmpty() }
                ?: (UNTITLED + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(now))),
            created = now, lastUsed = now,
            autoNamed = name.isNullOrBlank(),
            // New projects use the language pair of the project used last.
            pair = AppState.nextPair.value?.code ?: AppState.projects.value.maxByOrNull { it.lastUsed }?.pair ?: LangPair.TH_EN.code,
        )
        synchronized(lock) { writeMeta(p) }
        refresh()
        return p
    }

    /** Shows project [p]. A project being recorded keeps recording. */
    fun open(p: Project) {
        val old = AppState.view.value
        if (old?.id == p.id) return
        val s = space(p)
        val opened = (readMeta(dir(p.id)) ?: s.project.value).copy(lastUsed = System.currentTimeMillis())
        synchronized(lock) { writeMeta(opened) }
        s.project.value = opened
        AppState.view.value = s
        // Let go in the background at that very moment: show a freshly loaded copy instead.
        if (open[p.id] !== s) AppState.view.value = space(p)
        release(old)
        refresh()
        Log.d(TAG, "showing ${p.id} \"${p.name}\" with ${s.captions.value.size} pieces")
    }

    /** Back to the projects list. A project being recorded keeps recording. */
    fun close() {
        val old = AppState.view.value
        AppState.view.value = null
        release(old)
        refresh()
    }

    /** Updates the copy in memory, if the project is open. */
    private fun changed(p: Project) { open[p.id]?.project?.value = p }

    fun rename(p: Project, name: String) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        val updated = (readMeta(dir(p.id)) ?: p).copy(name = n, autoNamed = false)
        synchronized(lock) { writeMeta(updated) }
        changed(updated)
        refresh()
    }

    /** Still "Untitled · date" and never named by the user: waiting for a title from what was said. */
    fun needsName(p: Project) = p.autoNamed && p.name.startsWith(UNTITLED)

    /** Sets a title made by the app, unless the user named the project meanwhile. */
    fun autoRename(id: String, name: String): Boolean {
        val updated = synchronized(lock) {
            val stored = readMeta(dir(id)) ?: return false
            if (!needsName(stored)) return false
            stored.copy(name = name).also { writeMeta(it) }
        }
        changed(updated)
        refresh()
        return true
    }

    /** Switches the language pair; pieces already recorded keep their languages. */
    fun setPair(p: Project, pair: LangPair) {
        val updated = (readMeta(dir(p.id)) ?: p).copy(pair = pair.code)
        synchronized(lock) { writeMeta(updated) }
        changed(updated)
        refresh()
    }

    /** Deletes the project with all its recordings. Listening in it must be stopped first. */
    fun delete(p: Project) {
        if (AppState.view.value?.id == p.id) AppState.view.value = null
        if (AppState.live.value?.id == p.id) AppState.live.value = null
        open[p.id]?.let { closeSpace(it, write = false, force = true) }
        synchronized(lock) { dir(p.id).deleteRecursively() }
        refresh()
    }

    /** Writes every open project now (when listening stops, when the app goes to the background). */
    fun flush() {
        open.values.forEach { s -> save(s, s.project.value, s.captions.value) }
    }

    /** Where recordings of project [s] go. */
    fun audioDir(s: ProjectSpace): File {
        check(dir(s.id).isDirectory) { "project ${s.id} was deleted" } // never brings a deleted project's folder back
        return File(dir(s.id), "audio").apply { mkdirs() }
    }

    private fun dir(id: String) = File(root, id)

    /** Folder of project [id] (null before init). */
    fun projectDir(id: String): File? = if (::root.isInitialized) File(root, id) else null

    /** Database of an open project, opened the first time it's needed. [ProjectSpace.saved] is each piece as last written. */
    private fun db(s: ProjectSpace): PieceDb = s.db ?: PieceDb.open(dir(s.id).apply { mkdirs() }).also { s.db = it }

    /**
     * Makes the open projects' folders complete on disk (pieces.db with nothing left in its log), e.g. before
     * copying or uploading them. Projects that aren't open are always complete.
     */
    fun checkpoint() = synchronized(lock) { open.values.forEach { it.db?.checkpoint() } }

    private fun save(s: ProjectSpace, p: Project, captions: List<Caption>) = synchronized(lock) {
        val d = dir(p.id)
        if (!d.isDirectory || open[s.id] !== s) return@synchronized // deleted or let go meanwhile
        val saved = s.saved
        runCatching {
            val store = db(s)
            // Only pieces that changed since the last save are written: cheap even with thousands of pieces.
            val changed = captions.filter { saved[it.id] !== it }
            val ids = if (saved.size > captions.size - changed.size) captions.mapTo(HashSet(captions.size)) { it.id } else null
            val removed = ids?.let { saved.keys.filter { k -> k !in it } }.orEmpty()
            if (changed.isEmpty() && removed.isEmpty()) return@runCatching
            store.write(changed.map { c -> PieceDb.Row(c.id, c.time, searchText(c), toJson(c).toString()) }, removed)
            changed.forEach { saved[it.id] = it }
            removed.forEach { saved.remove(it) }
            val stored = readMeta(d) ?: p
            val meta = stored.copy(
                lastUsed = maxOf(stored.lastUsed, captions.lastOrNull()?.let { it.time + it.durationMs } ?: 0),
                items = captions.count { it.heard.isNotBlank() || it.turns.isNotEmpty() },
                sessions = captions.map { it.session }.distinct().size,
                totalMs = captions.sumOf { it.durationMs },
            )
            if (meta != stored) {
                writeMeta(meta)
                // The projects list (sidebar, home) shows length and count as they grow, without rereading every folder.
                AppState.projects.value = AppState.projects.value.map { if (it.id == meta.id) meta else it }
            }
        }.onFailure { Log.e(TAG, "save ${p.id} failed", it) }
    }

    /** Plain text of a piece, for searching. */
    fun searchText(c: Caption): String = buildString {
        append(c.heard).append('\n').append(c.translation)
        c.turns.forEach { append('\n').append(it.heard).append('\n').append(it.translation) }
        if (c.heard.isBlank()) append('\n').append(c.preview)
    }

    private fun loadCaptions(s: ProjectSpace): List<Caption> = runCatching {
        val d = dir(s.id)
        val audio = File(d, "audio")
        val store = db(s)
        migrate(d, store, audio)
        store.readAll().map { fromJson(JSONObject(it), audio) }.also { list -> list.forEach { s.saved[it.id] = it } }.map { c ->
            // Pieces that were still being translated when the app closed.
            if (c.done) c else c.copy(
                status = if (c.translation.isNotBlank()) SegmentStatus.DONE else SegmentStatus.NO_SPEECH,
                note = if (c.translation.isNotBlank()) null else "Not translated · the app was closed",
            )
        }
    }.getOrElse { Log.e(TAG, "load ${s.id} failed", it); emptyList() }

    /** Moves the pieces of an older project from captions.json into pieces.db (once; the old file is kept aside). */
    private fun migrate(d: File, store: PieceDb, audio: File) {
        val old = File(d, "captions.json")
        if (!old.isFile) return
        if (store.count() == 0) {
            val a = JSONArray(old.readText())
            val rows = (0 until a.length()).map { a.getJSONObject(it) }.map { j ->
                val c = fromJson(j, audio)
                PieceDb.Row(c.id, c.time, searchText(c), j.toString())
            }
            store.write(rows, emptyList())
            Log.d(TAG, "moved ${rows.size} pieces of ${d.name} from captions.json to ${PieceDb.FILE}")
        }
        old.renameTo(File(d, "captions.json.old"))
    }

    private fun readMeta(d: File): Project? = runCatching {
        val j = JSONObject(File(d, "project.json").readText())
        Project(
            id = d.name, name = j.getString("name"), created = j.getLong("created"), lastUsed = j.getLong("lastUsed"),
            items = j.optInt("items"), sessions = j.optInt("sessions"), totalMs = j.optLong("totalMs"),
            pair = LangPair.of(j.optString("pair")).code,
            autoNamed = j.optBoolean("autoNamed", j.getString("name").startsWith(UNTITLED)),
        )
    }.getOrNull()

    private fun writeMeta(p: Project) {
        val d = dir(p.id).apply { mkdirs() }
        writeAtomic(
            File(d, "project.json"),
            JSONObject().put("schema", SCHEMA).put("name", p.name).put("created", p.created).put("lastUsed", p.lastUsed)
                .put("items", p.items).put("sessions", p.sessions).put("totalMs", p.totalMs).put("pair", p.pair).put("autoNamed", p.autoNamed).toString(),
        )
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    fun toJson(c: Caption): JSONObject = JSONObject().apply {
        put("id", c.id); put("time", c.time); put("durationMs", c.durationMs); put("status", c.status.name)
        // Direction is stored explicitly (th -> en or en -> th) so the pieces can be used as training pairs later.
        val lang = c.lang.ifEmpty { Lang.of(c.heard) }
        if (lang.isNotEmpty()) { put("lang", lang); put("target", Lang.target(lang, c.translation.ifBlank { c.turns.firstOrNull()?.translation.orEmpty() })) }
        put("heard", c.heard); put("translation", c.translation)
        if (c.preview.isNotBlank()) put("preview", c.preview) // the live grey words, kept as they were
        if (c.engine.isNotEmpty()) put("engine", c.engine)
        c.audioFile?.let { put("audio", File(it).name) } // by name: the folder may move (backup/restore)
        c.voiceFile?.let { put("voice", File(it).name); put("voiceText", c.voiceText) }
        c.explanation?.let { put("explanation", it) }
        c.explainSource?.let { put("explainSource", it) }
        put("mine", c.mine)
        if (c.source.isNotEmpty()) put("source", c.source)
        c.speaker?.let { put("speaker", it) }
        if (c.turns.isNotEmpty()) put("turns", JSONArray().apply {
            c.turns.forEach {
                val lang = it.lang.ifEmpty { Lang.of(it.heard) }
                put(JSONObject().put("speaker", it.speaker).put("lang", lang).put("target", Lang.target(lang, it.translation))
                    .put("heard", it.heard).put("translation", it.translation).apply { it.person?.let { p -> put("person", p) } })
            }
        })
        c.note?.let { put("note", it) }
        put("offline", c.offline)
        put("session", c.session)
        // Every earlier version, oldest first: (replaced text, why, by whom) pairs for fine-tuning.
        if (c.revisions.isNotEmpty()) put("revisions", JSONArray().apply {
            c.revisions.forEach { r ->
                put(JSONObject().apply {
                    put("heard", r.heard); put("translation", r.translation)
                    if (r.lang.isNotEmpty()) { put("lang", r.lang); put("target", Lang.target(r.lang, r.translation)) }
                    put("engine", r.engine)
                    if (r.turns.isNotEmpty()) put("turns", JSONArray().apply {
                        r.turns.forEach { put(JSONObject().put("speaker", it.speaker).put("lang", it.lang).put("heard", it.heard).put("translation", it.translation)) }
                    })
                    put("reason", r.reason); put("by", r.by); put("time", r.time)
                })
            }
        })
    }

    fun fromJson(j: JSONObject, audioDir: File): Caption = Caption(
        id = j.getLong("id"),
        time = j.getLong("time"),
        durationMs = j.optLong("durationMs"),
        status = runCatching { SegmentStatus.valueOf(j.getString("status")) }.getOrDefault(SegmentStatus.DONE),
        heard = j.optString("heard"),
        preview = j.optString("preview"),
        translation = j.optString("translation"),
        audioFile = j.optString("audio").takeIf { it.isNotEmpty() }?.let { File(audioDir, it) }?.takeIf { it.isFile }?.absolutePath,
        explanation = if (j.has("explanation")) j.getString("explanation") else null,
        explainSource = if (j.has("explainSource")) j.getString("explainSource") else null,
        mine = j.optBoolean("mine"),
        voiceFile = j.optString("voice").takeIf { it.isNotEmpty() }?.let { File(audioDir, it) }?.takeIf { it.isFile }?.absolutePath,
        voiceText = j.optString("voiceText"),
        source = j.optString("source"),
        speaker = if (j.has("speaker")) j.getInt("speaker") else null,
        turns = j.optJSONArray("turns")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { Turn(it.optString("speaker"), it.optString("heard"), it.optString("translation"), it.optString("lang").ifEmpty { Lang.of(it.optString("heard")) }, if (it.has("person")) it.getInt("person") else null) }
        }.orEmpty(),
        note = if (j.has("note")) j.getString("note") else null,
        offline = j.optBoolean("offline"),
        session = j.optLong("session"),
        // Older saves have no language: tell it from the script of what was heard.
        lang = j.optString("lang").ifEmpty { Lang.of(j.optString("heard")) },
        engine = j.optString("engine").ifEmpty { if (j.optBoolean("offline")) Lang.DEVICE_ENGINE else "" },
        revisions = j.optJSONArray("revisions")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { k ->
                Revision(
                    heard = k.optString("heard"),
                    translation = k.optString("translation"),
                    lang = k.optString("lang"),
                    engine = k.optString("engine"),
                    turns = k.optJSONArray("turns")?.let { a ->
                        (0 until a.length()).map { a.getJSONObject(it) }
                            .map { Turn(it.optString("speaker"), it.optString("heard"), it.optString("translation"), it.optString("lang")) }
                    }.orEmpty(),
                    reason = k.optString("reason"),
                    by = k.optString("by"),
                    time = k.optLong("time"),
                )
            }
        }.orEmpty(),
    )
}
