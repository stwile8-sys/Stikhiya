package ru.stikhiya.sim

/**
 * Растения: семена прорастают во влажной земле на свету и в тепле, стебель тянется вверх и даёт листья,
 * трава расползается по влажной земле. Вода идёт от земли вверх по растению и испаряется листьями —
 * без полива растение вянет и сохнет (сухое горит очень легко). Жара выше 60 °C и мороз губят зелень.
 *
 * Время роста настоящее (стебель ~4 см в сутки), поэтому ускоряется множителем химии:
 * при «химия ×3600» росток виден через полминуты.
 * В клетке растения [World.mix] — запас воды 0..1, [World.life] — сколько ещё может вырасти.
 */
class Plants(private val w: World) {
    var chemSpeed = 600f

    val seed = Mat.id("seeds")
    val stem = Mat.id("stem")
    val leaf = Mat.id("leaf")
    val grass = Mat.id("grass")
    val dry = Mat.id("dry_plant")
    private val soil = Mat.id("soil")

    private val isPlant = BooleanArray(Mat.count).also { a -> a[stem] = true; a[leaf] = true; a[grass] = true }
    private val lightTop = IntArray(w.w)
    private var tick = 0

    companion object {
        const val PERIOD = 6                 // растения считаем раз в 6 шагов
        const val GERM_TIME = 8.6e4f         // с — сутки на прорастание
        const val STEM_TIME = 2.2e4f         // с на сантиметр стебля
        const val GRASS_TIME = 8.6e4f        // с на сантиметр травы
        const val SPREAD_TIME = 1.7e5f       // с — трава захватывает соседнюю клетку земли
        const val THIRST = 1f / 1.7e5f       // доля запаса воды, испаряемая за секунду на свету
    }

    fun step() {
        tick++
        if (tick % PERIOD != 0) return
        computeLight()
        val dt = Units.DT * PERIOD * chemSpeed
        val type = w.type
        // снизу вверх: вода поднимается по стеблю за один проход
        for (y in w.h - 1 downTo 0) {
            val row = y * w.w
            for (x in 0 until w.w) {
                val i = row + x
                val id = type[i].toInt()
                when {
                    id == seed -> seedCell(i, x, y, dt)
                    isPlant[id] -> plantCell(i, id, x, y, dt)
                }
            }
        }
    }

    /** Свет сверху: в каждом столбце — до первой непрозрачной клетки (растения и жидкости свет пропускают). */
    private fun computeLight() {
        for (x in 0 until w.w) {
            var y = 0
            while (y < w.h) {
                val id = w.type[y * w.w + x].toInt()
                val opaque = (Mat.isSolid(id) || Mat.isPowder(id)) && !isPlant[id] && id != seed && Mat[id].alpha > 0.6f
                if (opaque) break
                y++
            }
            lightTop[x] = y
        }
    }

    private fun lit(x: Int, y: Int) = y <= lightTop[x] + 1

    private fun okTemp(t: Float) = t in 5f..38f

    /** Влажность земли под семенем/корнем: доля пор, заполненных водой. */
    private fun soilWater(j: Int): Float {
        if (j < 0 || w.type[j].toInt() != soil) return 0f
        if (w.mixId[j].toInt() != Mat.WATER) return 0f
        val por = Mat.porosity[soil] * w.amt[j]
        return if (por > 0f) w.mix[j] / por else 0f
    }

    private fun drink(j: Int, amount: Float) {
        w.mix[j] = maxOf(0f, w.mix[j] - amount)
        if (w.mix[j] <= 1e-5f) { w.mix[j] = 0f; w.mixId[j] = 0 }
    }

