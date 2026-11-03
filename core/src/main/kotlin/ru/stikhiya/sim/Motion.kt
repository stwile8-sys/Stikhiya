package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Движение: порошки падают с ускорением и осыпаются, жидкости растекаются и слоятся по плотности,
 * газы всплывают или опускаются относительно воздуха, пузыри поднимаются сквозь жидкость.
 */
class Motion(private val w: World, private val air: Air) {
    private var tick = 0

    fun step(tick: Int) {
        this.tick = tick
        val ww = w.w
        val hh = w.h
        val type = w.type
        val stamp = w.stamp
        for (yy in 0 until hh) {
            val y = hh - 1 - yy
            val ltr = ((tick + y) and 1) == 0
            for (xx in 0 until ww) {
                val x = if (ltr) xx else ww - 1 - xx
                val i = y * ww + x
                if (stamp[i] == tick) continue
                val id = type[i].toInt()
                if (id == Mat.AIR) continue
                when (Mat.phase[id]) {
                    Phase.POWDER.ordinal -> powder(i, x, y, id)
                    Phase.LIQUID.ordinal -> liquid(i, x, y, id)
                    Phase.GAS.ordinal, Phase.FLAME.ordinal -> gas(i, x, y, id)
                }
            }
        }
    }

    private fun steps(v: Float): Int {
        val f = v.toInt()
        return f + if (w.rnd() < v - f) 1 else 0
    }

    /** Может ли падающая клетка с плотностью rho войти в клетку j. */
    private fun canSink(rho: Float, mover: Int, j: Int): Boolean {
        val t = w.type[j].toInt()
        if (t == Mat.AIR) return true
        val ph = Mat.phase[t]
        if (ph <= Phase.FLAME.ordinal) return true
        if (ph == Phase.LIQUID.ordinal) {
            val dj = w.cellDensity(j)
            if (rho <= dj * 1.0005f) return false
            var p = (rho - dj) / rho * 2.5f
            if (t == mover) p *= 0.3f       // тепловая конвекция внутри одной жидкости
            p *= (1f - Mat.viscosity[t] * 0.8f)
            return w.rnd() < p
        }
        return false
    }

    private fun move(a: Int, b: Int) {
        w.swap(a, b)
        w.stamp[a] = tick
        w.stamp[b] = tick
    }

    // ---------------- Порошки ----------------

    private fun powder(i0: Int, x0: Int, y0: Int, id: Int) {
        var i = i0
        var x = x0
        var y = y0
        val ww = w.w
        // сильный ветер или взрыв сдувает порошок; мокрый держится
        val por = Mat.porosity[id]
        val fill = if (por > 0f && w.mixId[i].toInt() != 0) w.mix[i] / (por * w.amt[i]) else 0f
        val vth = 1.5f * sqrt(Mat.density[id] / 200f) * (1f + 8f * fill)
        if (blow(i, x, y, vth) >= 0) return
        val rho = w.cellDensity(i)
        w.vy[i] = minOf(w.vy[i] + Units.G, Units.MAX_FALL)
        var n = steps(w.vy[i])
        if (n < 1) n = 1
        var movedAny = false
        var s = 0
        while (s < n) {
            s++
            if (y >= w.h - 1) { w.vy[i] = 0f; break }
            val below = i + ww
            if (canSink(rho, id, below)) {
                val intoLiquid = Mat.isLiquid(w.type[below].toInt())
                move(i, below)
                i = below; y++; movedAny = true
                if (intoLiquid) { w.vy[i] = minOf(w.vy[i], 0.5f); break }
                continue
            }
            // прямо вниз нельзя — осыпаемся по склону
            if (w.rnd() > friction(i, id)) {
                val dir = if (w.rnd() < 0.5f) -1 else 1
                var slid = false
                for (k in 0 until 2) {
                    val dx = if (k == 0) dir else -dir
                    val nx = x + dx
                    if (nx < 0 || nx >= ww) continue
                    val side = i + dx
                    val diag = below + dx
                    if (!passable(side)) continue
                    if (canSink(rho, id, diag)) {
                        move(i, diag)
                        i = diag; x = nx; y++; slid = true; movedAny = true
                        w.vy[i] *= 0.6f
                        break
                    }
                }
                if (slid) continue
            }
            w.vy[i] = 0f
            break
        }
        w.stamp[i] = tick
        if (!movedAny && y > 0) floatUp(i, rho)
    }

