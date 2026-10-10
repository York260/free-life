package com.freelife.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** 用手機內建的語音合成朗讀。沒有中文語音時不會出聲,也不會當機。 */
class Speaker(ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingDone: (() -> Unit)? = null

    private val app = ctx.applicationContext

    init {
        // 用「念得出中文」的引擎(自動測試選出的,或 Google),不用手機預設:三星引擎常沒有中文語音
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                ready = listOf(Locale.TAIWAN, Locale.CHINA, Locale.CHINESE).any { loc ->
                    val r = engine.setLanguage(loc)
                    r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                }
                if (ready) VoiceStyle.apply(app, engine, Locale.TAIWAN)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        finish()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        finish()
                    }
                })
            }
        }
        val pkg = SpeechEngines.preferred(app)
        tts = try {
            if (pkg == null) TextToSpeech(app, listener) else TextToSpeech(app, listener, pkg)
        } catch (e: Exception) {
            null
        }
    }

    /** 設定改了(聲音、音調、語速)就重新套用。 */
    fun refreshStyle() {
        val engine = tts ?: return
        if (ready) VoiceStyle.apply(app, engine, Locale.TAIWAN)
    }

    private fun finish() {
        main.post {
            val d = pendingDone
            pendingDone = null
            d?.invoke()
        }
    }

    /** 朗讀 text;朗讀完(或無法朗讀)時呼叫 onDone。 */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val engine = tts
        if (!ready || engine == null || text.isBlank()) {
            onDone()
            return
        }
        pendingDone = onDone
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "free-life")
    }

    fun stop() {
        pendingDone = null
        tts?.stop()
    }

    fun shutdown() {
        pendingDone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
