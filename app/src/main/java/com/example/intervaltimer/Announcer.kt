package com.example.intervaltimer

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** The voice used for spoken announcements. */
enum class VoiceChoice(
    val label: Int,
    /** Google text-to-speech voice names to look for, best first. */
    val candidates: List<String>,
    /** Pitch used when those voices aren't on the phone, so the four still sound different. */
    val fallbackPitch: Float,
) {
    OFF(R.string.voice_off, emptyList(), 1f),
    FEMALE_1(R.string.voice_female_1, listOf("en-us-x-sfg", "en-us-x-tpf", "en-gb-x-gba"), 1.15f),
    FEMALE_2(R.string.voice_female_2, listOf("en-us-x-iob", "en-us-x-tpc", "en-us-x-iog", "en-gb-x-gbc"), 1.35f),
    MALE_1(R.string.voice_male_1, listOf("en-us-x-iom", "en-us-x-tpd", "en-gb-x-gbd"), 0.85f),
    MALE_2(R.string.voice_male_2, listOf("en-us-x-iol", "en-gb-x-rjs", "en-gb-x-gbb"), 0.7f),
}

/** Speaks short announcements with the chosen voice, at alarm volume. */
class Announcer(context: Context) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech
    private var ready = false
    private var pending: String? = null
    private var choice = VoiceChoice.FEMALE_1

    /** False when the phone lacks the Google voices and a pitch-shifted default voice is used. */
    var realVoice = false
        private set

    val isReady: Boolean get() = ready

    init {
        val app = context.applicationContext
        val google = runCatching { app.packageManager.getPackageInfo(GOOGLE_TTS, 0); true }.getOrDefault(false)
        tts = if (google) TextToSpeech(app, this, GOOGLE_TTS) else TextToSpeech(app, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        apply()
        pending?.let { say(it) }
        pending = null
    }

    fun setChoice(c: VoiceChoice) {
        if (c == choice && ready) return
        choice = c
        if (ready) apply()
    }

    fun speak(text: String) {
        if (ready) say(text) else pending = text
    }

    fun shutdown() {
        runCatching { tts.stop(); tts.shutdown() }
    }

    private fun say(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "announce")
    }

    private fun apply() {
        val voice = findVoice()
        if (voice != null) {
            tts.setVoice(voice)
            tts.setPitch(1f)
            realVoice = true
        } else {
            runCatching { tts.setLanguage(Locale.US) }
            tts.setPitch(choice.fallbackPitch)
            realVoice = false
        }
        tts.setSpeechRate(1f)
    }

    private fun findVoice(): Voice? {
        val voices = runCatching { tts.voices }.getOrNull() ?: return null
        val usable = voices.filter {
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (it.features ?: emptySet<String>())
        }
        for (prefix in choice.candidates) {
            val matches = usable.filter { it.name.startsWith(prefix) }
            // Prefer voices that work offline.
            (matches.firstOrNull { !it.isNetworkConnectionRequired } ?: matches.firstOrNull())?.let { return it }
        }
        return null
    }

    companion object {
        private const val GOOGLE_TTS = "com.google.android.tts"
    }
}
