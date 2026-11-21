package com.example.lingualocal.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Caption
import com.example.lingualocal.Config
import com.example.lingualocal.Revision
import com.example.lingualocal.data.Lang
import com.example.lingualocal.SegmentStatus
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Ask": a chat about the open project, on cloud Gemini through Firebase AI Logic, with tools the model calls
 * itself. It can read the transcript, listen to the original recordings again (tone, who said what, a better
 * transcript and translation) and search the web for context.
 */
object AskAssistant {
    enum class Role { USER, AI }
    /** [steps] are the tools the model used for this answer, shown above it ("Listened to 14:05:12"). */
    data class Msg(val role: Role, val text: String, val steps: List<String> = emptyList(), val error: Boolean = false)

    /** One saved chat of a project. [updated] orders the list, newest first. */
    data class Thread(val id: Long, val title: String, val updated: Long, val messages: List<Msg>)

    /** Messages of the open chat. */
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    /** Every chat of the open project, newest first. Saved in the project's folder (ask.json). */
    val threads = MutableStateFlow<List<Thread>>(emptyList())
    /** Id of the open chat; null for a new one that has no messages yet. */
    val currentThread = MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow(false)
    /** What the model is doing right now, e.g. "Listening to 14:05:12…". */
    val activity = MutableStateFlow("")
    /** Tools used so far for the answer in progress, shown live while Gemini works. */
    val liveSteps = MutableStateFlow<List<String>>(emptyList())

    private var appContext: Context? = null
    private var chat: Chat? = null
    private var projectId: String? = null

    /** The project Ask is open in (on screen, or being recorded), with its pieces in memory. */
    private fun askSpace(): com.example.lingualocal.ProjectSpace? =
        listOfNotNull(AppState.view.value, AppState.live.value).firstOrNull { it.id == projectId }
    /** Pieces Gemini has listened to in this chat; it may only correct those. */
    private val heardIds = mutableSetOf<Long>()

    /** Answers keep coming even if the panel is closed meanwhile. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) { appContext = context.applicationContext }

    fun send(question: String) { scope.launch { ask(question) } }

    fun online(): Boolean {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Each project has its own chats; opening a project loads them and reopens the latest one. */
    fun forProject(id: String?) {
        if (id == projectId) return
        projectId = id
        threads.value = id?.let { loadThreads(it) }.orEmpty()
        show(threads.value.firstOrNull())
    }

    /** Bumped when another chat opens, so an answer still in flight doesn't land in the wrong chat. */
    private var generation = 0
    private fun reply(gen: Int, msg: Msg) {
        if (gen != generation) return
        messages.update { it + msg }
        persist()
    }

    /** Starts a new chat. The current one stays saved in the list. */
    fun newChat() = show(null)

    /** Opens a saved chat of this project. */
    fun open(threadId: Long) {
        if (busy.value || threadId == currentThread.value) return
        threads.value.firstOrNull { it.id == threadId }?.let { show(it) }
    }

    private fun show(t: Thread?) {
        generation++
        chat = null // rebuilt from the saved messages on the next question
        heardIds.clear()
        currentThread.value = t?.id
        messages.value = t?.messages.orEmpty()
        activity.value = ""
    }

    /** Puts the open chat at the top of the list and writes the list to disk. */
    private fun persist() {
        val pid = projectId ?: return
        val tid = currentThread.value ?: return
        val msgs = messages.value
        if (msgs.isEmpty()) return
        val title = threads.value.firstOrNull { it.id == tid }?.title
            ?: msgs.first { it.role == Role.USER }.text.lineSequence().first().take(TITLE_MAX)
        threads.value = listOf(Thread(tid, title, System.currentTimeMillis(), msgs)) + threads.value.filter { it.id != tid }
        val snapshot = threads.value
        io.launch { writeThreads(pid, snapshot) }
    }

