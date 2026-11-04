package ru.stikhiya.sim

/**
 * Рост живого из списка 500: мох и лишайник ползут по камню, плесень заводится на влажной еде,
 * водоросли растут в освещённой воде и выделяют кислород, ряска затягивает поверхность воды,
 * корни уходят вниз в землю, саженец вырастает в дерево с кроной, злаки и цветы тянутся к свету.
 * Время роста настоящее и ускоряется множителем химии, как у [Plants].
 */
class Growth(private val w: World) {
    var chemSpeed = 600f

    private class Rule(val time: Float, val maxH: Int = 0)

    private fun id(k: String) = Mat.id(k)
    private val moss = id("moss"); private val mold = id("mold"); private val mush = id("mushrooms")
    private val algae = id("algae"); private val cactus = id("cactus"); private val liana = id("liana")
    private val roots = id("roots"); private val flowers = id("flowers"); private val sapling = id("sapling")
    private val lichen = id("lichen"); private val duck = id("duckweed"); private val wheat = id("wheat")
    private val sunflower = id("sunflower"); private val reed = id("reed")
    private val soil = id("soil"); private val sand = id("sand"); private val wood = id("wood")
    private val leaf = id("leaf"); private val water = Mat.WATER; private val oxygen = Mat.OXYGEN

    private val rules = HashMap<Int, Rule>().apply {
        put(moss, Rule(2e5f)); put(lichen, Rule(1.5e6f)); put(mold, Rule(6e4f)); put(mush, Rule(4e4f, 6))
        put(algae, Rule(6e4f)); put(cactus, Rule(1.2e5f, 14)); put(liana, Rule(3e4f, 60)); put(roots, Rule(4e4f, 30))
        put(flowers, Rule(4e4f, 4)); put(sapling, Rule(2.5e4f, 30)); put(duck, Rule(4e4f))
        put(wheat, Rule(3e4f, 9)); put(sunflower, Rule(2e4f, 22)); put(reed, Rule(2e4f, 22))
    }
    private val isLife = BooleanArray(Mat.count).also { a -> for (k in rules.keys) a[k] = true }
    private val lightTop = IntArray(w.w)
    private var tick = 0

    fun step() {
        tick++
        if (tick % PERIOD != 0) return
        computeLight()
        val dt = Units.DT * PERIOD * chemSpeed
        val n = w.n
        for (i in 0 until n) {
            val id = w.type[i].toInt()
            if (!isLife[id]) continue
            val t = w.temp[i]
            if (t < 2f || t > 45f) continue
            val r = rules[id] ?: continue
            if (w.rnd() >= dt / r.time) continue
            grow(i, id, r)
        }
    }

    /** Свет сверху: столбец освещён до первой непрозрачной клетки (жидкость и живое свет пропускают). */
    private fun computeLight() {
        for (x in 0 until w.w) {
            var y = 0
            while (y < w.h) {
                val id = w.type[y * w.w + x].toInt()
                if (Mat.isSolid(id) && !isLife[id] && Mat[id].alpha > 0.7f || Mat.isPowder(id)) break
                y++
            }
            lightTop[x] = y
        }
    }

    private fun lit(i: Int) = i / w.w <= lightTop[i % w.w]
    private fun open(j: Int) = j >= 0 && w.type[j].toInt() == Mat.AIR
    private fun up(i: Int) = if (i >= w.w) i - w.w else -1
    private fun down(i: Int) = if (i + w.w < w.n) i + w.w else -1
    private fun side(i: Int, d: Int): Int { val x = i % w.w + d; return if (x in 0 until w.w) i + d else -1 }
    private fun typeAt(j: Int) = if (j < 0) -1 else w.type[j].toInt()

    /** Высота столбца одного вида под клеткой (включая её). */
    private fun height(i: Int, id: Int, alt: Int = -1): Int {
        var h = 0; var j = i
        while (j >= 0 && (typeAt(j) == id || typeAt(j) == alt)) { h++; j = down(j) }
        return h
    }

    private fun wet(i: Int): Boolean {
        for (d in 0 until 4) {
            val j = Neighbors.at(w, i, d)
            if (j < 0) continue
            val t = w.type[j].toInt()
            if (Mat.isLiquid(t) && (t == water || Mat[t].key.contains("water"))) return true
            if (Mat.isPowder(t) && w.mixId[j].toInt() == water && w.mix[j] > 0.02f) return true
        }
        return false
    }

    private fun place(j: Int, id: Int, from: Int) {
        w.put(j, id, w.temp[from])
    }

