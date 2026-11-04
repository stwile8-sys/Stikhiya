import ru.stikhiya.sim.*
// Воздушный шар из нейлона, стальных тросов и горелки: пока греется — привязан, потом отпускаем
fun main(args: Array<String>) {
    val r = args.getOrNull(0)?.toInt() ?: 60
    val w = World(480, 200); val s = Simulator(w); box(w)
    val e = Craft.buildBalloon(w, s.bodies, 240, 10, r)
    var mass = 0f; for (id in 1..400) s.bodies.get(id)?.let { mass += it.mass }
    s.bodies.toggleFix(e, 0, 240f, 10f)
    println("R=$r: масса ${"%.2f".format(mass * 1000)} г, оболочка ${"%.2f".format(s.bodies.get(e)!!.mass * 1000)} г, тел ${s.bodies.count}")
    var released = false; var y0 = 0f; var maxRise = 0f
    for (t in listOf(2, 5, 10, 15, 20, 30, 45, 60)) {
        while (s.tick < t * 60) { s.step()
            val lift = s.bodies.liftOf(e) / Units.GRAVITY
            if (!released && lift > mass * 1.05f) { s.bodies.toggleFix(e, 0, 240f, 10f); released = true; y0 = s.bodies.get(e)!!.body.position.y / B; println("  отпустили на ${"%.1f".format(s.tick / 60f)} с") }
            if (released) s.bodies.get(e)?.let { maxRise = maxOf(maxRise, y0 - it.body.position.y / B) } }
        val eb = s.bodies.get(e)
        if (args.size > 1) snap(w, "/tmp/bal_$t.png")
        println("t=${t}с оболочка y=${eb?.let { "%.1f".format(it.body.position.y / B) } ?: "нет"}  подъёмная сила ${"%.2f".format(s.bodies.liftOf(e) / Units.GRAVITY * 1000)} г  нейлона ${count(w, "balloon_nylon")}  пламени ${count(w, "flame")}")
    }
    println("поднялся максимум на ${"%.0f".format(maxRise)} см; шаг ${"%.2f".format(run { val t0 = System.nanoTime(); run(s, 60); (System.nanoTime() - t0) / 1e6 / 60 })} мс")
}
