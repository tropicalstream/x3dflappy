package com.x3dflappy.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A generous bank of synthesized effects (no audio binaries ship). Everything
 * is rendered to WAV at first launch and played through SoundPool. One sample
 * (DRONE) loops as a droning Minter-style backdrop while you fly.
 */
class Sfx(private val context: Context) {

    companion object {
        const val FLAP = 0
        const val SCORE = 1
        const val CRASH = 2
        const val START = 3
        const val GAMEOVER = 4
        const val HISCORE = 5
        const val WHOOSH = 6      // gate passing by
        const val NEARMISS = 7
        const val COMBO = 8
        const val CHIRP = 9       // idle creature call
        const val ZAP = 10
        const val POWER = 11
        const val THRUST = 12     // alt flap
        const val WARN = 13       // tight gap ahead
        const val BLIP = 14       // UI
        const val DRONE = 15      // looped backdrop
        private const val COUNT = 16
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.9f
    private var droneStream = 0
    private val rng = Random(3)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                ids[FLAP] = load(dir, "flap", synthFlap())
                ids[SCORE] = load(dir, "score", arpeggio(intArrayOf(660, 880, 1320), 70, 0.6f))
                ids[CRASH] = load(dir, "crash", synthCrash())
                ids[START] = load(dir, "start", arpeggio(intArrayOf(392, 523, 659, 784), 90, 0.6f))
                ids[GAMEOVER] = load(dir, "over", synthGameover())
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568), 90, 0.65f))
                ids[WHOOSH] = load(dir, "whoosh", synthWhoosh())
                ids[NEARMISS] = load(dir, "near", synthNearMiss())
                ids[COMBO] = load(dir, "combo", synthCombo())
                ids[CHIRP] = load(dir, "chirp", synthChirp())
                ids[ZAP] = load(dir, "zap", synthZap())
                ids[POWER] = load(dir, "power", synthPower())
                ids[THRUST] = load(dir, "thrust", synthThrust())
                ids[WARN] = load(dir, "warn", synthWarn())
                ids[BLIP] = load(dir, "blip", synthBlip())
                ids[DRONE] = load(dir, "drone", synthDrone())
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]
        if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f)
        if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun startDrone() {
        if (!loaded || droneStream != 0) return
        val v = (volume * 0.5f).coerceIn(0f, 1f)
        droneStream = pool.play(ids[DRONE], v, v, 0, -1, 1f)
    }

    fun stopDrone() {
        if (droneStream != 0) { pool.stop(droneStream); droneStream = 0 }
    }

    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun synthFlap() = buf(150) { t ->
        val sweep = 900f - 500f * t
        (noise() * exp(-t * 26f) * 0.5f + sine(sweep, t) * exp(-t * 14f) * 0.5f)
    }
    private fun synthThrust() = buf(170) { t ->
        (saw(180f + 400f * t, t) * 0.4f + noise() * exp(-t * 20f) * 0.5f) * exp(-t * 9f)
    }
    private fun synthCrash() = buf(600) { t ->
        (noise() * exp(-t * 6f) + sine(70f, t) * exp(-t * 4f) * 0.7f) * 0.7f
    }
    private fun synthGameover() = buf(700) { t ->
        val f = 400f - t * 300f
        (saw(f, t) * 0.4f + sine(f, t) * 0.4f) * exp(-t * 2.5f)
    }
    private fun synthWhoosh() = buf(240) { t ->
        val env = max(0f, 1f - (t - 0.12f).let { it * it } * 40f)
        noise() * env * 0.5f
    }
    private fun synthNearMiss() = buf(220) { t -> sine(1400f - 600f * t, t) * exp(-t * 7f) * 0.5f }
    private fun synthCombo() = buf(160) { t -> (sine(880f, t) + sine(1320f, t) * 0.5f) * exp(-t * 8f) * 0.5f }
    private fun synthChirp() = buf(120) { t -> sine(1200f + 800f * sin(30f * t), t) * exp(-t * 12f) * 0.4f }
    private fun synthZap() = buf(180) { t -> (saw(1500f + 300f * sine(80f, t), t) + 0.4f * noise()) * exp(-t * 12f) * 0.5f }
    private fun synthPower() = buf(300) { t -> sine(300f + 900f * t, t) * exp(-t * 5f) * 0.5f }
    private fun synthWarn() = buf(120) { t -> sine(520f, t) * exp(-t * 10f) * 0.5f }
    private fun synthBlip() = buf(50) { t -> sine(1000f, t) * exp(-t * 40f) * 0.5f }

    /** Loopable dark pad — two detuned saws + slow tremolo. */
    private fun synthDrone(): ShortArray {
        val ms = 2000
        return buf(ms) { t ->
            val trem = 0.7f + 0.3f * sin(2f * PI.toFloat() * 0.5f * t)
            (saw(55f, t) * 0.35f + saw(55.4f, t) * 0.35f + sine(110f, t) * 0.2f) * trem * 0.5f
        }
    }

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
