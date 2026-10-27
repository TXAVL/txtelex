package com.customkey.vietime.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bảng màu giao diện TxTelex. Sáng/tối theo hệ thống; nền xám nhạt, thẻ trắng bo tròn, màu nhấn xanh dương
 * lấy từ logo. Mọi màn dùng [Tx] để lấy màu — đổi ở đây là đổi cả app.
 */
@Immutable
class TxPalette(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val fill: Color,
    val accent: Color,
    val onAccent: Color,
    val green: Color,
    val red: Color,
    val bar: Color,
)

private val LightPalette = TxPalette(
    dark = false,
    bg = Color(0xFFF2F3F7), card = Color.White,
    label = Color(0xFF14171F), secondary = Color(0xFF6B7180), tertiary = Color(0xFFA2A8B5),
    separator = Color(0x1F14171F), fill = Color(0x14293A66),
    accent = Color(0xFF1668F0), onAccent = Color.White,
    green = Color(0xFF2FB964), red = Color(0xFFE5384B), bar = Color(0xF5FFFFFF),
)
private val DarkPalette = TxPalette(
    dark = true,
    bg = Color(0xFF0B0D13), card = Color(0xFF171A23),
    label = Color(0xFFF1F3F9), secondary = Color(0xFF9AA2B5), tertiary = Color(0xFF5E667A),
    separator = Color(0x2EFFFFFF), fill = Color(0x1FFFFFFF),
    accent = Color(0xFF5B9CFF), onAccent = Color(0xFF06101F),
    green = Color(0xFF3BD67B), red = Color(0xFFFF5C6F), bar = Color(0xF2222633),
)

val LocalTx = staticCompositionLocalOf { LightPalette }

/** Lối tắt lấy màu theo giao diện hiện tại (đọc trong @Composable). Tên cũ giữ lại để các màn cũ vẫn chạy. */
object Tx {
    val palette: TxPalette @Composable get() = LocalTx.current
    val Background: Color @Composable get() = LocalTx.current.bg
    val Text: Color @Composable get() = LocalTx.current.label
    val TextDim: Color @Composable get() = LocalTx.current.secondary
    val TextFaint: Color @Composable get() = LocalTx.current.tertiary
    val Stroke: Color @Composable get() = LocalTx.current.separator
    val Glass: Color @Composable get() = LocalTx.current.fill
    val GlassStrong: Color @Composable get() = LocalTx.current.fill
    val Deep: Color @Composable get() = LocalTx.current.card
    val Cyan: Color @Composable get() = LocalTx.current.accent
    val CyanSoft: Color @Composable get() = LocalTx.current.accent
    val Blue: Color @Composable get() = LocalTx.current.accent
    val Violet: Color @Composable get() = LocalTx.current.accent
    val Night: Color @Composable get() = LocalTx.current.onAccent
    val Green: Color @Composable get() = LocalTx.current.green
    val Red: Color @Composable get() = LocalTx.current.red
    val accent: Brush @Composable get() = SolidColor(LocalTx.current.accent)
    val accentSoft: Brush @Composable get() = SolidColor(LocalTx.current.accent.copy(alpha = 0.14f))
    val border: Brush @Composable get() = SolidColor(LocalTx.current.separator)
    val background: Brush @Composable get() = SolidColor(LocalTx.current.bg)
}

private fun scheme(p: TxPalette) = if (p.dark) darkColorScheme(
    primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.label,
    surface = p.card, onSurface = p.label, surfaceVariant = p.fill, onSurfaceVariant = p.secondary,
    outline = p.separator, error = p.red,
) else lightColorScheme(
    primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.label,
    surface = p.card, onSurface = p.label, surfaceVariant = p.fill, onSurfaceVariant = p.secondary,
    outline = p.separator, error = p.red,
)

private val TxType = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun TxTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalTx provides p) {
        MaterialTheme(colorScheme = scheme(p), typography = TxType, content = content)
    }
}

/** Nền màn hình. */
@Composable
fun TxBackground(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalTx.current.bg), content = content)
}

// ───────────────────────────── trang + nhóm ─────────────────────────────

/**
 * Một trang cuộn có tiêu đề lớn. [onBack] khác null ⇒ hiện nút quay lại. [bottomSpace] chừa chỗ cho thanh tab nổi.
 */
@Composable
fun TxPage(
    title: String,
    onBack: (() -> Unit)? = null,
    bottomSpace: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalTx.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        if (onBack != null) {
            Row(
                Modifier.clickable(remember { MutableInteractionSource() }, null, onClick = onBack).padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Back, p.accent, 22.dp)
                Spacer(Modifier.width(2.dp))
                Text("Quay lại", color = p.accent, fontSize = 16.sp)
            }
        }
        Text(title, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = p.label,
            style = MaterialTheme.typography.headlineMedium)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        Spacer(Modifier.height(bottomSpace + 24.dp))
    }
}

