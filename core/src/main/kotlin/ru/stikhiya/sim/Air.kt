package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.ln

/**
 * Воздух на грубой сетке (клетка 4 × 4 см): давление, скорость ветра и доля кислорода.
 * Стены и плотные завалы не пропускают воздух, горячий воздух всплывает, ветер переносит тепло и кислород.
 *
 * Звук в игре распространяется медленнее настоящего — иначе давление выравнивалось бы за один шаг
 * и взрывов не было бы видно. Скорости — в м/с, давление — в кПа сверх атмосферного.
 */
class Air(private val w: World) {
    val cs = 4
    val aw = (w.w + cs - 1) / cs
    val ah = (w.h + cs - 1) / cs
    val an = aw * ah

    private val ropeId = Mat.id("rope")
    private val cableId = Mat.id("cable")

    val pv = FloatArray(an)                 // кПа сверх атмосферного
    val vx = FloatArray(an)                 // м/с, поток через правую грань клетки
    val vy = FloatArray(an)                 // м/с, поток через нижнюю грань (+ вниз)
    val o2 = FloatArray(an) { O2_NORMAL }   // доля кислорода по объёму
    val blocked = BooleanArray(an)

    private val avgT = FloatArray(an)
    private val dev = FloatArray(an)
    private val fluidFrac = FloatArray(an)
    private val fanX = FloatArray(an)
    private val fanY = FloatArray(an)
    private val tVx = FloatArray(an)
    private val tVy = FloatArray(an)
    private val tP = FloatArray(an)
    private val tO2 = FloatArray(an)
    private val tT = FloatArray(w.n)
    private val solidCount = IntArray(an)
    private val denseCount = IntArray(an)

    /** Клетка соединена с общим воздухом комнаты (не внутри закрытой печи или коробки). */
    val open = BooleanArray(an) { true }
    private val queue = IntArray(an)
    private var tick = 0

    /** Перевод м/с в клетки за шаг (мелкие клетки). */
    val cellsPerTick = Units.DT / Units.CELL

    /** Номер клетки воздуха для каждой мелкой клетки. */
    private val coarse = IntArray(w.n) { i -> ((i / w.w) / cs) * aw + (i % w.w) / cs }

    fun cellOf(i: Int): Int = coarse[i]

    fun clear() {
        pv.fill(0f); vx.fill(0f); vy.fill(0f); o2.fill(O2_NORMAL)
    }

    fun step() {
        tick++
        scan()
        if (tick % 20 == 1) findOpen()
        fans()
        buoyancy()
        solve()
        advect()
        advectHeat()
        edges()
    }

    // ---------------- Что внутри каждой клетки воздуха ----------------

    private fun scan() {
        avgT.fill(0f); fluidFrac.fill(0f); fanX.fill(0f); fanY.fill(0f)
        solidCount.fill(0); denseCount.fill(0)
        // каждая полоса — свои строки клеток воздуха, пересечений нет
        val bands = minOf(Parallel.bands, ah)
        Parallel.forBands(bands) { b -> scanRows(Parallel.from(b, bands, ah), Parallel.to(b, bands, ah)) }
        finishScan()
    }

    private fun scanRows(ay0: Int, ay1: Int) {
        val ww = w.w
        for (y in ay0 * cs until minOf(ay1 * cs, w.h)) {
            val row = (y / cs) * aw
            for (x in 0 until ww) {
                val i = y * ww + x
                val c = row + x / cs
                val id = w.type[i].toInt()
                val ph = Mat.phase[id]
                if (Mat.isFan[id]) {
                    fanX[c] += Mat.fanX[id].toFloat(); fanY[c] += Mat.fanY[id].toFloat()
                } else if (ph == Phase.SOLID.ordinal) {
                    // тонкие верёвки и тросы воздух не перекрывают
                    if (id != ropeId && id != cableId) solidCount[c]++
                } else if (ph >= Phase.LIQUID.ordinal) {
                    denseCount[c]++
                } else {
                    avgT[c] += w.temp[i]
                    fluidFrac[c] += 1f
                }
            }
        }
    }

