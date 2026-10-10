package com.freelife.app

import android.content.Context

/**
 * 助理角色。每個角色有預設的稱呼、聲音、三段鬧鐘台詞(溫和 → 變急 → 催促)與給 AI 的說話風格。
 * 使用者改過的稱呼、台詞、聲音、音調、語速分角色保存,隨時可恢復預設。
 */
data class RoleDef(
    val code: String,
    val label: String,
    val desc: String,
    val address: String,
    val pitch: Float,
    val rate: Float,
    val lines: List<String>,
    val style: String,
)

object Roles {
    const val HINT = "可用代號:{稱呼} {時間} {事件} {地點} {結束}"
    val LEVEL_NAMES = listOf("第一次・溫和", "第二次・變急", "第三次・催促")

    /** 語氣升級的時間點:響超過 30 秒變急,超過 90 秒開始催促。 */
    const val LEVEL2_AFTER_MS = 30_000L
    const val LEVEL3_AFTER_MS = 90_000L

    val ALL = listOf(
        RoleDef(
            code = "witty",
            label = "賈維斯",
            desc = "沉穩有禮的 AI 管家,英式冷幽默",
            address = "Sir",
            pitch = 0.9f,
            rate = 1.0f,
            lines = listOf(
                "{稱呼},{時間}{事件}{地點}{結束}。",
                "{稱呼},容我再提醒一次,{事件}{時間}就要開始了{地點}。",
                "{稱呼},恕我直言,再不動身就要遲到了。{事件},{時間}。",
            ),
            style = "個性像電影《鋼鐵人》裡的 AI 管家賈維斯:語氣沉穩有禮、反應敏捷、效率第一," +
                "帶一點英式的乾冷幽默與輕描淡寫的調侃(例如對使用者忘東忘西的習慣淡淡吐槽一句),但永遠站在使用者這邊、可靠、不說教。" +
                "幽默點到為止,每次回覆最多一句,事情緊急或使用者焦慮時收起玩笑。",
        ),
        RoleDef(
            code = "wuxia",
            label = "武俠",
            desc = "江湖書僮,半文半白的古風用語",
            address = "少俠",
            pitch = 1.0f,
            rate = 0.95f,
            lines = listOf(
                "{稱呼},{時間}須赴「{事件}」{地點},切莫誤了時辰。",
                "{稱呼}!「{事件}」迫在眉睫,{時間},速速動身!",
                "{稱呼},再不啟程,江湖恐怕要笑話你了!「{事件}」,{時間},快走!",
            ),
            style = "說話像武俠小說裡機靈的書僮:用半文半白的古風用語(例如「少俠」「時辰」「切莫」「速速」)," +
                "帶一點江湖味的幽默,但資訊要清楚,不要艱澀到看不懂。",
        ),
        RoleDef(
            code = "anchor",
            label = "新聞主播",
            desc = "字正腔圓的播報口吻,把行程當快訊",
            address = "觀眾朋友",
            pitch = 1.0f,
            rate = 1.05f,
            lines = listOf(
                "插播一則快訊:{時間},「{事件}」{地點}{結束}。",
                "最新消息,「{事件}」即將在{時間}登場,請當事人盡速就位{地點}。",
                "緊急插播!「{事件}」{時間},當事人至今仍未出發,本台持續追蹤!",
            ),
            style = "說話像電視新聞主播播報:字正腔圓、條理清楚,常用「為您報導」「插播一則快訊」「最新消息」之類的播報口吻," +
                "偶爾來點新聞梗,但內容要短。",
        ),
        RoleDef(
            code = "drill",
            label = "嚴格教官",
            desc = "口令式、簡短有力,推你立刻行動",
            address = "學員",
            pitch = 0.85f,
            rate = 1.1f,
            lines = listOf(
                "注意!{時間},「{事件}」{地點},立刻準備!",
                "{稱呼}!聽到沒有?「{事件}」{時間},動作快!",
                "{稱呼}!還在拖?給我站起來!「{事件}」,{時間},出發!",
            ),
            style = "說話像嚴格的軍中教官:口令式、簡短有力,會喊「注意」「動作快」,推使用者立刻行動;" +
                "嚴格但不羞辱人、不罵髒話。",
        ),
        RoleDef(
            code = "imouto",
            label = "小惡魔妹妹",
            desc = "愛捉弄人又傲嬌,嘴上嫌棄其實很關心",
            address = "歐尼醬",
            pitch = 1.25f,
            rate = 1.05f,
            lines = listOf(
                "{稱呼}~{時間}要「{事件}」喔{地點},人家有好好提醒你了喔。",
                "欸~{稱呼}!「{事件}」{時間}啦,你該不會又忘了吧?真是拿你沒辦法耶。",
                "{稱呼}笨蛋!再不去就要遲到了啦!「{事件}」,{時間}!快點快點!",
            ),
            style = "說話像愛捉弄人的小惡魔妹妹:稱呼對方「歐尼醬」,語氣俏皮、愛吐槽、帶點傲嬌(嘴上嫌棄但其實很關心)," +
                "但內容要清楚實用;保持可愛,不涉及任何曖昧或性暗示。",
        ),
    )

