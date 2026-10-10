package com.yourapp.translatebubble

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslatorHelper {

    private val arToEn: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .build()
        Translation.getClient(options)
    }

    private val enToAr: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ARABIC)
            .build()
        Translation.getClient(options)
    }

    suspend fun translate(text: String, arabicToEnglish: Boolean): Result<String> {
        val translator = if (arabicToEnglish) arToEn else enToAr
        return try {
            ensureModelDownloaded(translator)
            val translated = runTranslate(translator, text)
            Result.success(translated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Signals "leave this block alone" rather than a real translation
    // failure - the caller already treats any failure as "skip, don't
    // show an overlay for this block" (via Result.getOrNull()), so this
    // reuses that same path instead of needing a new one.
    private class SkipTranslation : Exception("Arabic source, one-directional mode: left untouched")

    // ---------------------------------------------------------------------
    // Translate many blocks at once, a few at a time in parallel, instead
    // of one-by-one. Much faster when a screen has lots of text blocks.
    //
    // IMPORTANT: direction is decided PER BLOCK, not once for the whole
    // screen. A screen that's mostly English with a little Arabic (or vice
    // versa) used to pick ONE direction for everything just because a
    // single stray character of the other language showed up somewhere in
    // the combined text - so blocks that were already in the "wrong"
    // direction just passed through unchanged, making it look like
    // everything got translated into one language. Now each block's own
    // text decides its own direction.
    // ---------------------------------------------------------------------
    suspend fun translateBatch(
        texts: List<String>,
        maxConcurrent: Int = 4,
        forcedDirection: Boolean? = null // null = auto-detect per block, one-directional (English/other -> Arabic only; Arabic source is left untouched, not translated to English); true = force Arabic->English; false = force English->Arabic
    ): List<Result<String>> = coroutineScope {
        val semaphore = Semaphore(maxConcurrent)
        texts.map { text ->
            async {
                semaphore.withPermit {
                    when (forcedDirection) {
                        // Explicit user choice always wins, including an
                        // explicit request for Arabic->English.
                        true -> translate(text, arabicToEnglish = true)
                        false -> translate(text, arabicToEnglish = false)
                        // Auto-detect: only ever translate INTO Arabic.
                        // An Arabic-dominant block is already in the
                        // target language, so it's skipped rather than
                        // run through arToEn - matching Google Lens set
                        // to "English -> Arabic", which leaves Arabic
                        // source text on screen untouched instead of
                        // flipping it into English.
                        null -> if (isArabicDominant(text)) {
                            Result.failure(SkipTranslation())
                        } else {
                            translate(text, arabicToEnglish = false)
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun ensureModelDownloaded(translator: Translator) =
        suspendCancellableCoroutine<Unit> { cont ->
            // No longer requires Wi-Fi: on mobile data the model still
            // downloads (once, then cached), instead of silently failing.
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private suspend fun runTranslate(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { translatedText -> cont.resume(translatedText) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    // Old check: true if the text contains ANY Arabic character at all.
    // Kept for compatibility, but prefer isArabicDominant() below for
    // deciding translation direction - "any" is too easily tripped by a
    // single stray character (an emoji-adjacent mark, a name, etc.).
    fun looksArabic(text: String): Boolean {
        return text.any { it.code in 0x0600..0x06FF }
    }

    // True if Arabic letters outnumber Latin letters in this specific
    // piece of text. This is what should decide translation direction,
    // block by block - never for a whole screen's text combined.
    fun isArabicDominant(text: String): Boolean {
        var arabicCount = 0
        var latinCount = 0
        for (ch in text) {
            when {
                ch.code in 0x0600..0x06FF -> arabicCount++
                ch.isLetter() && ch.code < 0x0250 -> latinCount++
            }
        }
        return arabicCount > latinCount
    }

    fun close() {
        arToEn.close()
        enToAr.close()
    }
}