    private fun grow(i: Int, id: Int, r: Rule) {
        when (id) {
            moss, lichen -> {
                // ползёт по поверхности камня/земли/дерева на соседнюю свободную клетку, касающуюся твёрдого
                if (id == moss && !wet(i) && w.rnd() < 0.7f) return
                val j = Neighbors.at(w, i, w.rndInt(4))
                if (!open(j)) return
                if (touchesSurface(j)) place(j, id, i)
            }
            mold -> {
                val j = Neighbors.at(w, i, w.rndInt(4))
                if (j >= 0 && Mat.has(typeAt(j), Tag.FOOD) && w.owner[j].toInt() == 0) place(j, mold, i)
                else if (open(j) && w.rnd() < 0.2f) place(j, mold, i)
            }
            mush -> {
                if (lit(i)) return
                val u = up(i)
                if (open(u) && height(i, mush) < r.maxH) place(u, mush, i)
            }
            algae -> {
                if (!lit(i)) return
                val j = Neighbors.at(w, i, w.rndInt(4))
                if (j >= 0 && typeAt(j) == water) {
                    if (w.rnd() < 0.7f) place(j, algae, i) else w.put(j, oxygen, w.temp[j])   // фотосинтез: пузырёк O₂
                }
            }
            duck -> {
                val d = if (w.rnd() < 0.5f) -1 else 1
                val j = side(i, d)
                if (j >= 0 && typeAt(j) == water && open(up(j))) place(j, duck, i)
                else { val u = up(j); if (u >= 0 && j >= 0 && typeAt(j) == water && open(u)) place(j, duck, i) }
            }
            roots -> {
                val dx = w.rndInt(3) - 1
                val d = down(i) ?: -1
                val j = if (dx == 0 || d < 0) d else side(d, dx)
                if (j >= 0 && typeAt(j) == soil && height(i, roots) < r.maxH) place(j, roots, i)
            }
            liana -> {
                val u = up(i)
                val cand = intArrayOf(u, side(u, -1), side(u, 1), side(i, -1), side(i, 1))
                val j = cand[w.rndInt(cand.size)]
                if (open(j) && touchesSurface(j) && w.rnd() < 0.9f) place(j, liana, i)
            }
            cactus, flowers, wheat, sunflower, reed -> {
                val base = baseOf(i, id)
                val bt = typeAt(down(base))
                val ok = when (id) {
                    cactus -> bt == sand || bt == soil
                    reed -> bt == soil || bt == sand || wet(base)
                    else -> bt == soil && (wet(down(base)) || w.mixId[down(base)].toInt() == water)
                }
                if (!ok) return
                if (id != cactus && !lit(i)) return
                val u = up(i)
                val canGrow = open(u) || (id == reed && typeAt(u) == water)
                if (canGrow && typeAt(i) == id && height(i, id) < r.maxH) place(u, id, i)
                // цветок и подсолнух: шапка
                if ((id == sunflower || id == flowers) && height(i, id) >= r.maxH - 1) {
                    val s = side(i, if (w.rnd() < 0.5f) -1 else 1)
                    if (open(s)) place(s, if (id == sunflower) sunflower else flowers, i)
                }
            }
            sapling -> {
                // ствол растёт под верхушкой, крона — вокруг
                val h = height(i, sapling, wood)
                val u = up(i)
                if (h < r.maxH && open(u)) {
                    w.put(i, wood, w.temp[i])
                    place(u, sapling, i)
                    if (h > 8) for (d in listOf(-1, 1)) { val s = side(i, d); if (open(s) && w.rnd() < 0.5f) place(s, leaf, i) }
                } else if (h >= r.maxH) {
                    // крона
                    for (k in 0 until 3) {
                        val dx = w.rndInt(9) - 4; val dy = -w.rndInt(5)
                        val x = i % w.w + dx; val y = i / w.w + dy
                        if (w.inside(x, y)) { val j = w.idx(x, y); if (open(j)) place(j, leaf, i) }
                    }
                }
            }
        }
    }

    /** Низ стебля этого вида. */
    private fun baseOf(i: Int, id: Int): Int {
        var j = i
        while (true) { val d = down(j); if (d < 0 || typeAt(d) != id) return j; j = d }
    }

    private fun touchesSurface(j: Int): Boolean {
        for (d in 0 until 4) {
            val k = Neighbors.at(w, j, d)
            if (k < 0) continue
            val t = w.type[k].toInt()
            if ((Mat.isSolid(t) || Mat.isPowder(t)) && !isLife[t]) return true
        }
        return false
    }

    companion object { const val PERIOD = 6 }
}
