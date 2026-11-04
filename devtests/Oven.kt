import ru.stikhiya.sim.*
fun burningCount(s: Simulator, w: World): Int { var c = 0; for (i in 0 until w.n) if (s.chemistry.isBurning(i)) c++; return c }
fun flames(w: World, x0: Int, y0: Int, x1: Int, y1: Int): Int { var c = 0; for (y in y0..y1) for (x in x0..x1) if (w.type[w.idx(x, y)].toInt() == Mat.FLAME) c++; return c }
fun main() {
    // 1) закрытая кирпичная печь (стенки 3 см), внутри дрова, поджигаем
    for (closed in listOf(false, true)) {
        val w = World(200, 120); val s = Simulator(w); box(w)
        fill(w, "brick", 60, 40, 140, 42); fill(w, "brick", 60, 98, 140, 100)
        fill(w, "brick", 60, 40, 62, 100); fill(w, "brick", 138, 40, 140, 100)
        if (!closed) { for (y in 60..97) for (x in 138..140) w.put(w.idx(x, y), 0) }
        fill(w, "wood", 80, 80, 120, 90)
        for (k in 0 until 300) { for (y in 92..97) for (x in 82..118) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 3f } }; s.step() }
        for (sec in listOf(6, 8, 15, 30, 60)) { while (s.tick < sec * 60) s.step()
            var o2 = 1f; for (y in 50..90) for (x in 70..130) o2 = minOf(o2, s.air.oxygen(w.idx(x, y)))
            println("${if (closed) "закрытая" else "открытая"} t=${sec}с горит ${burningCount(s, w)} пламени ${flames(w, 63, 43, 137, 97)} дерева ${count(w, "wood")} угля ${count(w, "charcoal")} O2 мин ${"%.3f".format(o2)}") }
    }
    // 2) тушение водой: горящий брус, сверху льём воду
    run {
        val w = World(200, 120); val s = Simulator(w); box(w)
        fill(w, "wood", 80, 100, 120, 110)
        for (k in 0 until 300) { for (y in 112..118) for (x in 82..118) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 3f } }; s.step() }
        run(s, 300); println("брус горит: ${burningCount(s, w)}")
        fill(w, "water", 70, 60, 130, 90)
        for (sec in listOf(1, 3, 10)) { val t0 = s.tick; run(s, sec * 60 - (if (sec == 1) 0 else if (sec == 3) 60 else 180)); println("  вода +${sec}с горит ${burningCount(s, w)} пламени ${flames(w, 0, 0, 199, 118)} пара ${count(w, "steam")}") }
    }
}
