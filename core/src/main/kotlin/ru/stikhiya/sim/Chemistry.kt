package ru.stikhiya.sim

/**
 * Смеси и реакции: растворение, впитывание в поры, стекание и капли, испарение,
 * застывание клея, схватывание цемента и гипса, кристаллизация, горение, обугливание.
 *
 * Медленные процессы (сушка влажных порошков, застывание клея, схватывание цемента и гипса)
 * ускоряются множителем [chemSpeed].
 * Быстрые (растворение, впитывание, горение) идут в реальном времени.
 */
class Chemistry(private val w: World, private val air: Air) {
    var chemSpeed = 600f

    private val burning = 2 // бит в flags: клетка горит

    fun step() {
        val type = w.type
        val n = w.n
        // обходим с чередованием направления, чтобы не было перекоса
        val start = if ((w.rndInt(2)) == 0) 0 else n - 1
        val dir = if (start == 0) 1 else -1
        var i = start
        for (c in 0 until n) {
            val id = type[i].toInt()
            if (id != Mat.AIR) {
                when (Mat.phase[id]) {
                    Phase.POWDER.ordinal -> powder(i, id)
                    Phase.LIQUID.ordinal -> liquid(i, id)
                    Phase.SOLID.ordinal -> solid(i, id)
                    Phase.GAS.ordinal -> gas(i, id)
                    Phase.FLAME.ordinal -> flame(i)
                }
            }
            i += dir
        }
    }

    // ================= Порошки =================

