package ru.stikhiya.sim

/**
 * Химические реакции между соседними клетками (кислоты, щёлочи, сода, металлы, известь, брожение…),
 * распад нестойкого (пена), а также «Кран» и «Сток».
 *
 * Скорость реакции — вероятность в секунду для пары соседей; медленные реакции (ржавление, брожение)
 * ускоряются множителем химии, как сушка и застывание.
 */
class Reactions(private val w: World, private val air: Air) {
    var chemSpeed = 600f

    class R(
        val a: String, val b: String?,
        val rate: Float,                 // событий в секунду на контакт при 20 °C
        val toA: String? = null,         // во что превращается a (null — не меняется, "air" — исчезает)
        val toB: String? = null,
        val probB: Float = 1f,           // вероятность, что b тоже превратится
        val partA: Float = 1f,           // какая доля a расходуется за событие
        val heat: Float = 0f,            // Дж за событие (+ выделяется, − поглощается)
        val gas: String? = null,         // газ, выходящий рядом
        val gasAmt: Float = 1f,          // его объём (сжатый газ расширяется сам)
        val extra: String? = null,       // ещё один продукт рядом (пена, пары)
        val extraProb: Float = 0f,
        val flame: Float = 0f,           // вероятность вспышки
        val minT: Float = -300f,
        val maxT: Float = 1e5f,
        val slow: Boolean = false,       // ускоряется «химией»
        val burningA: Boolean = false,   // только если a горит
        val soluteB: String? = null,     // в b должно быть растворено это вещество
        val warm: Boolean = true         // ускоряется при нагреве
    )

    private val acidsStrong = listOf("hcl", "dilute_h2so4")