    private fun seedCell(i: Int, x: Int, y: Int, dt: Float) {
        if (y >= w.h - 1) return
        val below = i + w.w
        if (w.type[below].toInt() != soil) return
        if (soilWater(below) < 0.15f || !okTemp(w.temp[i]) || !lit(x, y)) return
        w.life[i] += dt / GERM_TIME * (0.6f + 0.8f * w.rnd())
        if (w.life[i] >= 1f) {
            val t = w.temp[i]
            w.put(i, stem, t)
            w.mix[i] = 0.9f
            w.life[i] = 12f + w.rnd() * 30f   // высота будущего растения, см
            drink(below, 0.02f)
        }
    }

    private fun plantCell(i: Int, id: Int, x: Int, y: Int, dt: Float) {
        val t = w.temp[i]
        // жара и мороз губят зелень
        if (t > 60f || t < -5f) { wither(i); return }
        // ---- вода: из земли или из растения ниже ----
        val below = if (y < w.h - 1) i + w.w else -1
        var water = w.mix[i]
        if (below >= 0) {
            val bt = w.type[below].toInt()
            if (bt == soil) {
                val sw = soilWater(below)
                if (sw > 0.05f && water < 1f) {
                    val take = minOf(1f - water, 0.3f)
                    water += take
                    drink(below, take * 0.05f)
                }
            } else if (isPlant[bt]) {
                val wb = w.mix[below]
                if (wb > water) { val d = (wb - water) * 0.5f; water += d; w.mix[below] = wb - d }
            }
        }
        if (id == leaf) {
            // лист пьёт из соседнего стебля
            for (d in 2..3) {
                val j = Neighbors.at(w, i, d)
                if (j >= 0 && w.type[j].toInt() == stem && w.mix[j] > water) {
                    val dd = (w.mix[j] - water) * 0.5f; water += dd; w.mix[j] -= dd
                }
            }
        }
        // испарение листьями: на свету и в тепле быстрее
        val light = lit(x, y)
        val k = (if (id == leaf) 2f else 1f) * (if (light) 1f else 0.3f) * (1f + maxOf(0f, t - 20f) / 15f)
        water -= THIRST * k * dt
        if (water < 0.12f) { wither(i); return }
        w.mix[i] = minOf(1f, water)

        // ---- рост ----
        if (!light || !okTemp(t) || water < 0.5f) return
        if (id == grass && below >= 0 && w.type[below].toInt() == soil) spreadGrass(i, x, y, dt)
        if (w.life[i] <= 0f) return
        val up = if (y > 0) i - w.w else -1
        if (up < 0 || w.type[up].toInt() != Mat.AIR) return
        val time = if (id == grass) GRASS_TIME else STEM_TIME
        if (id == leaf) return
        if (w.rnd() >= dt / time) return
        val left = w.life[i] - 1f
        w.put(up, id, t)
        w.mix[up] = water * 0.95f
        w.life[up] = left
        w.life[i] = 0f
        // стебель: листья через каждые несколько сантиметров
        if (id == stem && w.rnd() < 0.3f) {
            for (d in 2..3) {
                val j = Neighbors.at(w, up, d)
                if (j >= 0 && w.type[j].toInt() == Mat.AIR && w.rnd() < 0.7f) {
                    w.put(j, leaf, t); w.mix[j] = water * 0.9f; w.life[j] = 0f
                }
            }
        }
    }

    /** Трава расползается по соседней влажной земле. */
    private fun spreadGrass(i: Int, x: Int, y: Int, dt: Float) {
        if (w.rnd() >= dt / SPREAD_TIME) return
        val dx = if (w.rnd() < 0.5f) -1 else 1
        for (dy in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (!w.inside(nx, ny) || ny + 1 >= w.h) continue
            val j = w.idx(nx, ny)
            val under = j + w.w
            if (w.type[j].toInt() == Mat.AIR && w.type[under].toInt() == soil && soilWater(under) > 0.15f && lit(nx, ny)) {
                w.put(j, grass, w.temp[i])
                w.mix[j] = 0.8f
                w.life[j] = 1f + w.rndInt(5)
                return
            }
        }
    }

    private fun wither(i: Int) {
        val t = w.temp[i]
        w.put(i, dry, t)
    }
}
