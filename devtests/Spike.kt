import ru.stikhiya.sim.*
fun liquidTop(w: World): String { var top = w.h; var topX = 0; for (y in 0 until w.h) { for (x in 0 until w.w) if (Mat.isLiquid(w.type[w.idx(x, y)].toInt())) { top = y; topX = x; break }; if (top < w.h) break }
    // типичный уровень: медиана верха по столбцам
    val tops = (0 until w.w).map { x -> (0 until w.h).firstOrNull { Mat.isLiquid(w.type[w.idx(x, it)].toInt()) } ?: w.h }.filter { it < w.h }.sorted()
    return "самая высокая капля y=$top (x=$topX), обычный уровень y=${tops.getOrNull(tops.size / 2)}" }
fun main() {
    val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
    fill(w, "water", 0, 170, 479, 196); fill(w, "oil", 0, 160, 479, 169)
    run(s, 120)
    // медные дуги и брусок падают в воду
    val l = IntList(); val cu = Mat.id("copper")
    for (x in 150..330) { val yc = 120 + ((x - 240) * (x - 240)) / 400; for (d in 0..5) { val i = w.idx(x, yc + d); if (w.type[i].toInt() == 0) { w.put(i, cu); l.add(i) } } }
    s.bodies.create(l); l.clear()
    for (x in 20..140) for (y in 130..137) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, cu); l.add(i) } }
    s.bodies.create(l)
    for (sec in listOf(2, 10, 30, 60)) { while (s.tick < 120 + sec * 60) s.step(); println("$sec с: ${liquidTop(w)}, тел ${s.bodies.count}") }
    val t0 = System.nanoTime(); run(s, 120); println("шаг ${"%.2f".format((System.nanoTime() - t0) / 1e6 / 120)} мс")
}
