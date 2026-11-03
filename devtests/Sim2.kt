import ru.stikhiya.sim.*
fun avgT(w: World, key: String): Float { val id = Mat.id(key); var s = 0.0; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { s += w.temp[i]; c++ }; return if (c == 0) -999f else (s / c).toFloat() }
fun main() {
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "iron", 10, 45, 50, 47, t = 600f); fill(w, "water", 10, 38, 50, 44)
        for (sec in 1..8) { for (k in 0 until 1800) { fill(w, "iron", 10, 47, 50, 47, t = 600f); s.step() }
            var steamMass = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.STEAM) steamMass += w.cellMass(i)
            println("5) ${sec*30} с: вода ${"%.1f".format(liquidVolume(w, "water"))} мл, T воды ${"%.1f".format(avgT(w, "water"))}, пар ${count(w, "steam")} кл (${"%.2f".format(steamMass*1000)} г) Tвозд макс ${"%.0f".format(maxT(w))} NaN=${nanCheck(w)}") }
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "sawdust", 20, 38, 40, 48)
        for (k in 0 until 600) { for (x in 22..38) { val i = w.idx(x, 36); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() }
        val b = (0 until w.n).count { s.chemistry.isBurning(it) }
        run(s, 1200)
        println("6a) опилки: горело $b, через 20 с ${stats(w, "sawdust", "charcoal", "ash", "smoke", "flame")}")
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "wood", 20, 30, 40, 40)
        for (k in 0 until 3600) { for (x in 22..38) { val i = w.idx(x, 41); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() }
        val b = (0 until w.n).count { s.chemistry.isBurning(it) }
        println("6b) дерево после 60 с пламени: горит $b, T низа дерева ${"%.0f".format(w.temp[w.idx(30,40)])}, ${stats(w, "wood", "charcoal", "ash")}")
        run(s, 3600)
        println("6c) ещё 60 с без поджига: горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}, ${stats(w, "wood", "charcoal", "ash", "smoke")}")
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "alcohol", 10, 44, 50, 48)
        w.put(w.idx(30, 43), Mat.FLAME); w.amt[w.idx(30, 43)] = 3f
        run(s, 600)
        println("10) спирт + спичка: горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}, спирта ${"%.1f".format(liquidVolume(w, "alcohol"))} мл, пламени ${count(w, "flame")}")
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "water", 1, 30, 58, 48)
        fill(w, "sugar", 20, 25, 40, 29)
        run(s, 3600)
        println("11) сахар в холодной воде через 60 с: зёрен ${count(w, "sugar")}")
        for (x in 1..58) for (y in 30..48) { val i = w.idx(x, y); if (w.type[i].toInt() == Mat.WATER) w.temp[i] = 90f }
        run(s, 3600)
        println("11b) горячая вода: зёрен ${count(w, "sugar")}, масса сахара ${"%.1f".format(soluteMass(w, "sugar")*1000)} г")
    }
}
