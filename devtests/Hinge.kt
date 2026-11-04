import ru.stikhiya.sim.*
fun plank(w: World, s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val l = IntList(); val id = Mat.id(key)
    for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, id); l.add(i) } }
    s.bodies.create(l); return w.owner[l.a[0]].toInt()
}
fun deg(r: Float) = Math.toDegrees(r.toDouble()).toInt()
fun main() {
    // 1) доска на шарнире у левого края: падает и повисает
    run { val w = World(200, 120); val s = Simulator(w); box(w)
        val id = plank(w, s, "wood", 100, 50, 140, 52); s.bodies.hinge(id, 0, 100.5f, 51.5f)
        for (t in listOf(30, 60, 120, 300)) { while (s.tick < t) s.step(); val b = s.bodies.get(id)!!.body
            println("1) t=${t / 60.0}с угол ${deg(b.angle)}°, центр ${"%.1f".format(b.position.x / B)},${"%.1f".format(b.position.y / B)}") } }
    // 2) дверца печи: каменная печь стоит, дверца на шарнире сверху, открываем рукой
    run { val w = World(200, 120); val s = Simulator(w); box(w)
        fill(w, "brick", 60, 60, 100, 62); fill(w, "brick", 60, 100, 100, 102); fill(w, "brick", 60, 60, 62, 102); fill(w, "brick", 98, 60, 100, 75)
        // дверца — вертикальная пластина справа (y 76..99), шарнир вверху
        val id = plank(w, s, "iron", 98, 76, 100, 99); s.bodies.hinge(id, 0, 99.5f, 76.5f)
        run(s, 120); val b = s.bodies.get(id)!!.body
        println("2) дверца закрыта: угол ${deg(b.angle)}°")
        s.bodies.grab(id, 99.5f, 98f)
        for (k in 0 until 120) { s.bodies.drag(99.5f + k * 0.3f, 98f - k * 0.1f); s.step() }
        println("   тянем рукой: угол ${deg(b.angle)}°"); s.bodies.release(); run(s, 240)
        println("   отпустили: угол ${deg(b.angle)}° (дверца должна вернуться к печи и остановиться)") }
    // 3) крепление: брусок прибит к стене и не падает; снимаем — падает
    run { val w = World(200, 120); val s = Simulator(w); box(w)
        val id = plank(w, s, "stone", 50, 30, 70, 40); s.bodies.toggleFix(id, 0, 60f, 35f); run(s, 120)
        val b = s.bodies.get(id)!!.body; println("3) прибит: y=${"%.1f".format(b.position.y / B)} (было 35.5)")
        s.bodies.toggleFix(id, 0, 60f, 35f); run(s, 120); println("   снят: y=${"%.1f".format(b.position.y / B)}") }
    // 4) рука поднимает камень
    run { val w = World(200, 120); val s = Simulator(w); box(w)
        val id = plank(w, s, "stone", 50, 100, 60, 110); run(s, 30); s.bodies.grab(id, 55f, 105f)
        for (k in 0 until 120) { s.bodies.drag(55f, 105f - k * 0.5f); s.step() }
        println("4) подняли камень: y=${"%.1f".format(s.bodies.get(id)!!.body.position.y / B)} (палец на 45)") }
}
