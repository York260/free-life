package com.freelife.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * 離線生動語音:開源的 Kokoro 模型(用 sherpa-onnx 在手機上跑)。
 * 模型約 147MB,第一次使用時從 GitHub 下載;之後完全離線。
 * 所有合成都在背景執行緒,結果寫成 WAV,鬧鐘再用鬧鐘音量播放。
 */
object Kokoro {
    const val MODEL_URL = "https://github.com/York260/free-life/releases/download/voice-models/kokoro-zh.zip"

    /** 由雲端量音高分出來的中文女聲、男聲編號(見 .github/scripts/voices.py)。 */
    val FEMALE: List<Int> get() = KokoroVoices.FEMALE
    val MALE: List<Int> get() = KokoroVoices.MALE

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var tts: OfflineTts? = null
    @Volatile var downloading = false
        private set

    fun dir(ctx: Context) = File(ctx.filesDir, "kokoro")
    fun isReady(ctx: Context) = File(dir(ctx), "ok").exists()

    /** 使用者選了生動語音,而且模型已經下載好。 */
    fun active(ctx: Context) = AppSettings.voiceEngine(ctx) == "kokoro" && isReady(ctx)

    // ── 下載 ──
    // 交給系統的下載管理員:App 在背景、螢幕關掉、網路中斷都會自動續傳,通知列看得到進度。
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kokoro", Context.MODE_PRIVATE)

    /** 還有沒完成的下載(例如上次下載到一半就離開這頁)。 */
    fun pendingDownload(ctx: Context): Boolean = prefs(ctx).getLong("dl_id", -1L) >= 0 && !isReady(ctx)

