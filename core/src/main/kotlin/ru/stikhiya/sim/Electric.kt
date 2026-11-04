package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Электричество: потенциалы в проводниках (закон Ома, Кирхгоф), ток нагревает провода (закон Джоуля — Ленца),
 * лампочка раскаляется и светит, в воде с солью или кислотой идёт электролиз: на «−» водород, на «+» кислород.
 * Молния — канал раскалённого воздуха от точки касания до земли.
 *
 * Проводимость клетки 1 см: G = σ·S/L = σ·0,01 м. Батарея: «+» держит 12 В, «−» — 0 В, у каждой клетки
 * батареи внутреннее сопротивление. Решаем систему методом сопряжённых градиентов, начиная с прошлого шага.
 */
class Electric(private val w: World, private val air: Air) {
    private val n = w.n
    val phi = FloatArray(n)                 // потенциал, В
    private val idx = IntArray(n) { -1 }    // номер неизвестной или −1
    private var cells = IntArray(256)
    private var m = 0
    private val gas = FloatArray(n)         // накопленный заряд электролиза, Кл
    private var tick = 0
    var active = false
        private set

    private val plus = Mat.id("battery_plus")
    private val minus = Mat.id("battery_minus")
    private val lamp = Mat.id("lamp")
    private val hydrogen = Mat.id("hydrogen")
    private val oxygen = Mat.id("oxygen")

    /** Удельная проводимость вещества, См/м (0 — изолятор). */
    private val sigma = FloatArray(Mat.count) { id ->
        val s = Mat[id]
        val k = s.key
        when {
            id == plus || id == minus -> 2000f            // ~0,05 Ом на клетку — внутреннее сопротивление
            id == lamp -> 10f                             // нить: ~10 Ом на клетку
            k == "nichrome" -> 200f                       // спираль: ~0,5 Ом на клетку
            k == "copper" || k == "molten_copper" -> 5.9e7f
            k == "silver" -> 6.3e7f
            k == "gold" -> 4.1e7f
            k.contains("aluminum") || k == "al_powder" -> if (s.phase == Phase.POWDER) 1e3f else 3.5e7f
            k == "iron" || k == "steel" || k == "cast_iron" || k == "cable" -> 1.0e7f
            k == "iron_filings" -> 1e3f
            k == "graphite" -> 1e5f
            k == "mercury" -> 1.0e6f
            k == "water" -> 0.05f                         // чистая вода почти не проводит; соль — см. ниже
            k == "hcl" -> 30f
            k.contains("h2so4") -> 20f
            k == "lye" -> 20f
            k == "vinegar" -> 1f
            k.contains("salt") && s.phase == Phase.LIQUID -> 5f
            k == "lava" -> 5f
            s.category == Category.METAL && s.phase == Phase.SOLID -> 1.0e7f
            s.category == Category.METAL && s.phase == Phase.LIQUID -> 1.0e6f
            else -> 0f
        }
    }

    private fun sig(i: Int): Float {
        val id = w.type[i].toInt()
        var s = sigma[id]
        if (id == Mat.WATER && w.mixId[i].toInt() != 0) s += 140f * w.mix[i]   // ~5 См/м у морской воды
        return s
    }

    private fun conductive(i: Int) = sigma[w.type[i].toInt()] > 0f

    // ---- система уравнений ----
    private var diag = FloatArray(256)
    private var rhs = FloatArray(256)
    private var x = FloatArray(256)
    private var r = FloatArray(256)
    private var p = FloatArray(256)
    private var ap = FloatArray(256)
    // соседи (номер неизвестной или −1) и проводимости связей, считаются раз за шаг
    private var nb = IntArray(1024)
    private var gl = FloatArray(1024)

    private fun link(i: Int, j: Int): Float {
        val a = sig(i); val b = sig(j)
        if (a <= 0f || b <= 0f) return 0f
        // проводимость между центрами клеток: два полуклеточных сопротивления последовательно
        val g = 2f * a * b / (a + b) * Units.CELL
        return minOf(g, G_MAX)
    }

    fun step() {
        tick++
        if (tick % 10 == 1) rebuild()
        if (!active) return
        solve()
        effects()
    }

