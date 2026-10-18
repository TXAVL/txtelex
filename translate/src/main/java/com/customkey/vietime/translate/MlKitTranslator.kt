package com.customkey.vietime.translate

import android.content.Context
import com.customkey.vietime.tools.OnDeviceTranslator
import com.google.android.play.core.splitcompat.SplitCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/** Dịch Việt ⇄ Anh hoàn toàn trên máy bằng ML Kit. Nạp qua reflection từ base (Translator). */
class MlKitTranslator : OnDeviceTranslator {
    override fun translate(context: Context, text: String, from: String, to: String, done: (String?) -> Unit) {
        // Provider MlKitInitProvider bị gỡ khỏi manifest base (module on-demand) ⇒ khởi tạo tay.
        runCatching { SplitCompat.install(context); MlKitContext.initializeIfNeeded(context) }.onFailure { return done(null) }
        val src = TranslateLanguage.fromLanguageTag(from) ?: return done(null)
        val dst = TranslateLanguage.fromLanguageTag(to) ?: return done(null)
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build())
        client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .continueWithTask { client.translate(text) }
            .addOnSuccessListener { done(it); client.close() }
            .addOnFailureListener { done(null); client.close() }
    }
}
