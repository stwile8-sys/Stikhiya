package ru.stikhiya.sim

import kotlin.math.abs
import kotlin.math.exp

/**
 * Тепло: теплопроводность между клетками, теплоотдача газ — поверхность (конвекция + излучение),
 * кипение у горячей стенки, всплытие тёплого воздуха и фазовые переходы с теплотой перехода.
 */
class Heat(private val w: World, private val air: Air) {
    private val n = w.n
    private val cap = FloatArray(n)     // теплоёмкость клетки, Дж/К
    private val k = FloatArray(n)       // эффективная теплопроводность, Вт/(м·К)
    private val kind = ByteArray(n)     // 0 — газ, 1 — жидкость, 2 — твёрдое/порошок

    fun step() {
        prepare()
        conduct()
        convectAir()
        walls()
        transitions()
    }

    private fun prepare() {
        val bands = Parallel.bands
        Parallel.forBands(bands) { b -> prepareRange(Parallel.from(b, bands, n), Parallel.to(b, bands, n)) }
    }

    private fun prepareRange(from: Int, to: Int) {
        val type = w.type
        val temp = w.temp
        val airC = Units.P_ATM * Units.AIR_MOLAR / Units.R_GAS * Units.VOL * Mat.heatCap[Mat.AIR]
        for (i in from until to) {
            val id = type[i].toInt()
            if (id == Mat.AIR) {
                // воздух — самый частый случай, считаем без лишних вызовов
                cap[i] = airC / maxOf(temp[i] + Units.KELVIN, 1f)
                kind[i] = 0
                k[i] = Mat.cond[Mat.AIR]
                continue
            }
            cap[i] = w.heatCapacity(i)
            val ph = Mat.phase[id]
            kind[i] = when {
                ph <= Phase.FLAME.ordinal -> 0
                ph == Phase.LIQUID.ordinal -> 1
                else -> 2
            }
            var kk = Mat.cond[id]
            if (ph == Phase.POWDER.ordinal && w.mixId[i].toInt() != 0) {
                // влажный порошок проводит тепло заметно лучше сухого
                val por = Mat.porosity[id]
                if (por > 0f) kk += (w.mix[i] / por) * 1.2f * Mat.cond[w.mixId[i].toInt()] / 0.6f
            }
            k[i] = kk
        }
    }

    /**
     * Теплопроводность в три прохода, каждый — параллельно по полосам:
     * пары соседей в строке, затем пары строк (чётная + следующая), затем (нечётная + следующая).
     * В одном проходе разные полосы не трогают одни и те же клетки.
     */
    private fun conduct() {
        val hh = w.h
        val bands = Parallel.bands
        Parallel.forBands(bands) { b -> for (y in Parallel.from(b, bands, hh) until Parallel.to(b, bands, hh)) rowPairs(y) }
        val pairs = hh / 2
        Parallel.forBands(bands) { b -> for (p in Parallel.from(b, bands, pairs) until Parallel.to(b, bands, pairs)) columnPairs(p * 2) }
        val pairsOdd = (hh - 1) / 2
        Parallel.forBands(bands) { b -> for (p in Parallel.from(b, bands, pairsOdd) until Parallel.to(b, bands, pairsOdd)) columnPairs(p * 2 + 1) }
    }

    // между двумя клетками воздуха тепло переносит движение воздуха, а не теплопроводность
    private fun rowPairs(y: Int) {
        val ww = w.w
        val type = w.type
        val air = Mat.AIR.toShort()
        val row = y * ww
        for (x in 0 until ww - 1) {
            val i = row + x
            if (type[i] == air && type[i + 1] == air) continue
            exchange(i, i + 1)
        }
    }

    private fun columnPairs(y: Int) {
        if (y + 1 >= w.h) return
        val ww = w.w
        val type = w.type
        val air = Mat.AIR.toShort()
        val row = y * ww
        for (x in 0 until ww) {
            val i = row + x
            if (type[i] == air && type[i + ww] == air) continue
            exchange(i, i + ww)
        }
    }