    /** Writes run one at a time, in order, so an older list never overwrites a newer one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun askFile(projectId: String) = com.example.lingualocal.data.ProjectStore.projectDir(projectId)?.let { File(it, "ask.json") }

    private fun writeThreads(pid: String, list: List<Thread>) = runCatching {
        val f = askFile(pid) ?: return@runCatching
        if (!f.parentFile!!.isDirectory) return@runCatching // project was deleted meanwhile
        val json = org.json.JSONArray(list.map { t ->
            org.json.JSONObject().put("id", t.id).put("title", t.title).put("updated", t.updated).put("messages", org.json.JSONArray(t.messages.map { m ->
                org.json.JSONObject().put("role", m.role.name).put("text", m.text).apply {
                    if (m.steps.isNotEmpty()) put("steps", org.json.JSONArray(m.steps))
                    if (m.error) put("error", true)
                }
            }))
        })
        val tmp = File(f.parentFile, "ask.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }.onFailure { Log.w(TAG, "couldn't save Ask chats", it) }

    private fun loadThreads(pid: String): List<Thread> = runCatching {
        val f = askFile(pid)?.takeIf { it.isFile } ?: return emptyList()
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val ms = t.getJSONArray("messages")
            Thread(t.getLong("id"), t.optString("title"), t.optLong("updated"), (0 until ms.length()).map { j ->
                val m = ms.getJSONObject(j)
                val st = m.optJSONArray("steps")
                Msg(
                    runCatching { Role.valueOf(m.getString("role")) }.getOrDefault(Role.AI), m.optString("text"),
                    st?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(), m.optBoolean("error"),
                )
            })
        }.sortedByDescending { it.updated }
    }.getOrElse { Log.w(TAG, "couldn't read Ask chats", it); emptyList() }

    /** A saved chat's questions and answers, so Gemini remembers them when the chat is continued. */
    private fun history(msgs: List<Msg>) = buildList {
        msgs.forEachIndexed { i, m ->
            val next = msgs.getOrNull(i + 1)
            if (m.role == Role.USER && next != null && next.role == Role.AI && !next.error) {
                add(content("user") { text(m.text) })
                add(content("model") { text(next.text) })
            }
        }
    }