    /** Лёгкий порошок (опилки) всплывает в жидкости. */
    private fun floatUp(i: Int, rho: Float) {
        val above = i - w.w
        val t = w.type[above].toInt()
        if (!Mat.isLiquid(t)) return
        val da = w.cellDensity(above)
        if (da > rho * 1.02f && w.rnd() < (da - rho) / da * 1.5f) move(i, above)
    }

    /** Трение порошка: влажный песок держит форму, переувлажнённый — оплывает. */
    private fun friction(i: Int, id: Int): Float {
        var f = Mat.friction[id]
        val por = Mat.porosity[id]
        if (por > 0f && w.mixId[i].toInt() != 0) {
            val fill = w.mix[i] / (por * w.amt[i])
            f = if (fill > 0.92f) 0.25f else f + (1f - f) * minOf(1f, fill / 0.25f)
        }
        return f
    }

    private fun passable(j: Int): Boolean {
        val t = w.type[j].toInt()
        return t == Mat.AIR || Mat.phase[t] <= Phase.LIQUID.ordinal
    }

    // ---------------- Жидкости ----------------

    private fun liquid(i0: Int, x0: Int, y0: Int, id: Int) {
        var i = i0
        var x = x0
        var y = y0
        val ww = w.w
        // мелкие капли (туман, конденсат) медленно оседают и сливаются
        if (w.amt[i] < 0.02f) {
            if (coalesce(i, id)) return
            if (w.rnd() > 0.15f) { w.stamp[i] = tick; return }
        }
        if (blow(i, x, y, 8f) >= 0) return
        val rho = w.cellDensity(i)
        w.vy[i] = minOf(w.vy[i] + Units.G, Units.MAX_FALL)
        var n = steps(w.vy[i])
        if (n < 1) n = 1
        var fell = false
        var s = 0
        while (s < n) {
            s++
            if (y >= w.h - 1) break
            val below = i + ww
            val bt = w.type[below].toInt()
            if (bt == id && w.amt[below] < 0.999f && sameSolute(i, below)) {
                if (merge(i, below)) return
            }
            if (canSink(rho, id, below)) {
                val intoLiquid = Mat.isLiquid(bt)
                move(i, below)
                i = below; y++; fell = true
                if (intoLiquid) break
                continue
            }
            break
        }
        if (fell) { w.stamp[i] = tick; return }
        w.vy[i] = 0f
        if (Mat.viscosity[id] > 0f && w.rnd() < Mat.viscosity[id]) { w.stamp[i] = tick; return }

        // вниз по диагонали
        if (y < w.h - 1) {
            val dir = if (w.rnd() < 0.5f) -1 else 1
            for (k in 0 until 2) {
                val dx = if (k == 0) dir else -dir
                val nx = x + dx
                if (nx < 0 || nx >= ww) continue
                val diag = i + ww + dx
                val side = i + dx
                if (passableForLiquid(side, rho) && canSink(rho, id, diag)) {
                    move(i, diag)
                    w.stamp[diag] = tick
                    return
                }
            }
        }

        // растекание в стороны
        var dir = if ((w.flags[i].toInt() and 1) == 0) -1 else 1
        val spread = Mat.spread[id]
        var k = 0
        while (k < spread) {
            k++
            val nx = x + dir
            if (nx < 0 || nx >= ww) { dir = -dir; break }
            val j = i + dir
            val t = w.type[j].toInt()
            if (t == Mat.AIR || Mat.phase[t] <= Phase.FLAME.ordinal) {
                move(i, j); i = j; x = nx
                // впереди обрыв — дальше падаем на следующем тике
                if (y < w.h - 1 && passable(i + ww)) break
                continue
            }
            if (t == id) {
                if (w.amt[j] < 0.999f && sameSolute(i, j) && merge(i, j)) return
                // проходим сквозь свою жидкость только ради выравнивания температуры/раствора
                break
            }
            if (Mat.isLiquid(t) && w.cellDensity(j) < rho && w.rnd() < 0.5f) {
                move(i, j); i = j; x = nx
                continue
            }
            dir = -dir
            break
        }
        w.flags[i] = ((w.flags[i].toInt() and 0xFE) or (if (dir > 0) 1 else 0)).toByte()
        w.stamp[i] = tick
    }

