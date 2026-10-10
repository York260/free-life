package com.freelife.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** 一次合成的結果。ok = 已經拿到可以播放的 WAV。 */
data class SynthResult(val ok: Boolean, val note: String, val ms: Long)

/** 手機上有哪些朗讀引擎、該用什麼順序試。 */
object SpeechEngines {
    const val GOOGLE = "com.google.android.tts"

    fun installed(ctx: Context): List<String> = try {
        ctx.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun systemDefault(ctx: Context): String? = try {
        Settings.Secure.getString(ctx.contentResolver, "tts_default_synth")
    } catch (e: Exception) {
        null
    }

    /** 上次成功的 → 系統預設 → Google → 其他。null 代表「交給系統預設」。 */
    fun candidates(ctx: Context): List<String?> {
        val inst = installed(ctx)
        val order = listOfNotNull(
            AppSettings.ttsEngine(ctx).ifBlank { null },
            systemDefault(ctx),
            GOOGLE,
        ) + inst
        val list: List<String?> = order.distinct().filter { it in inst }
        return list.ifEmpty { listOf(null) }
    }

    fun label(pkg: String?): String = when {
        pkg == null -> "系統預設"
        pkg.contains("samsung", true) -> "三星"
        pkg == GOOGLE -> "Google"
        else -> pkg.substringAfterLast('.')
    }
}

/**
 * 用指定的朗讀引擎把一句話合成成 WAV 檔。
 * 不靠引擎自己播放、也不只靠它寫檔:合成時把引擎吐出的聲音資料(PCM)自己收起來,
 * 寫成標準 WAV,之後再用「鬧鐘」音量播放。所有呼叫都要在主執行緒。
 */
class SpeechSynth(private val ctx: Context, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var generation = 0

    fun release() {
        generation++
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (ignored: Exception) {
        }
        tts = null
    }

    fun synth(engine: String?, text: String, locale: Locale, out: File, timeoutMs: Long, done: (SynthResult) -> Unit) {
        release()
        val gen = generation
        val t0 = System.currentTimeMillis()
        val tag = SpeechEngines.label(engine)
        val raw = File(out.path + ".engine")
        val pcm = ByteArrayOutputStream()
        var rate = 0
        var enc = AudioFormat.ENCODING_PCM_16BIT
        var channels = 1
        var finished = false
        var inst: TextToSpeech? = null

        lateinit var timeout: Runnable
        fun finish(ok: Boolean, note: String) {
            if (finished || gen != generation) return
            finished = true
            main.removeCallbacks(timeout)
            val r = SynthResult(ok, note, System.currentTimeMillis() - t0)
            main.post { done(r) }
        }
        timeout = Runnable { finish(false, "逾時,引擎沒有回應") }
        main.postDelayed(timeout, timeoutMs)

        fun finishWithData() {
            if (finished || gen != generation) return
            val bytes = synchronized(pcm) { pcm.toByteArray() }
            val engineLen = if (raw.exists()) raw.length() else -1L
            when {
                bytes.size > 2000 && rate > 0 -> {
                    try {
                        writeWav(out, bytes, rate, enc, channels)
                        finish(true, "收到聲音 ${bytes.size / 1024}KB ${rate}Hz")
                    } catch (e: Exception) {
                        finish(false, "寫音檔失敗:${e.message}")
                    }
                }
                engineLen > 1000 -> {
                    raw.copyTo(out, overwrite = true)
                    finish(true, "引擎寫檔 ${engineLen / 1024}KB")
                }
                else -> finish(false, "沒有聲音資料(收到 ${bytes.size}B,檔案 ${engineLen}B)")
            }
        }

        val onInit = TextToSpeech.OnInitListener { status ->
            main.post {
                if (finished || gen != generation) return@post
                val t = inst
                if (status != TextToSpeech.SUCCESS || t == null) {
                    finish(false, "引擎啟動失敗($status)")
                    return@post
                }
                var lang = t.setLanguage(locale)
                if ((lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) && locale.language == "zh") {
                    lang = t.setLanguage(Locale.CHINESE)
                }
                log("[$tag] 語言碼=$lang(-1缺資料 -2不支援)")
                if (lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    finish(false, "不支援這個語言")
                    return@post
                }
                pickVoice(t, locale, tag)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                        rate = sampleRateInHz
                        enc = audioFormat
                        channels = channelCount.coerceAtLeast(1)
                    }

                    override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                        if (audio != null) synchronized(pcm) { pcm.write(audio) }
                    }

                    override fun onDone(utteranceId: String?) {
                        // 等檔案寫完再收
                        main.postDelayed({ finishWithData() }, 150L)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { finish(false, "引擎回報錯誤") }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        main.post { finish(false, "引擎錯誤碼 $errorCode") }
                    }
                })
                raw.delete()
                out.delete()
                val rc = t.synthesizeToFile(text, Bundle(), raw, "synth_$gen")
                if (rc != TextToSpeech.SUCCESS) finish(false, "合成指令失敗($rc)")
            }
        }
        inst = try {
            if (engine == null) TextToSpeech(ctx, onInit) else TextToSpeech(ctx, onInit, engine)
        } catch (e: Exception) {
            finish(false, "建立引擎失敗:${e.message}")
            null
        }
        tts = inst
    }

    /** 目前的聲音沒下載或要網路,就換一個已安裝的同語言聲音。 */
    private fun pickVoice(t: TextToSpeech, locale: Locale, tag: String) {
        try {
            val voices = t.voices?.toList() ?: emptyList()
            val same = voices.filter { it.locale.language == locale.language }
            val cur = t.voice
            log(
                "[$tag] 目前聲音=${cur?.name ?: "無"}${cur?.let { flags(it) } ?: ""};" +
                    "同語言 ${same.size} 個:" + same.take(5).joinToString("、") { it.name + flags(it) },
            )
            val bad = cur == null || notInstalled(cur) || cur.isNetworkConnectionRequired ||
                cur.locale.language != locale.language
            if (bad) {
                val best = same
                    .filter { !notInstalled(it) && !it.isNetworkConnectionRequired }
                    .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
                    .firstOrNull()
                if (best != null) {
                    t.voice = best
                    log("[$tag] 改用聲音 ${best.name}")
                }
            }
        } catch (e: Exception) {
            log("[$tag] 讀聲音清單失敗:${e.message}")
        }
    }

    private fun notInstalled(v: Voice) = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

    private fun flags(v: Voice): String =
        (if (notInstalled(v)) "(未下載)" else "") + (if (v.isNetworkConnectionRequired) "(需網路)" else "")

    companion object {
        /** PCM → 16 位元 WAV(浮點數格式先轉成 16 位元,播放器才吃得下)。 */
        fun writeWav(f: File, data: ByteArray, rate: Int, enc: Int, ch: Int) {
            val pcm16: ByteArray
            val bits: Int
            when (enc) {
                AudioFormat.ENCODING_PCM_8BIT -> {
                    pcm16 = data
                    bits = 8
                }
                AudioFormat.ENCODING_PCM_FLOAT -> {
                    val fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val out = ByteBuffer.allocate(fb.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN)
                    while (fb.hasRemaining()) {
                        out.putShort((fb.get().coerceIn(-1f, 1f) * 32767).toInt().toShort())
                    }
                    pcm16 = out.array()
                    bits = 16
                }
                else -> {
                    pcm16 = data
                    bits = 16
                }
            }
            val byteRate = rate * ch * bits / 8
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm16.size)
            h.put("WAVE".toByteArray(Charsets.US_ASCII))
            h.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            h.putShort(1).putShort(ch.toShort()).putInt(rate).putInt(byteRate)
            h.putShort((ch * bits / 8).toShort()).putShort(bits.toShort())
            h.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm16.size)
            f.outputStream().use {
                it.write(h.array())
                it.write(pcm16)
            }
        }
    }
}