/** Nhóm có tiêu đề nhỏ phía trên, thẻ bo tròn, chú thích xám phía dưới. Các dòng tự có đường kẻ ngăn cách khi dùng [TxDivider]. */
@Composable
fun TxSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalTx.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (header != null) Text(header.uppercase(), Modifier.padding(start = 14.dp, bottom = 7.dp), color = p.secondary,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.card), content = content)
        if (footer != null) Text(footer, Modifier.padding(start = 14.dp, end = 14.dp, top = 7.dp), color = p.secondary, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
fun TxDivider(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.6.dp).background(LocalTx.current.separator))
}

/** Dòng chuẩn trong [TxSection]: tiêu đề + chú thích, biểu tượng bên trái và nội dung bên phải tuỳ ý. */
@Composable
fun TxRow(
    title: String,
    subtitle: String? = null,
    glyph: Glyph? = null,
    tint: Color? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = LocalTx.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) { TxIconTile(glyph, tint ?: p.accent); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (destructive) p.red else p.label, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = p.secondary, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
        trailing?.invoke(this)
        if (chevron) GlyphIcon(Glyph.Chevron, p.tertiary, 18.dp)
    }
}

/** Ô vuông bo góc có màu, chứa biểu tượng trắng — như biểu tượng trong Cài đặt iOS. */
@Composable
fun TxIconTile(glyph: Glyph, color: Color, size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(color), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, Color.White, size * 0.62f)
    }
}

/** Công tắc kiểu iOS. */
@Composable
fun TxSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalTx.current
    val track by animateColorAsState(if (checked) p.green else if (p.dark) Color(0xFF3A3F4D) else Color(0xFFD9DCE4), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "thumb")
    Box(
        Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Switch) { onChange(!checked) },
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** Dòng công tắc: tiêu đề, chú thích, công tắc bên phải. */
@Composable
fun TxSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    TxRow(title, subtitle, onClick = { onChange(!checked) }) { TxSwitch(checked, onChange) }
}

// ───────────────────────────── thẻ + nút (tên cũ) ─────────────────────────────

/** Thẻ bo tròn nền trắng/xám đậm. (Tên cũ "Glass" — giờ là thẻ phẳng, không còn viền neon.) */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalTx.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (glow) p.accent.copy(alpha = 0.12f) else p.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Nút chính: viên thuốc màu nhấn. */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalTx.current
    Box(
        modifier.heightIn(min = 50.dp).clip(CircleShape)
            .background(if (enabled) p.accent else p.fill)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) p.onAccent else p.tertiary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