    private fun passableForLiquid(j: Int, rho: Float): Boolean {
        val t = w.type[j].toInt()
        if (t == Mat.AIR || Mat.phase[t] <= Phase.FLAME.ordinal) return true
        return Mat.isLiquid(t) && w.cellDensity(j) < rho
    }

    private fun sameSolute(a: Int, b: Int): Boolean {
        val ma = w.mixId[a].toInt()
        val mb = w.mixId[b].toInt()
        return ma == mb || ma == 0 || mb == 0
    }

    /** Перелить жидкость из a в неполную клетку b той же жидкости. true — a опустела. */
    private fun merge(a: Int, b: Int): Boolean {
        val room = 1f - w.amt[b]
        val t = minOf(room, w.amt[a])
        if (t <= 0f) return false
        val ca = w.mix[a]
        val cb = w.mix[b]
        val na = w.amt[a]
        val nb = w.amt[b]
        // смешение раствора и температуры по массе
        val solMass = cb * nb + ca * t
        val newB = nb + t
        val heatB = w.heatCapacity(b)
        val heatA = w.heatCapacity(a) * (t / na)
        w.temp[b] = (w.temp[b] * heatB + w.temp[a] * heatA) / (heatB + heatA)
        if (w.mixId[b].toInt() == 0) w.mixId[b] = w.mixId[a]
        w.mix[b] = if (newB > 0f) solMass / newB else 0f
        w.amt[b] = newB
        w.amt[a] = na - t
        w.stamp[b] = tick
        if (w.amt[a] < 1e-4f) {
            w.toAir(a)
            w.stamp[a] = tick
            return true
        }
        return false
    }

    /** Капли сливаются с соседней жидкостью того же вида. */
    private fun coalesce(i: Int, id: Int): Boolean {
        val s = w.rndInt(4)
        for (k in 0 until 4) {
            val j = Neighbors.at(w, i, (s + k) and 3)
            if (j < 0) continue
            if (w.type[j].toInt() == id && w.amt[j] > w.amt[i] && w.amt[j] < 0.999f && sameSolute(i, j)) {
                return merge(i, j)
            }
        }
        return false
    }

    // ---------------- Газы ----------------

    private fun gas(i: Int, x: Int, y: Int, id: Int) {
        val ww = w.w
        if (w.amt[i] > 2f) expand(i, id)
        // газ уносится ветром
        if (gasWind(i, x, y)) return
        val rhoG = w.massDensity(id, w.temp[i])

        // пузырь в жидкости — всплывает
        if (y > 0) {
            val up = i - ww
            if (Mat.isLiquid(w.type[up].toInt()) && w.rnd() < 0.6f) { move(i, up); return }
        }

        var moves = 1
        if (id == Mat.FLAME) moves = 2
        var cur = i
        var cx = x
        var cy = y
        for (m in 0 until moves) {
            val upC = if (cy > 0) cur - ww else -1
            val refT = if (upC >= 0) w.temp[upC] else w.temp[cur]
            val rhoAir = Units.gasDensity(Units.AIR_MOLAR, refT)
            val b = (rhoAir - rhoG) / rhoAir // >0 — легче воздуха
            var moved = -1
            if (b > 0.005f) {
                if (cy > 0 && w.rnd() < minOf(1f, b * 3f + 0.2f)) moved = tryGas(cur, upC, rhoG, true)
                if (moved < 0 && cy > 0 && w.rnd() < 0.5f) {
                    val dx = if (w.rnd() < 0.5f) -1 else 1
                    if (cx + dx in 0 until ww) moved = tryGas(cur, upC + dx, rhoG, true)
                }
            } else if (b < -0.005f) {
                if (cy < w.h - 1 && w.rnd() < minOf(1f, -b * 2f + 0.1f)) moved = tryGas(cur, cur + ww, rhoG, false)
                if (moved < 0 && cy < w.h - 1 && w.rnd() < 0.4f) {
                    val dx = if (w.rnd() < 0.5f) -1 else 1
                    if (cx + dx in 0 until ww) moved = tryGas(cur, cur + ww + dx, rhoG, false)
                }
            }
            if (moved < 0 && w.rnd() < 0.5f) {
                // диффузия в стороны
                val dx = if (w.rnd() < 0.5f) -1 else 1
                if (cx + dx in 0 until ww) {
                    val j = cur + dx
                    if (w.type[j].toInt() == Mat.AIR) { move(cur, j); moved = j }
                }
            }
            if (moved < 0) break
            if (moved == cur - ww || moved == cur - ww - 1 || moved == cur - ww + 1) cy--
            else if (moved >= cur + ww - 1) cy++
            cx = moved % ww
            cur = moved
        }
        w.stamp[cur] = tick
    }

