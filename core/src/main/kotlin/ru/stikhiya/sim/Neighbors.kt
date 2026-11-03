package ru.stikhiya.sim

/** Соседи клетки по четырём сторонам: вверх, вниз, влево, вправо. */
object Neighbors {
    /** Номер соседа в направлении d (0 — вверх, 1 — вниз, 2 — влево, 3 — вправо) или -1 за краем. */
    fun at(w: World, i: Int, d: Int): Int {
        val x = i % w.w
        return when (d) {
            0 -> if (i >= w.w) i - w.w else -1
            1 -> if (i + w.w < w.n) i + w.w else -1
            2 -> if (x > 0) i - 1 else -1
            else -> if (x < w.w - 1) i + 1 else -1
        }
    }

    fun countAir(w: World, i: Int): Int {
        var c = 0
        for (d in 0 until 4) {
            val j = at(w, i, d)
            if (j >= 0 && w.type[j].toInt() == Mat.AIR) c++
        }
        return c
    }

    /** Есть ли рядом воздух (в том числе с дымом) или пламя — доступ кислорода. */
    fun hasOxygen(w: World, i: Int): Boolean {
        for (d in 0 until 4) {
            val j = at(w, i, d)
            if (j < 0) continue
            if (breathable(w.type[j].toInt())) return true
        }
        return false
    }

    fun breathable(t: Int) = t == Mat.AIR || t == Mat.FLAME || t == Mat.SMOKE

    fun hasType(w: World, i: Int, id: Int): Boolean {
        for (d in 0 until 4) {
            val j = at(w, i, d)
            if (j >= 0 && w.type[j].toInt() == id) return true
        }
        return false
    }

    /** Случайный соседний воздух, верх в приоритете. */
    fun randomAir(w: World, i: Int, preferUp: Boolean = false): Int {
        if (preferUp) {
            val up = at(w, i, 0)
            if (up >= 0 && w.type[up].toInt() == Mat.AIR && w.rnd() < 0.7f) return up
        }
        val s = w.rndInt(4)
        for (k in 0 until 4) {
            val j = at(w, i, (s + k) and 3)
            if (j >= 0 && w.type[j].toInt() == Mat.AIR) return j
        }
        return -1
    }

    fun randomOfType(w: World, i: Int, id: Int): Int {
        val s = w.rndInt(4)
        for (k in 0 until 4) {
            val j = at(w, i, (s + k) and 3)
            if (j >= 0 && w.type[j].toInt() == id) return j
        }
        return -1
    }
}