    private val manual: List<R> = buildList {
        // ---- газ CO₂ из карбонатов ----
        add(R("soda", "vinegar", 1.2f, toA = "co2", toB = "water", probB = 0.5f, heat = -40f, gas = null,
            extra = "foam", extraProb = 0.7f))
        for (acid in acidsStrong) add(R("soda", acid, 3f, toA = "co2", toB = "water", probB = 0.5f, heat = 60f,
            extra = "foam", extraProb = 0.5f))
        add(R("soda", "h2so4", 3f, toA = "co2", toB = "dilute_h2so4", probB = 0.3f, heat = 200f))
        for (stone in listOf("chalk" to 0.8f, "limestone" to 0.25f, "marble" to 0.15f)) {
            add(R(stone.first, "vinegar", stone.second * 0.15f, toA = "air", partA = 0.2f, toB = "water", probB = 0.1f, gas = "co2"))
            for (acid in acidsStrong) add(R(stone.first, acid, stone.second, toA = "air", partA = 0.2f, toB = "water", probB = 0.2f,
                heat = 30f, gas = "co2", extra = "foam", extraProb = 0.2f))
        }
        // ---- металл + кислота → водород ----
        val metals = listOf("zinc" to 1.5f, "iron" to 0.12f, "steel" to 0.1f, "cast_iron" to 0.12f,
            "iron_filings" to 0.8f, "aluminum" to 0.25f, "al_powder" to 1.5f, "magnesium" to 1.5f, "tin" to 0.05f,
            "molten_sodium" to 4f, "sodium" to 4f)
        for ((m, k) in metals) {
            add(R(m, "hcl", k, toA = "air", partA = 0.15f, toB = "water", probB = 0.15f, heat = 120f, gas = "hydrogen", gasAmt = 2f))
            add(R(m, "dilute_h2so4", k * 0.7f, toA = "air", partA = 0.15f, toB = "water", probB = 0.15f, heat = 120f,
                gas = "hydrogen", gasAmt = 2f))
        }
        add(R("magnesium", "vinegar", 0.2f, toA = "air", partA = 0.1f, toB = "water", probB = 0.1f, heat = 40f, gas = "hydrogen"))
        add(R("zinc", "vinegar", 0.01f, toA = "air", partA = 0.1f, gas = "hydrogen"))
        add(R("iron", "h2so4", 0.01f, toA = "air", partA = 0.1f, gas = "so2"))
        add(R("zinc", "h2so4", 0.1f, toA = "air", partA = 0.1f, gas = "so2", heat = 80f))
        add(R("rust", "hcl", 0.3f, toA = "air", partA = 0.25f, heat = 20f))
        // ---- щёлочь растворяет алюминий ----
        add(R("aluminum", "lye", 0.25f, toA = "air", partA = 0.12f, toB = "water", probB = 0.1f, heat = 150f, gas = "hydrogen", gasAmt = 2f))
        add(R("al_powder", "lye", 1.5f, toA = "air", partA = 0.2f, heat = 150f, gas = "hydrogen", gasAmt = 2f))
        add(R("zinc", "lye", 0.05f, toA = "air", partA = 0.1f, heat = 50f, gas = "hydrogen"))
        // ---- нейтрализация: кислота + щёлочь → солёная вода и тепло ----
        for (acid in listOf("hcl", "dilute_h2so4", "h2so4", "vinegar")) {
            val h = if (acid == "h2so4") 900f else if (acid == "vinegar") 120f else 400f
            add(R("lye", acid, 3f, toA = "water", toB = "water", heat = h))
            add(R("quicklime", acid, 2f, toA = "water", toB = "water", probB = 0.5f, heat = h))
            add(R("slaked_lime", acid, 2f, toA = "water", toB = "water", probB = 0.5f, heat = h * 0.7f))
        }
        // ---- концентрированная серная кислота ----
        add(R("h2so4", "water", 2f, toA = "dilute_h2so4", toB = "dilute_h2so4", probB = 0.5f, heat = 1100f))
        add(R("sugar", "h2so4", 1.5f, toA = "charcoal", toB = "dilute_h2so4", probB = 0.15f, heat = 900f, gas = "steam", gasAmt = 3f))
        for (org in listOf("paper", "cardboard", "wood", "sawdust", "flour", "starch", "bread", "wool"))
            add(R(org, "h2so4", 0.1f, toA = "charcoal", toB = "dilute_h2so4", probB = 0.1f, heat = 200f, gas = "steam"))
        // ---- натрий и вода ----
        for (na in listOf("sodium", "molten_sodium"))
            add(R(na, "water", 3f, toA = "air", partA = 0.12f, toB = "lye", probB = 0.15f, heat = 900f, gas = "hydrogen",
                gasAmt = 3f, flame = 0.12f))
        add(R("sodium", "chlorine", 0.6f, toA = "salt", toB = "air", heat = 3000f, flame = 0.4f))
        add(R("molten_sodium", "chlorine", 1.5f, toA = "salt", toB = "air", heat = 3000f, flame = 0.5f))
        // ---- горящий магний разлагает воду ----
        add(R("magnesium", "water", 3f, toB = "hydrogen", heat = 500f, flame = 0.3f, burningA = true))
        add(R("molten_magnesium", "water", 3f, toB = "hydrogen", heat = 500f, flame = 0.3f))
        // ---- известь ----
        add(R("quicklime", "water", 1.5f, toA = "slaked_lime", toB = "air", probB = 0.3f, heat = 1100f))
        add(R("quicklime", "seawater", 1.5f, toA = "slaked_lime", toB = "air", probB = 0.3f, heat = 1100f))
        // ---- ржавчина: железо + вода (+ воздух) ----
        add(R("iron", "water", 1f / 3e6f, toA = "rust", slow = true))
        add(R("steel", "water", 1f / 6e6f, toA = "rust", slow = true))
        add(R("cast_iron", "water", 1f / 4e6f, toA = "rust", slow = true))
        add(R("iron_filings", "water", 1f / 2e5f, toA = "rust", slow = true))
        add(R("iron", "salt", 1f / 1e6f, toA = "rust", slow = true))
        // ---- еда ----
        add(R("flour", "water", 1.2f, toA = "dough", toB = "air", probB = 0.6f))
        add(R("flour", "milk", 1.2f, toA = "dough", toB = "air", probB = 0.6f))
        add(R("milk", "vinegar", 0.8f, toA = "curd", toB = "water", probB = 0.3f))
        add(R("yeast", "water", 1f / 3000f, toB = "braga", gas = "co2", soluteB = "sugar", minT = 10f, maxT = 45f, slow = true))
        add(R("yeast", "braga", 1f / 6000f, gas = "co2", soluteB = "sugar", minT = 10f, maxT = 45f, slow = true))
        // ---- лава в воде застывает обсидианом ----
        add(R("lava", "water", 2f, toA = "obsidian", toB = "steam", heat = -2200f, warm = false))
        add(R("lava", "seawater", 2f, toA = "obsidian", toB = "steam", heat = -2200f, warm = false))
        // ---- ацетон растворяет пенопласт ----
        add(R("styrofoam", "acetone", 3f, toA = "air", toB = "molten_plastic", probB = 0.02f, warm = false))
        // ---- аммиак растворяется в воде ----
        add(R("ammonia", "water", 2f, toA = "air", heat = 30f))
        // ---- медный купорос: железо в растворе покрывается медью ----
        add(R("iron", "water", 0.3f, toA = "copper", soluteB = "copper_sulfate"))
        add(R("steel", "water", 0.25f, toA = "copper", soluteB = "copper_sulfate"))
        add(R("iron_filings", "water", 1f, toA = "copper", soluteB = "copper_sulfate"))
        add(R("zinc", "water", 0.4f, toA = "copper", soluteB = "copper_sulfate"))
        // ---- окислители и перекись ----
        add(R("permanganate", "glycerin", 0.4f, toA = "air", partA = 0.3f, toB = "flame", probB = 0.4f, heat = 600f, flame = 0.3f, gas = "steam"))
        add(R("permanganate", "h2o2", 3f, toB = "water", probB = 0.1f, heat = 150f, gas = "oxygen", gasAmt = 3f, extra = "foam", extraProb = 0.5f))
        add(R("yeast", "h2o2", 4f, toB = "water", probB = 0.1f, heat = 100f, gas = "oxygen", gasAmt = 2f, extra = "foam", extraProb = 0.7f))
        add(R("h2o2", null, 1f / 3e5f, toA = "water", gas = "oxygen", slow = true))
        add(R("soda", "water", 2f, toA = "co2", soluteB = "citric_acid", extra = "foam", extraProb = 0.6f, heat = -30f))
        add(R("malachite", "hcl", 0.3f, toA = "air", partA = 0.2f, gas = "co2", heat = 20f))
        // ---- щелочные и щёлочноземельные металлы в воде ----
        add(R("lithium", "water", 0.5f, toA = "air", partA = 0.05f, toB = "lye", probB = 0.05f, heat = 300f, gas = "hydrogen"))
        for (k in listOf("potassium", "molten_potassium"))
            add(R(k, "water", 6f, toA = "air", partA = 0.2f, toB = "lye", probB = 0.2f, heat = 1200f, gas = "hydrogen", gasAmt = 3f, flame = 0.4f))
        add(R("calcium", "water", 0.3f, toA = "air", partA = 0.05f, heat = 200f, gas = "hydrogen"))
        // ---- кухня ----
        add(R("rice", "water", 0.05f, toA = "cooked_rice", toB = "air", probB = 0.5f, minT = 85f, warm = false))
        add(R("popcorn_kernel", null, 3f, toA = "popcorn", extra = "popcorn", extraProb = 1f, minT = 180f, warm = false))

        // ---- распад и перегонка (без пары) ----
        add(R("foam", null, 0.07f, toA = "air", gas = "co2", gasAmt = 0.3f, warm = false))
        add(R("braga", null, 0.4f, toA = "water", extra = "alcohol_vapor", extraProb = 1f, heat = -60f, minT = 78f, warm = false))
    }

