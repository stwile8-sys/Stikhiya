import ru.stikhiya.sim.*
// Реактивная струя: стальная камера, внутри кран пропана и кран кислорода, справа сопло
fun main() {
    for (oxKey in listOf("oxygen", "air")) {
        val w = World(300, 120); val s = Simulator(w); box(w)
        fill(w, "steel", 40, 40, 100, 42); fill(w, "steel", 40, 78, 100, 80); fill(w, "steel", 40, 40, 42, 80)
        fill(w, "steel", 98, 40, 100, 56); fill(w, "steel", 98, 64, 100, 80) // сопло 57..63
        val tap = Mat.id("tap")
        for (y in 44..76 step 1) { w.put(w.idx(43, y), tap); w.mixId[w.idx(43, y)] = Mat.id("propane").toShort() }
        if (oxKey == "oxygen") for (y in 44..76 step 2) { w.put(w.idx(47, y), tap); w.mixId[w.idx(47, y)] = Mat.id("oxygen").toShort() }
        else for (y in 44..76 step 2) w.put(w.idx(42, y), 0) // дыры в задней стенке — воздухозаборник
        run(s, 120); println("пропана ${count(w, "propane")} кислорода ${count(w, "oxygen")} O2 ${s.air.oxygen(w.idx(70,60))} open=${s.air.open[s.air.cellOf(w.idx(70,60))]}")
        for (k in 0 until 60) { for (x in 50..95 step 5) { val q = w.idx(x, 76); if (!Mat.isSolid(w.type[q].toInt())) { w.put(q, Mat.FLAME); w.amt[q] = 4f } }; s.step() }
        var maxV = 0f; var maxLen = 0; var sumP = 0f
        for (k in 0 until 600) { s.step()
            val c = s.air.cellOf(w.idx(104, 60)); maxV = maxOf(maxV, s.air.vx[c]); sumP += s.air.pv[s.air.cellOf(w.idx(70, 60))]
            var len = 0; for (x in 101 until 299) { var f = false; for (y in 50..70) if (w.type[w.idx(x, y)].toInt() == Mat.FLAME) f = true; if (f) len = x - 100 }
            maxLen = maxOf(maxLen, len) }
        println("$oxKey: ветер у сопла max ${"%.1f".format(maxV)} м/с, давление в камере ср ${"%.1f".format(sumP / 600)} кПа, факел до ${maxLen} см, пламени ${count(w, "flame")}")
    }
}