    private fun powder(i: Int, id: Int) {
        // контакт с жидкостями: растворение или впитывание
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            val t = w.type[j].toInt()
            if (!Mat.isLiquid(t)) continue
            if (Mat.dissolves(id, t)) {
                if (dissolve(i, id, j, t)) return
            } else if (Mat.porosity[id] > 0f && Mat.wets[t]) {
                absorb(i, id, j, t)
            }
        }
        if (w.mixId[i].toInt() != 0) wetPowder(i, id)
        if (w.type[i].toInt() != id) return
        if (Mat.flammable[id]) combustion(i, id)
        if (w.type[i].toInt() == id && !Mat.charAt[id].isNaN()) charring(i, id)
    }

    /** Зерно растворяется в соседней жидкости до насыщения. true — зерно растворилось целиком. */
    private fun dissolve(i: Int, id: Int, j: Int, solvent: Int): Boolean {
        val mj = w.mixId[j].toInt()
        if (mj != 0 && mj != id) return false
        val sat = Mat.saturation(id, solvent, w.temp[j])
        val c = w.mix[j]
        if (c >= sat) return false
        val tau = Mat.solTime[id * Mat.count + solvent]
        val rate = minOf(1f, Units.DT / tau * Heat.chemFactor(maxOf(1f, w.temp[j])).coerceAtMost(8f))
        var dc = (sat - c) * rate
        val volL = 1e-3f * w.amt[j]
        var dm = dc * volL
        val grainMass = Mat.density[id] * Units.VOL * w.amt[i]
        if (dm > grainMass) { dm = grainMass; dc = dm / volL }
        w.mix[j] = c + dc
        w.mixId[j] = id.toShort()
        w.amt[i] -= dm / (Mat.density[id] * Units.VOL)
        if (w.amt[i] < 0.02f) {
            // остаток досыпаем в раствор
            val rest = Mat.density[id] * Units.VOL * maxOf(0f, w.amt[i])
            w.mix[j] += rest / volL
            w.toAir(i)
            return true
        }
        return false
    }

    /** Пористый порошок впитывает жидкость (песок — воду, опилки — клей, цемент — воду). */
    private fun absorb(i: Int, id: Int, j: Int, liq: Int) {
        val cur = w.mixId[i].toInt()
        if (cur != 0 && cur != liq) return
        val por = Mat.porosity[id] * w.amt[i]
        val room = por - w.mix[i]
        if (room <= 0.002f) return
        // капиллярное впитывание быстрое: за несколько тиков
        val take = minOf(room, w.amt[j], 0.12f * por * (0.3f + Mat.permeability[id]))
        if (take <= 0f) return
        // тепло жидкости приходит вместе с ней
        val ci = w.heatCapacity(i)
        val cl = take * Units.VOL * Mat.density[liq] * Mat.heatCap[liq]
        w.temp[i] = (w.temp[i] * ci + w.temp[j] * cl) / (ci + cl)
        w.mix[i] += take
        w.mixId[i] = liq.toShort()
        removeLiquid(j, take)
    }

    /** Убрать часть жидкости из клетки; растворённое остаётся в оставшейся части. */
    private fun removeLiquid(j: Int, vol: Float) {
        val a = w.amt[j]
        val left = a - vol
        val solute = w.mixId[j].toInt()
        if (left < 0.003f) {
            if (solute != 0) {
                val solMass = w.mix[j] * 1e-3f * a
                val t = w.temp[j]
                w.put(j, solute, t)
                w.amt[j] = minOf(1f, solMass / (Mat.density[solute] * Units.VOL))
                if (w.amt[j] < 0.01f) w.toAir(j)
            } else {
                w.toAir(j)
            }
            return
        }
        if (solute != 0) w.mix[j] = w.mix[j] * a / left
        w.amt[j] = left
    }

    private fun wetPowder(i: Int, id: Int) {
        val liq = w.mixId[i].toInt()
        val por = Mat.porosity[id] * w.amt[i]
        if (por <= 0f) { w.mixId[i] = 0; w.mix[i] = 0f; return }
        val fill = w.mix[i] / por

        // --- стекание вниз и капли ---
        val below = Neighbors.at(w, i, 1)
        if (below >= 0) {
            val bt = w.type[below].toInt()
            if (bt == Mat.AIR || Mat.isGas(bt)) {
                val fc = Mat.fieldCap[id]
                if (fill > fc && bt == Mat.AIR) {
                    val excess = (fill - fc) * por
                    if (excess > 0.03f) {
                        val drop = minOf(excess, 1f)
                        w.put(below, liq, w.temp[i])
                        w.amt[below] = drop
                        w.mix[i] -= drop
                    }
                }
            } else if (Mat.isPowder(bt) && Mat.porosity[bt] > 0f) {
                transferPores(i, id, below, bt, gravity = true)
            }
        }
        // --- капиллярный подсос в стороны и вверх ---
        val d = 2 + w.rndInt(2) // влево или вправо
        val side = Neighbors.at(w, i, if (w.rnd() < 0.25f) 0 else d)
        if (side >= 0) {
            val st = w.type[side].toInt()
            if (Mat.isPowder(st) && Mat.porosity[st] > 0f) transferPores(i, id, side, st, gravity = false)
        }
        if (w.mix[i] <= 1e-5f) { w.mixId[i] = 0; w.mix[i] = 0f; w.cure[i] = 0f; return }

        val t = w.temp[i]
        // --- кипение жидкости в порах ---
        val boilTo = Mat.hotTo[liq]
        if (boilTo >= 0 && Mat.isGas(boilTo) && t > Mat.hotAt[liq]) {
            boilPores(i, liq, boilTo)
            if (w.mix[i] <= 1e-5f) { w.mixId[i] = 0; w.mix[i] = 0f; return }
        }

        val air = Neighbors.countAir(w, i)
        // --- испарение с поверхности ---
        val vol = Mat.volatility[liq]
        if (vol > 0f && air > 0) {
            val dv = minOf(w.mix[i], vol * Heat.vaporFactor(t) * chemSpeed * Units.DT * air * 0.8f)
            evaporationCooling(i, liq, dv)
            w.mix[i] -= dv
            if (w.mix[i] <= 1e-5f) { w.mixId[i] = 0; w.mix[i] = 0f; w.cure[i] = 0f; return }
        }

        val curFill = w.mix[i] / por
        // --- клей в порах застывает ---
        if (Mat.isBinder[liq]) {
            val exposure = when {
                air > 0 -> 1f
                !Mat.cureNeedsAir[liq] -> 1f
                Neighbors.hasType(w, i, Mat.gluedOf[id]) -> 0.35f
                else -> 0.12f
            }
            w.cure[i] += Units.DT * chemSpeed / Mat.cureTime[liq] * Heat.chemFactor(t) * exposure
            if (w.cure[i] >= 1f) {
                val g = Mat.gluedOf[id]
                if (curFill >= 0.3f && g >= 0) {
                    w.mixId[i] = 0; w.mix[i] = 0f
                    w.transform(i, g)
                    w.amt[i] = 1f
                } else {
                    // клея слишком мало — не склеивает, просто высыхает
                    w.mixId[i] = 0; w.mix[i] = 0f; w.cure[i] = 0f
                }
            }
            return
        }
        // --- цемент и гипс схватываются с водой ---
        if (!Mat.setTime[id].isNaN() && liq == Mat.WATER && curFill >= 0.45f) {
            val dp = Units.DT * chemSpeed / Mat.setTime[id] * Heat.chemFactor(t)
            if (dp > 0f) {
                w.cure[i] += dp
                val mass = Mat.density[id] * Units.VOL * w.amt[i]
                w.temp[i] += Mat.setHeat[id] * mass * dp / w.heatCapacity(i)
                if (w.cure[i] >= 1f) {
                    w.mixId[i] = 0; w.mix[i] = 0f
                    w.transform(i, Mat.setTo[id])
                    w.amt[i] = 1f
                }
            }
        }
    }

    /** Испарение забирает теплоту парообразования у клетки. */
    private fun evaporationCooling(i: Int, liq: Int, dv: Float) {
        val gas = Mat.hotTo[liq]
        if (gas < 0 || !Mat.isGas(gas) || dv <= 0f) return
        val e = dv * Units.VOL * Mat.density[liq] * Mat.hotLatent[liq]
        w.temp[i] -= e / w.heatCapacity(i)
    }

    /** Переток жидкости между порами соседних клеток. */
    private fun transferPores(i: Int, id: Int, j: Int, jt: Int, gravity: Boolean) {
        val liq = w.mixId[i].toInt()
        val mj = w.mixId[j].toInt()
        if (mj != 0 && mj != liq) return
        val pi = Mat.porosity[id] * w.amt[i]
        val pj = Mat.porosity[jt] * w.amt[j]
        if (pj <= 0f) return
        val fi = w.mix[i] / pi
        val fj = w.mix[j] / pj
        val perm = minOf(Mat.permeability[id], Mat.permeability[jt])
        var v = 0f
        if (gravity) {
            // сверху вниз: стекает всё, что выше удерживаемого капиллярами, плюс выравнивание
            val excess = maxOf(0f, fi - Mat.fieldCap[id])
            v = (excess * 0.5f + maxOf(0f, fi - fj) * 0.15f) * pi * perm
        } else if (fi > fj) {
            v = (fi - fj) * 0.04f * pi * perm
        }
        if (v <= 1e-6f) return
        v = minOf(v, w.mix[i], pj - w.mix[j])
        if (v <= 0f) return
        w.mix[i] -= v
        w.mix[j] += v
        w.mixId[j] = liq.toShort()
    }

    /** Жидкость в порах выкипает, забирая тепло; пар выходит в соседнюю пустую клетку. */
    private fun boilPores(i: Int, liq: Int, gas: Int) {
        val boil = Mat.hotAt[liq]
        val ci = w.heatCapacity(i)
        val energy = (w.temp[i] - boil) * ci
        val liqMass = w.mix[i] * Units.VOL * Mat.density[liq]
        val m = minOf(liqMass, energy / Mat.hotLatent[liq])
        if (m <= 0f) return
        w.mix[i] -= m / (Units.VOL * Mat.density[liq])
        w.temp[i] = boil + (energy - m * Mat.hotLatent[liq]) / ci
        val e = Neighbors.randomAir(w, i, preferUp = true)
        if (e >= 0) {
            w.put(e, gas, boil)
            w.amt[e] = m / (Units.gasDensity(Mat.molar[gas], boil) * Units.VOL)
        }
    }

    // ================= Жидкости =================

    private fun liquid(i: Int, id: Int) {
        if (w.mixId[i].toInt() != 0) solution(i, id)
        if (w.type[i].toInt() != id) return
        // клей застывает сам
        if (Mat.isBinder[id]) {
            val air = Neighbors.countAir(w, i)
            val exposure = when {
                air > 0 -> 1f
                !Mat.cureNeedsAir[id] -> 1f
                Neighbors.hasType(w, i, Mat.curedTo[id]) -> 0.3f
                else -> 0.08f
            }
            w.cure[i] += Units.DT * chemSpeed / Mat.cureTime[id] * Heat.chemFactor(w.temp[i]) * exposure
            if (w.cure[i] >= 1f) { w.transform(i, Mat.curedTo[id]); w.amt[i] = 1f; return }
        }
        // испарение открытой поверхности
        val vol = Mat.volatility[id]
        if (vol > 0f) {
            val air = Neighbors.countAir(w, i)
            if (air > 0) {
                // открытая поверхность испаряется в реальном времени (лужа сохнет сутки);
                // мелкие капли тумана — быстро, у них огромная поверхность на объём
                val tiny = if (w.amt[i] < 0.02f) 300f else 1f
                val dv = minOf(w.amt[i], vol * Heat.vaporFactor(w.temp[i]) * tiny * Units.DT * air)
                evaporationCooling(i, id, dv)
                removeLiquid(i, dv)
                if (w.type[i].toInt() != id) return
            }
        }
        if (Mat.flammable[id]) combustion(i, id)
    }

    /** Раствор: вещество расходится по соседним клеткам, при пересыщении выпадает кристаллами. */
    private fun solution(i: Int, id: Int) {
        val solute = w.mixId[i].toInt()
        val j = Neighbors.at(w, i, w.rndInt(4))
        if (j >= 0 && w.type[j].toInt() == id) {
            val mj = w.mixId[j].toInt()
            if (mj == 0 || mj == solute) {
                val ai = w.amt[i]
                val aj = w.amt[j]
                val dc = (w.mix[i] - w.mix[j]) * 0.06f
                if (dc != 0f) {
                    val dm = dc * minOf(ai, aj)
                    w.mix[i] -= dm / ai
                    w.mix[j] += dm / aj
                    if (w.mix[j] > 1e-6f) w.mixId[j] = solute.toShort()
                }
            }
        }
        if (w.mix[i] < 1e-6f) { w.mixId[i] = 0; w.mix[i] = 0f; return }

        val sat = Mat.saturation(solute, id, w.temp[i])
        if (sat > 0f && w.mix[i] > sat * 1.02f) {
            val excessMass = (w.mix[i] - sat) * 1e-3f * w.amt[i]
            val grain = Mat.density[solute] * Units.VOL
            // кристалл растёт на соседнем зерне
            val k = Neighbors.randomOfType(w, i, solute)
            if (k >= 0 && w.amt[k] < 1f) {
                val add = minOf(excessMass / grain, 1f - w.amt[k])
                w.amt[k] += add
                w.mix[i] -= add * grain / (1e-3f * w.amt[i])
                return
            }
            // у поверхности появляется корочка
            if (w.rnd() < 0.05f) {
                val e = Neighbors.randomAir(w, i, preferUp = true)
                if (e >= 0) {
                    val a = minOf(1f, excessMass / grain)
                    w.put(e, solute, w.temp[i])
                    w.amt[e] = a
                    w.mix[i] -= a * grain / (1e-3f * w.amt[i])
                }
            }
        }
    }

    // ================= Твёрдые =================

    private val burnerId = Mat.id("burner")
    private val propaneId = Mat.id("propane")

    /** Горелка: пропан из баллона сгорает над соплом, если вокруг есть кислород. */
    private fun burner(i: Int) {
        val up = Neighbors.at(w, i, 0)
        if (up < 0) return
        // автоматика: термодатчик под куполом (у ближайшего потолка над соплом, как у настоящего шара);
        // выше 170 °C горелка притухает. Под открытым небом горит всегда.
        if (w.rnd() < 0.05f) {
            var j = up
            var k = 0
            while (j >= 0 && k < 190 && !Mat.isCondensed(w.type[j].toInt())) { j -= w.w; k++ }
            var hot = false
            if (j >= 0 && k < 190 && k > 4) {
                var sum = 0f; var n = 0
                var q = j + w.w
                for (m in 0 until 6) { if (q < w.n && w.type[q].toInt() == Mat.AIR) { sum += w.temp[q]; n++ }; q += w.w }
                hot = n > 0 && sum / n > THERMOSTAT
            }
            w.cure[i] = if (hot) 1f else 0f
        }
        if (w.cure[i] > 0.5f || w.rnd() > 0.6f) return
        val t = w.type[up].toInt()
        if (!Mat.isGas(t)) return
        // над соплом другой газ: горючий поджигаем, кислород раздувает пламя
        if (t != Mat.AIR && t != Mat.FLAME) {
            if (Mat.flammable[t]) w.temp[up] = maxOf(w.temp[up], Mat.autoIgnition[t] + 10f)
            else if (t == Mat.OXYGEN) { w.put(up, Mat.FLAME, Mat.flameTemp[propaneId] * 1.15f); w.amt[up] = 3f; w.mixId[up] = propaneId.toShort() }
            return
        }
        if (air.oxygen(up) < Air.O2_FLAME_MIN) return
        // ~3,3 мг пропана в секунду на клетку сопла ≈ 150 Вт
        val fuel = 3.3e-6f * Units.DT / 0.6f
        air.consumeOxygen(up, fuel * Mat.oxygenNeed[propaneId])
        val e = fuel * Mat.burnHeat[propaneId] * 0.9f
        val ft = Mat.flameTemp[propaneId]
        if (t == Mat.FLAME) { w.amt[up] = minOf(40f, w.amt[up] + flameAmount(e, ft, Units.AMBIENT)); w.temp[up] = maxOf(w.temp[up], ft) }
        else { w.put(up, Mat.FLAME, ft); w.amt[up] = flameAmount(e, ft, w.temp[up]) }
        w.mixId[up] = propaneId.toShort()
    }

    private fun solid(i: Int, id: Int) {
        if (id == burnerId) { burner(i); return }
        if (Mat.flammable[id]) combustion(i, id)
        if (w.type[i].toInt() == id && !Mat.charAt[id].isNaN()) charring(i, id)
    }

    // ================= Газы и пламя =================

    private fun gas(i: Int, id: Int) {
        if (!Mat.lifetime[id].isNaN()) {
            w.life[i] -= Units.DT
            if (w.life[i] <= 0f) { w.toAir(i); return }
        }
        if (Mat.flammable[id]) {
            val t = w.temp[i]
            if (t >= Mat.autoIgnition[id] || (t >= Mat.flashPoint[id] && Neighbors.hasType(w, i, Mat.FLAME))) {
                // горючему газу нужен кислород: из воздуха вокруг или из соседнего чистого кислорода
                val ox = Neighbors.randomOfType(w, i, Mat.OXYGEN)
                if (ox < 0 && air.oxygen(i) < Air.O2_FLAME_MIN) return
                // горючий газ сгорает почти мгновенно
                val mass = w.cellMass(i)
                val need = mass * Mat.oxygenNeed[id]
                if (ox >= 0) {
                    w.amt[ox] -= need / maxOf(w.massDensity(Mat.OXYGEN, w.temp[ox]) * Units.VOL, 1e-9f)
                    if (w.amt[ox] < 0.05f) w.toAir(ox)
                } else air.consumeOxygen(i, need)
                val e = Mat.burnHeat[id] * mass * 0.8f
                val ft = if (ox >= 0) Mat.flameTemp[id] * 1.15f else Mat.flameTemp[id]
                val a0 = w.amt[i]
                w.transform(i, Mat.FLAME)
                w.temp[i] = ft
                w.amt[i] = flameAmount(e, ft, t)
                w.mixId[i] = id.toShort() // пламя помнит топливо — по нему цвет
                // водород, метан, пары бензина с воздухом — взрыв: горячие газы распирают воздух
                val gy = Mat.gasYield[id]
                if (gy > 0f) air.addPressure(i, gy * 2f)
                // продукты горения горячее в разы — расширяются; в трубе с соплом это реактивная струя
                val dv = a0 * ((ft + Units.KELVIN) / (t + Units.KELVIN) - 1f)
                air.addPressure(i, dv / (air.cs * air.cs) * 101.325f / Air.SRC_GAIN)
            }
        }
    }

    private fun flame(i: Int) {
        // соли металлов окрашивают пламя: натрий — жёлтым, стронций — красным, медь — зелёным…
        if (w.rnd() < 0.25f) {
            val j = Neighbors.at(w, i, w.rndInt(4))
            if (j >= 0) {
                val t = w.type[j].toInt()
                val c = if (Mat.has(t, Tag.COLORANT) && Mat.flameColor[t] != 0) t
                    else w.mixId[j].toInt().let { m -> if (m != 0 && Mat.isLiquid(t) && Mat.has(m, Tag.COLORANT)) m else 0 }
                if (c != 0) w.mixId[i] = c.toShort()
            }
        }
        // в быстрой струе (сопло) догорающий газ уносится далеко: факел вытягивается
        val v = kotlin.math.abs(air.windX(i)) + kotlin.math.abs(air.windY(i))
        w.life[i] -= if (v > 1.5f) Units.DT * (1.5f / v) else Units.DT
        if (w.life[i] <= 0f || w.temp[i] < 450f) w.toAir(i)
    }

    /** Сколько «объёмов» горячего газа нужно, чтобы унести энергию e при температуре пламени. */
    private fun flameAmount(e: Float, flameT: Float, ambient: Float): Float {
        val perAmt = Units.gasDensity(Mat.molar[Mat.FLAME], flameT) * Units.VOL * Mat.heatCap[Mat.FLAME]
        val dT = maxOf(100f, flameT - ambient)
        return (e / (perAmt * dT)).coerceIn(0.5f, 40f)
    }

    // ================= Горение =================

    private fun combustion(i: Int, id: Int) {
        val f = w.flags[i].toInt()
        val t = w.temp[i]
        val isBurning = (f and burning) != 0
        // порох несёт окислитель в себе и горит даже засыпанным
        val selfOx = Mat.oxygenNeed[id] <= 0f
        // кислород приходит из соседней клетки с воздухом, а не из самого топлива
        val src = if (selfOx) i else breathableNeighbor(i)
        val contact = selfOx || src >= 0
        val o2 = if (selfOx) Air.O2_NORMAL else if (src >= 0) air.oxygen(src) else 0f
        if (!isBurning) {
            if (!contact || o2 < Air.O2_FLAME_MIN + 0.01f) return
            // мокрое не загорается: вода в порах забирает тепло (сырой порох, сырые опилки)
            val liq = w.mixId[i].toInt()
            if (Mat.isPowder(id) && liq != 0 && !Mat.flammable[liq] &&
                w.mix[i] > 0.3f * Mat.porosity[id] * w.amt[i]) return
            // поверхность мелких частиц вспыхивает от пламени раньше, чем прогреется вся клетка
            val hot = hottestFire(i)
            val surfaceT = if (hot > t) t + Mat.fineness[id] * (hot - t) else t
            val ignite = t >= Mat.autoIgnition[id] || (hot > 0f && surfaceT >= Mat.flashPoint[id])
            if (ignite) w.flags[i] = (f or burning).toByte()
            return
        }
        // задохнулось: кончился кислород, засыпали, залили, углекислый газ
        // засыпали, залили или задохнулось — гаснет; снова вспыхнет, только если раскалено и есть воздух
        if (!contact || o2 < Air.O2_FLAME_MIN) { w.flags[i] = (f and burning.inv()).toByte(); return }

        // горит поверхность: чем больше граней открыто воздуху и чем больше кислорода, тем быстрее
        val faces = if (selfOx) 2 else oxygenFaces(i)
        val rate = if (selfOx) 1f else (o2 / Air.O2_NORMAL).coerceIn(0.3f, 1.2f)
        var dp = Units.DT / Mat.burnTime[id] * faces * rate
        // облако тонкой пыли (мука, крахмал, пудра) сгорает почти мгновенно — пылевой взрыв
        val dust = Mat.isPowder(id) && Mat.fineness[id] >= 0.85f && faces >= 3
        if (dust) dp *= 60f
        val mass = Mat.density[id] * Units.VOL * w.amt[i]
        val burned = mass * minOf(dp, maxOf(0f, 1f - w.burn[i]) + 1e-6f)
        val e = Mat.burnHeat[id] * burned
        w.burn[i] += dp
        // кислород уходит из воздуха; продукты горения уносит и разбавляет поток над огнём
        if (!selfOx) air.consumeOxygen(src, burned * Mat.oxygenNeed[id])
        val gy = Mat.gasYield[id]
        if (gy > 0f) {
            // газы взрыва распирают воздух: объём при температуре пламени → давление
            val tf = Mat.flameTemp[id] + Units.KELVIN
            val gasVol = gy * burned * tf / Units.KELVIN
            air.addPressure(i, gasVol / (air.cs * air.cs * Units.VOL) * 101.325f * EXPLOSION_SCALE)
        }
        if (dust) air.addPressure(i, e * 0.004f)
        // угли и дерево сами раскалены; жидкость получает лишь малую долю тепла пламени
        // термит горит без газа — почти всё тепло остаётся в расплаве
        val selfShare = if (Mat.isLiquid(id)) 0.02f else if (selfOx && gy <= 0f) 0.85f else 0.3f
        // угли раскалены, но не горячее собственного пламени — лишнее уходит в пламя
        w.temp[i] = minOf(w.temp[i] + selfShare * e / w.heatCapacity(i), maxOf(w.temp[i], Mat.flameTemp[id]))

        // пламя над горящей клеткой уносит остальное тепло
        val up = Neighbors.randomAir(w, i, preferUp = true)
        val ft = Mat.flameTemp[id]
        if (up >= 0) {
            val ambient = w.temp[up]
            w.put(up, Mat.FLAME, ft)
            w.amt[up] = flameAmount((1f - selfShare) * e, ft, ambient)
            w.mixId[up] = id.toShort()
        } else {
            val fl = Neighbors.randomOfType(w, i, Mat.FLAME)
            if (fl >= 0) w.amt[fl] = minOf(40f, w.amt[fl] + flameAmount((1f - selfShare) * e, ft, Units.AMBIENT))
        }
        if (w.rnd() < Mat.smoke[id]) {
            val s = Neighbors.randomAir(w, i, preferUp = true)
            if (s >= 0) w.put(s, Mat.SMOKE, ft * 0.6f)
        }
        // погасло, если остыло (залили, засыпали холодным); мелкие частицы держит собственное пламя
        val tNow = w.temp[i]
        val fire = hottestFire(i)
        val effT = if (fire > tNow) tNow + Mat.fineness[id] * (fire - tNow) else tNow
        if (effT < Mat.flashPoint[id] - 60f) w.flags[i] = (w.flags[i].toInt() and burning.inv()).toByte()
        if (w.burn[i] >= 1f) {
            val res = Mat.burnTo[id]
            val tt = w.temp[i]
            if (res >= 0 && w.rnd() < Mat.burnResidue[id]) {
                w.put(i, res, tt)
            } else {
                w.toAir(i)
            }
        }
    }

    private fun oxygenFaces(i: Int): Int {
        var c = 0
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            if (Neighbors.breathable(w.type[j].toInt())) c++
        }
        return maxOf(c, 1)
    }

    /** Соседняя клетка, через которую поступает воздух (−1 — нет доступа). */
    private fun breathableNeighbor(i: Int): Int {
        val s = w.rndInt(4)
        for (k in 0 until 4) {
            val j = Neighbors.at(w, i, (s + k) and 3)
            if (j >= 0 && Neighbors.breathable(w.type[j].toInt())) return j
        }
        return -1
    }

    /** Температура самого горячего пламени или горящей клетки рядом; 0 — огня рядом нет. */
    private fun hottestFire(i: Int): Float {
        var best = 0f
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            val tj = w.type[j].toInt()
            // язык пламени горяч, даже если клетка уже отдала тепло соседям
            val t = if (tj == Mat.FLAME) maxOf(w.temp[j], 900f)
                else if ((w.flags[j].toInt() and burning) != 0 && Mat.flammable[tj]) w.temp[j]
                // раскалённое (жидкий металл, лава, угли) поджигает то, чего касается
                else if (w.temp[j] > 500f && Mat.isCondensed(tj)) w.temp[j] else 0f
            if (t > best) best = t
        }
        return best
    }

    /** Без доступа воздуха горячее дерево и опилки превращаются в уголь. */
    private fun charring(i: Int, id: Int) {
        if (w.temp[i] < Mat.charAt[id]) return
        // в задохнувшейся печи воздух рядом есть, но кислорода в нём мало — дрова томятся в уголь
        if (Neighbors.hasOxygen(w, i) && air.oxygen(i) >= Air.O2_FLAME_MIN) return
        w.cure[i] += Units.DT / 20f * (1f + (w.temp[i] - Mat.charAt[id]) / 100f)
        if (w.cure[i] >= 1f) {
            w.transform(i, Mat.charTo[id])
            w.amt[i] = 1f
            w.flags[i] = (w.flags[i].toInt() and burning.inv()).toByte()
        }
    }

    fun isBurning(i: Int) = (w.flags[i].toInt() and burning) != 0

    companion object {
        /** Игровой масштаб давления взрыва (звук в игре медленнее настоящего). */
        const val EXPLOSION_SCALE = 0.02f
        /** Горелка держит воздух над собой не горячее этого, °C. */
        const val THERMOSTAT = 170f
    }
}
