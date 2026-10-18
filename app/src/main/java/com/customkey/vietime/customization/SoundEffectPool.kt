package com.customkey.vietime.customization

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Kiểu âm phím. */
enum class SoundProfile(val id: String, val title: String, val subtitle: String) {
    OFF("off", "Tắt", "Không phát âm"),
    SYSTEM("system", "Hệ thống", "Âm gõ mặc định của máy"),
    BLUE("blue", "Blue switch", "Phím cơ clicky, giòn và rõ"),
    BROWN("brown", "Brown switch", "Phím cơ tactile, êm vừa"),
    RED("red", "Red switch", "Phím cơ linear, trầm và mượt"),
    TYPEWRITER("typewriter", "Máy đánh chữ", "Tiếng búa kim loại + chuông Enter"),
    WATER("water", "Giọt nước", "Tiếng tách nước trong trẻo"),
    CUSTOM("custom", "Âm của bạn", "Nạp file .wav / .mp3 riêng");

    val synthesized: Boolean get() = this == BLUE || this == BROWN || this == RED || this == TYPEWRITER || this == WATER

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: BLUE }
}

/** Loại phím phát âm (âm khác nhau cho phím chữ / cách / xoá / Enter). */
enum class SoundKind { KEY, SPACE, DELETE, ENTER }

/**
 * Tổng hợp âm phím bằng toán (thuần Kotlin, không file âm thanh có bản quyền): mỗi âm là tổ hợp
 * nhiễu lọc dải + sóng sin tắt dần theo hàm mũ. Kết quả PCM float 44.1 kHz → WAV 16-bit mono.
 */
object KeySynth {
    const val RATE = 44_100

    fun render(profile: SoundProfile, kind: SoundKind): FloatArray {
        val p = when (kind) { SoundKind.KEY -> 1f; SoundKind.SPACE -> 0.72f; SoundKind.DELETE -> 0.88f; SoundKind.ENTER -> 0.8f }
        val seed = profile.ordinal * 31L + kind.ordinal
        val out = when (profile) {
            SoundProfile.BLUE -> buf(48).also {
                noiseBurst(it, 0f, 4500f * p, 2.0f, 3f, 1.0f, seed)
                tone(it, 0f, 3200f * p, 4f, 0.35f)
                noiseBurst(it, 12f, 1800f * p, 1.2f, 6f, 0.7f, seed + 7)
                tone(it, 12f, 180f * p, 10f, 0.45f)
            }
            SoundProfile.BROWN -> buf(55).also {
                noiseBurst(it, 0f, 2200f * p, 1.2f, 5f, 0.6f, seed)
                tone(it, 0f, 300f * p, 14f, 0.55f)
                noiseBurst(it, 9f, 1400f * p, 1.0f, 7f, 0.45f, seed + 3)
            }
            SoundProfile.RED -> buf(55).also {
                noiseBurst(it, 0f, 900f * p, 0.7f, 8f, 0.55f, seed)
                tone(it, 0f, 220f * p, 18f, 0.7f)
                tone(it, 1f, 440f * p, 8f, 0.2f)
            }
            SoundProfile.TYPEWRITER -> buf(if (kind == SoundKind.ENTER) 420 else 95).also {
                noiseBurst(it, 0f, 3000f * p, 1.5f, 4f, 1.0f, seed)
                for ((f, a) in listOf(2400f to 0.22f, 3700f to 0.16f, 5100f to 0.1f)) tone(it, 0.5f, f * p, 35f, a)
                tone(it, 0f, 120f * p, 20f, 0.5f)
                if (kind == SoundKind.ENTER) { tone(it, 60f, 2100f, 300f, 0.45f); tone(it, 60f, 4200f, 180f, 0.12f) }
            }
            SoundProfile.WATER -> buf(75).also {
                sweep(it, 0f, 500f * p, 1500f * p, 25f, 40f, 0.9f)
                sweep(it, 30f, 900f * p, 1900f * p, 12f, 18f, 0.25f)
            }
            else -> buf(10)
        }
        normalize(out, 0.9f)
        fadeOut(out, 3f)
        return out
    }

    private fun buf(ms: Int) = FloatArray(RATE * ms / 1000)
    private fun idx(ms: Float) = (ms * RATE / 1000f).toInt()

    /** Nhiễu trắng qua lọc dải (biquad band-pass) với đường bao tắt dần [decayMs]. */
    private fun noiseBurst(o: FloatArray, startMs: Float, center: Float, q: Float, decayMs: Float, amp: Float, seed: Long) {
        val w0 = 2 * PI * center / RATE
        val alpha = sin(w0) / (2 * q)
        val b0 = alpha; val a0 = 1 + alpha; val a1 = -2 * kotlin.math.cos(w0); val a2 = 1 - alpha
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        var r = seed * 6364136223846793005L + 1442695040888963407L
        val s = idx(startMs)
        val tau = decayMs * RATE / 1000f
        for (i in s until o.size) {
            r = r * 6364136223846793005L + 1442695040888963407L
            val x = ((r ushr 33).toDouble() / (1L shl 31).toDouble()) * 2 - 1
            val y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x; y2 = y1; y1 = y
            val env = exp(-(i - s) / tau.toDouble())
            if (env < 1e-4) break
            o[i] += (y * env * amp * 3).toFloat()
        }
    }

