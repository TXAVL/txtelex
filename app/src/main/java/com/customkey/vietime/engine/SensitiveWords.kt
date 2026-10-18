// MIT License — Copyright (c) 2026 Phil Trinh
// Extracted from ptrinh/viettelex (https://github.com/ptrinh/viettelex), see assets/licenses/viettelex-LICENSE.txt.
// Modified for com.customkey.vietime.

package com.customkey.vietime.engine

/** Lọc từ tục khỏi THANH GỢI Ý (vẫn học). Emoji không bị lọc. */
object SensitiveWords {
    fun filter(words: List<String>, enabled: Boolean): List<String> =
        if (enabled) words.filter { it.lowercase() !in set } else words

    val set: Set<String> = hashSetOf(
        "đcm", "đm", "dm", "dcm", "vcl", "vkl", "vl", "cl", "clgt", "cmnr",
        "cứt", "lồn", "cặc", "buồi", "đĩ", "điếm", "đụ", "địt",
        "tml", "sml", "dâm", "đkm", "dkm", "buscu",
        "fuck", "shit", "bitch", "wtf",
    )
}