/** Nút phụ: viên thuốc nền nhạt, chữ màu nhấn. */
@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalTx.current
    Box(
        modifier.heightIn(min = 44.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = p.accent, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

/** Nhãn mục nhỏ in hoa. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp), color = LocalTx.current.secondary,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
}

// ───────────────────────────── thanh tab nổi ─────────────────────────────

class TxTabSpec(val title: String, val glyph: Glyph)

/** Thanh tab nổi dạng viên thuốc, ô chọn trượt bằng lò xo. [collapsed] ⇒ chỉ còn biểu tượng. */
@Composable
fun TxFloatingTabBar(tabs: List<TxTabSpec>, selected: Int, collapsed: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalTx.current
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Triple<Int, Int, Int>>() }
    val sel = bounds[selected]
    val spec = spring<Dp>(dampingRatio = 0.78f, stiffness = 380f)
    val pillX by animateDpAsState(with(density) { (sel?.first ?: 0).toDp() }, spec, label = "px")
    val pillW by animateDpAsState(with(density) { (sel?.second ?: 0).toDp() }, spec, label = "pw")
    val pillH by animateDpAsState(with(density) { (sel?.third ?: 0).toDp() }, spec, label = "ph")
    Box(
        modifier.navigationBarsPadding().padding(bottom = 10.dp)
            .shadow(14.dp, CircleShape, ambientColor = Color.Black.copy(0.25f), spotColor = Color.Black.copy(0.25f))
            .background(p.bar, CircleShape).padding(5.dp),
    ) {
        if (sel != null) Box(Modifier.offset(x = pillX).size(pillW, pillH).background(p.accent, CircleShape))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                val fg = if (on) p.onAccent else p.secondary
                Column(
                    Modifier.onPlaced { bounds[i] = Triple(it.positionInParent().x.toInt(), it.size.width, it.size.height) }
                        .clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }
                        .padding(horizontal = if (collapsed) 16.dp else 15.dp, vertical = if (collapsed) 11.dp else 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    GlyphIcon(t.glyph, fg, 22.dp)
                    if (!collapsed) Text(t.title, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

// ───────────────────────────── biểu tượng vẽ tay ─────────────────────────────

enum class Glyph { Keyboard, Sliders, Palette, Speaker, Layout, Grid, Bolt, Book, Swipe, Lock, Info, Chevron, Back, Check, Cloud, Bug }

/** Biểu tượng nét mảnh vẽ bằng Canvas trên lưới 24×24 — không cần file ảnh. */
@Composable
fun GlyphIcon(g: Glyph, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        fun dot(x: Float, y: Float, r: Float = 1.1f) = drawCircle(color, r * u, o(x, y))
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, o(x1, y1), o(x2, y2), st.width, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, o(x, y), Size(w * u, h * u), CornerRadius(r * u), st)
        when (g) {
            Glyph.Keyboard -> {
                box(2f, 6f, 20f, 12f, 2.5f)
                for (i in 0..3) dot(6.2f + i * 3.9f, 10f); line(8f, 14.4f, 16f, 14.4f)
            }
            Glyph.Sliders -> {
                line(3f, 7f, 21f, 7f); line(3f, 12f, 21f, 12f); line(3f, 17f, 21f, 17f)
                drawCircle(color, 2.3f * u, o(8f, 7f)); drawCircle(color, 2.3f * u, o(16f, 12f)); drawCircle(color, 2.3f * u, o(10f, 17f))
            }
            Glyph.Palette -> {
                drawCircle(color, 9f * u, o(12f, 12f), style = st)
                dot(8.5f, 10f); dot(12f, 7.6f); dot(15.5f, 10f); dot(9f, 14.6f)
            }
            Glyph.Speaker -> {
                val path = Path().apply { moveTo(4f * u, 9.5f * u); lineTo(8f * u, 9.5f * u); lineTo(13f * u, 5f * u); lineTo(13f * u, 19f * u); lineTo(8f * u, 14.5f * u); lineTo(4f * u, 14.5f * u); close() }
                drawPath(path, color, style = st)
                drawArc(color, -40f, 80f, false, o(10f, 7f), Size(10f * u, 10f * u), style = st)
            }
            Glyph.Layout -> { box(3f, 4f, 18f, 16f, 2.5f); line(12f, 4f, 12f, 20f); line(3f, 12f, 12f, 12f) }
            Glyph.Grid -> { box(3.5f, 3.5f, 7f, 7f, 2f); box(13.5f, 3.5f, 7f, 7f, 2f); box(3.5f, 13.5f, 7f, 7f, 2f); box(13.5f, 13.5f, 7f, 7f, 2f) }
            Glyph.Bolt -> {
                val path = Path().apply { moveTo(13.5f * u, 2.5f * u); lineTo(5f * u, 13.5f * u); lineTo(11f * u, 13.5f * u); lineTo(10f * u, 21.5f * u); lineTo(19f * u, 10f * u); lineTo(13f * u, 10f * u); close() }
                drawPath(path, color, style = st)
            }
            Glyph.Book -> { box(5f, 3.5f, 14f, 17f, 2f); line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f) }
            Glyph.Swipe -> {
                val path = Path().apply { moveTo(4f * u, 17f * u); cubicTo(8f * u, 4f * u, 12f * u, 22f * u, 20f * u, 7f * u) }
                drawPath(path, color, style = st); drawCircle(color, 2.2f * u, o(20f, 7f))
            }
            Glyph.Lock -> { box(5f, 10.5f, 14f, 10f, 2.5f); drawArc(color, 180f, 180f, false, o(8f, 3.5f), Size(8f * u, 9f * u), style = st) }
            Glyph.Info -> { drawCircle(color, 9f * u, o(12f, 12f), style = st); dot(12f, 7.6f); line(12f, 11f, 12f, 16.6f) }
            Glyph.Chevron -> {
                val path = Path().apply { moveTo(9f * u, 5f * u); lineTo(16f * u, 12f * u); lineTo(9f * u, 19f * u) }
                drawPath(path, color, style = st)
            }
            Glyph.Back -> {
                val path = Path().apply { moveTo(15f * u, 5f * u); lineTo(8f * u, 12f * u); lineTo(15f * u, 19f * u) }
                drawPath(path, color, style = st)
            }
            Glyph.Check -> {
                val path = Path().apply { moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) }
                drawPath(path, color, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.Cloud -> {
                val path = Path().apply {
                    moveTo(7f * u, 18.5f * u); cubicTo(2f * u, 18.5f * u, 2f * u, 11f * u, 7.5f * u, 11f * u)
                    cubicTo(8.5f * u, 5.5f * u, 17f * u, 5.5f * u, 17.5f * u, 11.5f * u)
                    cubicTo(22f * u, 11.5f * u, 22f * u, 18.5f * u, 17f * u, 18.5f * u); close()
                }
                drawPath(path, color, style = st)
            }
            Glyph.Bug -> { box(7f, 8f, 10f, 12f, 5f); line(12f, 8f, 12f, 20f); line(3f, 12f, 7f, 12f); line(17f, 12f, 21f, 12f); line(9f, 5f, 10.5f, 8f); line(15f, 5f, 13.5f, 8f) }
        }
    }
}