    private fun exchange(i: Int, j: Int) {
        val temp = w.temp
        val ti = temp[i]
        val tj = temp[j]
        val dT = tj - ti
        if (abs(dT) < 0.02f) return
        val ki = kind[i].toInt()
        val kj = kind[j].toInt()
        val a = w.type[i].toInt()
        val b = w.type[j].toInt()
        val g: Float // теплопроводность контакта, Вт/К
        if (ki == 0 && kj == 0) {
            g = harmonic(k[i], k[j]) * Units.CELL
        } else if (ki == 0 || kj == 0) {
            // газ у поверхности: естественная конвекция + излучение
            val gas = if (ki == 0) a else b
            val surf = if (ki == 0) j else i
            val gasCell = if (ki == 0) i else j
            var h = if (gas == Mat.FLAME) 150f else 15f
            val ts = temp[surf]
            val tg = temp[gasCell]
            // плёночная конденсация пара на холодной стенке
            if (Mat.coldTo[gas] >= 0 && Mat.isLiquid(Mat.coldTo[gas]) && ts < Mat.coldAt[gas]) h = 2000f
            val eps = Mat.emissivity[gas] * 0.9f
            if (eps > 0f) {
                val t1 = tg + Units.KELVIN
                val t2 = ts + Units.KELVIN
                h += eps * Units.SIGMA * (t1 * t1 + t2 * t2) * (t1 + t2)
            }
            // в рыхлый порошок горячий газ проникает между зёрнами — поверхность обмена больше
            val sid = w.type[surf].toInt()
            if (Mat.isPowder(sid)) h *= 1f + 6f * Mat.porosity[sid]
            g = h * Units.AREA
        } else if ((ki == 1 || kj == 1) && a != b) {
            // жидкость у стенки или другой жидкости: конвекция, кипение, плёночное кипение
            val fluid = if (ki == 1 && (kj != 1 || ti < tj)) i else j
            val wall = if (fluid == i) j else i
            val fid = w.type[fluid].toInt()
            var h = 1000f
            val boilTo = Mat.hotTo[fid]
            if (boilTo >= 0 && Mat.isGas(boilTo)) {
                val boil = air.boilPoint(fid, Mat.hotAt[fid], fluid)
                if (temp[fluid] >= boil - 0.5f && temp[wall] > boil) {
                    // очень горячая стенка — паровая подушка (эффект Лейденфроста), но кипит всё равно бойко
                    h = if (temp[wall] - boil > 300f) 2500f else 10000f
                }
            }
            g = maxOf(h * Units.AREA, harmonic(k[i], k[j]) * Units.CELL)
        } else {
            g = harmonic(k[i], k[j]) * Units.CELL
        }
        var e = g * dT * Units.DT // Дж, тепло из j в i
        val ci = cap[i]
        val cj = cap[j]
        val eMax = 0.45f * dT * ci * cj / (ci + cj)
        if (abs(e) > abs(eMax)) e = eMax
        temp[i] = ti + e / ci
        temp[j] = tj - e / cj
    }

    /** Тёплый воздух поднимается, холодный опускается; немного перемешивается в стороны. */
    private var flip = false

