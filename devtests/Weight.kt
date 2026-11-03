import ru.stikhiya.sim.*
fun main() {
    for (key in listOf("wood", "concrete", "iron")) {
        val w = World(120, 140); val s = Simulator(w); fill(w, "stone", 0, 135, 119, 139); fill(w, "sand", 0, 90, 119, 134)
        run(s, 120)
        val top = (0 until w.n).filter { w.type[it].toInt() == Mat.id("sand") }.minOf { it / w.w }
        mk(w, s, key, 50, 5, 69, 14)
        var landed = -1
        repeat(240) { t -> s.step(); val rb = s.bodies.get(1)!!; if (landed < 0 && rb.body.linearVelocity.y < 0.5f && t > 10) landed = t }
        val rb = s.bodies.get(1)!!
        val bottom = rb.body.position.y / B + 5f
        println("${Mat[Mat.id(key)].name}: масса ${"%.0f".format(rb.mass * 1000)} г, упало за ${landed / 60f} с, низ на y=${"%.1f".format(bottom)}, песок был с y=$top → вмятина ${"%.1f".format(bottom - top)} клеток")
    }
}
