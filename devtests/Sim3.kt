import ru.stikhiya.sim.*
fun avgT2(w: World, key: String): Float { val id = Mat.id(key); var s = 0.0; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { s += w.temp[i]; c++ }; return if (c == 0) -999f else (s / c).toFloat() }
fun main() {
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "iron", 10, 45, 50, 47, t = 600f); fill(w, "water", 10, 38, 50, 44)
        for (sec in 1..6) { for (k in 0 until 1800) { fill(w, "iron", 10, 47, 50, 47, t = 600f); s.step() }
            var steamMass = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.STEAM) steamMass += w.cellMass(i)
            println("5) ${sec*30} с: вода ${"%.1f".format(liquidVolume(w, "water"))} мл, T воды ${"%.1f".format(avgT2(w, "water"))}, пар ${count(w, "steam")} кл (${"%.2f".format(steamMass*1000)} г) NaN=${nanCheck(w)}") }
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        for (k in 0 until 10) Brush.apply(w, Tool.DRAW, Mat.id("sawdust"), 30, 42, 6)
        run(s, 200)
        for (k in 0 until 60) { Brush.apply(w, Tool.DRAW, Mat.FLAME, 30, 44, 3); s.step() }
        val b = (0 until w.n).count { s.chemistry.isBurning(it) }
        val before = count(w, "sawdust")
        run(s, 1200)
        println("6a) опилки + огонь 1 с: загорелось $b, опилок $before → ${count(w, "sawdust")}, ${stats(w, "ash", "charcoal", "smoke")}")
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "wood", 20, 30, 40, 40)
        for (k in 0 until 7200) { for (x in 22..38) { val i = w.idx(x, 41); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() }
        println("6b) дерево после 120 с пламени: горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}, T низа ${"%.0f".format(w.temp[w.idx(30,40)])}")
        run(s, 3600)
        println("6c) ещё 60 с без поджига: горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}, ${stats(w, "wood", "charcoal", "ash", "smoke")}")
    }
    run {
        val w = World(60, 50); box(w); val s = Simulator(w); s.chemSpeed = 3600f
        fill(w, "sand", 15, 30, 45, 48); fill(w, "water", 15, 25, 45, 28)
        run(s, 600)
        println("12) мокрый песок ×3600, 10 с: воды ${"%.1f".format(liquidVolume(w, "water"))} мл")
        run(s, 3600)
        println("12b) ещё 60 с: воды ${"%.1f".format(liquidVolume(w, "water"))} мл, T песка ${"%.1f".format(avgT2(w, "sand"))}")
    }
}