    /** Проводники, связанные с батареей (оторванные куски не считаем — экономим время). */
    private fun rebuild() {
        for (k in 0 until m) idx[cells[k]] = -1
        m = 0
        var hasSource = false
        // поиск в ширину от полюсов батарей по проводникам
        var qn = 0
        for (i in 0 until n) {
            val t = w.type[i].toInt()
            if (t != plus && t != minus) continue
            hasSource = true
            for (d in 0 until 4) {
                val j = Neighbors.at(w, i, d)
                if (j < 0 || idx[j] >= 0) continue
                val tj = w.type[j].toInt()
                if (sigma[tj] <= 0f || tj == plus || tj == minus) continue
                if (m == cells.size) grow()
                idx[j] = m; cells[m++] = j
            }
        }
        while (qn < m) {
            val i = cells[qn++]
            for (d in 0 until 4) {
                val j = Neighbors.at(w, i, d)
                if (j < 0 || idx[j] >= 0) continue
                val tj = w.type[j].toInt()
                if (sigma[tj] <= 0f || tj == plus || tj == minus) continue
                if (m == cells.size) grow()
                idx[j] = m; cells[m++] = j
            }
        }
        active = hasSource && m > 0
    }

    private fun grow() {
        val s = cells.size * 2
        cells = cells.copyOf(s); diag = FloatArray(s); rhs = FloatArray(s); x = FloatArray(s)
        r = FloatArray(s); p = FloatArray(s); ap = FloatArray(s); nb = IntArray(s * 4); gl = FloatArray(s * 4)
    }

    private fun srcPhi(t: Int) = if (t == plus) VOLTAGE else 0f

    private fun solve() {
        for (k in 0 until m) {
            val i = cells[k]
            var d = G_LEAK
            var b = 0f
            for (dd in 0 until 4) {
                val j = Neighbors.at(w, i, dd)
                val o = k * 4 + dd
                nb[o] = -1; gl[o] = 0f
                if (j < 0) continue
                val g = link(i, j)
                if (g <= 0f) continue
                d += g
                val tj = w.type[j].toInt()
                if (tj == plus || tj == minus) b += g * srcPhi(tj)
                else { nb[o] = idx[j]; gl[o] = g }
            }
            diag[k] = d; rhs[k] = b; x[k] = phi[i]
        }
        // r = b − A·x
        mul(x, ap)
        var rr = 0f
        for (k in 0 until m) { r[k] = rhs[k] - ap[k]; p[k] = r[k] / diag[k]; rr += r[k] * p[k] }
        var it = 0
        while (it < ITER && rr > 1e-12f) {
            mul(p, ap)
            var pap = 0f
            for (k in 0 until m) pap += p[k] * ap[k]
            if (pap <= 0f) break
            val a = rr / pap
            var rr2 = 0f
            for (k in 0 until m) { x[k] += a * p[k]; r[k] -= a * ap[k]; rr2 += r[k] * r[k] / diag[k] }
            val beta = rr2 / rr
            for (k in 0 until m) p[k] = r[k] / diag[k] + beta * p[k]
            rr = rr2
            it++
        }
        for (k in 0 until m) phi[cells[k]] = x[k]
    }

    private fun mul(v: FloatArray, out: FloatArray) {
        for (k in 0 until m) {
            var s = diag[k] * v[k]
            val o = k * 4
            for (dd in 0 until 4) { val q = nb[o + dd]; if (q >= 0) s -= gl[o + dd] * v[q] }
            out[k] = s
        }
    }

    /** Нагрев током, свечение лампы, электролиз. */
    private fun effects() {
        for (k in 0 until m) {
            val i = cells[k]
            var heat = 0f
            for (dd in 0 until 4) {
                val j = Neighbors.at(w, i, dd)
                if (j < 0) continue
                val g = link(i, j)
                if (g <= 0f) continue
                val tj0 = w.type[j].toInt()
                val u = phi[i] - (if (tj0 == plus || tj0 == minus) srcPhi(tj0) else phi[j])
                // мощность на связи U²·G делится пропорционально сопротивлению половинок: греется плохой проводник
                val si = sig(i); val sj = sig(j)
                val share = sj / (si + sj)
                heat += share * u * u * g
                val tj = w.type[j].toInt()
                if (tj == plus || tj == minus) w.temp[j] += (1f - share) * u * u * g * Units.DT / w.heatCapacity(j)
            }
            if (heat > 0f) {
                val c = w.heatCapacity(i)
                w.temp[i] = minOf(w.temp[i] + heat * Units.DT / c, if (w.type[i].toInt() == lamp) 2800f else 6000f)
            }
            // электролиз: ток из металла в раствор (или обратно) на границе
            val t = w.type[i].toInt()
            if (Mat.isLiquid(t) && Mat.phase[t] == Phase.LIQUID.ordinal && sigma[t] < 1000f) electrolysis(i)
        }
    }