/** 設定頁的「自動測試語音」:每個引擎都試,選出能念中文的,再用鬧鐘音量播給你聽。 */
object VoiceProbe {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    const val TEST_TEXT = "長官,這是語音測試,聽得到就代表成功。"

    fun isRunning() = running

    fun run(ctx: Context, onDone: (Boolean) -> Unit = {}) {
        if (running) return
        running = true
        val app = ctx.applicationContext
        fun log(s: String) = SpeechDiag.add(app, s)
        SpeechDiag.begin(app, "自動測試")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        log("手機:${Build.MANUFACTURER} ${Build.MODEL},Android ${Build.VERSION.RELEASE}")
        log(
            "音量:鬧鐘 ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}," +
                "媒體 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)},響鈴模式 ${am.ringerMode}",
        )
        val inst = SpeechEngines.installed(app)
        log("已安裝引擎:" + inst.joinToString("、").ifEmpty { "(看不到)" } + ";系統預設:" + (SpeechEngines.systemDefault(app) ?: "?"))
        val engines = SpeechEngines.candidates(app)
        val synth = SpeechSynth(app) { log(it) }
        val out = File(app.cacheDir, "probe.wav")

        fun end(ok: Boolean) {
            synth.release()
            running = false
            onDone(ok)
        }

        fun success(e: String?, tag: String) {
            AppSettings.setTtsEngine(app, e ?: "")
            log("結論:鬧鐘改用「$tag」引擎,並用鬧鐘音量播放。現在播放測試語音…")
            synth.release()
            playWav(app, out) { played ->
                log(if (played) "播放完成。有聽到「長官,這是語音測試」就成功了。" else "播放失敗。")
                running = false
                onDone(true)
            }
        }

        fun step(i: Int) {
            if (i >= engines.size) {
                log("結論:所有引擎都合成不出中文語音,鬧鐘會改響鈴聲。")
                if (SpeechEngines.GOOGLE !in inst) {
                    log("建議:到 Play 商店搜尋「Google 語音服務」(Speech Services by Google)安裝,再按一次自動測試。")
                } else {
                    log("建議:到 設定 → 一般 → 語言 → 文字轉語音,把引擎改成 Google,並在它的設定裡下載「中文(台灣)」語音,再按一次自動測試。")
                }
                end(false)
                return
            }
            val e = engines[i]
            val tag = SpeechEngines.label(e)
            synth.synth(e, TEST_TEXT, Locale.TAIWAN, out, 12_000L) { r ->
                log("[$tag] 中文:${if (r.ok) "成功" else "失敗"},${r.note}(${r.ms}ms)")
                if (r.ok) {
                    success(e, tag)
                } else {
                    // 英文也試一次:英文可以、中文不行 = 中文語音沒下載
                    synth.synth(e, "This is a voice test.", Locale.US, out, 10_000L) { r2 ->
                        log("[$tag] 英文:${if (r2.ok) "成功" else "失敗"},${r2.note}")
                        if (!r2.ok) {
                            step(i + 1)
                            return@synth
                        }
                        // 引擎正常、中文不行:通常是中文語音正在背景下載,等一下再試
                        log("[$tag] 判斷:引擎正常,中文語音可能正在下載,25 秒後再試…")
                        main.postDelayed({
                            synth.synth(e, TEST_TEXT, Locale.TAIWAN, out, 15_000L) { r3 ->
                                log("[$tag] 中文第二次:${if (r3.ok) "成功" else "失敗"},${r3.note}")
                                if (r3.ok) success(e, tag) else step(i + 1)
                            }
                        }, 25_000L)
                    }
                }
            }
        }
        step(0)
    }

    /** 打開手機的「文字轉語音」設定(換引擎、下載語音)。 */
    fun openTtsSettings(ctx: Context) {
        val tries = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(SpeechEngines.GOOGLE),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (ignored: Exception) {
            }
        }
    }

    /** 用鬧鐘音量播放 WAV;鬧鐘音量太小就暫時調到七成,播完還原。 */
    fun playWav(ctx: Context, f: File, done: (Boolean) -> Unit) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val want = (max * 0.7f).toInt()
        var changed = false
        try {
            if (cur < want) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, want, 0)
                changed = true
            }
        } catch (ignored: Exception) {
        }
        fun restore() {
            if (changed) try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, cur, 0)
            } catch (ignored: Exception) {
            }
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener {
                restore()
                it.release()
                done(true)
            }
            mp.setOnErrorListener { p, what, extra ->
                SpeechDiag.add(ctx, "播放器錯誤 $what/$extra")
                restore()
                p.release()
                done(false)
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            SpeechDiag.add(ctx, "播放例外:${e.message}")
            restore()
            mp.release()
            done(false)
        }
    }
}
