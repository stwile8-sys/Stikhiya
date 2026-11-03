import ru.stikhiya.sim.*
fun colTop(w: World, x0: Int, x1: Int): String { val t = (x0..x1).map { x -> (0 until w.h).firstOrNull { Mat.isLiquid(w.type[w.idx(x, it)].toInt()) } ?: w.h }; return "${t.minOrNull()}..${t.maxOrNull()}" }
fun main(args: Array<String>) {
    if (args.isNotEmpty()) { tank(); return }
    // 1) U-трубка: левое колено полное, правое пустое, связь снизу под стенкой
    run {
        val w = World(480, 200); val s = Simulator(w); box(w)
        fill(w, "stone", 100, 190, 220, 198); fill(w, "stone", 100, 60, 104, 190); fill(w, "stone", 216, 60, 220, 190)
        fill(w, "stone", 150, 60, 170, 180)  // перегородка, снизу проход 181..189
        fill(w, "water", 105, 70, 149, 189); fill(w, "water", 150, 181, 170, 189); fill(w, "water", 171, 181, 215, 189)
        for (t in listOf(30, 60, 120, 300, 600)) { while (s.tick < t) s.step(); println("U t=${t / 60.0}с лево ${colTop(w, 105, 149)} право ${colTop(w, 171, 215)} воды ${"%.0f".format(liquidVolume(w, "water"))}") }
    }
    tank()
}
fun tank() {
    run {
        val w = World(480, 200); val s = Simulator(w); box(w)
        fill(w, "stone", 50, 40, 54, 150); fill(w, "stone", 120, 40, 124, 150); fill(w, "stone", 50, 151, 124, 155)
        fill(w, "water", 55, 50, 119, 150); for (x in 120..124) w.put(w.idx(x, 140), 0)
        for (t in listOf(20, 60, 180, 600)) { while (s.tick < t) s.step()
            val jet = (125..300).filter { x -> (100..198).any { Mat.isLiquid(w.type[w.idx(x, it)].toInt()) } }
            println("бак t=${t / 60.0}с уровень ${colTop(w, 55, 119)} вода снаружи x ${jet.minOrNull()}..${jet.maxOrNull()}") }
    }
    // 3) прорыв плотины
    run {
        val w = World(480, 200); val s = Simulator(w); box(w)
        fill(w, "water", 1, 100, 80, 198)
        for (t in listOf(15, 30, 60, 90, 120, 180, 300, 600, 1200)) { while (s.tick < t) s.step()
            println("плотина t=${t / 60.0}с у левой ${colTop(w, 1, 10)} середина ${colTop(w, 200, 280)} у правой ${colTop(w, 469, 478)}") }
        val t0 = System.nanoTime(); run(s, 120); println("шаг ${"%.2f".format((System.nanoTime() - t0) / 1e6 / 120)} мс")
    }
    // 4) колокол: перевёрнутая чашка с воздухом под водой
    run {
        val w = World(480, 200); val s = Simulator(w); box(w)
        fill(w, "water", 1, 100, 478, 198); fill(w, "stone", 200, 150, 260, 153); fill(w, "stone", 200, 154, 203, 175); fill(w, "stone", 257, 154, 260, 175)
        for (y in 154..170) for (x in 204..256) w.put(w.idx(x, y), 0)
        for (t in listOf(60, 300)) { while (s.tick < t) s.step(); var a = 0; for (y in 154..175) for (x in 204..256) if (w.type[w.idx(x, y)].toInt() == 0) a++; println("колокол t=${t / 60}с воздуха $a (было 901)") }
    }
}
