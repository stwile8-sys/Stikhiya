package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Сообщающиеся сосуды. У связной жидкости давление одно: если у неё несколько поверхностей,
 * разделённых стенкой (U-трубка, дыра в баке, вода по обе стороны бревна), жидкость перетекает
 * от верхней поверхности к нижней со скоростью Торричелли v = √(2gh), пока уровни не сравняются.
 * Волнистая поверхность одного пруда не трогается — волны живут сами.
 */
class Hydro(private val w: World) {
    private val n = w.n
    private val comp = IntArray(n)
    private val surf = IntArray(n)
    private val airMark = IntArray(n)
    private val queue = IntArray(n)
    private val cells = IntArray(n)
    private val bnd = IntArray(n)
    private val grp = IntArray(n)       // номер поверхности для клетки границы (индекс в bnd)
    private val sinks = IntArray(4096)
    private val sinkDir = IntArray(4096)
    private val tops = IntArray(4096)
    private var label = 0
    private var surfLabel = 0
    private var airLabel = 0
    private var airRound = 0

    private fun open(j: Int): Boolean {
        val t = w.type[j].toInt()
        return t == Mat.AIR || Mat.phase[t] == Phase.GAS.ordinal
    }

    private fun member(i: Int, id: Int): Boolean {
        if (w.type[i].toInt() != id || w.vy[i] > 0.6f || w.vy[i] < -0.2f) return false
        val below = i + w.w
        return below >= n || !open(below)
    }

    fun step(tick: Int) {
        if (tick and 1 != 0) return
        val roundStart = label
        airRound = airLabel
        for (i in 0 until n) {
            val id = w.type[i].toInt()
            if (!Mat.isLiquid(id) || comp[i] > roundStart) continue
            if (!member(i, id)) continue
            component(i, id)
        }
    }

    private fun component(seed: Int, id: Int) {
        val ww = w.w
        val lab = ++label
        var head = 0
        var tail = 0
        comp[seed] = lab; queue[tail++] = seed
        var m = 0
        var nb = 0
        while (head < tail) {
            val c = queue[head++]
            cells[m++] = c
            val x = c % ww
            var isB = false
            for (k in 0 until 4) {
                val j = when (k) { 0 -> if (x > 0) c - 1 else -1; 1 -> if (x < ww - 1) c + 1 else -1; 2 -> c - ww; else -> c + ww }
                if (j < 0 || j >= n) continue
                if (open(j)) { if (k != 3) isB = true; continue }
                if (comp[j] != lab && member(j, id)) { comp[j] = lab; queue[tail++] = j }
            }
            if (isB) bnd[nb++] = c
        }
        if (m < 6 || nb < 2) return
        // поверхности: границы с воздухом, связные по 8 соседям
        val firstSurf = surfLabel + 1
        for (b in 0 until nb) {
            val c0 = bnd[b]
            if (surf[c0] >= firstSurf) continue
            val sl = ++surfLabel
            var h = 0
            var t = 0
            surf[c0] = sl; queue[t++] = c0
            while (h < t) {
                val c = queue[h++]
                val x = c % ww
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    if (nx < 0 || nx >= ww) continue
                    val j = c + dy * ww + dx
                    if (j < 0 || j >= n || comp[j] != lab || surf[j] >= firstSurf) continue
                    if (!isBoundary(j)) continue
                    surf[j] = sl; queue[t++] = j
                }
            }
        }
        if (surfLabel - firstSurf < 1) return   // одна поверхность
        // источник: самая высокая клетка с воздухом сверху
        var topY = Int.MAX_VALUE
        var srcSurf = 0
        for (b in 0 until nb) {
            val c = bnd[b]
            if (c - ww < 0 || !open(c - ww)) continue
            val y = c / ww
            if (y < topY) { topY = y; srcSurf = surf[c] }
        }
        if (srcSurf == 0) return
        // приёмники на других поверхностях
        var ns = 0
        for (b in 0 until nb) {
            val c = bnd[b]
            if (surf[c] == srcSurf) continue
            val x = c % ww
            val y = c / ww
            if (c - ww >= 0 && open(c - ww)) {
                if (y - 1 >= topY + 2) ns = addSink(ns, c - ww, 0)
            } else if (y >= topY + 2) {
                if (x > 0 && open(c - 1)) ns = addSink(ns, c - 1, -1)
                if (x < ww - 1 && open(c + 1)) ns = addSink(ns, c + 1, 1)
            }
        }
        if (ns == 0) return
        // приёмник должен быть в том же воздухе, что над источником (пузырь под водой — не приёмник)
        var srcAir = -1
        for (b in 0 until nb) { val c = bnd[b]; if (c / ww == topY && c - ww >= 0 && open(c - ww)) { srcAir = c - ww; break } }
        if (airMark[srcAir] <= airRound) floodAir(srcAir)
        val region = airMark[srcAir]
        var good = 0
        var bestY = -1
        for (s in 0 until ns) {
            val j = sinks[s]
            if (airMark[j] != region) continue
            val y = j / ww
            if (y > bestY) { bestY = y; good = 0 }
            if (y == bestY) { sinks[good] = j; sinkDir[good] = sinkDir[s]; good++ }
        }
        val dh = bestY - topY
        if (good == 0 || dh < 2) return
        var nt = 0
        for (b in 0 until nb) {
            val c = bnd[b]
            if (c / ww == topY && surf[c] == srcSurf && open(c - ww) && nt < tops.size) tops[nt++] = c
        }
        val vCells = sqrt(2f * Units.GRAVITY * dh * 0.01f) * 100f * Units.DT
        val width = minOf(good, nt, 8)
        val k = (vCells * 2f * 0.6f * width).toInt().coerceIn(1, 40)
        var moves = 0
        while (moves < k && nt > 0 && good > 0) {
            val a = w.rndInt(nt)
            val b = w.rndInt(good)
            val src = tops[a]
            val dst = sinks[b]
            w.swap(src, dst)
            w.vy[dst] = 0f
            w.vx[dst] = sinkDir[b] * minOf(vCells, 8f)
            tops[a] = tops[--nt]
            sinks[b] = sinks[good - 1]; sinkDir[b] = sinkDir[good - 1]; good--
            moves++
        }
    }

    private fun isBoundary(j: Int): Boolean {
        val ww = w.w
        val x = j % ww
        return (j - ww >= 0 && open(j - ww)) || (x > 0 && open(j - 1)) || (x < ww - 1 && open(j + 1))
    }

    private fun addSink(ns: Int, j: Int, dir: Int): Int {
        if (ns >= sinks.size) return ns
        sinks[ns] = j; sinkDir[ns] = dir
        return ns + 1
    }

    private fun floodAir(start: Int) {
        val ww = w.w
        val lab = ++airLabel
        var h = 0
        var t = 0
        airMark[start] = lab; queue[t++] = start
        while (h < t) {
            val c = queue[h++]
            val x = c % ww
            if (x > 0) visit(c - 1, lab).let { if (it) queue[t++] = c - 1 }
            if (x < ww - 1) visit(c + 1, lab).let { if (it) queue[t++] = c + 1 }
            if (c - ww >= 0) visit(c - ww, lab).let { if (it) queue[t++] = c - ww }
            if (c + ww < n) visit(c + ww, lab).let { if (it) queue[t++] = c + ww }
        }
    }

    private fun visit(j: Int, lab: Int): Boolean {
        if (airMark[j] == lab || !open(j)) return false
        airMark[j] = lab
        return true
    }
}
