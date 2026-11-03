import ru.stikhiya.sim.*
fun mk(w: World, s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int) { val l = IntList(); val id = Mat.id(key); for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, id); l.add(i) } }; s.bodies.create(l) }
fun bodyInfo(s: Simulator): String = (1..30).mapNotNull { s.bodies.get(it) }.joinToString("; ") { rb -> val p = rb.body.position; "№${rb.id}: x=${"%.0f".format(p.x / B)} y=${"%.1f".format(p.y / B)} угол=${"%.0f".format(Math.toDegrees(rb.body.angle.toDouble()))}° клеток=${rb.count} под водой ${"%.0f".format(rb.wetFraction * 100)}%" }
fun main() {
    run { val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99)
        mk(w, s, "wood", 80, 20, 119, 27); mk(w, s, "iron", 30, 10, 39, 19)
        run(s, 180); println("1) падение на пол: ${bodyInfo(s)}  (пол y=95; низ бревна должен быть ~95)") }
    run { val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99); fill(w, "water", 0, 60, 199, 94)
        mk(w, s, "wood", 80, 30, 119, 39); mk(w, s, "iron", 30, 30, 39, 39)
        run(s, 600); println("2) в воде: ${bodyInfo(s)}  (вода с y=60; дерево 10 клеток высотой — должно сидеть ~7 под водой)")
        var water = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.WATER) water += w.amt[i]; println("   воды ${"%.0f".format(water)} (было 7000)") }
    run { val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99); fill(w, "sand", 40, 70, 160, 94)
        run(s, 120); mk(w, s, "wood", 70, 20, 109, 27)
        run(s, 300); println("3) на песке: ${bodyInfo(s)}  (верх песка ~70)") }
    run { val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99)
        mk(w, s, "wood", 60, 80, 140, 84)
        run(s, 60)
        // подожгли середину доски: нагрели клетки выше самовоспламенения
        for (i in 0 until w.n) if (w.owner[i].toInt() == 1 && (i % w.w) in 95..105) w.temp[i] = 600f
        run(s, 1800)
        println("4) горящая доска: тел ${s.bodies.count}: ${bodyInfo(s)}  ${stats(w, "ash", "charcoal")}") }
    run { val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99)
        fill(w, "gunpowder", 100, 90, 110, 94); mk(w, s, "wood", 90, 70, 130, 74); run(s, 60)
        val y0 = s.bodies.get(1)!!.body.position.y / B
        for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("gunpowder") && (i % w.w) == 100) w.temp[i] = 500f
        var minY = 999f; repeat(180) { s.step(); s.bodies.get(1)?.let { minY = minOf(minY, it.body.position.y / B) } }
        println("5) взрыв под доской: доска была y=${"%.1f".format(y0)}, поднималась до y=${"%.1f".format(minY)}, пороха ${count(w, "gunpowder")}") }
    run { val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 195, 479, 199); fill(w, "water", 300, 150, 479, 194)
        for (k in 0 until 20) mk(w, s, if (k % 3 == 0) "iron" else "wood", 20 + k * 22, 20 + (k % 4) * 15, 35 + k * 22, 26 + (k % 4) * 15)
        run(s, 120); val t0 = System.nanoTime(); run(s, 300); val ms = (System.nanoTime() - t0) / 1e6 / 300
        println("6) 20 тел: ${"%.2f".format(ms)} мс на шаг, тел ${s.bodies.count}, NaN=${nanCheck(w)}") }
}