    /** Sin tắt dần (cộng hưởng vỏ phím / thanh kim loại). */
    private fun tone(o: FloatArray, startMs: Float, freq: Float, decayMs: Float, amp: Float) {
        val s = idx(startMs)
        val tau = decayMs * RATE / 1000f
        for (i in s until o.size) {
            val t = i - s
            val env = exp(-t / tau.toDouble())
            if (env < 1e-4) break
            val attack = (t / (RATE * 0.0005)).coerceAtMost(1.0)
            o[i] += (sin(2 * PI * freq * t / RATE) * env * attack * amp).toFloat()
        }
    }

    /** Sin quét tần số theo hàm mũ (tiếng "tách" giọt nước). */
    private fun sweep(o: FloatArray, startMs: Float, f0: Float, f1: Float, sweepMs: Float, decayMs: Float, amp: Float) {
        val s = idx(startMs)
        val n = idx(sweepMs).coerceAtLeast(1)
        val tau = decayMs * RATE / 1000f
        val k = ln(f1 / f0) / n
        var phase = 0.0
        for (i in s until o.size) {
            val t = i - s
            val f = f0 * exp(k * t.coerceAtMost(n))
            phase += 2 * PI * f / RATE
            val env = exp(-t / tau.toDouble()) * (t / (RATE * 0.002)).coerceAtMost(1.0)
            if (t > n && env < 1e-4) break
            o[i] += (sin(phase) * env * amp).toFloat()
        }
    }

    private fun normalize(o: FloatArray, peak: Float) {
        val m = o.maxOfOrNull { abs(it) } ?: return
        if (m <= 0f) return
        val k = peak / m
        for (i in o.indices) o[i] *= k
    }

    private fun fadeOut(o: FloatArray, ms: Float) {
        val n = idx(ms).coerceAtMost(o.size)
        for (i in 0 until n) o[o.size - 1 - i] *= i.toFloat() / n
    }

    /** PCM float → file WAV 16-bit mono. */
    fun toWav(pcm: FloatArray, rate: Int = RATE): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + data); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(data)
        for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}

/**
 * Phát âm phím độ trễ thấp: SoundPool nạp sẵn 4 âm (phím / cách / xoá / Enter) của kiểu đang chọn.
 * [setProfile] gọi ở luồng nền (tổng hợp + nạp); [play] gọi trên main, không chặn.
 */
class SoundEffectPool(context: Context) {
    private val ctx = context.applicationContext
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var pool: SoundPool? = null
    @Volatile private var ids = IntArray(SoundKind.entries.size)
    @Volatile var profile = SoundProfile.OFF; private set
    /** 0…1. */
    @Volatile var volume = 0.6f

    @Synchronized
    fun setProfile(p: SoundProfile, force: Boolean = false) {
        if (!force && p == profile && (pool != null || !(p.synthesized || p == SoundProfile.CUSTOM))) return
        pool?.release(); pool = null
        profile = p
        if (!p.synthesized && p != SoundProfile.CUSTOM) return
        val sp = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build()
        val newIds = IntArray(SoundKind.entries.size)
        if (p == SoundProfile.CUSTOM) {
            val f = customFile(ctx)
            if (f.exists()) { val id = sp.load(f.absolutePath, 1); newIds.fill(id) }
        } else {
            val dir = File(ctx.cacheDir, "keysound").apply { mkdirs() }
            for (k in SoundKind.entries) {
                val f = File(dir, "${p.id}_${k.name.lowercase()}_v1.wav")
                if (!f.exists()) f.writeBytes(KeySynth.toWav(KeySynth.render(p, k)))
                newIds[k.ordinal] = sp.load(f.absolutePath, 1)
            }
        }
        ids = newIds
        pool = sp
    }

    fun play(kind: SoundKind) {
        val v = volume
        if (v <= 0f) return
        when (profile) {
            SoundProfile.OFF -> {}
            SoundProfile.SYSTEM -> audio.playSoundEffect(when (kind) {
                SoundKind.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                SoundKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                SoundKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                SoundKind.KEY -> AudioManager.FX_KEYPRESS_STANDARD
            }, v)
            else -> {
                val p = pool ?: return
                val id = ids[kind.ordinal]
                if (id == 0) return
                // Âm riêng của người dùng: đổi cao độ nhẹ để phân biệt phím cách / xoá.
                val rate = if (profile == SoundProfile.CUSTOM) when (kind) {
                    SoundKind.SPACE -> 0.85f; SoundKind.DELETE -> 0.93f; SoundKind.ENTER -> 0.9f; SoundKind.KEY -> 1f
                } else 1f
                p.play(id, v, v, 1, 0, rate)
            }
        }
    }

    fun release() { synchronized(this) { pool?.release(); pool = null; profile = SoundProfile.OFF } }

    companion object {
        const val KEY_PROFILE = "sound.profile"
        const val KEY_VOLUME = "sound.volume"
        /** Tăng mỗi khi nạp file âm riêng mới ⇒ IME nạp lại. */
        const val KEY_CUSTOM_VERSION = "sound.customVersion"
        const val MAX_CUSTOM_BYTES = 2 * 1024 * 1024

        fun customFile(ctx: Context) = File(ctx.applicationContext.filesDir, "custom_key_sound")
    }
}
