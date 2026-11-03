import ru.stikhiya.sim.*
fun main() {
    val w = World(200, 100); val s = Simulator(w); fill(w, "stone", 0, 95, 199, 99); fill(w, "water", 0, 60, 199, 94)
    mk(w, s, "wood", 80, 30, 119, 39)
    for (t in listOf(60, 120, 180, 300, 600, 900, 1500)) { while (s.tick < t) s.step(); val rb = s.bodies.get(1)!!
        println("t=$t центр y=${"%.1f".format(rb.body.position.y / B)} под водой ${"%.0f".format(rb.wetFraction*100)}% vy=${"%.2f".format(rb.body.linearVelocity.y)}") }
    for (y in 48..66) { val sb = StringBuilder("$y: "); for (x in 74..126) { val i = w.idx(x, y); val t = w.type[i].toInt()
        sb.append(when { w.owner[i].toInt() != 0 -> '#'; t == 0 -> '.'; t == Mat.WATER -> 'W'; else -> Mat[t].key[0] }) }; println(sb) }
}
