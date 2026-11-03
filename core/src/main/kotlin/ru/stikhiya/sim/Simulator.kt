package ru.stikhiya.sim

/** Один шаг мира = 1/60 секунды. */
class Simulator(val world: World) {
    val air = Air(world)
    val heat = Heat(world, air)
    val motion = Motion(world, air)
    val chemistry = Chemistry(world, air)
    val bodies = Bodies(world, air)
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
        air.step()
        bodies.step()
        motion.step(tick)
    }

    fun clear() {
        bodies.clear()
        world.clear()
        air.clear()
    }
}

enum class Tool(val title: String, val inPanel: Boolean = true) {
    HAND("Рука"),
    DRAW("Кисть"),
    ERASE("Ластик"),
    HEAT("Нагреть"),
    COOL("Охладить"),
    STIR("Мешать"),
    INSPECT("Смотреть"),
    // кисти воздуха — в категории «Особые»
    WIND("Ветер", false),
    PRESSURE_UP("Давление +", false),
    PRESSURE_DOWN("Давление −", false)
}

/** Инструменты игрока: рисование веществом, ластик, нагрев, охлаждение, перемешивание. */
object Brush {
    /** rec — сюда записываются нарисованные твёрдые клетки (из них потом делаются тела). */
    fun apply(w: World, tool: Tool, substance: Int, cx: Int, cy: Int, r: Int, rec: IntList? = null) {
        if (tool == Tool.HAND || tool == Tool.INSPECT || !tool.inPanel) return
        val r2 = r * r + r
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r2) continue
                val x = cx + dx
                val y = cy + dy
                if (!w.inside(x, y)) continue
                val i = w.idx(x, y)
                when (tool) {
                    Tool.DRAW -> if (draw(w, i, substance) && rec != null && Mat.isSolid(substance)) rec.add(i)
                    Tool.ERASE -> if (w.type[i].toInt() != Mat.AIR) w.toAir(i)
                    Tool.HEAT -> heatCell(w, i, 15f)
                    Tool.COOL -> heatCell(w, i, -15f)
                    Tool.STIR -> stir(w, i, x, y, r)
                    else -> {}
                }
            }
        }
    }

    private fun draw(w: World, i: Int, id: Int): Boolean {
        if (w.type[i].toInt() != Mat.AIR) return false
        // порошки сыплются неплотно — так естественнее
        if (Mat.isPowder(id) && w.rnd() < 0.35f) return false
        if (Mat.isGas(id) && id != Mat.FLAME && w.rnd() < 0.5f) return false
        val t = w.temp[i]
        w.put(i, id)
        if (id == Mat.FLAME) { w.amt[i] = 6f; return true }
        // газы рисуются при своей температуре, а если она не задана — при температуре воздуха
        if (Mat.isGas(id) && Mat[id].spawnTemp == Units.AMBIENT) w.temp[i] = t
        return true
    }

    /** Нагрев/охлаждение: мощность ~ 1 кВт/см³ — паяльная лампа в миниатюре. */
    private fun heatCell(w: World, i: Int, rate: Float) {
        val c = w.heatCapacity(i)
        val mass = maxOf(w.cellMass(i), 1e-7f)
        // одинаковая мощность на массу, но не быстрее 20 °C за тик
        val dT = (rate * 1000f * Units.DT * 60f * mass / c).coerceIn(-40f, 40f)
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