    fun download(ctx: Context, onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        var id = prefs(app).getLong("dl_id", -1L)
        if (id < 0 || status(dm, id) == null) {
            val dest = File(app.getExternalFilesDir(null), "kokoro-zh.zip")
            dest.delete()
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(MODEL_URL))
                .setTitle("Free Life 生動語音")
                .setDescription("下載語音模型(約 147MB)")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(android.net.Uri.fromFile(dest))
            id = dm.enqueue(req)
            prefs(app).edit().putLong("dl_id", id).apply()
        }
        cbProgress = onProgress
        cbDone = onDone
        watch(app, dm, id)
    }

    // 設定頁重開時換成新的畫面接收進度
    @Volatile private var cbProgress: (Int) -> Unit = {}
    @Volatile private var cbDone: (Boolean, String) -> Unit = { _, _ -> }

    /** 回傳 (狀態, 已下載, 總大小, 原因);查不到回傳 null。 */
    private fun status(dm: android.app.DownloadManager, id: Long): LongArray? {
        dm.query(android.app.DownloadManager.Query().setFilterById(id)).use { c ->
            if (c == null || !c.moveToFirst()) return null
            fun col(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            return longArrayOf(
                col(android.app.DownloadManager.COLUMN_STATUS),
                col(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                col(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                col(android.app.DownloadManager.COLUMN_REASON),
            )
        }
    }

    private fun watch(app: Context, dm: android.app.DownloadManager, id: Long) {
        if (downloading) return
        downloading = true
        Thread {
            var result: Pair<Boolean, String>
            try {
                while (true) {
                    val st = status(dm, id) ?: throw IllegalStateException("下載被取消了")
                    when (st[0].toInt()) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> break
                        android.app.DownloadManager.STATUS_FAILED -> throw IllegalStateException("下載失敗(代碼 ${st[3]})")
                    }
                    val pct = if (st[2] > 0) (st[1] * 90 / st[2]).toInt() else 0
                    main.post { cbProgress(pct) }
                    Thread.sleep(1000)
                }
                main.post { cbProgress(92) }
                val zip = File(app.getExternalFilesDir(null), "kokoro-zh.zip")
                val d = dir(app)
                d.deleteRecursively()
                d.mkdirs()
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        val f = File(d, e.name)
                        if (!f.canonicalPath.startsWith(d.canonicalPath)) continue
                        if (e.isDirectory) {
                            f.mkdirs()
                        } else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zin.copyTo(it) }
                        }
                    }
                }
                File(d, "ok").writeText("1")
                zip.delete()
                prefs(app).edit().remove("dl_id").apply()
                main.post { cbProgress(97) }
                val okLoad = engine(app) != null
                result = if (okLoad) true to "下載完成" else false to "模型載入失敗"
            } catch (e: Exception) {
                prefs(app).edit().remove("dl_id").apply()
                try {
                    dm.remove(id)
                } catch (ignored: Exception) {
                }
                result = false to (e.message ?: "下載失敗")
            }
            downloading = false
            val (ok, msg) = result
            main.post {
                if (ok) cbProgress(100)
                cbDone(ok, msg)
            }
        }.start()
    }

    fun remove(ctx: Context) {
        release()
        dir(ctx).deleteRecursively()
        AppSettings.setVoiceEngine(ctx, "system")
    }

    // ── 合成 ──
    @Synchronized
    private fun engine(ctx: Context): OfflineTts? {
        tts?.let { return it }
        if (!isReady(ctx)) return null
        val d = dir(ctx).absolutePath
        return try {
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "$d/model.int8.onnx",
                        voices = "$d/voices.bin",
                        tokens = "$d/tokens.txt",
                        dataDir = "$d/espeak-ng-data",
                        lexicon = "$d/lexicon-us-en.txt,$d/lexicon-zh.txt",
                        dictDir = "$d/dict",
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "$d/date-zh.fst,$d/phone-zh.fst,$d/number-zh.fst",
                maxNumSentences = 1,
            )
            OfflineTts(config = cfg).also { tts = it }
        } catch (e: Throwable) {
            CrashLog.save(ctx, Exception("Kokoro 載入失敗", e))
            null
        }
    }

    fun release() {
        try {
            tts?.release()
        } catch (ignored: Throwable) {
        }
        tts = null
    }

    /** 目前角色要用的聲音編號與語速。 */
    fun voiceOf(ctx: Context, code: String = AppSettings.tone(ctx)): Int = Roles.kokoroVoice(ctx, code)

    /** 背景合成 text → out(WAV);完成時在主執行緒回呼。 */
    fun synth(ctx: Context, text: String, sid: Int, speed: Float, out: File, done: (Boolean, String) -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            val t0 = System.currentTimeMillis()
            val ok = try {
                val e = engine(app) ?: throw IllegalStateException("模型沒有下載")
                val n = e.numSpeakers()
                val audio = e.generate(text, sid.coerceIn(0, maxOf(0, n - 1)), speed)
                out.delete()
                audio.samples.isNotEmpty() && audio.save(out.absolutePath) && out.length() > 1000
            } catch (e: Throwable) {
                CrashLog.save(app, Exception("Kokoro 合成失敗", e))
                false
            }
            val ms = System.currentTimeMillis() - t0
            main.post { done(ok, if (ok) "Kokoro 合成 ${ms}ms" else "Kokoro 合成失敗") }
        }
    }

    /** 試聽:用某個聲音念一句(鬧鐘音量)。 */
    fun preview(ctx: Context, text: String, sid: Int, speed: Float, onDone: (Boolean) -> Unit) {
        val app = ctx.applicationContext
        val f = File(app.cacheDir, "kokoro_preview.wav")
        synth(app, text, sid, speed, f) { ok, _ ->
            if (ok) VoiceProbe.playWav(app, f) { onDone(it) } else onDone(false)
        }
    }
}


/** 排好鬧鐘時先在背景把三段台詞做成音檔,響的時候直接播,不用等合成。 */
object KokoroCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "kokoro_cache").apply { mkdirs() }

    fun file(ctx: Context, text: String, sid: Int, speed: Float): File =
        File(dir(ctx), "%08x.wav".format("$text|$sid|$speed".hashCode()))

    fun prerender(ctx: Context, r: Reminder) {
        val app = ctx.applicationContext
        if (!Kokoro.active(app) || r.ringless || r.done) return
        val at = r.triggerAt ?: return
        val now = System.currentTimeMillis()
        if (at < now || at - now > 3 * 86_400_000L) return
        cleanup(app)
        val sid = Kokoro.voiceOf(app)
        val speed = AppSettings.ttsRate(app)
        AlarmSpeech.lines(app, r, at).forEach { line ->
            val f = file(app, line, sid, speed)
            if (!f.exists()) Kokoro.synth(app, line, sid, speed, f) { _, _ -> }
        }
    }

    private fun cleanup(ctx: Context) {
        val old = System.currentTimeMillis() - 4 * 86_400_000L
        dir(ctx).listFiles()?.filter { it.lastModified() < old }?.forEach { it.delete() }
    }
}
