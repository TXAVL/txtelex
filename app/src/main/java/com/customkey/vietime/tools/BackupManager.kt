package com.customkey.vietime.tools

import android.content.Context
import com.customkey.vietime.BuildConfig
import com.customkey.vietime.ime.ImePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sao lưu & khôi phục: mọi cài đặt (kiểu gõ, theme, âm thanh, bố cục, gõ tắt…), mẫu câu và mục
 * clipboard ĐÃ GHIM → một file JSON. Không chứa lịch sử chưa ghim, từ đã học hay ảnh nền.
 */
object BackupManager {
    const val FORMAT = "txtelex-backup"
    const val VERSION = 1
    /** Khoá chỉ có ý nghĩa trên máy này — không sao lưu. */
    private val SKIP = setOf("theme.wallVersion", "sound.customVersion", "plus.purchased", "englishMode")

    data class Result(val settings: Int, val snippets: Int, val pinned: Int)

    fun export(ctx: Context): String {
        val prefs = JSONObject()
        for ((k, v) in ImePrefs.of(ctx).all) {
            if (k in SKIP || v == null) continue
            prefs.put(k, JSONObject().put("t", typeOf(v)).put("v", if (v is Set<*>) JSONArray(v.toList()) else v))
        }
        val snippets = JSONArray(ToolsRepo.snippets(ctx).items.map { it.text })
        val pinned = JSONArray(ToolsRepo.clipboard(ctx).items.filter { it.pinned }.map { it.text })
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("app", BuildConfig.VERSION_NAME).put("createdAt", System.currentTimeMillis())
            .put("prefs", prefs).put("snippets", snippets).put("pinnedClips", pinned)
            .toString(2)
    }

    /** Nhập: ghi đè cài đặt có trong file, GỘP mẫu câu và mục ghim. Ném IllegalArgumentException nếu file sai. */
    fun import(ctx: Context, text: String): Result {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("File không phải JSON") }
        require(root.optString("format") == FORMAT) { "Không phải file sao lưu TxTelex" }
        require(root.optInt("version", 0) <= VERSION) { "File từ bản TxTelex mới hơn — cập nhật app trước" }
        var n = 0
        root.optJSONObject("prefs")?.let { p ->
            val e = ImePrefs.of(ctx).edit()
            for (k in p.keys()) {
                if (k in SKIP) continue
                val o = p.optJSONObject(k) ?: continue
                when (o.optString("t")) {
                    "b" -> e.putBoolean(k, o.optBoolean("v"))
                    "i" -> e.putInt(k, o.optInt("v"))
                    "l" -> e.putLong(k, o.optLong("v"))
                    "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                    "s" -> e.putString(k, o.optString("v"))
                    "set" -> o.optJSONArray("v")?.let { a -> e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                    else -> continue
                }
                n++
            }
            e.apply()
        }
        var sn = 0
        root.optJSONArray("snippets")?.let { a ->
            ToolsRepo.editSnippets(ctx) { st -> for (i in a.length() - 1 downTo 0) if (st.add(a.optString(i)) != null) sn++; sn > 0 }
        }
        var pc = 0
        root.optJSONArray("pinnedClips")?.let { a ->
            ToolsRepo.editClipboard(ctx) { v ->
                for (i in 0 until a.length()) {
                    val t = a.optString(i)
                    v.add(t, System.currentTimeMillis() - i)
                    v.items.firstOrNull { it.text == t }?.let { v.setPinned(it.id, true); pc++ }
                }
                pc > 0
            }
        }
        return Result(n, sn, pc)
    }

    private fun typeOf(v: Any): String = when (v) {
        is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "set"; else -> "s"
    }
}