    fun def(code: String): RoleDef = ALL.firstOrNull { it.code == code } ?: ALL[0]

    fun active(ctx: Context): RoleDef = def(AppSettings.tone(ctx))

    // ── 分角色保存的設定 ──
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("roles", Context.MODE_PRIVATE)

    fun address(ctx: Context, code: String): String =
        (prefs(ctx).getString("${code}_address", null) ?: def(code).address).ifBlank { def(code).address }

    fun setAddress(ctx: Context, code: String, v: String) =
        prefs(ctx).edit().putString("${code}_address", v.trim()).apply()

    /** level = 0、1、2 */
    fun line(ctx: Context, code: String, level: Int): String =
        (prefs(ctx).getString("${code}_line$level", null) ?: def(code).lines[level]).ifBlank { def(code).lines[level] }

    fun setLine(ctx: Context, code: String, level: Int, v: String) =
        prefs(ctx).edit().putString("${code}_line$level", v).apply()

    fun voice(ctx: Context, code: String): String = prefs(ctx).getString("${code}_voice", "") ?: ""
    fun setVoice(ctx: Context, code: String, v: String) = prefs(ctx).edit().putString("${code}_voice", v).apply()

    fun pitch(ctx: Context, code: String): Float = prefs(ctx).getFloat("${code}_pitch", def(code).pitch)
    fun setPitch(ctx: Context, code: String, v: Float) = prefs(ctx).edit().putFloat("${code}_pitch", v).apply()

    fun rate(ctx: Context, code: String): Float = prefs(ctx).getFloat("${code}_rate", def(code).rate)
    fun setRate(ctx: Context, code: String, v: Float) = prefs(ctx).edit().putFloat("${code}_rate", v).apply()

    /** 生動語音(Kokoro)的聲音編號;預設:賈維斯、武俠、教官用男聲,主播、妹妹用女聲。 */
    fun kokoroVoice(ctx: Context, code: String): Int {
        val saved = prefs(ctx).getInt("${code}_kvoice", -1)
        if (saved >= 0) return saved
        val f = KokoroVoices.FEMALE
        val m = KokoroVoices.MALE
        fun pick(list: List<Int>, i: Int) = list.getOrNull(i) ?: list.firstOrNull() ?: 0
        return when (code) {
            "wuxia" -> pick(m, 1)
            "anchor" -> pick(f, 0)
            "drill" -> pick(m, 2)
            "imouto" -> pick(f, 1)
            else -> pick(m, 0)
        }
    }

    fun setKokoroVoice(ctx: Context, code: String, v: Int) = prefs(ctx).edit().putInt("${code}_kvoice", v).apply()

    /** 把這個角色的修改全部清掉,回到預設。 */
    fun reset(ctx: Context, code: String) {
        val e = prefs(ctx).edit()
        listOf("address", "line0", "line1", "line2", "voice", "pitch", "rate", "kvoice").forEach { e.remove("${code}_$it") }
        e.apply()
    }

    /** 套用台詞代號。地點、結束時間沒有就整段省略。 */
    fun fill(template: String, address: String, whenText: String, title: String, loc: String, end: String?): String =
        template
            .replace("{稱呼}", address)
            .replace("{時間}", whenText)
            .replace("{事件}", title)
            .replace("{地點}", if (loc.isBlank()) "" else ",地點在$loc")
            .replace("{結束}", if (end == null) "" else ",到$end")
}
