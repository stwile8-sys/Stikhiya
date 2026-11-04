package ru.stikhiya.game

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import ru.stikhiya.sim.Air
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Parallel
import ru.stikhiya.sim.Phase
import ru.stikhiya.sim.Units
import ru.stikhiya.sim.World
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.sqrt

enum class ViewMode(val title: String) {
    NORMAL("обычный"),
    TEMPERATURE("температура"),
    DENSITY("плотность"),
    MIXTURES("смеси"),
    WIND("ветер"),
    PRESSURE("давление"),
    OXYGEN("кислород")
}

/** Рисует мир в текстуру: одна клетка — один пиксель, дальше растягивается на экран. */
class WorldRenderer(private val w: World, private val air: Air) {
    private val pixmap = Pixmap(w.w, w.h, Pixmap.Format.RGBA8888)
    val texture = Texture(pixmap).apply { setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) }
    private val colors = IntArray(w.n)
    var mode = ViewMode.NORMAL
    private var frame = 0

    // Фон — воздух
    private val bgR = 0x0E / 255f
    private val bgG = 0x11 / 255f
    private val bgB = 0x16 / 255f

    // Предрассчитанные цвета веществ
    private val baseR = FloatArray(Mat.count) { ((Mat[it].color shr 16) and 0xFF) / 255f }
    private val baseG = FloatArray(Mat.count) { ((Mat[it].color shr 8) and 0xFF) / 255f }
    private val baseB = FloatArray(Mat.count) { (Mat[it].color and 0xFF) / 255f }
    private val alpha = FloatArray(Mat.count) { Mat[it].alpha }
    private val colorVar = FloatArray(Mat.count) { Mat[it].colorVar }

    // Свечение огня и раскалённого (как в The Powder Toy): размытый слой в полразрешения,
    // рисуется поверх мира со сложением цветов
    private val gw = (w.w + 1) / 2
    private val gh = (w.h + 1) / 2
    private val glR = FloatArray(gw * gh)
    private val glG = FloatArray(gw * gh)
    private val glB = FloatArray(gw * gh)
    private val tmpR = FloatArray(gw * gh)
    private val tmpG = FloatArray(gw * gh)
    private val tmpB = FloatArray(gw * gh)
    private val glowPix = Pixmap(gw, gh, Pixmap.Format.RGBA8888)
    val glowTexture = Texture(glowPix).apply { setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val glowColors = IntArray(gw * gh)
    var glowOn = true

    /** Чем ярче огонь на экране — тем громче треск (для звука). */
    var fireLight = 0f
        private set

    fun update() {
        frame++
        val n = w.n
        // каждая полоса красит свои клетки — считаем на всех ядрах
        val bands = Parallel.bands
        Parallel.forBands(bands) { b -> paint(Parallel.from(b, bands, n), Parallel.to(b, bands, n)) }
        val buf = pixmap.pixels
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.position(0)
        buf.asIntBuffer().put(colors)
        buf.position(0)
        texture.draw(pixmap, 0, 0)
        if (mode == ViewMode.NORMAL && glowOn) updateGlow()
    }

    private fun updateGlow() {
        val ww = w.w
        // старое свечение гаснет за несколько кадров — пламя оставляет мягкий след
        for (k in glR.indices) { glR[k] *= 0.55f; glG[k] *= 0.55f; glB[k] *= 0.55f }
        var total = 0f
        val type = w.type
        val temp = w.temp
        for (i in 0 until w.n) {
            val id = type[i].toInt()
            val t = temp[i]
            var r: Float; var g: Float; var b: Float
            if (id == Mat.FLAME) {
                val k = flameHeat(i, t)
                r = 0.55f * k; g = 0.26f * k * k; b = 0.06f * k * k * k
                total += k
            } else if (t > 550f && id != Mat.AIR) {
                // раскалённое светится: тёмно-красное → оранжевое → белое
                val k = ((t - 550f) / 900f).coerceIn(0f, 1.2f) * (if (Mat.isGas(id)) 0.3f else 1f)
                r = 0.35f * k; g = 0.12f * k * k; b = 0.03f * k * k * k
            } else if ((w.flags[i].toInt() and 2) != 0 && Mat.flammable[id]) {
                r = 0.3f; g = 0.12f; b = 0.02f
                total += 0.5f
            } else continue
            val c = ((i / ww) shr 1) * gw + ((i % ww) shr 1)
            glR[c] += r; glG[c] += g; glB[c] += b
        }
        fireLight = total
        blur(glR, tmpR); blur(glG, tmpG); blur(glB, tmpB)
        for (k in glowColors.indices) {
            val r = (glR[k] * 0.9f).coerceIn(0f, 1f)
            val g = (glG[k] * 0.9f).coerceIn(0f, 1f)
            val b = (glB[k] * 0.9f).coerceIn(0f, 1f)
            glowColors[k] = ((r * 255f).toInt() shl 24) or ((g * 255f).toInt() shl 16) or ((b * 255f).toInt() shl 8) or 0xFF
        }
        val buf = glowPix.pixels
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.position(0)
        buf.asIntBuffer().put(glowColors)
        buf.position(0)
        glowTexture.draw(glowPix, 0, 0)
    }

    /** Размытие 1-2-1 по строкам и столбцам. */
    private fun blur(a: FloatArray, t: FloatArray) {
        for (y in 0 until gh) {
            val row = y * gw
            for (x in 0 until gw) {
                val l = if (x > 0) a[row + x - 1] else a[row + x]
                val r = if (x < gw - 1) a[row + x + 1] else a[row + x]
                t[row + x] = (l + 2f * a[row + x] + r) * 0.25f
            }
        }
        for (y in 0 until gh) {
            val row = y * gw
            for (x in 0 until gw) {
                val u = if (y > 0) t[row - gw + x] else t[row + x]
                val d = if (y < gh - 1) t[row + gw + x] else t[row + x]
                a[row + x] = (u + 2f * t[row + x] + d) * 0.25f
            }
        }
    }

    /** Яркость языка пламени 0..1.4: свежий и горячий — ярче, догорающий — тусклее. */
    private fun flameHeat(i: Int, t: Float): Float {
        val life = (w.life[i] / 0.35f).coerceIn(0f, 1f)
        val hot = ((t - 400f) / 900f).coerceIn(0f, 1.2f)
        return (0.25f + 0.75f * life) * (0.4f + hot) * minOf(1.3f, 0.6f + w.amt[i] * 0.15f)
    }

    private fun paint(from: Int, to: Int) {
        for (i in from until to) {
            colors[i] = when (mode) {
                ViewMode.NORMAL -> normal(i)
                ViewMode.TEMPERATURE -> temperature(i)
                ViewMode.DENSITY -> density(i)
                ViewMode.MIXTURES -> mixtures(i)
                ViewMode.WIND -> wind(i)
                ViewMode.PRESSURE -> pressure(i)
                ViewMode.OXYGEN -> oxygen(i)
            }
            // шарнир: пока рисуется деталь, всё остальное полупрозрачное
            val m = dimMask
            if (m != null && !m[i]) colors[i] = dim(colors[i])
        }
    }

    /** Маска клеток, которые не надо приглушать (null — ничего не приглушаем). */
    @Volatile var dimMask: BooleanArray? = null

    private fun dim(c: Int): Int {
        val k = 0.4f
        val r = ((c ushr 24) and 0xFF) / 255f; val g = ((c ushr 16) and 0xFF) / 255f; val b = ((c ushr 8) and 0xFF) / 255f
        return rgb(bgR + (r - bgR) * k, bgG + (g - bgG) * k, bgB + (b - bgB) * k, 1f)
    }

    fun draw(batch: SpriteBatch, x: Float, y: Float, width: Float, height: Float) {
        batch.draw(texture, x, y, width, height)
        if (mode == ViewMode.NORMAL && glowOn) {
            // свечение складывается с картинкой: огонь светится и подсвечивает соседей
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
            batch.draw(glowTexture, x, y, width, height)
            batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        }
    }

    // ---------------- Обычный вид ----------------

    private fun normal(i: Int): Int {
        val id = w.type[i].toInt()
        val t = w.temp[i]
        if (id == Mat.AIR) {
            // горячий воздух чуть светится — видно марево над огнём
            if (t > 150f) {
                val k = ((t - 150f) / 1500f).coerceIn(0f, 0.35f)
                return rgb(bgR + k * 0.9f, bgG + k * 0.25f, bgB, 1f)
            }
            return BG
        }
        val ph = Mat.phase[id]
        val s = w.shade[i] / 128f * colorVar[id]
        var r = baseR[id] * (1f + s)
        var g = baseG[id] * (1f + s)
        var b = baseB[id] * (1f + s)
        var a = alpha[id]

        when (ph) {
            Phase.FLAME.ordinal -> return flameColor(i, t)
            Phase.GAS.ordinal -> {
                a *= minOf(1f, 0.35f + w.amt[i] * 0.25f)
                if (id == Mat.SMOKE) a *= minOf(1f, w.life[i] / 6f)
            }
            Phase.LIQUID.ordinal -> {
                val m = w.mixId[i].toInt()
                if (m != 0) {
                    // раствор чуть мутнеет и окрашивается
                    val k = minOf(0.45f, w.mix[i] * 0.4f)
                    r += (baseR[m] - r) * k; g += (baseG[m] - g) * k; b += (baseB[m] - b) * k
                    a = minOf(1f, a + k * 0.3f)
                }
                if (Mat.isBinder[id] && w.cure[i] > 0f) {
                    val c = Mat.curedTo[id]
                    val k = w.cure[i] * 0.7f
                    r += (baseR[c] - r) * k; g += (baseG[c] - g) * k; b += (baseB[c] - b) * k
                }
                a *= minOf(1f, 0.25f + w.amt[i] * 0.9f)
            }
            Phase.POWDER.ordinal -> {
                val m = w.mixId[i].toInt()
                if (m != 0) {
                    val por = Mat.porosity[id]
                    val fill = if (por > 0f) (w.mix[i] / (por * w.amt[i])).coerceIn(0f, 1f) else 0f
                    // мокрое темнеет и чуть принимает цвет жидкости
                    val dark = 1f - 0.38f * fill
                    r = (r + (baseR[m] - r) * 0.15f * fill) * dark
                    g = (g + (baseG[m] - g) * 0.15f * fill) * dark
                    b = (b + (baseB[m] - b) * 0.15f * fill) * dark
                }
                if (w.amt[i] < 0.95f) a *= 0.4f + 0.6f * w.amt[i]
            }
        }
        // накал
        if (t > 480f) {
            val k = ((t - 480f) / 700f).coerceIn(0f, 0.92f)
            r += (glowR(t) - r) * k; g += (glowG(t) - g) * k; b += (glowB(t) - b) * k
            a = maxOf(a, k)
        }
        // горящая клетка мерцает
        if ((w.flags[i].toInt() and 2) != 0 && Mat.flammable[id]) {
            val f = 0.25f + 0.2f * (((i * 7 + frame * 13) and 15) / 15f)
            r += (1f - r) * f; g += (0.55f - g) * f; b += (0.1f - b) * f
        }
        return rgb(r, g, b, a)
    }

    /**
     * Язык пламени: у основания — жёлто-белый, выше — оранжевый, на кончике — тёмно-красный
     * (цвет — от «возраста» горячего газа и его температуры).
     */
    private fun flameColor(i: Int, t: Float): Int {
        val flicker = 0.82f + 0.18f * (((i * 31 + frame * 7) and 7) / 7f)
        val heat = flameHeat(i, t).coerceIn(0f, 1.2f)
        var r = minOf(1f, 0.55f + 0.5f * heat) * flicker
        var g = (heat * heat * 0.8f) * flicker
        var b = (heat * heat * heat * 0.45f) * flicker
        // цвет пламени зависит от топлива (mixId пламени): газ — синий, магний — белый, натрий — жёлтый
        val fuel = w.mixId[i].toInt()
        val fc = if (fuel > 0 && fuel < Mat.count) Mat.flameColor[fuel] else 0
        if (fc != 0) {
            val m = 0.7f * minOf(1f, heat + 0.3f)
            r += (((fc shr 16) and 0xFF) / 255f * flicker - r) * m
            g += (((fc shr 8) and 0xFF) / 255f * flicker - g) * m
            b += ((fc and 0xFF) / 255f * flicker - b) * m
        }
        return rgb(r, g, b, (0.3f + 0.7f * heat).coerceIn(0f, 1f))
    }

    // ---------------- Температура ----------------

    private fun temperature(i: Int): Int {
        val t = w.temp[i]
        val solid = w.type[i].toInt() != Mat.AIR
        var r: Float; var g: Float; var b: Float
        if (t < Units.AMBIENT) {
            val k = ((Units.AMBIENT - t) / 70f).coerceIn(0f, 1f)
            r = 0.12f + (0.15f - 0.12f) * k; g = 0.14f + (0.45f - 0.14f) * k; b = 0.18f + (1f - 0.18f) * k
        } else {
            val u = (log10(1f + (t - Units.AMBIENT) / 10f) / log10(151f)).coerceIn(0f, 1f)
            if (u < 0.33f) {
                val k = u / 0.33f
                r = 0.12f + (0.85f - 0.12f) * k; g = 0.14f + (0.1f - 0.14f) * k; b = 0.18f + (0.05f - 0.18f) * k
            } else if (u < 0.66f) {
                val k = (u - 0.33f) / 0.33f
                r = 0.85f + 0.15f * k; g = 0.1f + 0.75f * k; b = 0.05f + 0.05f * k
            } else {
                val k = (u - 0.66f) / 0.34f
                r = 1f; g = 0.85f + 0.15f * k; b = 0.1f + 0.9f * k
            }
        }
        if (!solid) { r = bgR + (r - bgR) * 0.75f; g = bgG + (g - bgG) * 0.75f; b = bgB + (b - bgB) * 0.75f }
        return rgb(r, g, b, 1f)
    }

    // ---------------- Плотность ----------------

    private fun density(i: Int): Int {
        val d = w.cellDensity(i)
        val v = (log10(d + 0.1f) / 4f).coerceIn(0f, 1f)
        return rgb(v * 0.95f, v * 0.9f, 0.15f + v * 0.7f, 1f)
    }

    // ---------------- Смеси ----------------

    private fun mixtures(i: Int): Int {
        val id = w.type[i].toInt()
        if (id == Mat.AIR) return BG
        var r = baseR[id] * 0.25f + 0.05f
        var g = baseG[id] * 0.25f + 0.05f
        var b = baseB[id] * 0.25f + 0.05f
        val m = w.mixId[i].toInt()
        if (m != 0) {
            if (Mat.isPowder(id)) {
                val por = Mat.porosity[id]
                val fill = if (por > 0f) (w.mix[i] / (por * w.amt[i])).coerceIn(0f, 1f) else 0f
                r += 0.05f * fill; g += 0.35f * fill; b += 0.85f * fill
            } else if (Mat.isLiquid(id)) {
                val sat = Mat.saturation(m, id, w.temp[i])
                val k = if (sat > 0f) (w.mix[i] / sat).coerceIn(0f, 1f) else 0.5f
                r += 0.1f * k; g += 0.8f * k; b += 0.7f * k
            }
        }
        if (w.cure[i] > 0f) {
            val k = w.cure[i].coerceIn(0f, 1f)
            r += 0.9f * k; g += 0.75f * k
        }
        if ((w.flags[i].toInt() and 2) != 0 && Mat.flammable[id]) { r = 1f; g *= 0.4f; b *= 0.2f }
        return rgb(r, g, b, 1f)
    }

    // ---------------- Воздух ----------------

    /** Ветер: оттенок — направление, яркость — скорость. */
    private fun wind(i: Int): Int {
        val wx = air.windX(i)
        val wy = air.windY(i)
        val s = sqrt(wx * wx + wy * wy)
        // лёгкие сквозняки не раскрашиваем — видно только заметный ветер
        val v = maxOf(0f, s - 0.1f) / (s + 1.5f)
        val hue = ((atan2(wy, wx) / (2f * Math.PI.toFloat())) + 1f) % 1f
        // оттенок по кругу цветов: вправо — красный, вниз — зелёный, влево — голубой, вверх — фиолетовый
        val h6 = hue * 6f
        var r = (abs(h6 - 3f) - 1f).coerceIn(0f, 1f) * v
        var g = (2f - abs(h6 - 2f)).coerceIn(0f, 1f) * v
        var b = (2f - abs(h6 - 4f)).coerceIn(0f, 1f) * v
        val id = w.type[i].toInt()
        if (id != Mat.AIR) { r = r * 0.6f + baseR[id] * 0.4f; g = g * 0.6f + baseG[id] * 0.4f; b = b * 0.6f + baseB[id] * 0.4f }
        return rgb(maxOf(r, bgR), maxOf(g, bgG), maxOf(b, bgB), 1f)
    }

    /** Давление: красное — выше атмосферного, синее — ниже. */
    private fun pressure(i: Int): Int {
        val p = air.pv[air.cellOf(i)]
        val k = abs(p) / (abs(p) + 15f)
        var r = bgR; var g = bgG; var b = bgB
        if (p > 0f) { r += (1f - r) * k; g += (0.25f - g) * k * 0.5f } else { b += (1f - b) * k; g += (0.45f - g) * k }
        val id = w.type[i].toInt()
        if (id != Mat.AIR) { r = r * 0.55f + baseR[id] * 0.45f; g = g * 0.55f + baseG[id] * 0.45f; b = b * 0.55f + baseB[id] * 0.45f }
        return rgb(r, g, b, 1f)
    }

    /** Кислород: зелёный — норма, красный — выгорел. */
    private fun oxygen(i: Int): Int {
        val o = air.o2[air.cellOf(i)]
        val lack = ((Air.O2_NORMAL - o) / Air.O2_NORMAL).coerceIn(0f, 1f)
        var r = 0.08f + 0.85f * lack
        var g = 0.35f * (1f - lack) + 0.08f
        var b = 0.12f
        val id = w.type[i].toInt()
        if (id != Mat.AIR) { r = r * 0.5f + baseR[id] * 0.5f; g = g * 0.5f + baseG[id] * 0.5f; b = b * 0.5f + baseB[id] * 0.5f }
        return rgb(r, g, b, 1f)
    }

    // ---------------- Цвета ----------------

    private fun glowR(t: Float) = ((t - 450f) / 400f).coerceIn(0f, 1f)
    private fun glowG(t: Float) = ((t - 750f) / 600f).coerceIn(0f, 1f) * 0.85f
    private fun glowB(t: Float) = ((t - 1300f) / 700f).coerceIn(0f, 1f) * 0.8f

    /** Смешивает цвет с фоном по непрозрачности и упаковывает в 0xRRGGBBAA. */
    private fun rgb(r0: Float, g0: Float, b0: Float, a: Float): Int {
        val k = a.coerceIn(0f, 1f)
        val r = (bgR + (r0 - bgR) * k).coerceIn(0f, 1f)
        val g = (bgG + (g0 - bgG) * k).coerceIn(0f, 1f)
        val b = (bgB + (b0 - bgB) * k).coerceIn(0f, 1f)
        return ((r * 255f).toInt() shl 24) or ((g * 255f).toInt() shl 16) or ((b * 255f).toInt() shl 8) or 0xFF
    }

    private val BG = rgb(bgR, bgG, bgB, 1f)

    fun dispose() {
        texture.dispose()
        pixmap.dispose()
        glowTexture.dispose()
        glowPix.dispose()
    }
}
