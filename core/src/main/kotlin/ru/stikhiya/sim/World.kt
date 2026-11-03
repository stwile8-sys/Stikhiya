package ru.stikhiya.sim

/**
 * Мир — сетка клеток. Каждое свойство клетки хранится в отдельном массиве.
 *
 * Значение [mix] зависит от вида клетки:
 *  - порошок: [mixId] — жидкость в порах, [mix] — доля объёма клетки, занятая жидкостью;
 *  - жидкость: [mixId] — растворённое вещество, [mix] — концентрация, кг/л.
 */
class World(val w: Int, val h: Int) {
    val n = w * h

    val type = ShortArray(n)
    val temp = FloatArray(n) { Units.AMBIENT }
    val vy = FloatArray(n)          // скорость падения, клеток/тик
    val vx = FloatArray(n)          // скорость вбок (инерция жидкости — волны), клеток/тик
    val amt = FloatArray(n) { 1f }  // количество вещества в клетке (1 — полная клетка; у газа — сжатие)
    val mixId = ShortArray(n)
    val mix = FloatArray(n)
    val latent = FloatArray(n)      // накопленная теплота перехода, Дж/кг (+ к плавлению/кипению, − к замерзанию)
    val cure = FloatArray(n)        // 0..1: застывание клея, схватывание, обугливание
    val burn = FloatArray(n)        // 0 — не горит, >0 — доля сгоревшего
    val life = FloatArray(n)        // оставшаяся жизнь газа, с
    val shade = ByteArray(n)        // оттенок зерна
    val flags = ByteArray(n)        // бит 0 — направление растекания
    val stamp = IntArray(n)         // номер тика, в котором клетка уже двигалась
    val owner = ShortArray(n)       // номер твёрдого тела + 1, если клетка принадлежит телу

    var seed = 0x2545F491

    fun idx(x: Int, y: Int) = y * w + x
    fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h

    fun clear() {
        type.fill(0); temp.fill(Units.AMBIENT); vy.fill(0f); vx.fill(0f); amt.fill(1f)
        mixId.fill(0); mix.fill(0f); latent.fill(0f); cure.fill(0f); burn.fill(0f)
        life.fill(0f); flags.fill(0); stamp.fill(0); owner.fill(0)
    }

    /** Поставить вещество в клетку (все прочие свойства сбрасываются). */
    fun put(i: Int, id: Int, t: Float = Mat[id].spawnTemp) {
        type[i] = id.toShort()
        temp[i] = t
        vy[i] = 0f
        vx[i] = 0f
        amt[i] = 1f
        mixId[i] = 0
        mix[i] = 0f
        latent[i] = 0f
        cure[i] = 0f
        burn[i] = 0f
        life[i] = Mat.lifetime[id].let { if (it.isNaN()) 0f else it * (0.7f + 0.6f * rnd()) }
        shade[i] = (rndInt(256) - 128).toByte()
        flags[i] = rndInt(2).toByte()
    }

    /** Превратить клетку в воздух, сохранив температуру. */
    fun toAir(i: Int) {
        val t = temp[i]
        put(i, Mat.AIR, t)
    }

    /** Сменить вещество, сохранив температуру, оттенок и количество (с пересчётом по массе). */
    fun transform(i: Int, to: Int) {
        val from = type[i].toInt()
        val newAmt = convertAmount(from, to, amt[i], temp[i])
        type[i] = to.toShort()
        amt[i] = newAmt
        latent[i] = 0f
        cure[i] = 0f
        burn[i] = 0f
        vy[i] = 0f
        life[i] = Mat.lifetime[to].let { if (it.isNaN()) 0f else it * (0.7f + 0.6f * rnd()) }
        if (!Mat.isPowder(to) && !Mat.isLiquid(to)) { mixId[i] = 0; mix[i] = 0f }
    }

