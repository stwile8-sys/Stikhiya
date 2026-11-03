import ru.stikhiya.sim.*
fun main() {
    for ((name, key) in listOf("длинная палка" to "wood", "бетонная плита" to "concrete")) {
        val w = World(300, 120); val s = Simulator(w); fill(w, "stone", 0, 115, 299, 119); fill(w, "water", 0, 60, 299, 114)
        // палка 100×3 чуть наклонена: рисуем ступенькой
        val l = IntList(); val id = Mat.id(key)
        for (k in 0 until 100) for (d in 0 until 3) { val x = 100 + k; val y = 30 + k / 25 + d; val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, id); l.add(i) } }
        s.bodies.create(l)
        val sb = StringBuilder("$name: ")
        var maxW = 0f
        for (t in 1..900) { s.step(); val rb = s.bodies.get(1)!!; maxW = maxOf(maxW, kotlin.math.abs(rb.body.angularVelocity))
            if (t % 90 == 0) sb.append("${t / 60}с[y=${"%.0f".format(rb.body.position.y / B)} ${"%.0f".format(Math.toDegrees(rb.body.angle.toDouble()))}° ${"%.0f".format(rb.wetFraction * 100)}%] ") }
        println(sb.toString() + " макс ω=${"%.2f".format(maxW)} рад/с")
    }
}