    /** Сдвинуть газ в j: в воздух — всегда, в другой газ — если тот тяжелее (вверх) или легче (вниз). */
    private fun tryGas(i: Int, j: Int, rhoG: Float, up: Boolean): Int {
        if (j < 0 || j >= w.n) return -1
        val t = w.type[j].toInt()
        if (t == Mat.AIR) { move(i, j); return j }
        if (!Mat.isGas(t)) return -1
        if (t == w.type[i].toInt()) return -1
        val rj = w.massDensity(t, w.temp[j])
        if ((up && rj > rhoG * 1.01f) || (!up && rj < rhoG * 0.99f)) { move(i, j); return j }
        return -1
    }

    /** Газ и пламя переносятся ветром. true — клетка сдвинулась. */
    private fun gasWind(i: Int, x: Int, y: Int): Boolean {
        val ux = air.windX(i) * air.cellsPerTick
        val uy = air.windY(i) * air.cellsPerTick
        if (abs(ux) + abs(uy) < 0.1f) return false
        var cur = i
        var cx = x
        var cy = y
        val nx = minOf(3, steps(abs(ux)))
        val ny = minOf(3, steps(abs(uy)))
        val dx = if (ux > 0f) 1 else -1
        val dy = if (uy > 0f) 1 else -1
        var kx = 0
        var ky = 0
        while (kx < nx || ky < ny) {
            val goX = kx < nx && (ky >= ny || w.rnd() < 0.5f)
            val tx = if (goX) cx + dx else cx
            val ty = if (goX) cy else cy + dy
            if (goX) kx++ else ky++
            if (tx < 0 || tx >= w.w || ty < 0 || ty >= w.h) break
            val j = ty * w.w + tx
            val t = w.type[j].toInt()
            if (t != Mat.AIR && (!Mat.isGas(t) || t == w.type[cur].toInt())) break
            move(cur, j)
            cur = j; cx = tx; cy = ty
        }
        if (cur == i) return false
        w.stamp[cur] = tick
        return true
    }

    /**
     * Ветер сильнее порога сдувает клетку по течению (порошок, брызги).
     * Возвращает новую позицию или -1.
     */
    private fun blow(i: Int, x: Int, y: Int, threshold: Float): Int {
        val wx = air.windX(i)
        val wy = air.windY(i)
        val speed = abs(wx) + abs(wy)
        if (speed <= threshold) return -1
        if (w.rnd() > (speed - threshold) / threshold) return -1
        val n = minOf(4, maxOf(1, (speed * air.cellsPerTick * 0.25f).toInt()))
        var cur = i
        var cx = x
        var cy = y
        for (k in 0 until n) {
            val goX = w.rnd() * speed < abs(wx)
            val tx = if (goX) cx + (if (wx > 0f) 1 else -1) else cx
            val ty = if (goX) cy else cy + (if (wy > 0f) 1 else -1)
            if (tx < 0 || tx >= w.w || ty < 0 || ty >= w.h) break
            val j = ty * w.w + tx
            val t = w.type[j].toInt()
            if (t != Mat.AIR && !Mat.isGas(t)) break
            move(cur, j)
            cur = j; cx = tx; cy = ty
        }
        if (cur == i) return -1
        w.vy[cur] = 0f
        w.stamp[cur] = tick
        return cur
    }

    /** Сжатый газ (только что выкипевший) расширяется в соседние пустые клетки. */
    private fun expand(i: Int, id: Int) {
        val s = w.rndInt(4)
        var splits = 0
        for (k in 0 until 4) {
            if (w.amt[i] <= 2f || splits >= 2) break
            val j = Neighbors.at(w, i, (s + k) and 3)
            if (j < 0 || w.type[j].toInt() != Mat.AIR) continue
            val half = w.amt[i] * 0.5f
            val t = w.temp[i]
            val life = w.life[i]
            w.put(j, id, t)
            w.amt[j] = half
            w.life[j] = life
            w.amt[i] = half
            w.stamp[j] = tick
            splits++
        }
    }

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1e-3f
}
