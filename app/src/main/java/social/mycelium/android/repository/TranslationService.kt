package social.mycelium.android.repository

import android.util.Log
import android.util.LruCache
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

/**
 * On-demand translation service using Google ML Kit.
 * User-initiated only (not automatic). Results are cached by note ID.
 * Encodes URLs, nostr tags, and LN invoices before translation to preserve them.
 */
object TranslationService {

    private const val TAG = "TranslationService"

    data class TranslationResult(
        val translatedText: String,
        val sourceLang: String,
        val targetLang: String
    )

    private val cache = LruCache<String, TranslationResult>(200)

    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.5f)
            .build()
    )

    private val translators = object : LruCache<TranslatorOptions, Translator>(3) {
        override fun create(options: TranslatorOptions): Translator = Translation.getClient(options)
        override fun entryRemoved(evicted: Boolean, key: TranslatorOptions, oldValue: Translator, newValue: Translator?) {
            oldValue.close()
        }
    }

    private val lnRegex = Pattern.compile("\\blnbc[a-z0-9]+\\b", Pattern.CASE_INSENSITIVE)
    private val tagRegex = Pattern.compile(
        "(nostr:)?@?(nsec1|npub1|nevent1|naddr1|note1|nprofile1|nrelay1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
        Pattern.CASE_INSENSITIVE
    )
    private val urlRegex = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE)

    /** Get cached translation result for a note ID, or null if not yet translated. */
    fun getCached(noteId: String): TranslationResult? = cache.get(noteId)

    /**
     * Translate note content on demand. Returns the translation result or null if
     * the content is already in the target language or translation failed.
     * Target language defaults to the device locale.
     */
    suspend fun translate(noteId: String, content: String): TranslationResult? {
        cache.get(noteId)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val sourceLang = identifyLanguage(content) ?: return@withContext null
                val targetLang = Locale.getDefault().language
                if (sourceLang == targetLang || sourceLang == "und") return@withContext null

                val sourceCode = TranslateLanguage.fromLanguageTag(sourceLang)
                val targetCode = TranslateLanguage.fromLanguageTag(targetLang)
                if (sourceCode == null || targetCode == null) return@withContext null

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceCode)
                    .setTargetLanguage(targetCode)
                    .build()
                val translator = translators.get(options)

                // Download model if needed, then translate
                awaitTask { translator.downloadModelIfNeeded().addOnCompleteListener(it) }

                val dict = buildDictionary(content)
                val encoded = encodeDictionary(content, dict)

                // Translate paragraph by paragraph
                val paragraphs = encoded.split("\n")
                val translated = paragraphs.map { paragraph ->
                    awaitTask<String> { translator.translate(paragraph).addOnCompleteListener(it) }
                }
                val result = decodeDictionary(translated.joinToString("\n"), dict)

                if (result.equals(content, ignoreCase = true)) return@withContext null

                val translationResult = TranslationResult(
                    translatedText = result,
                    sourceLang = sourceLang,
                    targetLang = targetLang
                )
                cache.put(noteId, translationResult)
                translationResult
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun identifyLanguage(text: String): String? {
        return try {
            awaitTask<String> { languageIdentifier.identifyLanguage(text).addOnCompleteListener(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Language identification failed: ${e.message}")
            null
        }
    }

    private suspend fun <T> awaitTask(block: (com.google.android.gms.tasks.OnCompleteListener<T>) -> Unit): T {
        return suspendCancellableCoroutine { cont ->
            block { task ->
                if (task.isSuccessful) {
                    cont.resume(task.result)
                } else {
                    cont.resume(task.result) // May be null for Void tasks
                }
            }
        }
    }

    private fun buildDictionary(text: String): Map<String, String> {
        val dict = mutableMapOf<String, String>()
        var counter = 0
        // LN invoices
        val lnMatcher = lnRegex.matcher(text)
        while (lnMatcher.find()) {
            dict["LN${counter++}"] = lnMatcher.group()
        }
        // Nostr tags
        val tagMatcher = tagRegex.matcher(text)
        while (tagMatcher.find()) {
            dict["NT${counter++}"] = tagMatcher.group()
        }
        // URLs
        val urlMatcher = urlRegex.matcher(text)
        while (urlMatcher.find()) {
            dict["URL${counter++}"] = urlMatcher.group()
        }
        return dict
    }

    private fun encodeDictionary(text: String, dict: Map<String, String>): String {
        var result = text
        for ((placeholder, original) in dict) {
            result = result.replace(original, placeholder, ignoreCase = true)
        }
        return result
    }

    private fun decodeDictionary(text: String, dict: Map<String, String>): String {
        var result = text
        for ((placeholder, original) in dict) {
            result = result.replace(placeholder, original, ignoreCase = true)
        }
        return result
    }

    fun clear() {
        cache.evictAll()
    }
}
