import ru.stikhiya.sim.*
fun main() {
    for (powder in listOf("sand", "sugar")) for (key in listOf("iron", "wood")) {
        val w = World(160, 140); val s = Simulator(w); fill(w, "stone", 0, 135, 159, 139); fill(w, powder, 0, 80, 159, 134)
        run(s, 120)
        val l = IntList(); val id = Mat.id(key)
        for (y in 10..30) for (x in 70..90) if ((x - 80) * (x - 80) + (y - 20) * (y - 20) <= 100) { val i = w.idx(x, y); w.put(i, id); l.add(i) }
        s.bodies.create(l)
        val ys = mutableListOf<String>()
        for (sec in listOf(1, 5, 15, 30)) { while (s.tick < 120 + sec * 60) s.step(); ys += "${sec}с y=${"%.1f".format(s.bodies.get(1)!!.body.position.y / B)}" }
        println("${Mat[id].name} в ${Mat[Mat.id(powder)].name.lowercase()}: ${ys.joinToString("  ")}  (центр круга; песок с y≈80, меньше y — выше)")
    }
}