    /** Ручные реакции + общие правила по тегам (ручные главнее: пара, описанная вручную, не дублируется). */
    val list: List<R> = manual + TagRules.generate(manual)

    // ---- таблицы по номерам ----
    private val cnt = Mat.count
    private val pair = IntArray(cnt * cnt) { -1 }
    private val next = IntArray(list.size) { -1 }   // следующая реакция той же пары
    private val single = IntArray(cnt) { -1 }
    private val active = BooleanArray(cnt)
    private val rToA = IntArray(list.size)
    private val rToB = IntArray(list.size)
    private val rGas = IntArray(list.size)
    private val rExtra = IntArray(list.size)
    private val rSolute = IntArray(list.size)

    private val tap = Mat.id("tap")
    private val drain = Mat.id("drain")
    private val oxygen = Mat.id("oxygen")
    private val saltpeter = Mat.id("saltpeter")
    private val vacuum = Mat.id("vacuum")
    private val burningBit = 2

    init {
        list.forEachIndexed { k, r ->
            val a = Mat.id(r.a)
            if (r.b == null) single[a] = k
            else {
                val b = Mat.id(r.b)
                // несколько реакций одной пары — цепочкой (ржавление и медный купорос у железа с водой)
                val key = a * cnt + b
                if (pair[key] < 0) pair[key] = k
                else { var q = pair[key]; while (next[q] >= 0) q = next[q]; next[q] = k }
            }
            active[a] = true
            rToA[k] = Mat.idOrNeg(r.toA)
            rToB[k] = Mat.idOrNeg(r.toB)
            rGas[k] = Mat.idOrNeg(r.gas)
            rExtra[k] = Mat.idOrNeg(r.extra)
            rSolute[k] = Mat.idOrNeg(r.soluteB)
        }
        active[tap] = true
        active[drain] = true
        active[oxygen] = true
        active[saltpeter] = true
        active[vacuum] = true
    }

