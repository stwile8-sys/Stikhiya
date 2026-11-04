package ru.stikhiya.sim

import ru.stikhiya.sim.Category as C
import ru.stikhiya.sim.Phase as P

/**
 * Детали для самодельных машин: ткань, нейлон, верёвка, горелка.
 *
 * Ткань и нейлон — тонкие плёнки: клетка — это 1 см полотна (толщина доли миллиметра),
 * поэтому «плотность» здесь — поверхностная плотность полотна, пересчитанная на объём клетки:
 * нейлон шара 25 г/м² → 0,0025 г на клетку (2,5 кг/м³), хлопковая ткань 150 г/м² → 15 кг/м³.
 * Верёвка (тонкий шнур) весит ~2 г/м → 0,02 г на клетку (20 кг/м³).
 * Мир — срез толщиной 1 см: подъёмная сила растёт с площадью шара, а вес оболочки — с её длиной,
 * поэтому, как и в жизни, маленький шар не взлетит, а большой и лёгкий — взлетит.
 */
object Craft {
    val list: List<Substance> = listOf(
        Substance("cloth", "Ткань", P.SOLID, C.ORGANIC, 0xC9B8A0, density = 15f, heatCapacity = 1300f,
            conductivity = 0.06f, colorVar = 0.05f, flashPoint = 255f, autoIgnition = 400f, burnTime = 2f,
            burnHeat = 1.6e7f, burnTo = "ash", burnResidue = 0.1f, smoke = 0.1f, flameTemp = 900f, oxygenNeed = 1.2f,
            fineness = 0.6f, description = "хлопковое полотно 150 г/м², не пропускает воздух; горит"),
        Substance("balloon_nylon", "Нейлон для шара", P.SOLID, C.ORGANIC, 0xD8402E, density = 2.5f, heatCapacity = 1700f,
            conductivity = 0.25f, colorVar = 0.08f, hotAt = 260f, hotTo = "molten_plastic", hotLatent = 2.0e5f,
            flashPoint = 400f, autoIgnition = 420f, burnTime = 1f, burnHeat = 3.1e7f, smoke = 0.3f,
            flameTemp = 900f, oxygenNeed = 2.5f, fineness = 0.6f,
            description = "сверхлёгкая оболочка воздушного шара 25 г/м²; нейлон-6,6 плавится при 260 °C"),
        Substance("nomex", "Огнеупорная ткань", P.SOLID, C.ORGANIC, 0xE0C060, density = 8f, heatCapacity = 1200f,
            conductivity = 0.05f, colorVar = 0.05f, hotAt = 450f, hotTo = "ash", hotLatent = 5e5f,
            description = "арамид (номекс) 80 г/м²: не горит, выдерживает ~400 °C — для юбки шара у горелки"),
        Substance("rope", "Верёвка", P.SOLID, C.ORGANIC, 0xB89A62, density = 20f, heatCapacity = 1300f,
            conductivity = 0.1f, colorVar = 0.06f, flashPoint = 260f, autoIgnition = 400f, burnTime = 6f,
            burnHeat = 1.6e7f, burnTo = "ash", burnResidue = 0.1f, smoke = 0.1f, flameTemp = 900f, oxygenNeed = 1.2f,
            fineness = 0.4f, description = "гибкая: рисуется цепочкой звеньев, концы цепляются за то, чего касаются"),
        Substance("cable", "Трос стальной", P.SOLID, C.METAL, 0x8A9098, density = 15f, heatCapacity = 490f,
            conductivity = 50f, colorVar = 0.03f, hotAt = 1450f, hotTo = "molten_steel", hotLatent = 2.7e5f,
            description = "тонкий стальной трос Ø 0,5 мм (1,5 г/м): гибкий, не горит — для подвеса у горелки"),
        Substance("battery_plus", "Батарея +", P.SOLID, C.SPECIAL, 0xC83A30, density = 2500f, heatCapacity = 900f,
            conductivity = 5f, colorVar = 0.02f, description = "полюс «+» батареи 12 В; соедините проводом с «−» через лампу или спираль"),
        Substance("battery_minus", "Батарея −", P.SOLID, C.SPECIAL, 0x2F4F9A, density = 2500f, heatCapacity = 900f,
            conductivity = 5f, colorVar = 0.02f, description = "полюс «−» батареи (0 В)"),
        Substance("lamp", "Лампочка", P.SOLID, C.SPECIAL, 0xE8E0B0, density = 50f, heatCapacity = 134f,
            conductivity = 0.01f, colorVar = 0.02f, description = "вольфрамовая нить ~10 Ом: от тока раскаляется и светит"),
        Substance("nichrome", "Нихромовая спираль", P.SOLID, C.METAL, 0x9C8A7A, density = 500f, heatCapacity = 450f,
            conductivity = 11f, colorVar = 0.04f, hotAt = 1400f, hotTo = "molten_steel", hotLatent = 2.9e5f,
            description = "спираль ~0,5 Ом на клетку: ток греет её докрасна — плитка, поджиг"),
        Substance("burner", "Горелка", P.SOLID, C.SPECIAL, 0x6E7B88, density = 300f, heatCapacity = 490f,
            conductivity = 50f, colorVar = 0.03f,
            description = "пропановая горелка ~150 Вт на клетку сопла: над ней всегда пламя (баллон внутри)")
    )

