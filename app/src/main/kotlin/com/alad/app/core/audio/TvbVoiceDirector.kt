package com.alad.app.core.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight TVB-style Vietnamese voice router.
 * Expected model text protocol: [[TVB:speaker_id:M]]text or [[TVB:speaker_id:F]]text.
 * Android TTS voices are used locally; pitch/rate shaping is a stylistic approximation,
 * not a cloned TVB actor voice.
 */
class TvbVoiceDirector(context: Context) : TextToSpeech.OnInitListener {
    enum class Gender { MALE, FEMALE }
    data class Segment(val speakerId: String, val gender: Gender, val text: String)

    companion object {
        private const val TAG = "TvbVoiceDirector"
        private val MARKER = Regex("\\[\\[TVB:([A-Za-z0-9_-]{1,32}):(M|F)\\]\\](.*)", RegexOption.IGNORE_CASE)
        fun parseTaggedSegment(raw: String): Segment? {
            val match = MARKER.matchEntire(raw.trim()) ?: return null
            val gender = if (match.groupValues[2].equals("M", true)) Gender.MALE else Gender.FEMALE
            val text = match.groupValues[3].trim()
            if (text.isBlank()) return null
            return Segment(match.groupValues[1], gender, text)
        }
    }

    private val appContext = context.applicationContext
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    private val speakerGender = ConcurrentHashMap<String, Gender>()
    private val speakerVoice = ConcurrentHashMap<String, String>()

    init { engine = TextToSpeech(appContext, this) }

    override fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Android TTS initialization failed: $status")
            return
        }
        val result = tts.setLanguage(Locale("vi", "VN"))
        ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setSpeechRate(0.94f)
        Log.i(TAG, "Vietnamese TTS ready=$ready")
    }

    /** Returns true only when a valid TVB-tagged segment was accepted. */
    fun speakTagged(raw: String): Boolean {
        val segment = parseTaggedSegment(raw) ?: return false
        val tts = engine ?: return false
        if (!ready) return false
        speakerGender[segment.speakerId] = segment.gender
        val voiceKey = "${segment.speakerId}:${segment.gender}"
        val pitch = if (segment.gender == Gender.MALE) 0.86f else 1.08f
        val rate = if (segment.gender == Gender.MALE) 0.91f else 0.96f
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val id = "tvb-${segment.speakerId}-${UUID.randomUUID()}"
        val params = android.os.Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
        }
        speakerVoice[segment.speakerId] = voiceKey
        val result = tts.speak(segment.text, TextToSpeech.QUEUE_ADD, params, id)
        return result == TextToSpeech.SUCCESS
    }

    fun stop() { engine?.stop() }
    fun shutdown() {
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        speakerGender.clear()
        speakerVoice.clear()
    }
}
