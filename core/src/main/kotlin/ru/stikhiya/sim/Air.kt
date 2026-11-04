package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.ln

/**
 * Воздух на грубой сетке (клетка 4 × 4 см): давление, скорость ветра и доля кислорода.
 * Стены и плотные завалы не пропускают воздух, горячий воздух всплывает, ветер переносит тепло и кислород.
 *
 * Течение считается как у настоящего воздуха на малых скоростях (метод проекции, как в «Stable Fluids»):
 * воздух почти несжимаем — звук быстрее любого ветра, поэтому давление выравнивается мгновенно,
 * а взрыв, кипение и горение «раздувают» объём (источник расширения) — газ разлетается от них.
 * Замкнутые объёмы (печь, банка) держат избыточное давление: скороварка, вакуум.
 * Тёплый воздух всплывает (сила Архимеда), за ним подтягивается холодный — тяга в печи и трубе.
 * Скорости — в м/с, давление — в кПа сверх атмосферного.
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
        phi.fill(0f); src.fill(0f); statP.fill(0f)
    }

    // ---- проекция ----
    private val phi = FloatArray(an)       // потенциал давления, м²/с
    private val src = FloatArray(an)       // расширение газа за шаг (доля объёма клетки)
    private val rhs = FloatArray(an)
    private val nbMask = IntArray(an)
    private val invN = FloatArray(an)
    /** Избыточное давление объёма (закрытая печь, банка; в комнате быстро уходит), кПа. */
    private val statP = FloatArray(an)
    private val region = IntArray(an) { -1 }
    private var regions = 0
    private var regSrc = FloatArray(16)
    private var regN = IntArray(16)
    private var regP = FloatArray(16)

    fun step() {
        tick++
        scan()
        if (tick % 20 == 1) { findOpen(); labelRegions(); findExposed() }
        fans()
        buoyancy()
        advect()
        project()
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
            if (fx > 0f && x < aw - 1) vx[c] += (FAN_SPEED * strength - vx[c]) * 0.8f
            if (fx < 0f && x > 0) vx[c - 1] += (-FAN_SPEED * strength - vx[c - 1]) * 0.8f
            if (fy > 0f && y < ah - 1) vy[c] += (FAN_SPEED * strength - vy[c]) * 0.8f
            if (fy < 0f && y > 0) vy[c - aw] += (-FAN_SPEED * strength - vy[c - aw]) * 0.8f
        }
    }

    /**
     * Горячий воздух легче окружающего: ускорение a = g·(T − T₀)/T₀ (Буссинеск), T₀ — воздух комнаты.
     * Подъём тёплого и опускание холодного согласует проекция — получается тяга и вихри.
     */
    private fun buoyancy() {
        val t0 = roomT + Units.KELVIN
        for (c in aw until an) {
            if (blocked[c] || blocked[c - aw]) continue
            val t = (avgT[c] + avgT[c - aw]) * 0.5f
            val a = (Units.GRAVITY * (t + Units.KELVIN - t0) / t0).coerceIn(-2f * Units.GRAVITY, 3f * Units.GRAVITY)
            vy[c - aw] -= a * Units.DT
        }
    }

    // ---------------- Перенос и проекция ----------------

    /** Ветер переносит сам себя и кислород (полулагранжев перенос), лёгкое перемешивание и трение. */
    private fun advect() {
        val k = Units.DT / (Units.CELL * cs) // клеток воздуха за шаг на 1 м/с
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) { tVx[c] = 0f; tVy[c] = 0f; tO2[c] = o2[c]; continue }
                // скорость в центре клетки
                val ux = (vx[c] + (if (x > 0) vx[c - 1] else 0f)) * 0.5f
                val uy = (vy[c] + (if (y > 0) vy[c - aw] else 0f)) * 0.5f
                val sx = (x - ux * k).coerceIn(0f, (aw - 1).toFloat())
                val sy = (y - uy * k).coerceIn(0f, (ah - 1).toFloat())
                // грань хранится со сдвигом на полклетки — отбор с поправкой
                tVx[c] = sample(vx, (x + 0.5f - ux * k - 0.5f).coerceIn(0f, (aw - 1).toFloat()), sy)
                tVy[c] = sample(vy, sx, (y + 0.5f - uy * k - 0.5f).coerceIn(0f, (ah - 1).toFloat()))
                tO2[c] = sample(o2, sx, sy)
            }
        }
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val c = y * aw + x
                if (blocked[c]) continue
                var sO = 0f; var n = 0
                if (x > 0 && !blocked[c - 1]) { sO += tO2[c - 1]; n++ }
                if (x < aw - 1 && !blocked[c + 1]) { sO += tO2[c + 1]; n++ }
                if (y > 0 && !blocked[c - aw]) { sO += tO2[c - aw]; n++ }
                if (y < ah - 1 && !blocked[c + aw]) { sO += tO2[c + aw]; n++ }
                vx[c] = tVx[c] * V_LOSS
                vy[c] = tVy[c] * V_LOSS
                o2[c] = if (n > 0) tO2[c] * 0.85f + sO / n * 0.15f else tO2[c]
                if (abs(vx[c]) < 0.01f || !vx[c].isFinite()) vx[c] = 0f
                if (abs(vy[c]) < 0.01f || !vy[c].isFinite()) vy[c] = 0f
                if (!o2[c].isFinite()) o2[c] = O2_NORMAL
            }
        }
    }

    /**
     * Проекция: подбираем давление так, чтобы из каждой клетки вытекало ровно столько, сколько в ней
     * рождается газа (взрыв, кипение, горение), — остальной воздух несжимаем. ∇²φ = ∇·u − S, u −= ∇φ.
     * Края мира — стены комнаты; в замкнутом объёме лишний газ поднимает давление всего объёма.
     */
    private fun project() {
        val dx = Units.CELL * cs
        val dt = Units.DT
        // источник по объёмам: средний поднимает давление объёма, остаток раздаётся течением
        java.util.Arrays.fill(regSrc, 0, regions, 0f)
        for (c in 0 until an) { val r = region[c]; if (r >= 0) regSrc[r] += src[c] }
        for (r in 0 until regions) {
            val mean = if (regN[r] > 0) regSrc[r] / regN[r] else 0f
            regSrc[r] = mean
            // ΔP/P = ΔV/V: газу некуда деться — растёт давление объёма (а в комнате — быстро уходит в глубину)
            regP[r] = (regP[r] + mean * Units.P_ATM / 1000f).coerceIn(P_MIN, P_MAX)
            regP[r] *= if (r == openRegion) 0.9f else 0.9995f
        }
        for (y in 0 until ah) for (x in 0 until aw) {
            val c = y * aw + x
            if (blocked[c]) { rhs[c] = 0f; continue }
            // грани в стену и за край мира — непроницаемы
            if (x == aw - 1 || blocked[c + 1]) vx[c] = 0f
            if (y == ah - 1 || blocked[c + aw]) vy[c] = 0f
        }
        for (y in 0 until ah) for (x in 0 until aw) {
            val c = y * aw + x
            if (blocked[c]) continue
            val div = (vx[c] - (if (x > 0) vx[c - 1] else 0f) + vy[c] - (if (y > 0) vy[c - aw] else 0f)) / dx
            val r = region[c]
            val s0 = src[c] - (if (r >= 0) regSrc[r] else 0f)
            rhs[c] = (div - s0 / dt) * dx * dx
        }
        // SOR с тёплым стартом; соседи и их число считаем один раз
        for (y in 0 until ah) for (x in 0 until aw) {
            val c = y * aw + x
            var m = 0; var n = 0
            if (!blocked[c]) {
                if (x > 0 && !blocked[c - 1]) { m = m or 1; n++ }
                if (x < aw - 1 && !blocked[c + 1]) { m = m or 2; n++ }
                if (y > 0 && !blocked[c - aw]) { m = m or 4; n++ }
                if (y < ah - 1 && !blocked[c + aw]) { m = m or 8; n++ }
            }
            nbMask[c] = m
            invN[c] = if (n > 0) 1f / n else 0f
        }
        val omega = 1.75f
        for (it in 0 until ITER) {
            for (c in 0 until an) {
                val m = nbMask[c]
                if (m == 0) continue
                var sum = 0f
                if (m and 1 != 0) sum += phi[c - 1]
                if (m and 2 != 0) sum += phi[c + 1]
                if (m and 4 != 0) sum += phi[c - aw]
                if (m and 8 != 0) sum += phi[c + aw]
                val v = (sum - rhs[c]) * invN[c]
                phi[c] += omega * (v - phi[c])
            }
        }
        // u −= ∇φ, давление p = ρ·φ/Δt
        for (y in 0 until ah) for (x in 0 until aw) {
            val c = y * aw + x
            if (blocked[c]) { vx[c] = 0f; vy[c] = 0f; pv[c] = 0f; continue }
            if (x < aw - 1 && !blocked[c + 1]) vx[c] = (vx[c] - (phi[c + 1] - phi[c]) / dx).coerceIn(-V_MAX, V_MAX)
            if (y < ah - 1 && !blocked[c + aw]) vy[c] = (vy[c] - (phi[c + aw] - phi[c]) / dx).coerceIn(-V_MAX, V_MAX)
            val r = region[c]
            val dyn = 1.2f * phi[c] / dt / 1000f
            pv[c] = (dyn + (if (r >= 0) regP[r] else 0f)).coerceIn(P_MIN, P_MAX)
            if (!phi[c].isFinite()) phi[c] = 0f
        }
        // среднее φ в объёме не важно — держим около нуля
        src.fill(0f)
    }

    private var openRegion = -1

    /**
     * Воздух под открытым небом комнаты (над ним до верха мира нет потолка). Мир — срез 1 см: тёплый воздух
     * здесь расходится и вглубь комнаты и остывает, а под куполом шара, в печи, под столом — держится.
     */
    val exposed = BooleanArray(an)

    private fun findExposed() {
        for (x in 0 until aw) {
            var sky = true
            for (y in 0 until ah) {
                val c = y * aw + x
                if (blocked[c]) sky = false
                exposed[c] = sky && open[c]
            }
        }
    }

    /** Связные объёмы воздуха (комната — тот, что касается края мира). Давление переносим из старой разметки. */
    private fun labelRegions() {
        val oldReg = region.copyOf()
        val oldP = regP.copyOf()
        java.util.Arrays.fill(region, -1)
        regions = 0
        openRegion = -1
        for (c0 in 0 until an) {
            if (blocked[c0] || region[c0] >= 0) continue
            val r = regions++
            if (r >= regN.size) { regN = regN.copyOf(r * 2); regSrc = regSrc.copyOf(r * 2); regP = regP.copyOf(r * 2) }
            var head = 0; var tail = 0
            queue[tail++] = c0; region[c0] = r
            var n = 0; var pSum = 0f; var isOpen = false
            while (head < tail) {
                val c = queue[head++]
                n++
                val o = oldReg[c]
                if (o >= 0 && o < oldP.size) pSum += oldP[o]
                if (open[c]) isOpen = true
                val x = c % aw
                if (x > 0 && !blocked[c - 1] && region[c - 1] < 0) { region[c - 1] = r; queue[tail++] = c - 1 }
                if (x < aw - 1 && !blocked[c + 1] && region[c + 1] < 0) { region[c + 1] = r; queue[tail++] = c + 1 }
                if (c >= aw && !blocked[c - aw] && region[c - aw] < 0) { region[c - aw] = r; queue[tail++] = c - aw }
                if (c + aw < an && !blocked[c + aw] && region[c + aw] < 0) { region[c + aw] = r; queue[tail++] = c + aw }
            }
            regN[r] = n
            regP[r] = if (n > 0) pSum / n else 0f
            if (isOpen && openRegion < 0) openRegion = r
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

    /**
     * Газ взрыва, горения, кипения или кисть давления: kPa — насколько газ «распёр» клетку воздуха
     * (Δp/p = ΔV/V). Течение разносит лишний объём, в замкнутом объёме растёт давление.
     */
    fun addPressure(i: Int, kPa: Float) {
        var c = cellOf(i)
        if (blocked[c]) {
            // взрыв внутри завала выходит в ближайший воздух
            val x = c % aw
            c = when {
                c >= aw && !blocked[c - aw] -> c - aw
                x > 0 && !blocked[c - 1] -> c - 1
                x < aw - 1 && !blocked[c + 1] -> c + 1
                c + aw < an && !blocked[c + aw] -> c + aw
                else -> return
            }
        }
        src[c] += kPa / 101.325f * SRC_GAIN
    }

    /** Кисть давления: круг радиусом r мелких клеток. */
    fun brushPressure(cx: Int, cy: Int, r: Int, kPa: Float) {
        forCells(cx, cy, r) { c -> if (!blocked[c]) src[c] += kPa / 101.325f * 0.5f }
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
        const val FAN_SPEED = 6f         // м/с, как у настольного вентилятора
        const val V_LOSS = 0.99f         // вязкость и турбулентность: порыв стихает за несколько секунд
        const val P_MIN = -100f
        const val P_MAX = 400f
        const val V_MAX = 60f
        const val ITER = 16
        /** Мир — срез 1 см: газ взрыва расходится и вглубь, в срезе остаётся лишь часть объёма. */
        const val SRC_GAIN = 4f
    }
}