    private fun convectAir() {
        val ww = w.w
        val type = w.type
        val temp = w.temp
        // обход строки то слева направо, то справа налево — иначе тёплый воздух «сносит» в одну сторону
        flip = !flip
        for (y in 1 until w.h) {
            val row = y * ww
            for (xx in 0 until ww) {
                val x = if (flip) ww - 1 - xx else xx
                val i = row + x
                if (type[i].toInt() != Mat.AIR) continue
                val j = i - ww
                if (type[j].toInt() == Mat.AIR) {
                    val ti = temp[i]
                    val tj = temp[j]
                    if (ti > tj + 0.3f) {
                        // сила всплытия ~ разнице плотностей
                        val p = (ti - tj) / (ti + Units.KELVIN) * 30f
                        if (w.rnd() < p) { temp[i] = tj; temp[j] = ti }
                    }
                }
                // некуда подниматься (потолок или над ним ещё теплее) — тёплый воздух растекается в стороны,
                // а холодный подтекает под него: так тёплый воздух заполняет купол шара сверху вниз
                val tj2 = type[j].toInt()
                val tc = temp[i]
                // быстрая проверка: тёплый ли воздух рядом с более холодным (в спокойной комнате — нет)
                val warmer = (x > 0 && tc > temp[i - 1] + 1f) || (x + 1 < ww && tc > temp[i + 1] + 1f)
                if (warmer && (Mat.isCondensed(tj2) || (tj2 == Mat.AIR && temp[j] >= tc - 0.3f))) {
                    // гравитационное течение быстрое (~√(g·Δρ/ρ·h) ≈ 1 м/с): шаг до 3 клеток
                    val d = if (w.rnd() < 0.5f) -1 else 1
                    var k = i
                    var r = 1 + w.rndInt(3)
                    var xx = x
                    while (r > 0) {
                        xx += d
                        if (xx !in 0 until ww || type[k + d].toInt() != Mat.AIR) break
                        k += d; r--
                    }
                    if (k != i) {
                        val ti = temp[i]; val tk = temp[k]
                        if (ti > tk + 1f && w.rnd() < (ti - tk) / (ti + Units.KELVIN) * 8f) { temp[i] = tk; temp[k] = ti }
                    }
                }
                // восходящий поток подмешивает окружающий воздух (вовлечение): столб над огнём расширяется и остывает
                if (x + 1 < ww && type[i + 1].toInt() == Mat.AIR) {
                    val d = temp[i + 1] - temp[i]
                    if (abs(d) > 2f) { val m = d * MIX; temp[i] += m; temp[i + 1] -= m }
                    else if (abs(d) > 0.3f && w.rnd() < 0.08f) { val t = temp[i]; temp[i] = temp[i + 1]; temp[i + 1] = t }
                }
            }
        }
    }

    /** Стены комнаты держат 20 °C: клетки у края медленно отдают или получают тепло. */
    private fun walls() {
        val ww = w.w
        val hh = w.h
        val g = 10f * Units.AREA * Units.DT
        for (x in 0 until ww) {
            edge(x, g)
            edge((hh - 1) * ww + x, g)
        }
        for (y in 1 until hh - 1) {
            edge(y * ww, g)
            edge(y * ww + ww - 1, g)
        }
    }

    private fun edge(i: Int, g: Float) {
        val dT = Units.AMBIENT - w.temp[i]
        if (abs(dT) < 0.05f) return
        var e = g * dT
        val eMax = 0.3f * dT * cap[i]
        if (abs(e) > abs(eMax)) e = eMax
        w.temp[i] += e / cap[i]
    }

