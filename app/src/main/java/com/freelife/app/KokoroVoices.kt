package com.freelife.app

/**
 * Kokoro v1.1 的聲音編號:0–2 是英文;3–57 是中文女聲(zf),58–102 是中文男聲(zm)。
 * 已在雲端逐一合成並量平均音高確認(.github/scripts/voices.py)。
 */
object KokoroVoices {
    val FEMALE: List<Int> = (3..57).toList()
    val MALE: List<Int> = (58..102).toList()
}
