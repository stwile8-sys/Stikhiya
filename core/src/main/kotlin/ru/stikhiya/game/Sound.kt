package ru.stikhiya.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звуки мира синтезируются прямо в коде (никаких файлов): треск огня, плеск, шипение пара,
 * взрыв, гул ветра. Громкость каждого — от того, сколько этого сейчас происходит в мире.
 * Игра раз в кадр сообщает уровни 0..1, отдельный поток пишет звук в устройство.
 */
class SoundEngine {
    companion object {
        const val RATE = 22050
        const val BLOCK = 512
    }

    // целевые уровни (пишет игра)
    @Volatile var fire = 0f
    @Volatile var splash = 0f
    @Volatile var hiss = 0f
    @Volatile var wind = 0f
    @Volatile var muted = false
    @Volatile private var boomQueued = 0f

    @Volatile private var running = false
    private var thread: Thread? = null

    // текущие (сглаженные) уровни — только в потоке звука
    private var cFire = 0f
    private var cSplash = 0f
    private var cHiss = 0f
    private var cWind = 0f
    private var master = 0f

    // состояние генераторов
    private var seed = 0x1234567
    private var crackEnv = 0f
    private var crackLp = 0f
    private var roarLp = 0f
    private var roarLp2 = 0f
    private var hissHp = 0f
    private var hissPrev = 0f
    private var splashLp = 0f
    private var splashBp = 0f
    private var dropEnv = 0f
    private var dropPhase = 0f
    private var dropFreq = 800f
    private var windLp = 0f
    private var windLp2 = 0f
    private var windMod = 0f
    private var boomEnv = 0f
    private var boomLp = 0f
    private var boomPhase = 0f
    private var boomFreq = 55f

    fun boom(strength: Float) {
        if (strength > boomQueued) boomQueued = strength.coerceIn(0f, 1f)
    }

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "Звук").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) {}
        thread = null
    }

    private fun loop() {
        val dev: AudioDevice = try { Gdx.audio.newAudioDevice(RATE, true) } catch (e: Throwable) { running = false; return }
        val buf = ShortArray(BLOCK)
        try {
            while (running) {
                synth(buf)
                dev.writeSamples(buf, 0, BLOCK)
            }
        } catch (_: Throwable) {
        } finally {
            try { dev.dispose() } catch (_: Throwable) {}
        }
    }

    private fun noise(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (2f / 16777216f) - 1f
    }

    private fun synth(out: ShortArray) {
        // уровни меняются плавно — без щелчков
        val k = 0.12f
        cFire += (fire - cFire) * k
        cSplash += (splash - cSplash) * 0.25f
        cHiss += (hiss - cHiss) * k
        cWind += (wind - cWind) * 0.06f
        master += ((if (muted) 0f else 1f) - master) * 0.3f
        val bq = boomQueued
        if (bq > 0f) {
            boomQueued = 0f
            boomEnv = maxOf(boomEnv, bq)
            boomFreq = 38f + 30f * (1f - bq) + noise() * 6f
        }
        if (master < 0.001f && boomEnv < 0.001f) { out.fill(0); return }

        val dt = 1f / RATE
        val crackP = cFire * cFire * 0.0025f + cFire * 0.0006f
        val dropP = cSplash * 0.0012f
        for (s in out.indices) {
            var v = 0f
            // ---- огонь: низкий гул пламени + сухие щелчки ----
            if (cFire > 0.002f) {
                val n = noise()
                roarLp += (n - roarLp) * 0.04f
                roarLp2 += (roarLp - roarLp2) * 0.04f
                v += roarLp2 * 2.2f * cFire
                if (noise() * 0.5f + 0.5f < crackP) crackEnv = 0.25f + 0.75f * (noise() * 0.5f + 0.5f)
                if (crackEnv > 0.001f) {
                    val c = noise()
                    crackLp += (c - crackLp) * 0.5f
                    v += (c - crackLp) * crackEnv * 0.7f
                    crackEnv *= 0.985f
                }
            }
            // ---- плеск: шум воды и капли ----
            if (cSplash > 0.002f) {
                val n = noise()
                splashLp += (n - splashLp) * 0.25f
                splashBp += (splashLp - splashBp) * 0.08f
                v += (splashLp - splashBp) * 0.9f * cSplash
                if (noise() * 0.5f + 0.5f < dropP) { dropEnv = 0.15f + 0.25f * cSplash; dropFreq = 500f + 900f * (noise() * 0.5f + 0.5f) }
                if (dropEnv > 0.001f) {
                    // капля: короткий тон, падающий вниз
                    dropPhase += 2f * PI.toFloat() * dropFreq * dt
                    dropFreq *= 1.00012f
                    v += sin(dropPhase) * dropEnv
                    dropEnv *= 0.9975f
                }
            }
            // ---- шипение пара ----
            if (cHiss > 0.002f) {
                val n = noise()
                val hp = n - hissPrev
                hissPrev = n
                hissHp += (hp - hissHp) * 0.6f
                v += hissHp * 0.35f * cHiss
            }
            // ---- ветер: глухой шум, медленно колышется ----
            if (cWind > 0.002f) {
                val n = noise()
                windLp += (n - windLp) * 0.02f
                windLp2 += (windLp - windLp2) * 0.05f
                windMod += (noise() - windMod) * 0.0004f
                v += windLp2 * 6f * cWind * (0.7f + 0.6f * windMod)
            }
            v *= master
            // ---- взрыв: удар и раскатистый низкий гул ----
            if (boomEnv > 0.0005f) {
                val n = noise()
                boomLp += (n - boomLp) * 0.03f
                boomPhase += 2f * PI.toFloat() * boomFreq * dt
                boomFreq *= 0.99997f
                v += (boomLp * 3.5f + sin(boomPhase) * 0.6f) * boomEnv * master
                boomEnv *= 0.99985f
            }
            // мягкое ограничение — без хрипа при громких сценах
            val y = v / (1f + abs(v))
            out[s] = (y * 26000f).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Уровень 0..1 из «количества»: быстро растёт вначале, потом насыщается. */
    fun level(amount: Float, half: Float): Float = 1f - exp(-amount / half)
}