    fun step() {
        val type = w.type
        val n = w.n
        val start = if (w.rndInt(2) == 0) 0 else n - 1
        val dir = if (start == 0) 1 else -1
        var i = start
        for (c in 0 until n) {
            val id = type[i].toInt()
            if (id != Mat.AIR && active[id]) cell(i, id)
            i += dir
        }
    }

    private fun cell(i: Int, id: Int) {
        when (id) {
            tap -> { tapCell(i); return }
            drain -> { drainCell(i); return }
            oxygen -> { oxygenCell(i); return }
            saltpeter -> { if (saltpeterCell(i)) return }
            vacuum -> { vacuumCell(i); return }
        }
        val s = single[id]
        if (s >= 0) {
            val r = list[s]
            val t = w.temp[i]
            if (t >= r.minT && t <= r.maxT && w.rnd() < prob(r, t)) apply(s, r, i, -1)
            if (w.type[i].toInt() != id) return
        }
        // жидкость, впитавшаяся в поры порошка, реагирует с ним изнутри (вода в муке, в извести)
        val pore = w.mixId[i].toInt()
        if (pore != 0 && Mat.isPowder(id) && w.mix[i] > 0.02f) {
            val k0 = pair[id * cnt + pore]
            if (k0 >= 0) {
                val r = list[k0]
                val t = w.temp[i]
                if (t >= r.minT && t <= r.maxT && rSolute[k0] < 0 && w.rnd() < prob(r, t)) {
                    // часть воды уходит в продукт
                    w.mix[i] = maxOf(0f, w.mix[i] - 0.1f)
                    if (w.mix[i] <= 0.001f) { w.mixId[i] = 0; w.mix[i] = 0f }
                    apply(k0, r, i, -1)
                    return
                }
            }
        }
        val j = Neighbors.at(w, i, w.rndInt(4))
        if (j < 0) return
        var k = pair[id * cnt + w.type[j].toInt()]
        while (k >= 0) {
            if (tryPair(k, i, j)) return
            k = next[k]
        }
    }

    private fun tryPair(k: Int, i: Int, j: Int): Boolean {
        val r = list[k]
        val t = w.temp[i]
        if (t < r.minT || t > r.maxT) return false
        if (r.burningA && (w.flags[i].toInt() and burningBit) == 0) return false
        val sol = rSolute[k]
        if (sol >= 0 && (w.mixId[j].toInt() != sol || w.mix[j] < 0.01f)) return false
        if (sol < 0 && w.mixId[j].toInt() != 0 && hasSoluteReaction(k)) return false
        if (w.owner[j].toInt() != 0 && rToB[k] >= 0) return false // тела не превращаем по клеткам
        if (w.rnd() < prob(r, maxOf(t, w.temp[j]))) apply(k, r, i, j)
        return true
    }

    /** В цепочке есть реакция с растворённым веществом — она главнее (купорос вместо ржавчины). */
    private fun hasSoluteReaction(k: Int): Boolean {
        var q = next[k]
        while (q >= 0) { if (rSolute[q] >= 0) return true; q = next[q] }
        return false
    }

    private fun prob(r: R, t: Float): Float {
        var p = r.rate * Units.DT
        if (r.slow) p *= chemSpeed
        if (r.warm) p *= Heat.chemFactor(t).coerceIn(0.1f, 16f)
        return p
    }

