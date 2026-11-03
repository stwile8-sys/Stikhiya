import ru.stikhiya.sim.*
fun main() {
    val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99); fill(w, "water", 0, 60, 199, 94)
    mk(w, s, "wood", 80, 30, 119, 39)
    for (t in listOf(30, 60, 90, 120)) {
        while (s.tick < t) s.step()
        val rb = s.bodies.get(1)!!
        val first = rb.stamped.a[0]; val n = rb.stamped.n
        val i0 = rb.stamped.a[0]; val y = i0 / w.w; val x = i0 % w.w
        println("t=$t y=${"%.1f".format(rb.body.position.y / B)} vy=${"%.2f".format(rb.body.linearVelocity.y)} stamped=$n под водой ${"%.0f".format(rb.wetFraction*100)}% первая клетка ($x,$y) слева=${Mat[w.type[w.idx(x-1,y)].toInt()].key} строка ${(0 until 5).map { rb.stamped.a[it] }}")
    }
}
