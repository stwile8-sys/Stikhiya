import ru.stikhiya.sim.*
fun burningCount(s: Simulator, w: World) = (0 until w.n).count { s.chemistry.isBurning(it) && Mat.flammable[w.type[it].toInt()] }
fun minO2(s: Simulator) = s.air.o2.minOrNull() ?: 0f
fun avgX(w: World, key: String): Float { val id = Mat.id(key); var sx = 0L; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { sx += i % w.w; c++ }; return if (c == 0) -1f else sx.toFloat() / c }
fun ignite(s: Simulator, w: World, x0: Int, x1: Int, y: Int, ticks: Int) { repeat(ticks) { for (x in x0..x1) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() } }
fun main() {
    // 1. Костёр на открытом воздухе: опилки под деревом
    run {
        val w = World(120, 80); box(w); val s = Simulator(w)
        fill(w, "sawdust", 40, 70, 80, 78); fill(w, "wood", 45, 60, 75, 69)
        for (k in 0 until 5) Brush.apply(w, Tool.DRAW, Mat.FLAME, 60, 69, 3)
        ignite(s, w, 40, 80, 69, 300)
        for (sec in listOf(10, 30, 60)) { run(s, if (sec == 10) 300 else if (sec == 30) 1200 else 1800)
            println("1) открытый костёр ${sec} с: горит ${burningCount(s, w)}, O2 мин ${"%.3f".format(minO2(s))}, CO2 ${count(w, "co2")}, дым ${count(w, "smoke")}, опилки ${count(w, "sawdust")}, дерево ${count(w, "wood")}") }
    }
    // 2. Закрытая печь
    run {
        val w = World(120, 80); box(w); val s = Simulator(w)
        fill(w, "brick", 30, 40, 90, 42); fill(w, "brick", 30, 70, 90, 72); fill(w, "brick", 30, 40, 32, 72); fill(w, "brick", 88, 40, 90, 72)
        fill(w, "sawdust", 33, 62, 87, 69)
        ignite(s, w, 33, 87, 61, 240)
        for (sec in listOf(5, 20, 60)) { run(s, if (sec == 5) 300 else if (sec == 20) 900 else 2400)
            val c = s.air.cellOf(w.idx(60, 55))
            println("2) закрытая печь ${sec} с: горит ${burningCount(s, w)}, O2 внутри ${"%.3f".format(s.air.o2[c])}, опилки ${count(w, "sawdust")}") }
    }
    // 3. Вентилятор и дым
    run {
        val w = World(160, 60); box(w); val s = Simulator(w)
        fill(w, "fan_r", 5, 20, 6, 40)
        fill(w, "smoke", 20, 25, 30, 35)
        val x0 = avgX(w, "smoke")
        run(s, 120); val x1 = avgX(w, "smoke")
        val c = s.air.cellOf(w.idx(20, 30))
        println("3) вентилятор: дым x ${"%.1f".format(x0)} → ${"%.1f".format(x1)} за 2 с, ветер ${"%.2f".format(s.air.windX(w.idx(20,30)))} м/с, давление ${"%.2f".format(s.air.pv[c])} кПа NaN=${nanCheck(w)}")
    }
    // 4. Порох в песке
    run {
        val w = World(120, 80); box(w); val s = Simulator(w)
        fill(w, "sand", 1, 50, 118, 78)
        fill(w, "gunpowder", 55, 62, 64, 68)
        // запал: дорожка пороха до поверхности
        fill(w, "gunpowder", 59, 50, 60, 61)
        val top0 = (0 until w.n).filter { w.type[it].toInt() == Mat.id("sand") }.minOf { it / w.w }
        var maxP = 0f; var minTop = 99
        repeat(240) { k -> if (k < 90) for (x in 58..61) { val i = w.idx(x, 49); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step(); maxP = maxOf(maxP, s.air.pv.maxOrNull() ?: 0f); val st = (0 until w.n).filter { w.type[it].toInt() == Mat.id("sand") }.minOf { it / w.w }; minTop = minOf(minTop, st) }
        println("4) порох: пороха осталось ${count(w, "gunpowder")}, макс давление ${"%.0f".format(maxP)} кПа, песок взлетал до y=$minTop (поверхность была $top0), дым ${count(w, "smoke")} NaN=${nanCheck(w)}")
    }
    // 5. Вода в вакууме
    run {
        val w = World(80, 60); box(w); val s = Simulator(w)
        fill(w, "water", 20, 40, 60, 58)
        repeat(600) { s.air.brushPressure(40, 30, 40, -15f); s.step() }
        val c = s.air.cellOf(w.idx(40, 45))
        val bp = s.air.boilPoint(Mat.WATER, 100f, w.idx(40, 45))
        println("5) вакуум: давление ${"%.1f".format(s.air.pv[c])} кПа, точка кипения ${"%.1f".format(bp)} °C, пара ${count(w, "steam")}, вода ${"%.1f".format(liquidVolume(w, "water"))} мл, T воды ${"%.1f".format(avgT2(w,"water"))}")
    }
    // 6. Скорость
    run {
        val w = World(480, 200); val s = Simulator(w)
        fill(w, "sand", 50, 20, 150, 80); fill(w, "water", 200, 20, 330, 120); fill(w, "stone", 0, 190, 479, 199)
        fill(w, "wood", 360, 150, 420, 189); fill(w, "fan_r", 10, 100, 11, 150)
        run(s, 120)
        val t0 = System.nanoTime(); run(s, 300); val ms = (System.nanoTime() - t0) / 1e6 / 300
        println("6) 480×200: ${"%.2f".format(ms)} мс на шаг  NaN=${nanCheck(w)}")
    }
}
fun avgT2(w: World, key: String): Float { val id = Mat.id(key); var s = 0.0; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { s += w.temp[i]; c++ }; return if (c == 0) -999f else (s / c).toFloat() }