    private fun apply(k: Int, r: R, i: Int, j: Int) {
        val aId = w.type[i]
        // тепло реакции — пополам между участниками
        if (r.heat != 0f) {
            if (j >= 0) {
                val ci = w.heatCapacity(i)
                val cj = w.heatCapacity(j)
                val e = r.heat
                w.temp[i] += e * ci / (ci + cj) / ci
                w.temp[j] += e * cj / (ci + cj) / cj
            } else w.temp[i] += r.heat / w.heatCapacity(i)
            if (w.temp[i] > 4000f) w.temp[i] = 4000f
            if (j >= 0 && w.temp[j] > 4000f) w.temp[j] = 4000f
        }
        val tA = rToA[k]
        var freed = -1
        if (tA >= 0 && w.owner[i].toInt() == 0) {
            var whole = true
            if (r.partA < 1f) {
                w.amt[i] -= r.partA
                whole = w.amt[i] < 0.05f
            }
            if (whole) {
                if (tA == Mat.AIR) { w.toAir(i); freed = i } else { w.transform(i, tA); if (!Mat.isGas(tA)) w.amt[i] = 1f }
            }
        }
        if (j >= 0) {
            val tB = rToB[k]
            if (tB >= 0 && w.rnd() < r.probB) {
                if (tB == Mat.AIR) { w.toAir(j); if (freed < 0) freed = j } else { w.transform(j, tB); if (!Mat.isGas(tB)) w.amt[j] = 1f }
            }
        }
        val g = rGas[k]
        if (g >= 0) spawn(g, if (freed >= 0) freed else place(i, j), r.gasAmt, maxOf(w.temp[i], 20f))
        val x = rExtra[k]
        if (x >= 0 && w.rnd() < r.extraProb) spawn(x, place(i, j), 1f, w.temp[i])
        if (r.flame > 0f && w.rnd() < r.flame) {
            val f = place(i, j)
            if (f >= 0) { w.put(f, Mat.FLAME, 1200f); w.amt[f] = 4f; w.mixId[f] = aId }
        }
    }

    /**
     * Вакуум — отсутствие вещества: соседний газ расширяется в него (давление падает вдвое),
     * жидкость вскипает и охлаждается (теплота испарения), всё остальное не трогается.
     */
    private fun vacuumCell(i: Int) {
        val j = Neighbors.at(w, i, w.rndInt(4))
        if (j < 0) return
        val t = w.type[j].toInt()
        if (t == vacuum || t == Mat.FLAME) return
        if (Mat.isGas(t)) {
            val a = w.amt[j] * 0.5f
            val temp = w.temp[j]
            w.put(i, t, temp)
            w.amt[i] = a; w.amt[j] = a
            return
        }
        if (Mat.isLiquid(t) && w.owner[j].toInt() == 0) {
            val vap = Mat.hotTo[t]
            if (vap < 0 || !Mat.isGas(vap)) return
            val part = 0.05f
            val mass = w.massDensity(t, w.temp[j]) * Units.VOL * part
            w.temp[j] -= Mat.hotLatent[t] * mass / w.heatCapacity(j)
            w.put(i, vap, w.temp[j])
            w.amt[i] = 0.3f
            w.amt[j] -= part
            if (w.amt[j] < 0.05f) w.put(j, vacuum, w.temp[j])
        }
    }

    /** Свободная клетка рядом с участниками (воздух, а если его нет — любой газ) — или −1. */
    private fun place(i: Int, j: Int): Int {
        val a = Neighbors.randomAir(w, i, preferUp = true)
        if (a >= 0) return a
        if (j >= 0) { val b = Neighbors.randomAir(w, j, preferUp = true); if (b >= 0) return b }
        for (c in intArrayOf(i, j)) {
            if (c < 0) continue
            for (d in 0 until 4) {
                val n = Neighbors.at(w, c, d)
                if (n >= 0 && Mat.isGas(w.type[n].toInt())) { w.toAir(n); return n }
            }
        }
        return -1
    }

    private fun spawn(id: Int, at: Int, amt: Float, t: Float) {
        if (at < 0) return
        if (w.type[at].toInt() != Mat.AIR) return
        w.put(at, id, if (Mat.isGas(id)) maxOf(t, Mat[id].spawnTemp.takeIf { it != Units.AMBIENT } ?: t) else t)
        if (Mat.isGas(id)) w.amt[at] = amt
    }

