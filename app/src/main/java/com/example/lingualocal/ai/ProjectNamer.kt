package com.example.lingualocal.ai

import android.util.Log
import com.example.lingualocal.AppState
import com.example.lingualocal.Config
import com.example.lingualocal.SegmentStatus
import com.example.lingualocal.data.ProjectStore
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Gives an "Untitled · date" project a short name from what was said, once its first few pieces are
 * translated (one small Gemini text request per project). A name the user typed is never replaced,
 * and the user can rename the project any time.
 */
object ProjectNamer {
    private const val TAG = "ProjectNamer"
    private const val MIN_PIECES = 3
    private const val RETRY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val model by lazy { Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(Config.CLOUD_TEXT_MODEL) }
    @Volatile private var busy = false
    /** project id -> when naming last failed (offline etc.), so it isn't retried on every new piece. */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Watches the project being recorded and the one on screen (often the same). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun init() {
        scope.launch {
            combine(AppState.live, AppState.view) { a, b -> listOfNotNull(a, b).distinct() }
                .flatMapLatest { spaces -> merge(*spaces.map { s -> combine(s.project, s.captions) { p, c -> p to c } }.toTypedArray()) }
                .collect { (p, captions) ->
                if (busy || !ProjectStore.needsName(p)) return@collect
                if ((failed[p.id] ?: 0L) > System.currentTimeMillis() - RETRY_MS) return@collect
                val pieces = captions.filter { it.status == SegmentStatus.DONE && (it.heard.isNotBlank() || it.turns.isNotEmpty()) }
                if (pieces.size < MIN_PIECES) return@collect
                busy = true
                scope.launch {
                    try {
                        val text = pieces.take(10).joinToString("\n") { c ->
                            val heard = c.heard.ifBlank { c.turns.joinToString(" / ") { it.heard } }
                            val tr = c.translation.ifBlank { c.turns.joinToString(" / ") { it.translation } }
                            if (c.translation.isNotBlank() || c.turns.any { it.translation.isNotBlank() }) "- $heard => $tr" else "- $heard"
                        }.take(2_000)
                        val r = model.generateContent(
                            "Here is the start of a conversation recorded by an interpreter app. Give it a short title " +
                                "(2 to 5 words, in English) saying what it is about, like \"Landlord · lease renewal\" or " +
                                "\"Clinic visit · fever\". Reply with the title only, no quotes.\n\n$text"
                        )
                        val name = r.text.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                            .trim().trim('"', '\'', '*', '.', ' ').take(48)
                        if (name.isBlank()) error("empty title")
                        if (ProjectStore.autoRename(p.id, name)) Log.d(TAG, "named ${p.id}: \"$name\"")
                    } catch (e: Exception) {
                        Log.w(TAG, "naming ${p.id} failed: ${e.message?.take(120)}")
                        failed[p.id] = System.currentTimeMillis()
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
