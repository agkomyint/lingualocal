package com.example.lingualocal.ai

import com.example.lingualocal.AppState
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit on-device Thai <-> English translation. Works with no internet and on any device. */
object OfflineTranslator {
    private val thaiToEnglish = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.THAI)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
    )
    private val englishToThai = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
    )

    suspend fun prepare() {
        try {
            val conditions = DownloadConditions.Builder().build()
            thaiToEnglish.downloadModelIfNeeded(conditions).await()
            englishToThai.downloadModelIfNeeded(conditions).await()
            AppState.offlineStatus.value = "Ready offline (ML Kit, on-device)"
        } catch (e: Exception) {
            AppState.offlineStatus.value = "Model download failed: ${e.message?.take(80)}"
        }
    }

    fun isThai(text: String) = text.any { it in '฀'..'๿' }

    suspend fun translate(text: String): String =
        (if (isThai(text)) thaiToEnglish else englishToThai).translate(text).await()

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