    private fun electrolysis(i: Int) {
        for (dd in 0 until 4) {
            val j = Neighbors.at(w, i, dd)
            if (j < 0) continue
            val tj = w.type[j].toInt()
            if (sigma[tj] < 1000f) continue   // электрод — металл, графит или батарея
            val pj = if (tj == plus || tj == minus) srcPhi(tj) else phi[j]
            val cur = link(i, j) * (pj - phi[i])   // + — ток из электрода в раствор: анод
            gas[i] += cur * Units.DT * GAS_SCALE
            // на катоде водорода по объёму вдвое больше, чем кислорода на аноде: 2H₂O → 2H₂ + O₂
            if (gas[i] > 2f || gas[i] < -1f) {
                val anode = gas[i] > 0f
                gas[i] = 0f
                val e = Neighbors.randomAir(w, i, preferUp = true)
                val tgt = if (e >= 0) e else i
                val tt = w.temp[i]
                // вода разлагается: часть клетки уходит газом
                if (tgt == i) { w.put(i, if (anode) oxygen else hydrogen, tt); w.amt[i] = 1f }
                else { w.put(tgt, if (anode) oxygen else hydrogen, tt); w.amt[tgt] = 1f; w.amt[i] -= 0.02f; if (w.amt[i] < 0.05f) w.toAir(i) }
            }
        }
    }

    /** Молния: ветвистый канал от точки вниз до земли; воздух в канале раскаляется до ~3000 °C, гром — давление. */
    fun lightning(x0: Int, y0: Int) {
        var x = x0
        var y = y0
        var steps = 0
        while (y < w.h - 1 && steps < 400) {
            steps++
            val i = w.idx(x, y)
            val t = w.type[i].toInt()
            if (Mat.isCondensed(t)) {
                // удар: всё, что горит, вспыхивает, проводник раскаляется
                w.temp[i] = minOf(6000f, w.temp[i] + if (sigma[t] > 1000f) 300f else 1500f)
                break
            }
            w.put(i, Mat.FLAME, 3000f)
            w.amt[i] = 3f
            w.mixId[i] = hydrogen.toShort()   // бело-голубой цвет
            if (steps % 6 == 0) air.addPressure(i, 25f)
            // зигзаг: в основном вниз, иногда вбок
            val r = w.rnd()
            x = (x + if (r < 0.25f) -1 else if (r > 0.75f) 1 else 0).coerceIn(0, w.w - 1)
            if (w.rnd() < 0.8f) y++
        }
    }

    /** Ток через клетку, А (для «Смотреть»). */
    fun currentAt(i: Int): Float {
        if (!active || idx[i] < 0 && sigma[w.type[i].toInt()] <= 0f) return 0f
        var s = 0f
        for (dd in 0 until 4) {
            val j = Neighbors.at(w, i, dd)
            if (j < 0) continue
            val tj = w.type[j].toInt()
            s += abs(link(i, j) * (phi[i] - (if (tj == plus || tj == minus) srcPhi(tj) else phi[j])))
        }
        return s * 0.5f
    }

    fun isConductor(i: Int) = sigma[w.type[i].toInt()] > 0f

    fun clear() { phi.fill(0f); gas.fill(0f); for (k in 0 until m) idx[cells[k]] = -1; m = 0; active = false }

    companion object {
        const val VOLTAGE = 12f
        const val G_MAX = 1e4f      // См: металлический провод — почти идеальный (иначе решение не сходится)
        const val G_LEAK = 1e-6f    // утечка в «землю», чтобы оторванный кусок провода имел потенциал
        const val ITER = 60
        /** Игровое ускорение электролиза (иначе пузырёк ждать минутами). */
        const val GAS_SCALE = 30f
        @Suppress("unused") private fun unused() = sqrt(1f)
    }
}