    /** Температура воздуха в комнате (среднее по открытому воздуху), °C — «наружный» воздух для шаров. */
    var roomT = Units.AMBIENT
        private set

    private fun finishScan() {
        val total = (cs * cs).toFloat()
        var sum = 0.0; var cnt = 0
        for (c in 0 until an) if (open[c] && fluidFrac[c] > 0f) { sum += avgT[c] / fluidFrac[c]; cnt++ }
        if (cnt > 0) roomT = (sum / cnt).toFloat()
        for (c in 0 until an) {
            // стена толщиной в одну клетку уже перекрывает воздух; рыхлый завал — только плотный
            blocked[c] = solidCount[c] >= cs || solidCount[c] + denseCount[c] >= 13
            val f = fluidFrac[c]
            avgT[c] = if (f > 0f) avgT[c] / f else Units.AMBIENT
            fluidFrac[c] = f / total
            if (blocked[c]) { vx[c] = 0f; vy[c] = 0f }
        }
    }

    /** Вентилятор гонит воздух через грань перед собой. */
    private fun fans() {
        for (c in 0 until an) {
            val fx = fanX[c]
            val fy = fanY[c]
            if (fx == 0f && fy == 0f) continue
            val x = c % aw
            val y = c / aw
            val strength = minOf(1f, (abs(fx) + abs(fy)) / cs)
            if (fx > 0f && x < aw - 1) vx[c] += (FAN_SPEED * strength - vx[c]) * 0.3f
            if (fx < 0f && x > 0) vx[c - 1] += (-FAN_SPEED * strength - vx[c - 1]) * 0.3f
            if (fy > 0f && y < ah - 1) vy[c] += (FAN_SPEED * strength - vy[c]) * 0.3f
            if (fy < 0f && y > 0) vy[c - aw] += (-FAN_SPEED * strength - vy[c - aw]) * 0.3f
        }
    }

    /**
     * Горячий воздух легче окружающего: сила Архимеда, a = g·ΔT/T.
     * ΔT — разница с соседним воздухом на той же высоте: равномерно тёплая комната не «кипит».
     */
    private fun buoyancy() {
        val r = 6
        for (y in 0 until ah) {
            val row = y * aw
            // скользящее среднее по строке
            var sum = 0f
            var cnt = 0
            for (x in 0..minOf(r, aw - 1)) if (!blocked[row + x]) { sum += avgT[row + x]; cnt++ }
            for (x in 0 until aw) {
                val c = row + x
                dev[c] = if (cnt > 0 && !blocked[c]) avgT[c] - sum / cnt else 0f
                val add = x + r + 1
                if (add < aw && !blocked[row + add]) { sum += avgT[row + add]; cnt++ }
                val rem = x - r
                if (rem >= 0 && !blocked[row + rem]) { sum -= avgT[row + rem]; cnt-- }
            }
        }
        for (c in aw until an) {
            if (blocked[c] || blocked[c - aw]) continue
            val t = (avgT[c] + avgT[c - aw]) * 0.5f
            val d = ((dev[c] + dev[c - aw]) * 0.5f).coerceIn(-400f, 400f)
            val a = (Units.GRAVITY * d / maxOf(t + Units.KELVIN, 100f)).coerceIn(-5f, 5f)
            vy[c - aw] -= a * Units.DT * BUOYANCY
        }
    }

    // ---------------- Давление и скорость ----------------

