package com.example.lingualocal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class Caption(
    val id: Long,
    /** Wall-clock time the speech started (System.currentTimeMillis). */
    val time: Long = System.currentTimeMillis(),
    val heard: String = "",
    val translation: String = "",
    val done: Boolean = false,
    val explanation: String? = null,
    val explainSource: String? = null,
)

/** Process-wide state shared by the listening service and the UI. */
object AppState {
    val running = MutableStateFlow(false)
    val liveStatus = MutableStateFlow("Idle")
    val vadStatus = MutableStateFlow("Not loaded")
    val nanoStatus = MutableStateFlow("Checking…")
    val offlineStatus = MutableStateFlow("Preparing…")

    val micLevel = MutableStateFlow(0f)
    val vadProbability = MutableStateFlow(0f)
    val speechActive = MutableStateFlow(false)
    val echoGuard = MutableStateFlow(false)

    /** Speak translations out loud. Off = text only, and the mic never pauses for our own voice. */
    val speakAloud = MutableStateFlow(true)

    val captions = MutableStateFlow<List<Caption>>(emptyList())
    private val nextId = AtomicLong(1)

    fun newCaption(): Long {
        val id = nextId.getAndIncrement()
        captions.update { (it + Caption(id)).takeLast(200) }
        return id
    }

    fun updateCaption(id: Long, transform: (Caption) -> Caption) {
        captions.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
