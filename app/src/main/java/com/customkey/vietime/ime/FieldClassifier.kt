package com.customkey.vietime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.customkey.vietime.engine.FieldTraits

/** Loại bàn phím cần dựng cho ô hiện tại. */
enum class FieldKind { TEXT, URL, EMAIL, NUMBER, PHONE, DATETIME }

/** Nhãn phím Enter theo IME action của ô. */
enum class EnterAction { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

/**
 * Kết quả phân loại một ô nhập (EditorInfo) — quyết định engine có được compose, có gợi ý,
 * có tự viết hoa, phím Enter làm gì. Hàm thuần (chỉ đọc hằng số int) ⇒ test được trên JVM.
 */
data class FieldConfig(
    val kind: FieldKind,
    /** Ô mật khẩu (password / web password / visible password thật / PIN): KHÔNG BAO GIỜ compose. */
    val isSecure: Boolean,
    /** Gõ nguyên văn, bỏ Telex/VNI (số, email, FORCE_ASCII, TYPE_NULL, mật khẩu hiện). */
    val passthrough: Boolean,
    val capSentences: Boolean,
    val capWords: Boolean,
    val capCharacters: Boolean,
    val suggestionsAllowed: Boolean,
    /** TYPE_NULL: app chỉ nhận key event (terminal…), không đọc/sửa text. */
    val rawKeys: Boolean,
    val multiLine: Boolean,
    val enterAction: EnterAction,
    /** actionId gửi performEditorAction; 0 = Enter thường (xuống dòng). */
    val actionId: Int,
    val noLearning: Boolean,
    /** Ô OTP/mã một lần (autofill hint hoặc số ngắn) — tắt mọi biến đổi. */
    val otp: Boolean,
    /** Ô địa chỉ / tìm kiếm (omnibox Chrome/Brave, Firefox): gợi ý chữ thường, không viết hoa. */
    val addressOrSearch: Boolean,
    val emailCompose: Boolean,
    val packageName: String?,
) {
    /** Có được sửa lại từ đã chốt (⌫ mở lại từ / phím dấu nạp từ trước con trỏ) không. */
    val canReEdit: Boolean get() = !rawKeys && !isSecure && kind != FieldKind.URL

    fun toTraits(): FieldTraits = FieldTraits(
        isSecure = isSecure,
        passthrough = passthrough || otp,
        capSentences = capSentences && !addressOrSearch,
        suggestionsAllowed = suggestionsAllowed && !otp,
        capWords = capWords,
        capCharacters = capCharacters,
        noLearning = noLearning || isSecure,
        packageName = packageName,
        urlField = kind == FieldKind.URL,
        emailField = kind == FieldKind.EMAIL,
        emailCompose = emailCompose,
        stripTools = !isSecure && !rawKeys,
        lowercaseSuggestions = addressOrSearch,
    )

    /** Bàn phím số (ô NUMBER/PHONE/DATETIME). */
    val numeric: Boolean get() = kind == FieldKind.NUMBER || kind == FieldKind.PHONE || kind == FieldKind.DATETIME

    companion object {
        /** Ô mặc định khi chưa có EditorInfo (khu Test phím / unit test). */
        val DEFAULT = FieldClassifier.classify(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0)
    }
}

/**
 * Phân loại EditorInfo → [FieldConfig]. Quy tắc đối chiếu hành vi ref (viettelex FieldMapping):
 * - Mật khẩu: TYPE_TEXT_VARIATION_PASSWORD / WEB_PASSWORD / VISIBLE_PASSWORD (trừ ô chat đa dòng
 *   giả visible-password để tắt gợi ý) và NUMBER_VARIATION_PASSWORD ⇒ secure (không compose).
 * - URI (thanh địa chỉ trình duyệt): vẫn gõ Telex được (tìm kiếm tiếng Việt), nhưng không gõ tắt,
 *   không sửa lại từ (omnibox tự hoàn tất), gợi ý chữ thường.
 * - Email / số / FORCE_ASCII / TYPE_NULL ⇒ passthrough.
 */
object FieldClassifier {
    /** OTP: Android autofill hint (API 26+ cho View). */
    private val OTP_HINTS = setOf("smsOTPCode", "otp", "one-time-code", "2faAppOTPCode", "emailOTPCode")

