import ru.stikhiya.sim.*

/** Семена на влажной земле прорастают, трава растёт и расползается; на сухой земле — нет; без воды вянут; сухое горит. */
fun wetSoil(w: World, x0: Int, x1: Int, y0: Int, y1: Int, fillFrac: Float) {
    fill(w, "soil", x0, y0, x1, y1)
    for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); if (fillFrac > 0f) { w.mixId[i] = Mat.WATER.toShort(); w.mix[i] = Mat.porosity[Mat.id("soil")] * fillFrac } }
}
fun main() {
    var fails = 0
    fun check(name: String, ok: Boolean, info: String) { println((if (ok) "OK   " else "FAIL ") + name + ": " + info); if (!ok) fails++ }
    val w = World(160, 100); val s = Simulator(w); box(w); s.chemSpeed = 3600f
    wetSoil(w, 1, 78, 85, 98, 0.6f)
    wetSoil(w, 82, 158, 85, 98, 0f)
    for (x in listOf(10, 25, 40, 60, 90, 110, 130)) w.put(w.idx(x, 84), Mat.id("seeds"))
    fill(w, "grass", 70, 84, 72, 84); fill(w, "grass", 140, 84, 142, 84)
    for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("grass")) { w.mix[i] = 0.8f; w.life[i] = 3f }
    val t0 = System.nanoTime()
    for (min in 1..4) {
        run(s, 3600)
        var tallest = 200; for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t == Mat.id("stem") && i % w.w < 80) tallest = minOf(tallest, i / w.w) }
        println("мин $min: стебель=${count(w,"stem")} листья=${count(w,"leaf")} трава=${count(w,"grass")} сухое=${count(w,"dry_plant")} семян=${count(w,"seeds")} высота=${84 - tallest} см")
    }
    println("шаг ${(System.nanoTime() - t0) / 1e6 / 14400} мс")
    var grassL = 0; var grassR = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("grass")) { if (i % w.w < 80) grassL++ else grassR++ }
    check("проросли на влажной", count(w, "stem") > 20, "стеблей ${count(w, "stem")}")
    check("на сухой земле трава сохла", grassR == 0, "трава справа $grassR, слева $grassL")
    // жжём
    fill(w, "flame", 5, 60, 75, 62); for (i in 0 until w.n) if (w.type[i].toInt() == Mat.FLAME) w.amt[i] = 6f
    for (k in 0 until w.n) if (w.type[k].toInt() == Mat.id("dry_plant")) w.temp[k] = 400f
    run(s, 600)
    println("после огня: стебель=${count(w,"stem")} сухое=${count(w,"dry_plant")} пепел=${count(w,"ash")}")
    println(if (fails == 0) "Растения работают" else "Провалено: $fails")
}
