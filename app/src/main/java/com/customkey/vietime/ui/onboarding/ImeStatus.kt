package com.customkey.vietime.ui.onboarding

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Trạng thái bàn phím trong hệ thống: đã bật (Bước 1) và đang được chọn (Bước 2). */
data class ImeStatus(val enabled: Boolean, val selected: Boolean) {
    companion object {
        fun read(ctx: Context): ImeStatus {
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            val pkg = ctx.packageName
            val enabled = imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            return ImeStatus(enabled, enabled && cur.startsWith("$pkg/"))
        }
    }
}

/**
 * Trạng thái IME tự cập nhật — dấu tích xanh hiện ngay khi người dùng vừa bật / chọn xong:
 * - ON_RESUME: quay lại từ màn Cài đặt hệ thống (Bước 1).
 * - ContentObserver trên ENABLED_INPUT_METHODS / DEFAULT_INPUT_METHOD: hộp thoại chọn bàn phím
 *   (Bước 2) là dialog hệ thống — activity không qua onResume.
 * - [onWindowFocus]: MainActivity gọi khi lấy lại focus (dự phòng máy không báo observer).
 */
@Composable
fun rememberImeStatus(onWindowFocus: State<Int>? = null): State<ImeStatus> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(ImeStatus.read(ctx)) }
    fun refresh() { val s = ImeStatus.read(ctx); if (s != state.value) state.value = s }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    DisposableEffect(ctx) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val cr = ctx.contentResolver
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
        onDispose { cr.unregisterContentObserver(observer) }
    }
    LaunchedEffect(onWindowFocus?.value) { refresh() }
    return state
}