    fun classify(editor: EditorInfo?): FieldConfig {
        if (editor == null) return FieldConfig.DEFAULT
        val hints = runCatching { editor.hintText?.toString() }.getOrNull()
        return classify(
            editor.inputType, editor.imeOptions, editor.packageName,
            hasActionLabel = editor.actionLabel != null, customActionId = editor.actionId,
            hintText = hints,
        )
    }

    fun classify(
        inputType: Int, imeOptions: Int, packageName: String? = null,
        hasActionLabel: Boolean = false, customActionId: Int = 0,
        hintText: String? = null, autofillHints: Array<String>? = null,
    ): FieldConfig {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS
        val rawKeys = inputType == InputType.TYPE_NULL
        val text = cls == InputType.TYPE_CLASS_TEXT

        val multiLineFlag = text && (flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val visiblePw = text && variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        // Một số app chat đặt VISIBLE_PASSWORD + MULTI_LINE chỉ để tắt gợi ý — không phải mật khẩu.
        val fakeVisiblePw = visiblePw && multiLineFlag
        val secure = (text && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || (visiblePw && !fakeVisiblePw)))
            || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        val email = text && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val uri = text && variation == InputType.TYPE_TEXT_VARIATION_URI
        val numericCls = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
            || cls == InputType.TYPE_CLASS_DATETIME
        val forceAscii = (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) != 0
        val passthrough = rawKeys || numericCls || email || forceAscii || secure

        val kind = when {
            cls == InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            cls == InputType.TYPE_CLASS_DATETIME -> FieldKind.DATETIME
            cls == InputType.TYPE_CLASS_NUMBER -> FieldKind.NUMBER
            email -> FieldKind.EMAIL
            uri -> FieldKind.URL
            else -> FieldKind.TEXT
        }
        val wantsAssist = (flags and (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)) != 0
        val noSuggest = text && !wantsAssist && ((flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 || fakeVisiblePw)
        val suggestionsAllowed = text && !secure && !passthrough && !noSuggest

        val otp = isOtp(kind, variation, hintText, autofillHints)

        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        var enter = EnterAction.NEWLINE
        var actionId = 0
        if (!multiLineFlag && hasActionLabel && customActionId != 0) {
            enter = EnterAction.DONE; actionId = customActionId
        } else if (!multiLineFlag && !noEnterAction) {
            when (action) {
                EditorInfo.IME_ACTION_GO -> { enter = EnterAction.GO; actionId = action }
                EditorInfo.IME_ACTION_SEARCH -> { enter = EnterAction.SEARCH; actionId = action }
                EditorInfo.IME_ACTION_SEND -> { enter = EnterAction.SEND; actionId = action }
                EditorInfo.IME_ACTION_NEXT -> { enter = EnterAction.NEXT; actionId = action }
                EditorInfo.IME_ACTION_DONE -> { enter = EnterAction.DONE; actionId = action }
                EditorInfo.IME_ACTION_PREVIOUS -> { enter = EnterAction.PREVIOUS; actionId = action }
            }
        }
        val addressOrSearch = text && !secure && (uri || variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
            (!multiLineFlag && !noEnterAction && (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH)))

        return FieldConfig(
            kind = kind, isSecure = secure, passthrough = passthrough,
            capSentences = text && (flags and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
            capWords = text && (flags and InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0,
            capCharacters = text && (flags and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0,
            suggestionsAllowed = suggestionsAllowed, rawKeys = rawKeys, multiLine = multiLineFlag,
            enterAction = enter, actionId = actionId,
            noLearning = (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0,
            otp = otp, addressOrSearch = addressOrSearch,
            emailCompose = false,   // email luôn gõ nguyên văn ở bản này
            packageName = packageName,
        )
    }

    private fun isOtp(kind: FieldKind, variation: Int, hintText: String?, autofillHints: Array<String>?): Boolean {
        if (autofillHints?.any { it in OTP_HINTS } == true) return true
        val h = hintText?.lowercase() ?: return false
        val looksOtp = "otp" in h || "mã xác" in h || "verification code" in h || "mã xác minh" in h || "one-time" in h
        return looksOtp && (kind == FieldKind.NUMBER || kind == FieldKind.TEXT) &&
            variation != InputType.TYPE_TEXT_VARIATION_URI
    }
}
