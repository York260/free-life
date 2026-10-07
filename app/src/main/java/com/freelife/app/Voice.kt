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

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                ready = listOf(Locale.TAIWAN, Locale.CHINA, Locale.CHINESE).any { loc ->
                    val r = engine.setLanguage(loc)
                    r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                }
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
