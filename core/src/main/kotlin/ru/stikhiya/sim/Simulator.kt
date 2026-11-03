package ru.stikhiya.sim

/** Один шаг мира = 1/60 секунды. */
class Simulator(val world: World) {
    val heat = Heat(world)
    val motion = Motion(world)
    val chemistry = Chemistry(world)
    var tick = 0
        private set

    var chemSpeed: Float
        get() = chemistry.chemSpeed
        set(v) { chemistry.chemSpeed = v }

    /** Время мира в секундах. */
    val time: Float get() = tick * Units.DT

    fun step() {
        tick++
        heat.step()
        chemistry.step()
        motion.step(tick)
    }
}

enum class Tool(val title: String) {
    HAND("Рука"),
    DRAW("Кисть"),
    ERASE("Ластик"),
    HEAT("Нагреть"),
    COOL("Охладить"),
    STIR("Мешать"),
    INSPECT("Смотреть")
}

/** Инструменты игрока: рисование веществом, ластик, нагрев, охлаждение, перемешивание. */
object Brush {
    fun apply(w: World, tool: Tool, substance: Int, cx: Int, cy: Int, r: Int) {
        if (tool == Tool.HAND || tool == Tool.INSPECT) return
        val r2 = r * r + r
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r2) continue
                val x = cx + dx
                val y = cy + dy
                if (!w.inside(x, y)) continue
                val i = w.idx(x, y)
                when (tool) {
                    Tool.DRAW -> draw(w, i, substance)
                    Tool.ERASE -> if (w.type[i].toInt() != Mat.AIR) w.toAir(i)
                    Tool.HEAT -> heatCell(w, i, 6f)
                    Tool.COOL -> heatCell(w, i, -6f)
                    Tool.STIR -> stir(w, i, x, y, r)
                    Tool.HAND, Tool.INSPECT -> {}
                }
            }
        }
    }

    private fun draw(w: World, i: Int, id: Int) {
        if (w.type[i].toInt() != Mat.AIR) return
        // порошки сыплются неплотно — так естественнее
        if (Mat.isPowder(id) && w.rnd() < 0.35f) return
        if (Mat.isGas(id) && id != Mat.FLAME && w.rnd() < 0.5f) return
        val t = w.temp[i]
        w.put(i, id)
        if (id == Mat.FLAME) { w.amt[i] = 6f; return }
        // газы рисуются при своей температуре, а если она не задана — при температуре воздуха
        if (Mat.isGas(id) && Mat[id].spawnTemp == Units.AMBIENT) w.temp[i] = t
    }

    /** Нагрев/охлаждение: мощность ~ 1 кВт/см³ — паяльная лампа в миниатюре. */
    private fun heatCell(w: World, i: Int, rate: Float) {
        val c = w.heatCapacity(i)
        val mass = maxOf(w.cellMass(i), 1e-7f)
        // одинаковая мощность на массу, но не быстрее 20 °C за тик
        val dT = (rate * 1000f * Units.DT * 60f * mass / c).coerceIn(-20f, 20f)
        val t = w.temp[i] + dT
        w.temp[i] = t.coerceIn(Units.ABS_ZERO + 1f, 6000f)
    }

    private fun stir(w: World, i: Int, x: Int, y: Int, r: Int) {
        if (w.rnd() > 0.3f) return
        val id = w.type[i].toInt()
        if (Mat.isSolid(id)) return
        val nx = x + w.rndInt(3) - 1
        val ny = y + w.rndInt(3) - 1
        if (!w.inside(nx, ny)) return
        val j = w.idx(nx, ny)
        if (Mat.isSolid(w.type[j].toInt())) return
        w.swap(i, j)
    }
}