    /**
     * Воздушный шар из тех же деталей, что есть у игрока: купол и юбка из нейлона (одно тело),
     * под устьем на двух стальных тросах — горелка. Возвращает номер тела оболочки.
     * cx, top — центр и верх купола, r — радиус купола в клетках (от ~55 шар уже поднимает себя).
     */
    fun buildBalloon(w: World, b: Bodies, cx: Int, top: Int, r: Int, burners: Int = 2): Int {
        val nylon = Mat.id("balloon_nylon")
        val cy = top + r
        val mouth = maxOf(6, r / 4)
        val skirtH = r / 2
        val cells = IntList(1024)
        fun put(x: Int, y: Int) {
            if (!w.inside(x, y)) return
            val i = w.idx(x, y)
            if (w.type[i].toInt() != Mat.AIR) return
            w.put(i, nylon); cells.add(i)
        }
        // купол — полукольцо толщиной 2 клетки
        for (y in top - 1..cy) for (x in cx - r - 1..cx + r + 1) {
            val d = kotlin.math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (d >= r - 1.5 && d <= r + 0.5) put(x, y)
        }
        val envCells = cells
        val cellsN = IntList(256)
        // юбка сужается к устью; у горелки — огнеупорная ткань (как у настоящих шаров)
        val nomex = Mat.id("nomex")
        // верх юбки — нейлон (часть купола), низ у горелки — огнеупорная ткань
        val nomexFrom = 0.6f
        var fNow = 0f
        fun putN(x: Int, y: Int) {
            if (!w.inside(x, y)) return
            val i = w.idx(x, y)
            if (w.type[i].toInt() != Mat.AIR) return
            if (fNow < nomexFrom) { w.put(i, nylon); envCells.add(i) } else { w.put(i, nomex); cellsN.add(i) }
        }
        for (side in intArrayOf(-1, 1)) {
            var lx = Int.MIN_VALUE; var ly = 0
            val steps = skirtH * 4
            for (k in 0..steps) {
                val f = k / steps.toFloat()
                fNow = f
                val x = (cx + side * (r - (r - mouth) * f)).toInt()
                val y = (cy + skirtH * f).toInt()
                if (lx != Int.MIN_VALUE && x != lx && y != ly) { putN(lx, y); putN(lx - side, y) }
                putN(x, y); putN(x - side, y)
                lx = x; ly = y
            }
        }
        // купол и юбка сшиты — одно тело (разные ткани)
        for (k in 0 until cellsN.n) envCells.add(cellsN.a[k])
        b.create(envCells)
        val env = w.owner[envCells.a[0]].toInt()
        // горелка под устьем
        val by = cy + skirtH + maxOf(10, r / 5)
        val bl = IntList(8)
        val bx0 = cx - burners / 2
        for (k in 0 until burners) { val i = w.idx(bx0 + k, by); if (w.type[i].toInt() == Mat.AIR) { w.put(i, BURNER); bl.add(i) } }
        b.create(bl)
        val my = cy + skirtH + 1.5f
        // тросы цепляются за бока горелки, сопло сверху свободно
        b.createRope(floatArrayOf(cx - mouth + 1.5f, bx0 - 0.5f), floatArrayOf(my, by + 0.5f), 2, CABLE)
        b.createRope(floatArrayOf(cx + mouth - 1.5f, bx0 + burners + 0.5f), floatArrayOf(my, by + 0.5f), 2, CABLE)
        return env
    }

    val ROPE get() = Mat.id("rope")
    val CABLE get() = Mat.id("cable")
    /** Рисуется цепочкой гибких звеньев. */
    fun isRope(id: Int) = id == ROPE || id == CABLE
    val BURNER get() = Mat.id("burner")
}
