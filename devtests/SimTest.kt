import ru.stikhiya.sim.*

fun count(w: World, key: String): Int { val id = Mat.id(key); var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) c++; return c }
fun avgY(w: World, key: String): Float { val id = Mat.id(key); var s = 0L; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { s += i / w.w; c++ }; return if (c == 0) -1f else s.toFloat() / c }
fun fill(w: World, key: String, x0: Int, y0: Int, x1: Int, y1: Int, t: Float? = null) { val id = Mat.id(key); for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); w.put(i, id); if (t != null) w.temp[i] = t } }
fun box(w: World) { for (x in 0 until w.w) { w.put(w.idx(x, w.h - 1), Mat.id("glass")) }; for (y in 0 until w.h) { w.put(w.idx(0, y), Mat.id("glass")); w.put(w.idx(w.w - 1, y), Mat.id("glass")) } }
fun nanCheck(w: World): Boolean { for (i in 0 until w.n) if (w.temp[i].isNaN() || w.amt[i].isNaN() || w.mix[i].isNaN()) { println("NaN at $i type=${Mat[w.type[i].toInt()].key} t=${w.temp[i]} amt=${w.amt[i]} mix=${w.mix[i]}"); return true }; return false }
fun run(s: Simulator, ticks: Int) { repeat(ticks) { s.step() } }
fun stats(w: World, vararg keys: String) = keys.joinToString("  ") { "$it=${count(w, it)}" }
fun liquidVolume(w: World, key: String): Float { val id = Mat.id(key); var v = 0f; for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t == id) v += w.amt[i]; if (Mat.isPowder(t) && w.mixId[i].toInt() == id) v += w.mix[i] }; return v }
fun soluteMass(w: World, key: String): Float { val id = Mat.id(key); var m = 0f; for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t == id) m += Mat.density[id] * Units.VOL * w.amt[i]; if (Mat.isLiquid(t) && w.mixId[i].toInt() == id) m += w.mix[i] * 1e-3f * w.amt[i] }; return m }
fun maxT(w: World): Float { var m = -1000f; for (i in 0 until w.n) if (w.temp[i] > m) m = w.temp[i]; return m }

fun main() {
    println("Веществ: ${Mat.count}")
    // 1. Песок тонет в воде
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "water", 1, 30, 58, 48)
        fill(w, "sand", 20, 5, 40, 12)
        run(s, 900)
        println("1) песок y=${"%.1f".format(avgY(w, "sand"))} вода y=${"%.1f".format(avgY(w, "water"))}  ${stats(w, "sand", "water")}  NaN=${nanCheck(w)}")
    }
    // 2. Масло всплывает
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "oil", 1, 30, 58, 40)
        fill(w, "water", 1, 5, 58, 15)
        run(s, 1500)
        println("2) масло y=${"%.1f".format(avgY(w, "oil"))} вода y=${"%.1f".format(avgY(w, "water"))}  NaN=${nanCheck(w)}")
    }
    // 3. Соль растворяется
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "water", 1, 20, 58, 48)
        fill(w, "salt", 25, 5, 34, 8)
        val m0 = soluteMass(w, "salt")
        run(s, 600); val c1 = count(w, "salt")
        run(s, 1800)
        println("3) соль зёрен: старт 40, через 10 с $c1, через 40 с ${count(w, "salt")}; масса соли ${"%.4f".format(m0 * 1000)} → ${"%.4f".format(soluteMass(w, "salt") * 1000)} г  NaN=${nanCheck(w)}")
    }
    // 4. Опилки + клей ПВА
    run {
        val w = World(60, 50); box(w); val s = Simulator(w); s.chemSpeed = 600f
        fill(w, "sawdust", 20, 35, 40, 48)
        fill(w, "glue", 22, 25, 38, 30)
        run(s, 300)
        var wet = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("sawdust") && w.mixId[i].toInt() == Mat.id("glue")) wet++
        println("4a) через 5 с: опилок с клеем $wet, ${stats(w, "glue", "sawdust", "glued_sawdust", "dried_glue")}")
        run(s, 3600 * 3)
        println("4b) через 3 мин (×600): ${stats(w, "glue", "sawdust", "glued_sawdust", "dried_glue")}  NaN=${nanCheck(w)}")
    }
    // 5. Вода закипает
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "iron", 10, 45, 50, 47, t = 600f)
        fill(w, "water", 10, 38, 50, 44)
        val v0 = liquidVolume(w, "water")
        for (k in 0 until 1200) { fill(w, "iron", 10, 47, 50, 47, t = 600f); s.step() }
        var steamMass = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.STEAM) steamMass += w.cellMass(i)
        println("5) вода ${"%.1f".format(v0)} → ${"%.1f".format(liquidVolume(w, "water"))} мл, пара ${count(w, "steam")} клеток (${"%.2f".format(steamMass * 1000)} г), макс T ${"%.0f".format(maxT(w))}  NaN=${nanCheck(w)}")
    }
    // 6. Дерево горит
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "wood", 20, 30, 40, 40)
        for (k in 0 until 600) { for (x in 22..38) { val i = w.idx(x, 44); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 3f } }; s.step() }
        val burningNow = (0 until w.n).count { s.chemistry.isBurning(it) }
        run(s, 1800)
        println("6) после 10 с огня горело $burningNow клеток; через 30 с ${stats(w, "wood", "charcoal", "ash", "smoke")}  макс T ${"%.0f".format(maxT(w))} NaN=${nanCheck(w)}")
    }
    // 7. Цемент + вода
    run {
        val w = World(60, 50); box(w); val s = Simulator(w); s.chemSpeed = 3600f
        fill(w, "cement", 20, 38, 40, 48)
        fill(w, "water", 20, 30, 40, 34)
        run(s, 600)
        println("7) через 10 с ×3600: ${stats(w, "cement", "water", "concrete")} NaN=${nanCheck(w)}")
    }
    // 8. Мокрый песок держит форму, вода стекает
    run {
        val w = World(60, 50); box(w); val s = Simulator(w)
        fill(w, "sand", 15, 30, 45, 48)
        fill(w, "water", 15, 20, 45, 25)
        run(s, 1200)
        var wet = 0; var sumFill = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("sand") && w.mixId[i].toInt() == Mat.WATER) { wet++; sumFill += w.mix[i] / 0.38f }
        println("8) мокрого песка $wet, средняя влажность ${"%.0f".format(100 * sumFill / maxOf(1, wet))}%, свободной воды ${count(w, "water")}  объём воды ${"%.1f".format(liquidVolume(w, "water"))}")
    }
    // 9. Скорость на полном мире
    run {
        val w = World(480, 200); val s = Simulator(w)
        fill(w, "sand", 50, 20, 150, 80); fill(w, "water", 200, 20, 330, 120); fill(w, "stone", 0, 190, 479, 199)
        fill(w, "wood", 360, 150, 420, 189); fill(w, "salt", 230, 5, 260, 15)
        run(s, 120)
        val t0 = System.nanoTime(); run(s, 300); val ms = (System.nanoTime() - t0) / 1e6 / 300
        println("9) 480×200: ${"%.2f".format(ms)} мс на шаг  NaN=${nanCheck(w)}")
    }
}