    private fun solve() {
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) continue
                val vl = if (x > 0 && !blocked[c - 1]) vx[c - 1] else 0f
                val vu = if (y > 0 && !blocked[c - aw]) vy[c - aw] else 0f
                val vr = if (x < aw - 1 && !blocked[c + 1]) vx[c] else 0f
                val vd = if (y < ah - 1 && !blocked[c + aw]) vy[c] else 0f
                val div = vl - vr + vu - vd
                pv[c] = (pv[c] * P_LOSS + div * K_P).coerceIn(P_MIN, P_MAX)
            }
        }
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) { vx[c] = 0f; vy[c] = 0f; continue }
                if (x < aw - 1 && !blocked[c + 1]) {
                    vx[c] = jet(vx[c] * V_LOSS + (pv[c] - pv[c + 1]) * K_V, pv[c] - pv[c + 1])
                } else vx[c] = 0f
                if (y < ah - 1 && !blocked[c + aw]) {
                    vy[c] = jet(vy[c] * V_LOSS + (pv[c] - pv[c + aw]) * K_V, pv[c] - pv[c + aw])
                } else vy[c] = 0f
            }
        }
    }

    /**
     * Большой перепад давления (сопло камеры сгорания, пробоина) разгоняет газ почти до скорости
     * Бернулли v = √(2Δp/ρ) — так получается реактивная струя.
     */
    private fun jet(v: Float, dp: Float): Float {
        val a = abs(dp)
        if (a < JET_DP) return v.coerceIn(-V_MAX, V_MAX)
        val target = kotlin.math.sqrt(2f * (a - JET_DP) * 1000f / 1.2f) * JET_K * (if (dp > 0f) 1f else -1f)
        val out = if ((target > 0f && v < target) || (target < 0f && v > target)) v + (target - v) * 0.2f else v
        return out.coerceIn(-V_MAX, V_MAX)
    }

    /** Ветер переносит сам себя и кислород; лёгкое сглаживание держит расчёт устойчивым. */
    private fun advect() {
        val k = Units.DT / (Units.CELL * cs) // клеток воздуха за шаг на 1 м/с
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) { tVx[c] = 0f; tVy[c] = 0f; tO2[c] = o2[c]; tP[c] = pv[c]; continue }
                val ux = (vx[c] + (if (x > 0) vx[c - 1] else 0f)) * 0.5f
                val uy = (vy[c] + (if (y > 0) vy[c - aw] else 0f)) * 0.5f
                val sx = (x - ux * k).coerceIn(0f, (aw - 1).toFloat())
                val sy = (y - uy * k).coerceIn(0f, (ah - 1).toFloat())
                tVx[c] = sample(vx, sx, sy)
                tVy[c] = sample(vy, sx, sy)
                tO2[c] = sample(o2, sx, sy)
                tP[c] = pv[c]
            }
        }
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) continue
                var sVx = 0f; var sVy = 0f; var sO = 0f; var sP = 0f; var n = 0
                if (x > 0 && !blocked[c - 1]) { sVx += tVx[c - 1]; sVy += tVy[c - 1]; sO += tO2[c - 1]; sP += tP[c - 1]; n++ }
                if (x < aw - 1 && !blocked[c + 1]) { sVx += tVx[c + 1]; sVy += tVy[c + 1]; sO += tO2[c + 1]; sP += tP[c + 1]; n++ }
                if (y > 0 && !blocked[c - aw]) { sVx += tVx[c - aw]; sVy += tVy[c - aw]; sO += tO2[c - aw]; sP += tP[c - aw]; n++ }
                if (y < ah - 1 && !blocked[c + aw]) { sVx += tVx[c + aw]; sVy += tVy[c + aw]; sO += tO2[c + aw]; sP += tP[c + aw]; n++ }
                if (n > 0) {
                    val inv = 1f / n
                    vx[c] = tVx[c] * 0.65f + sVx * inv * 0.35f
                    vy[c] = tVy[c] * 0.65f + sVy * inv * 0.35f
                    o2[c] = tO2[c] * 0.75f + sO * inv * 0.25f   // кислород быстро перемешивается
                    pv[c] = tP[c] * 0.8f + sP * inv * 0.2f
                } else {
                    vx[c] = tVx[c]; vy[c] = tVy[c]; o2[c] = tO2[c]
                }
                // слабые остатки движения гасим, чтобы воздух успокаивался; битые числа сбрасываем
                if (abs(vx[c]) < 0.03f || !vx[c].isFinite()) vx[c] = 0f
                if (abs(vy[c]) < 0.03f || !vy[c].isFinite()) vy[c] = 0f
                if (!pv[c].isFinite()) pv[c] = 0f
                if (!o2[c].isFinite()) o2[c] = O2_NORMAL
            }
        }
    }

    private fun sample(a: FloatArray, x: Float, y: Float): Float {
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, aw - 1)
        val y1 = minOf(y0 + 1, ah - 1)
        val fx = x - x0
        val fy = y - y0
        val a00 = a[y0 * aw + x0]
        val a10 = a[y0 * aw + x1]
        val a01 = a[y1 * aw + x0]
        val a11 = a[y1 * aw + x1]
        return (a00 * (1 - fx) + a10 * fx) * (1 - fy) + (a01 * (1 - fx) + a11 * fx) * fy
    }

    /** Ветер уносит тепло: температура воздуха в мелких клетках переносится по течению. */
    private fun advectHeat() {
        System.arraycopy(w.temp, 0, tT, 0, w.n)
        val bands = minOf(Parallel.bands, ah)
        Parallel.forBands(bands) { b -> advectHeatCells(Parallel.from(b, bands, an), Parallel.to(b, bands, an)) }
    }

    /** Каждая клетка воздуха меняет только свои 16 мелких клеток — полосы не пересекаются. */
    private fun advectHeatCells(c0: Int, c1: Int) {
        val ww = w.w
        val hh = w.h
        val type = w.type
        val temp = w.temp
        // обходим клетками воздуха: в штиль пропускаем сразу 16 мелких клеток
        for (c in c0 until c1) {
            val ux = vx[c] * cellsPerTick
            val uy = vy[c] * cellsPerTick
            if (abs(ux) + abs(uy) < 0.15f || blocked[c]) continue
            val x0 = (c % aw) * cs
            val y0 = (c / aw) * cs
            for (y in y0 until minOf(y0 + cs, hh)) {
                for (x in x0 until minOf(x0 + cs, ww)) {
                    val i = y * ww + x
                    if (type[i].toInt() != Mat.AIR) continue
                    // откуда пришёл воздух; смешиваем четыре соседние клетки воздуха плавно, без случайного шума
                    val sx = (x - ux.coerceIn(-3f, 3f)).coerceIn(0f, (ww - 1).toFloat())
                    val sy = (y - uy.coerceIn(-3f, 3f)).coerceIn(0f, (hh - 1).toFloat())
                    val ix = sx.toInt()
                    val iy = sy.toInt()
                    val fx = sx - ix
                    val fy = sy - iy
                    val ix1 = minOf(ix + 1, ww - 1)
                    val iy1 = minOf(iy + 1, hh - 1)
                    var sum = 0f
                    var wsum = 0f
                    val j00 = iy * ww + ix
                    val j10 = iy * ww + ix1
                    val j01 = iy1 * ww + ix
                    val j11 = iy1 * ww + ix1
                    val w00 = (1 - fx) * (1 - fy)
                    val w10 = fx * (1 - fy)
                    val w01 = (1 - fx) * fy
                    val w11 = fx * fy
                    if (type[j00].toInt() == Mat.AIR) { sum += tT[j00] * w00; wsum += w00 }
                    if (type[j10].toInt() == Mat.AIR) { sum += tT[j10] * w10; wsum += w10 }
                    if (type[j01].toInt() == Mat.AIR) { sum += tT[j01] * w01; wsum += w01 }
                    if (type[j11].toInt() == Mat.AIR) { sum += tT[j11] * w11; wsum += w11 }
                    if (wsum > 0.05f) temp[i] = sum / wsum
                }
            }
        }
    }

    /**
     * Мир — срез толщиной в клетку. На открытом месте огонь тянет воздух со всех сторон,
     * поэтому кислород быстро восполняется «из глубины». Внутри замкнутой коробки — только свой запас.
     */
    private fun edges() {
        for (c in 0 until an) {
            // у самого огня кислород расходуется, но свежий воздух подходит со всех сторон
            if (open[c]) { o2[c] = O2_NORMAL; continue }
            if (!blocked[c]) continue
            // стенка закрытой печи: мелкий воздух у внутренней поверхности — часть закрытого объёма
            val x = c % aw
            var m = o2[c]
            if (x > 0 && !blocked[c - 1]) m = minOf(m, o2[c - 1])
            if (x < aw - 1 && !blocked[c + 1]) m = minOf(m, o2[c + 1])
            if (c >= aw && !blocked[c - aw]) m = minOf(m, o2[c - aw])
            if (c + aw < an && !blocked[c + aw]) m = minOf(m, o2[c + aw])
            o2[c] = m
        }
    }

    /** Заливка от краёв мира: всё, куда воздух может пройти, — открытое пространство. */
    private fun findOpen() {
        open.fill(false)
        var head = 0
        var tail = 0
        for (c in 0 until an) {
            val x = c % aw
            val y = c / aw
            if ((x == 0 || y == 0 || x == aw - 1 || y == ah - 1) && !blocked[c]) {
                open[c] = true; queue[tail++] = c
            }
        }
        while (head < tail) {
            val c = queue[head++]
            val x = c % aw
            val y = c / aw
            if (x > 0) visit(c - 1).let { if (it) queue[tail++] = c - 1 }
            if (x < aw - 1) visit(c + 1).let { if (it) queue[tail++] = c + 1 }
            if (y > 0) visit(c - aw).let { if (it) queue[tail++] = c - aw }
            if (y < ah - 1) visit(c + aw).let { if (it) queue[tail++] = c + aw }
        }
        // стенки на границе открытого воздуха тоже дышат: у поверхности дров кислород не кончается
        for (c in 0 until an) {
            if (!blocked[c] || open[c]) continue
            val x = c % aw
            val y = c / aw
            // но не стенка, за которой закрытый объём (печь): там воздух только свой
            if ((x > 0 && !open[c - 1] && !blocked[c - 1]) || (x < aw - 1 && !open[c + 1] && !blocked[c + 1]) ||
                (y > 0 && !open[c - aw] && !blocked[c - aw]) || (y < ah - 1 && !open[c + aw] && !blocked[c + aw])) continue
            if ((x > 0 && open[c - 1] && !blocked[c - 1]) || (x < aw - 1 && open[c + 1] && !blocked[c + 1]) ||
                (y > 0 && open[c - aw] && !blocked[c - aw]) || (y < ah - 1 && open[c + aw] && !blocked[c + aw])) {
                surfaceOpen[c] = true
            }
        }
        for (c in 0 until an) if (surfaceOpen[c]) { open[c] = true; surfaceOpen[c] = false }
    }

    private val surfaceOpen = BooleanArray(an)

    private fun visit(c: Int): Boolean {
        if (open[c] || blocked[c]) return false
        open[c] = true
        return true
    }

    // ---------------- Доступ для остальной физики ----------------

    /** Давление в клетке, Па. */
    fun pressure(i: Int): Float = maxOf(1000f, Units.P_ATM + pv[cellOf(i)] * 1000f)

    fun windX(i: Int): Float {
        val c = coarse[i]
        return if ((i % w.w) >= cs) (vx[c] + vx[c - 1]) * 0.5f else vx[c] * 0.5f
    }

    fun windY(i: Int): Float {
        val c = cellOf(i)
        return (vy[c] + (if (c >= aw) vy[c - aw] else 0f)) * 0.5f
    }

    fun oxygen(i: Int): Float = o2[cellOf(i)]

    /** Горение съело кислород: масса O₂ в кг. */
    fun consumeOxygen(i: Int, kg: Float) {
        val c = cellOf(i)
        // воздух в клетке с учётом глубины печи/комнаты (~30 см вглубь среза)
        val airMass = 1.2f * Units.VOL * cs * cs * maxOf(0.15f, fluidFrac[c]) * DEPTH
        o2[c] = maxOf(0f, o2[c] - kg / airMass * 0.9f)
    }

    /** Газ взрыва или кисть давления. */
    fun addPressure(i: Int, kPa: Float) {
        val c = cellOf(i)
        if (blocked[c]) {
            // взрыв внутри завала давит на соседей
            spreadToNeighbors(c, kPa)
            return
        }
        pv[c] = (pv[c] + kPa).coerceIn(P_MIN, P_MAX)
    }

    private fun spreadToNeighbors(c: Int, kPa: Float) {
        val x = c % aw
        val y = c / aw
        var n = 0
        if (x > 0 && !blocked[c - 1]) n++
        if (x < aw - 1 && !blocked[c + 1]) n++
        if (y > 0 && !blocked[c - aw]) n++
        if (y < ah - 1 && !blocked[c + aw]) n++
        if (n == 0) { pv[c] = (pv[c] + kPa).coerceIn(P_MIN, P_MAX); return }
        val part = kPa / n
        if (x > 0 && !blocked[c - 1]) pv[c - 1] = (pv[c - 1] + part).coerceIn(P_MIN, P_MAX)
        if (x < aw - 1 && !blocked[c + 1]) pv[c + 1] = (pv[c + 1] + part).coerceIn(P_MIN, P_MAX)
        if (y > 0 && !blocked[c - aw]) pv[c - aw] = (pv[c - aw] + part).coerceIn(P_MIN, P_MAX)
        if (y < ah - 1 && !blocked[c + aw]) pv[c + aw] = (pv[c + aw] + part).coerceIn(P_MIN, P_MAX)
    }

    /** Кисть давления: круг радиусом r мелких клеток. */
    fun brushPressure(cx: Int, cy: Int, r: Int, kPa: Float) {
        forCells(cx, cy, r) { c -> if (!blocked[c]) pv[c] = (pv[c] + kPa).coerceIn(P_MIN, P_MAX) }
    }

    /** Кисть ветра: подтягивает скорость воздуха к скорости пальца. */
    fun brushWind(cx: Int, cy: Int, r: Int, ux: Float, uy: Float) {
        forCells(cx, cy, r) { c ->
            if (blocked[c]) return@forCells
            vx[c] += (ux - vx[c]) * 0.5f
            vy[c] += (uy - vy[c]) * 0.5f
        }
    }

    private inline fun forCells(cx: Int, cy: Int, r: Int, f: (Int) -> Unit) {
        val rr = maxOf(0, (r + cs - 1) / cs)
        val acx = cx / cs
        val acy = cy / cs
        for (y in acy - rr..acy + rr) {
            if (y < 0 || y >= ah) continue
            for (x in acx - rr..acx + rr) {
                if (x < 0 || x >= aw) continue
                if ((x - acx) * (x - acx) + (y - acy) * (y - acy) > rr * rr + rr) continue
                f(y * aw + x)
            }
        }
    }

    /**
     * Точка кипения (или конденсации) с учётом давления в клетке.
     * Под вакуумом вода кипит при комнатной температуре, в скороварке — выше 100 °C.
     */
    fun boilPoint(id: Int, base: Float, i: Int): Float {
        val k = Mat.boilShift[id]
        if (k == 0f) return base
        val p = pressure(i)
        if (abs(p - Units.P_ATM) < 200f) return base
        val t0 = base + Units.KELVIN
        val d = 1f - k * ln(p / Units.P_ATM)
        if (d <= 0.05f) return base + 2000f
        return t0 / d - Units.KELVIN
    }

    companion object {
        const val O2_NORMAL = 0.209f
        const val DEPTH = 2f             // объём воздуха на клетку среза (топливо тоже в срезе — печь задыхается честно)
        const val O2_FLAME_MIN = 0.14f   // ниже — пламя гаснет
        const val FAN_SPEED = 4f         // м/с
        const val BUOYANCY = 0.35f
        const val K_P = 0.3f
        const val K_V = 0.4f
        const val P_LOSS = 0.99f
        const val V_LOSS = 0.97f         // порыв ветра стихает за 1–2 секунды
        const val P_MIN = -100f
        const val P_MAX = 400f
        const val V_MAX = 30f
        const val JET_DP = 0.8f          // кПа: меньше — обычный ветер
        const val JET_K = 0.3f           // игровой масштаб скорости струи (звук в игре медленнее)
    }
}
