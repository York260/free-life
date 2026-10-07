package com.freelife.app

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** 對話中的一句話。fromUser = false 代表助理說的。 */
data class ChatMsg(val fromUser: Boolean, val text: String)

/** AI 的共用入口(使用 Claude)。 */
object Llm {
    /** 有填 Claude API 金鑰,才會啟用 AI。 */
    fun configured(ctx: Context): Boolean = AppSettings.apiKey(ctx).isNotBlank()

    /** 多輪對話;失敗丟 IOException。 */
    fun chat(ctx: Context, system: String, history: List<ChatMsg>, maxTokens: Int): String =
        chat(AppSettings.apiKey(ctx), AppSettings.model(ctx), system, history, maxTokens)

    fun chat(key: String, model: String, system: String, history: List<ChatMsg>, maxTokens: Int): String {
        val msgs = normalize(history)
        if (msgs.isEmpty()) throw IOException("沒有內容可以送出")
        return ClaudeClient.chat(key, model, system, msgs, maxTokens)
    }

    /** 測試金鑰是否可用,成功回傳 null,失敗回傳原因。 */
    fun test(key: String, model: String): String? =
        try {
            chat(key, model, "你是測試用助理。", listOf(ChatMsg(true, "只回答:OK")), 20)
            null
        } catch (e: Exception) {
            e.message ?: "未知錯誤"
        }

    /** 相鄰同角色的合併,並確保第一句是使用者說的(兩家 API 都要求交替)。 */
    private fun normalize(history: List<ChatMsg>): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        for (m in history) {
            if (m.text.isBlank()) continue
            val last = out.lastOrNull()
            if (last != null && last.fromUser == m.fromUser) {
                out[out.size - 1] = ChatMsg(m.fromUser, last.text + "\n" + m.text)
            } else {
                out.add(m)
            }
        }
        while (out.isNotEmpty() && !out.first().fromUser) out.removeAt(0)
        return out
    }

    /** 從 AI 回覆裡取出第一個 JSON 物件(模型偶爾會多寫幾個字或包在程式碼區塊裡)。 */
    fun extractObject(raw: String): JSONObject {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        if (a < 0 || b <= a) throw IOException("AI 回覆的格式無法解析")
        try {
            return JSONObject(raw.substring(a, b + 1))
        } catch (e: JSONException) {
            throw IOException("AI 回覆的格式無法解析", e)
        }
    }
}