    /** Количество после превращения так, чтобы масса сохранялась. Газ может быть «сжат» (amt > 1). */
    fun convertAmount(from: Int, to: Int, a: Float, t: Float): Float {
        val m = massDensity(from, t) * a
        val d = massDensity(to, t)
        if (d <= 0f) return a
        val r = m / d
        return if (Mat.isGas(to)) r else minOf(r, 1f)
    }

    /** Плотность вещества в клетке без учёта примесей, кг/м³. */
    fun massDensity(id: Int, t: Float): Float =
        if (Mat.isGas(id)) Units.gasDensity(Mat.molar[id], t)
        else Mat.density[id] * (1f - Mat.expansion[id] * (t - Units.AMBIENT))

    /** Полная плотность клетки (с растворённым веществом и жидкостью в порах), кг/м³. */
    fun cellDensity(i: Int): Float {
        val id = type[i].toInt()
        val t = temp[i]
        val base = massDensity(id, t)
        val m = mixId[i].toInt()
        if (m == 0) return base
        return when (Mat.phase[id]) {
            Phase.LIQUID.ordinal -> {
                // раствор: часть объёма растворителя занимает вещество
                val c = mix[i] * 1000f // кг/м³
                val g = Mat.grainDensity[m]
                val gd = if (g > 0f) g else Mat.density[m]
                base + c * (1f - base / gd)
            }
            Phase.POWDER.ordinal -> base + mix[i] * Mat.density[m]
            else -> base
        }
    }

    /** Масса вещества клетки, кг (с примесью). */
    fun cellMass(i: Int): Float {
        val id = type[i].toInt()
        val t = temp[i]
        var m = massDensity(id, t) * Units.VOL * amt[i]
        val mx = mixId[i].toInt()
        if (mx != 0) {
            m += if (Mat.isPowder(id)) mix[i] * Mat.density[mx] * Units.VOL
            else mix[i] * Units.VOL * 1000f * amt[i]
        }
        return m
    }

    /** Теплоёмкость клетки, Дж/К. */
    fun heatCapacity(i: Int): Float {
        val id = type[i].toInt()
        val t = temp[i]
        var c = massDensity(id, t) * Units.VOL * amt[i] * Mat.heatCap[id]
        val mx = mixId[i].toInt()
        if (mx != 0) {
            c += if (Mat.isPowder(id)) mix[i] * Mat.density[mx] * Units.VOL * Mat.heatCap[mx]
            else mix[i] * Units.VOL * 1000f * amt[i] * Mat.heatCap[mx]
        }
        return maxOf(c, 1e-6f)
    }

    fun swap(a: Int, b: Int) {
        val t0 = type[a]; type[a] = type[b]; type[b] = t0
        val f1 = temp[a]; temp[a] = temp[b]; temp[b] = f1
        val f2 = vy[a]; vy[a] = vy[b]; vy[b] = f2
        val f9 = vx[a]; vx[a] = vx[b]; vx[b] = f9
        val f3 = amt[a]; amt[a] = amt[b]; amt[b] = f3
        val s1 = mixId[a]; mixId[a] = mixId[b]; mixId[b] = s1
        val f4 = mix[a]; mix[a] = mix[b]; mix[b] = f4
        val f5 = latent[a]; latent[a] = latent[b]; latent[b] = f5
        val f6 = cure[a]; cure[a] = cure[b]; cure[b] = f6
        val f7 = burn[a]; burn[a] = burn[b]; burn[b] = f7
        val f8 = life[a]; life[a] = life[b]; life[b] = f8
        val b1 = shade[a]; shade[a] = shade[b]; shade[b] = b1
        val b2 = flags[a]; flags[a] = flags[b]; flags[b] = b2
        val o = owner[a]; owner[a] = owner[b]; owner[b] = o
    }

    // --- быстрый генератор случайных чисел (xorshift) ---
    fun rndInt(bound: Int): Int {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return ((x.toLong() and 0x7FFFFFFF) % bound).toInt()
    }

    fun rnd(): Float {
        var x = seed
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        seed = x
        return (x ushr 8) * (1f / 16777216f)
    }
}
