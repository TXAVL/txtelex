package com.customkey.vietime.tools

import android.content.Context
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest

/**
 * Hợp đồng mà module on-demand `:translate` (ML Kit, dịch hoàn toàn trên máy) cài đặt. Base APK
 * không chứa ML Kit — module chỉ có khi cài từ Google Play (AAB) và người dùng bấm "Dịch".
 */
interface OnDeviceTranslator {
    /** [from]/[to] = mã ngôn ngữ BCP-47 ("vi", "en"). [done] gọi trên luồng bất kỳ, null = lỗi. */
    fun translate(context: Context, text: String, from: String, to: String, done: (String?) -> Unit)
}

object Translator {
    const val MODULE = "translate"
    private const val IMPL = "com.customkey.vietime.translate.MlKitTranslator"
    @Volatile private var impl: OnDeviceTranslator? = null

    private fun load(): OnDeviceTranslator? = impl ?: runCatching {
        Class.forName(IMPL).getDeclaredConstructor().newInstance() as OnDeviceTranslator
    }.getOrNull()?.also { impl = it }

    fun isAvailable(ctx: Context): Boolean {
        val installed = runCatching { SplitInstallManagerFactory.create(ctx).installedModules.contains(MODULE) }.getOrDefault(false)
        return (installed || impl != null) && load() != null
    }

    /** Xin Play tải module (chỉ chạy khi app cài từ Google Play). */
    fun requestInstall(ctx: Context) {
        runCatching {
            SplitInstallManagerFactory.create(ctx).startInstall(SplitInstallRequest.newBuilder().addModule(MODULE).build())
        }
    }

    /** Có dấu tiếng Việt ⇒ dịch Việt → Anh, ngược lại Anh → Việt. */
    fun direction(text: String): Pair<String, String> =
        if (text.any { it.code > 0x7F && it.isLetter() }) "vi" to "en" else "en" to "vi"

    fun translate(ctx: Context, text: String, done: (String?) -> Unit) {
        val t = load() ?: return done(null)
        val (from, to) = direction(text)
        runCatching { t.translate(ctx.applicationContext, text, from, to, done) }.onFailure { done(null) }
    }
}