    private val clock get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val tools = listOf(
        FunctionDeclaration(
            "read_transcript",
            "Returns the recorded pieces of the open project in order: id, time, session number, speaker, " +
                "spoken language, what was heard, the translation, length and whether audio is kept. Call this first. " +
                "In a long project it returns an outline of the sessions (times and piece ids) and only the latest pieces; " +
                "then use search_transcript and read_pieces for earlier parts.",
            emptyMap(),
        ),
        FunctionDeclaration(
            "search_transcript",
            "Searches every piece of the open project (any length, all sessions) for words in what was heard or in the " +
                "translation. Returns the matching pieces with id and time, best matches first. Search in the language " +
                "the words were spoken or translated into (e.g. Thai and English words), several words at once.",
            mapOf("query" to Schema.string("Words to look for, separated by spaces.")),
        ),
        FunctionDeclaration(
            "read_pieces",
            "Returns the pieces with ids from from_id to to_id (at most $MAX_PIECES), e.g. a part of a long meeting " +
                "found with the outline or around a search match.",
            mapOf(
                "from_id" to Schema.long("First piece id."),
                "to_id" to Schema.long("Last piece id."),
            ),
        ),
        FunctionDeclaration(
            "listen_to_recording",
            "Call it for several pieces in the same turn when needed; they are listened to in parallel. Plays the original audio of one piece to a speech model and answers a question about it: tone of voice, " +
                "emotion, politeness, how many people speak and who says what, words the transcript may have missed, " +
                "or a corrected transcript and translation. Use it whenever the answer depends on how something was said.",
            mapOf(
                "id" to Schema.long("The piece id from read_transcript."),
                "question" to Schema.string("What to listen for, in English."),
            ),
        ),
        FunctionDeclaration(
            "correct_piece",
            "Replaces a piece's transcript and translation with the corrected version, after you listened to it with " +
                "listen_to_recording and are confident the original was wrong (missed words, wrong language, wrong meaning). " +
                "Don't use it for style or tiny wording preferences. The old text is kept so the user can undo it.",
            mapOf(
                "id" to Schema.long("The piece id."),
                "heard" to Schema.string("The corrected transcript, in the language actually spoken (Thai script for Thai, Myanmar Unicode for Burmese)."),
                "translation" to Schema.string("The corrected translation into the other language of the project's pair."),
                "language" to Schema.enumeration(listOf("th", "en", "my"), "Language actually spoken in the piece (my = Burmese)."),
                "reason" to Schema.string("Short reason shown to the user, e.g. 'missed the negative ไม่ (don't)'."),
                "speaker" to Schema.string("Only for pieces with turns: the speaker letter (A, B…) whose turn to correct."),
            ),
            optionalParameters = listOf("speaker"),
        ),
        FunctionDeclaration(
            "web_search",
            "Searches the web with Google for background: Thai customs, places, laws, prices, slang, names mentioned.",
            mapOf("query" to Schema.string("The search query.")),
        ),
    )

    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = Config.CLOUD_TEXT_MODEL,
            tools = listOf(Tool.functionDeclarations(tools)),
            systemInstruction = content {
                text(
                    """
                    You are the assistant inside LinguaLocal, a live interpreter used by people living in Thailand. Each project
                    is either Thai ⇄ English or Thai ⇄ Burmese (read_transcript says which). Write Burmese in Myanmar Unicode script.
                    The user asks about a conversation the app recorded and translated (the open project).
                    Use read_transcript to see it; in long meetings, find earlier parts with search_transcript and read_pieces
                    instead of guessing. When tone, intent, or the exact words matter, use listen_to_recording on the
                    relevant pieces; transcripts and translations can be wrong. Use web_search for outside facts.
                    Answer in the language the user writes in, short and practical. Refer to pieces by their time (e.g. 14:05:12).
                    When suggesting what to say, give the Thai with a romanised reading and the English meaning.
                    Never invent what was said: if the recordings don't show it, say so.
                    The first transcript comes from a faster model and can be wrong. When listening shows a piece's transcript or
                    translation is clearly wrong and you are confident, call correct_piece to fix it, then tell the user plainly
                    which piece was wrong, what it said before, and what you changed it to. If you're unsure, only report it.
                    """.trimIndent()
                )
            },
        )
    }

    /** Plain model for listening to audio and for search (Google Search can't share a request with our functions). */
    private val listener by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(Config.CLOUD_TEXT_MODEL) }
    private val searcher by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(Config.CLOUD_TEXT_MODEL, tools = listOf(Tool.googleSearch()))
    }

    suspend fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || busy.value) return
        val gen = generation
        val before = messages.value
        if (currentThread.value == null) currentThread.value = System.currentTimeMillis()
        messages.update { it + Msg(Role.USER, q) }
        persist()
        if (!online()) {
            reply(gen, Msg(Role.AI, "Ask needs the internet. Your recordings are safe; ask again when you're back online.", error = true))
            return
        }
        busy.value = true
        val steps = object : java.util.ArrayList<String>() {
            override fun add(element: String): Boolean = super.add(element).also { liveSteps.value = toList() }
        }
        liveSteps.value = emptyList()
        try {
            val c = chat ?: model.startChat(history(before)).also { chat = it }
            activity.value = "Thinking…"
            var response = c.sendMessage(content { text(q) })
            var rounds = 0
            while (response.functionCalls.isNotEmpty() && rounds++ < MAX_ROUNDS) {
                // Several recordings asked for at once are listened to in parallel.
                val results = coroutineScope {
                    response.functionCalls.map { call ->
                        async {
                            val result = runCatching { runTool(call.name, call.args, steps) }
                                .getOrElse { e -> Log.w(TAG, "tool ${call.name} failed", e); "Error: ${e.message?.take(200)}" }
                            FunctionResponsePart(call.name, JsonObject(mapOf("result" to JsonPrimitive(result))), call.id)
                        }
                    }.awaitAll()
                }
                activity.value = "Thinking…"
                response = c.sendMessage(content("user") { results.forEach { part(it) } })
            }
            val text = response.text?.trim().orEmpty().ifEmpty { "I couldn't come up with an answer. Try asking another way." }
            reply(gen, Msg(Role.AI, text, steps.toList()))
        } catch (e: Exception) {
            Log.w(TAG, "ask failed", e)
            if (gen == generation) chat = null // the history may be half-written; start clean next time
            reply(gen, Msg(Role.AI, "Something went wrong: ${e.message?.take(160) ?: e.javaClass.simpleName}", steps.toList(), error = true))
        } finally {
            busy.value = false
            activity.value = ""
            liveSteps.value = emptyList()
        }
    }

    private suspend fun runTool(name: String, args: Map<String, kotlinx.serialization.json.JsonElement>, steps: MutableList<String>): String =
        when (name) {
            "read_transcript" -> {
                activity.value = "Reading the transcript…"
                steps += "Read the transcript"
                transcript()
            }
            "search_transcript" -> {
                val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                activity.value = "Searching the transcript…"
                steps += "Searched the transcript for “$query”"
                search(query)
            }
            "read_pieces" -> {
                val from = args["from_id"]?.jsonPrimitive?.longOrNull ?: return "Missing from_id."
                val to = args["to_id"]?.jsonPrimitive?.longOrNull ?: return "Missing to_id."
                val all = askSpace()?.captions?.value.orEmpty().filter { it.status != SegmentStatus.RECORDING }
                val part = all.filter { it.id in from..to }
                if (part.isEmpty()) return "No pieces with ids $from to $to."
                activity.value = "Reading ${clock.format(Date(part.first().time))}…"
                steps += "Read ${clock.format(Date(part.first().time))} – ${clock.format(Date(part.last().time))}"
                val note = if (part.size > MAX_PIECES) "Only the first $MAX_PIECES of ${part.size} pieces are shown.\n" else ""
                note + rows(part.take(MAX_PIECES), all.map { it.session }.distinct())
            }
            "listen_to_recording" -> {
                val id = args["id"]?.jsonPrimitive?.longOrNull ?: return "Missing id."
                val question = args["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val c = askSpace()?.captions?.value.orEmpty().firstOrNull { it.id == id } ?: return "No piece with id $id."
                val time = clock.format(Date(c.time))
                activity.value = if (steps.count { it.startsWith("Listen") } > 0) "Listening to the recordings…" else "Listening to $time…"
                steps += "Listened to $time"
                listen(c, question).also { heardIds += id }
            }
            "correct_piece" -> correct(args, steps)
            "web_search" -> {
                val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                activity.value = "Searching “$query”…"
                steps += "Searched “$query”"
                searcher.generateContent("Search the web and answer briefly with the key facts: $query").text?.trim().orEmpty()
            }
            else -> "Unknown tool $name."
        }

    private fun transcript(): String {
        val captions = askSpace()?.captions?.value.orEmpty().filter { it.status != SegmentStatus.RECORDING }
        if (captions.isEmpty()) return "The project has no recordings yet."
        val sessions = captions.map { it.session }.distinct()
        if (captions.size <= MAX_PIECES) return rows(captions, sessions)
        // Long project: an outline of every session, then the latest pieces.
        val day = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        val outline = captions.groupBy { it.session }.values.mapIndexed { i, s ->
            "Session ${i + 1}: ${day.format(Date(s.first().time))} – ${clock.format(Date(s.last().time + s.last().durationMs))}, " +
                "${s.size} pieces, ids ${s.first().id}–${s.last().id}"
        }.joinToString("\n")
        return "This project has ${captions.size} pieces. Outline:\n$outline\n\n" +
            "Only the latest $MAX_PIECES pieces are below; use search_transcript or read_pieces for earlier parts.\n" +
            rows(captions.takeLast(MAX_PIECES), sessions)
    }

    /** Pieces matching the most words of [query], best first. */
    private fun search(query: String): String {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (words.isEmpty()) return "Empty query."
        val all = askSpace()?.captions?.value.orEmpty().filter { it.status != SegmentStatus.RECORDING }
        val hits = all.mapNotNull { c ->
            val text = com.example.lingualocal.data.ProjectStore.searchText(c).lowercase()
            val n = words.count { text.contains(it) }
            if (n > 0) c to n else null
        }.sortedWith(compareByDescending<Pair<Caption, Int>> { it.second }.thenBy { it.first.id }).take(MAX_HITS)
        if (hits.isEmpty()) return "No piece mentions ${words.joinToString(", ")}."
        return "${hits.size} matching pieces (best first):\n" + rows(hits.map { it.first }, all.map { it.session }.distinct())
    }

    private fun rows(list: List<Caption>, sessions: List<Long>): String {
        val rows = list.map { c ->
            JsonObject(
                buildMap {
                    put("id", JsonPrimitive(c.id))
                    put("time", JsonPrimitive(clock.format(Date(c.time))))
                    put("session", JsonPrimitive(sessions.indexOf(c.session) + 1))
                    put("seconds", JsonPrimitive(c.durationMs / 1000))
                    put("status", JsonPrimitive(c.status.name.lowercase()))
                    put("speaker", JsonPrimitive(if (c.mine) "the user (push-to-talk)" else c.speaker?.let { "voice $it" } ?: "unknown"))
                    if (c.lang.isNotBlank()) put("language", JsonPrimitive(c.lang))
                    if (c.turns.isNotEmpty()) {
                        put("turns", JsonArray(c.turns.map { t -> JsonPrimitive("${t.speaker}: ${t.heard} => ${t.translation}") }))
                    } else {
                        put("heard", JsonPrimitive(c.heard.take(MAX_TEXT)))
                        put("translation", JsonPrimitive(c.translation.take(MAX_TEXT)))
                    }
                    put("has_audio", JsonPrimitive(c.audioFile?.let { File(it).isFile } == true))
                }
            )
        }
        return JsonArray(rows).toString()
    }

    private suspend fun listen(c: Caption, question: String): String {
        val path = c.audioFile ?: return "This piece has no saved audio."
        val bytes = withContext(Dispatchers.IO) { File(path).takeIf { it.isFile }?.readBytes() } ?: return "The audio file is missing."
        val prompt = """
            This is a recording from a conversation in Thailand (${(askSpace()?.pair ?: com.example.lingualocal.data.LangPair.TH_EN).label}, possibly mixed).
            The app's transcript was: "${c.heard.ifBlank { c.turns.joinToString(" / ") { "${it.speaker}: ${it.heard}" } }}"
            Its translation was: "${c.translation}"
            Listen to the audio itself and answer: ${question.ifBlank { "What is said, by whom, and in what tone?" }}
            Be specific and brief. Quote Thai words with their English meaning.
            Check the transcript and translation against the audio. If either is wrong, give the corrected transcript (in the
            language spoken), the corrected translation, and how confident you are (high / medium / low).
        """.trimIndent()
        return listener.generateContent(content {
            inlineData(bytes, if (path.endsWith(".wav", true)) "audio/wav" else "audio/mp4")
            text(prompt)
        }).text?.trim().orEmpty()
    }

    private fun correct(args: Map<String, kotlinx.serialization.json.JsonElement>, steps: MutableList<String>): String {
        fun str(k: String) = args[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val id = args["id"]?.jsonPrimitive?.longOrNull ?: return "Missing id."
        val c = askSpace()?.caption(id) ?: return "No piece with id $id."
        if (id !in heardIds) return "Listen to piece $id with listen_to_recording before correcting it."
        val heard = str("heard"); val translation = str("translation")
        if (heard.isEmpty() || translation.isEmpty()) return "Both heard and translation are required."
        val lang = str("language").takeIf { it == Lang.TH || it == Lang.EN || it == Lang.MY } ?: Lang.of(heard)
        val reason = str("reason").ifEmpty { "Checked against the recording" }
        val speaker = str("speaker").uppercase()
        val engine = Lang.cloudEngine(Config.CLOUD_TEXT_MODEL)
        askSpace()?.updateCaption(id) { old ->
            val replaced = Revision(old.heard, old.translation, old.lang, old.engine, old.turns, reason, engine)
            val fixed = if (speaker.isNotEmpty() && old.turns.any { it.speaker.equals(speaker, true) }) {
                old.copy(turns = old.turns.map { if (it.speaker.equals(speaker, true)) it.copy(heard = heard, translation = translation, lang = lang) else it })
            } else {
                old.copy(heard = heard, translation = translation, lang = lang, turns = emptyList())
            }
            fixed.copy(engine = engine, status = SegmentStatus.DONE, note = null, revisions = old.revisions + replaced)
        }
        val time = clock.format(Date(c.time))
        activity.value = "Correcting $time…"
        steps += "Corrected $time"
        return "Saved. Piece $id now reads \"$heard\" → \"$translation\". The previous text was \"${c.heard.ifBlank { c.turns.joinToString(" / ") { it.heard } }}\" → \"${c.translation}\"."
    }

    /** Puts back what the piece said before Ask's last fix. The fix itself is kept in the history too. */
    fun undoCorrection(id: Long) {
        askSpace()?.updateCaption(id) { c ->
            val k = c.correction ?: return@updateCaption c
            val undone = Revision(c.heard, c.translation, c.lang, c.engine, c.turns, "Undone by user", Revision.USER_UNDO)
            c.copy(heard = k.heard, translation = k.translation, lang = k.lang, engine = k.engine, turns = k.turns, revisions = c.revisions + undone)
        }
    }

    private const val TAG = "AskAssistant"
    private const val MAX_ROUNDS = 6
    private const val MAX_PIECES = 300
    private const val MAX_HITS = 40
    private const val MAX_TEXT = 400
    private const val TITLE_MAX = 60
}