    // ================= Кран и сток =================

    private fun tapCell(i: Int) {
        var src = w.mixId[i].toInt()
        if (src == 0) {
            // кран без вещества запоминает первое, что его коснулось
            for (d in 0 until 4) {
                val j = Neighbors.at(w, i, d)
                if (j < 0) continue
                val t = w.type[j].toInt()
                if (t != Mat.AIR && t != tap && t != drain && !Mat.isSolid(t)) { w.mixId[i] = t.toShort(); src = t; break }
            }
            if (src == 0) return
        }
        val ph = Mat.phase[src]
        val p = when (ph) {
            Phase.LIQUID.ordinal -> 0.6f
            Phase.POWDER.ordinal -> 0.4f
            Phase.SOLID.ordinal -> 0.02f
            else -> 0.35f
        }
        if (w.rnd() > p) return
        // жидкость и порошок — вниз, газ — вверх, иначе в любую сторону
        val first = if (Mat.isGas(src)) 0 else 1
        var j = Neighbors.at(w, i, first)
        if (j < 0 || w.type[j].toInt() != Mat.AIR) j = Neighbors.randomAir(w, i)
        if (j < 0) return
        w.put(j, src)
        onSpawn(w, j)
        if (src == Mat.FLAME) w.amt[j] = 5f
    }

    private fun drainCell(i: Int) {
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            val t = w.type[j].toInt()
            if (t == Mat.AIR || t == tap || t == drain || Mat.isSolid(t) || w.owner[j].toInt() != 0) continue
            w.toAir(j)
            w.temp[j] = Units.AMBIENT
        }
    }

    // ================= Окислители =================

    /** Кислород рядом с горящим или раскалённым горючим раздувает огонь. */
    private fun oxygenCell(i: Int) {
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            val t = w.type[j].toInt()
            if (t == Mat.FLAME) {
                // чистый кислород: пламя жарче
                w.temp[j] = minOf(w.temp[j] + 300f, 3000f)
                w.toAir(i)
                return
            }
            if (!Mat.flammable[t] || Mat.isGas(t)) continue
            val burning = (w.flags[j].toInt() and burningBit) != 0
            if (burning || w.temp[j] >= Mat.flashPoint[t] - 50f) {
                // вспыхивает: тлеющая лучина в кислороде
                w.flags[j] = (w.flags[j].toInt() or burningBit).toByte()
                val mass = Mat.density[t] * Units.VOL * w.amt[j]
                val e = Mat.burnHeat[t] * mass * 0.06f
                w.burn[j] += 0.06f
                w.temp[j] = minOf(w.temp[j] + e / w.heatCapacity(j), Mat.flameTemp[t] + 500f)
                w.put(i, Mat.FLAME, Mat.flameTemp[t] + 400f)
                w.amt[i] = 4f
                return
            }
        }
    }

    /** Селитра у огня разлагается и отдаёт кислород. */
    private fun saltpeterCell(i: Int): Boolean {
        val t = w.temp[i]
        val j = Neighbors.at(w, i, w.rndInt(4))
        if (j < 0) return false
        val tj = w.type[j].toInt()
        val fire = tj == Mat.FLAME || (Mat.flammable[tj] && (w.flags[j].toInt() and burningBit) != 0)
        if (!fire && t < 400f) return false
        if (w.rnd() > 0.15f) return false
        val temp = maxOf(t, 600f)
        w.put(i, oxygen, temp)
        w.amt[i] = 6f
        if (Mat.flammable[tj]) {
            w.flags[j] = (w.flags[j].toInt() or burningBit).toByte()
            w.burn[j] += 0.1f
            w.temp[j] = minOf(w.temp[j] + 200f, Mat.flameTemp[tj] + 600f)
        }
        return true
    }

    companion object {
        private val seawater = Mat.id("seawater")
        private val salt = Mat.id("salt")

        /** Вещество только что нарисовано: морская вода — это вода с растворённой солью. */
        fun onSpawn(w: World, i: Int) {
            if (w.type[i].toInt() == seawater) {
                val t = w.temp[i]
                w.put(i, Mat.WATER, t)
                w.mixId[i] = salt.toShort()
                w.mix[i] = 0.035f
            }
        }
    }
}