    /**
     * Плавление, кипение, замерзание, конденсация. Пока накапливается теплота перехода,
     * температура стоит на точке перехода — как у кипящей воды.
     */
    private fun transitions() {
        val type = w.type
        val temp = w.temp
        val lat = w.latent
        for (i in 0 until n) {
            val id = type[i].toInt()
            if (id == Mat.AIR) continue
            val t = temp[i]
            val c = Mat.heatCap[id]
            val hot = Mat.hotTo[id]
            val cold = Mat.coldTo[id]
            // точки кипения и конденсации зависят от давления
            val hotAt = if (hot >= 0 && Mat.boilShift[id] != 0f && Mat.isLiquid(id)) air.boilPoint(id, Mat.hotAt[id], i) else Mat.hotAt[id]
            val coldAt = if (cold >= 0 && Mat.boilShift[id] != 0f && Mat.isGas(id)) air.boilPoint(id, Mat.coldAt[id], i) else Mat.coldAt[id]
            if (hot >= 0 && t > hotAt) {
                val ha = hotAt
                lat[i] += (t - ha) * c
                temp[i] = ha
                if (lat[i] >= Mat.hotLatent[id]) {
                    val rest = lat[i] - Mat.hotLatent[id]
                    convert(i, id, hot)
                    temp[i] = ha + rest / Mat.heatCap[hot]
                }
            } else if (cold >= 0 && t < coldAt) {
                val ca = coldAt
                lat[i] -= (ca - t) * c
                temp[i] = ca
                if (-lat[i] >= Mat.coldLatent[id]) {
                    val rest = -lat[i] - Mat.coldLatent[id]
                    convert(i, id, cold)
                    temp[i] = ca - rest / Mat.heatCap[cold]
                }
            } else if (lat[i] != 0f) {
                // переход не завершился — тепло возвращается
                if (lat[i] > 0f && hot >= 0 && t < hotAt) {
                    val give = minOf(lat[i], (hotAt - t) * c)
                    lat[i] -= give; temp[i] = t + give / c
                } else if (lat[i] < 0f && cold >= 0 && t > coldAt) {
                    val take = minOf(-lat[i], (t - coldAt) * c)
                    lat[i] += take; temp[i] = t - take / c
                } else if (hot < 0 && cold < 0) {
                    lat[i] = 0f
                }
            }
        }
    }

    /** Превращение с учётом примесей: растворённое вещество выпадает, жидкость из пор уходит. */
    private fun convert(i: Int, from: Int, to: Int) {
        val solute = w.mixId[i].toInt()
        if (Mat.isLiquid(from) && solute != 0 && !Mat.isLiquid(to)) {
            // вода выкипает или замерзает — соль/сахар остаются
            val solMass = w.mix[i] * 1e-3f * w.amt[i]
            w.mixId[i] = 0; w.mix[i] = 0f
            if (!giveSolute(i, from, solute, solMass)) {
                // некуда отдать — клетка становится кристаллом, а пар/лёд выходит в соседнюю пустую клетку
                val t = w.temp[i]
                val gasAmt = w.convertAmount(from, to, w.amt[i], t)
                w.put(i, solute, t)
                w.amt[i] = minOf(1f, solMass / (Mat.density[solute] * Units.VOL))
                val e = Neighbors.randomAir(w, i)
                if (e >= 0) { w.put(e, to, t); w.amt[e] = gasAmt }
                return
            }
        }
        if (Mat.isPowder(from) && w.mixId[i].toInt() != 0) {
            // порошок плавится — жидкость из пор выходит наружу
            w.mixId[i] = 0; w.mix[i] = 0f
        }
        w.transform(i, to)
    }

    /** Отдать растворённое вещество соседней клетке того же растворителя. */
    private fun giveSolute(i: Int, solvent: Int, solute: Int, mass: Float): Boolean {
        val j = Neighbors.randomOfType(w, i, solvent)
        if (j < 0) return mass < 1e-7f
        val mj = w.mixId[j].toInt()
        if (mj != 0 && mj != solute) return false
        w.mixId[j] = solute.toShort()
        w.mix[j] += mass / (1e-3f * w.amt[j])
        return true
    }

    companion object {
        /** Доля выравнивания температуры соседних клеток воздуха за шаг (турбулентное перемешивание). */
        const val MIX = 0.06f
        fun harmonic(a: Float, b: Float): Float = if (a + b <= 0f) 0f else 2f * a * b / (a + b)

        /** Во сколько раз быстрее идут химические процессы при нагреве (удвоение на 10 °C). */
        fun chemFactor(t: Float): Float = when {
            t <= 0f -> 0f
            t >= 80f -> 64f
            else -> exp(0.0693f * (t - Units.AMBIENT)).toFloat()
        }

        /** Рост скорости испарения с температурой (давление пара). */
        fun vaporFactor(t: Float): Float = when {
            t <= -20f -> 0.05f
            t >= 95f -> 95f
            else -> exp(0.0607f * (t - Units.AMBIENT)).toFloat()
        }
    }
}
