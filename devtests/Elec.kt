import ru.stikhiya.sim.*
fun main() {
    // 1) батарея — медный провод — лампа — провод — батарея
    run { val w = World(200, 100); val s = Simulator(w); box(w)
        fill(w, "battery_plus", 20, 50, 21, 51); fill(w, "battery_minus", 20, 60, 21, 61)
        fill(w, "copper", 22, 50, 100, 50); fill(w, "copper", 22, 61, 100, 61); fill(w, "copper", 100, 51, 100, 54); fill(w, "copper", 100, 57, 100, 60)
        fill(w, "lamp", 100, 55, 100, 56)
        for (t in listOf(10, 60, 180)) { while (s.tick < t) s.step(); val l = w.idx(100, 55)
            println("лампа t=${t / 60f}с: U у лампы ${"%.2f".format(s.electric.phi[w.idx(100, 54)] - s.electric.phi[w.idx(100, 57)])} В, ток ${"%.2f".format(s.electric.currentAt(l))} А, T нити ${w.temp[l].toInt()} °C, провод T ${w.temp[w.idx(60, 50)].toInt()}") } }
    // 2) электролиз солёной воды
    run { val w = World(200, 100); val s = Simulator(w); box(w)
        fill(w, "glass", 40, 90, 120, 92); fill(w, "glass", 40, 50, 42, 92); fill(w, "glass", 118, 50, 120, 92)
        fill(w, "seawater", 43, 60, 117, 89); for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("seawater")) { w.put(i, Mat.WATER); w.mixId[i] = Mat.id("salt").toShort(); w.mix[i] = 0.035f }
        fill(w, "graphite", 55, 40, 56, 85); fill(w, "graphite", 104, 40, 105, 85)
        fill(w, "battery_plus", 55, 38, 56, 39); fill(w, "battery_minus", 104, 38, 105, 39)
        var h = 0; var o = 0; for (k in 0 until 600) { s.step(); h = maxOf(h, count(w, "hydrogen")); o = maxOf(o, count(w, "oxygen")) }
        println("электролиз 10 с: водорода макс $h, кислорода макс $o, ток ${"%.3f".format(s.electric.currentAt(w.idx(55, 70)))} А") }
    // 3) короткое замыкание через нихром: спираль раскаляется
    run { val w = World(200, 100); val s = Simulator(w); box(w)
        fill(w, "battery_plus", 20, 50, 20, 50); fill(w, "battery_minus", 30, 50, 30, 50); fill(w, "nichrome", 21, 50, 29, 50)
        run(s, 600); println("нихром 10 с: T ${w.temp[w.idx(25, 50)].toInt()} °C, ток ${"%.2f".format(s.electric.currentAt(w.idx(25, 50)))} А") }
    // 4) молния
    run { val w = World(200, 100); val s = Simulator(w); box(w); fill(w, "wood", 90, 90, 110, 98); s.electric.lightning(100, 5); run(s, 300)
        println("молния: дерево ${count(w, "wood")} из 189, горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}") }
    val w = World(480, 200); val s = Simulator(w); box(w); fill(w, "battery_plus", 20, 50, 21, 51); fill(w, "battery_minus", 20, 60, 21, 61)
    fill(w, "copper", 22, 50, 400, 50); fill(w, "copper", 22, 61, 400, 61); fill(w, "copper", 400, 51, 400, 60); fill(w, "iron", 100, 100, 300, 150)
    run(s, 60); val t0 = System.nanoTime(); run(s, 120); println("шаг с проводами ${"%.2f".format((System.nanoTime() - t0) / 1e6 / 120)} мс")
}
