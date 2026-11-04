import ru.stikhiya.sim.*
import kotlin.math.*
// Проверка законов из справочника «Физика. Основные законы и формулы»: измерено в игре vs формула
fun body(w: World, s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int): Int {
    val l = IntList(); val id = Mat.id(key)
    for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { w.put(i, id); l.add(i) } }
    s.bodies.create(l); return w.owner[l.a[0]].toInt()
}
fun avgAir(w: World, a: Int, b: Int): Double { var s = 0.0; var n = 0; for (y in a..b) for (x in a..b) { val i = w.idx(x, y); if (w.type[i].toInt() == 0) { s += w.temp[i]; n++ } }; return s / n }
fun pct(a: Double, b: Double) = "%.1f %%".format(100.0 * (a - b) / b)
fun line(name: String, got: Double, want: Double, unit: String) = println("%-48s игра %8.3f  формула %8.3f %s  (%s)".format(name, got, want, unit, pct(got, want)))
fun main() {
    // 1.1 Свободное падение тела: h = g t²/2 (тяжёлое, сопротивление воздуха мало)
    run { val w = World(100, 200); val s = Simulator(w); box(w)
        val id = body(w, s, "iron", 45, 10, 54, 19); val b = s.bodies.get(id)!!.body
        val y0 = b.position.y / B; repeat(24) { s.step() }  // 0,4 с
        line("Падение тела за 0,4 с, м", (b.position.y / B - y0) / 100.0, 9.81 * 0.4 * 0.4 / 2, "м")
        line("Скорость тела через 0,4 с (v = g t)", b.linearVelocity.y / B / 100.0, 9.81 * 0.4, "м/с") }
    // Свободное падение песчинки (клетки)
    run { val w = World(40, 200); val s = Simulator(w); box(w)
        w.put(w.idx(20, 5), Mat.id("sand")); var t = 0
        while (t < 200) { t++; s.step(); val y = (0 until w.h).first { w.type[w.idx(20, it)].toInt() == Mat.id("sand") }; if (y >= 105) break }
        line("Падение песчинки на 1 м, с", t / 60.0, sqrt(2 * 1.0 / 9.81), "с") }
    // 1.2 Тела разной массы падают одинаково (Галилей)
    run { val w = World(200, 200); val s = Simulator(w); box(w)
        val a = body(w, s, "lead", 40, 10, 49, 19); val c = body(w, s, "oak", 140, 10, 149, 19)
        repeat(20) { s.step() }
        val ya = s.bodies.get(a)!!.body.position.y / B; val yc = s.bodies.get(c)!!.body.position.y / B
        line("Свинец/дуб: отношение пройденного пути", (ya - 14.5) / (yc - 14.5), 1.0, "") }
    // 1.6 Физический маятник: T = 2π√(2L/3g) — стержень на оси за конец
    run { val w = World(200, 200); val s = Simulator(w); box(w)
        val L = 60; val id = body(w, s, "steel", 100, 20, 100 + L - 1, 21); s.bodies.hinge(id, 0, 100.5f, 21f)
        val b = s.bodies.get(id)!!.body; var last = 0f; var cross = ArrayList<Int>(); var prevV = 0f
        for (k in 0 until 600) { s.step(); val v = b.angularVelocity; if (k > 5 && prevV > 0f && v <= 0f || k > 5 && prevV < 0f && v >= 0f) cross += k; prevV = v }
        val per = if (cross.size >= 3) (cross[2] - cross[0]) / 60.0 else -1.0
        line("Маятник-стержень 0,6 м (большой размах), с", per, 2 * PI * sqrt(2 * 0.6 / (3 * 9.81)) * 1.18, "с") }
    // 1.7 Архимед: доля погружения = ρтела/ρводы
    for (key in listOf("pine", "oak", "cork", "ice")) run {
        val w = World(160, 160); val s = Simulator(w); box(w)
        fill(w, "water", 1, 80, 158, 158)
        val id = body(w, s, key, 70, 50, 89, 69); repeat(900) { s.step() }
        val b = s.bodies.get(id)!!.body
        var sum = 0.0; var mn = 1e9; var mx = -1e9
        repeat(600) { s.step(); val bottom = b.position.y / B + 10f
            // уровень воды рядом с телом (по обе стороны), с учётом неполной верхней клетки
            var lv = 0.0; var n = 0
            for (x in listOf(55, 60, 100, 105)) { val y = (0 until w.h).first { Mat.isLiquid(w.type[w.idx(x, it)].toInt()) }; lv += y + 1 - w.amt[w.idx(x, y)]; n++ }
            val sub = ((bottom - lv / n) / 20.0).coerceIn(0.0, 1.0); sum += sub; mn = minOf(mn, sub); mx = maxOf(mx, sub) }
        val sub = sum / 600
        println("   (качание: %.3f…%.3f)".format(mn, mx))
        line("Архимед: погружение «${Mat[Mat.id(key)].name}»", sub.toDouble(), Mat.density[Mat.id(key)] / 998.0, "") }
    // 2.1 Уравнение газа pV=nRT: нагрев замкнутого воздуха 20 → 313 °C (T вдвое) — давление вдвое
    run { val w = World(100, 100); val s = Simulator(w); box(w)
        fill(w, "stone", 20, 20, 79, 23); fill(w, "stone", 20, 76, 79, 79); fill(w, "stone", 20, 20, 23, 79); fill(w, "stone", 76, 20, 79, 79)
        repeat(60) { s.step() }
        for (y in 24..75) for (x in 24..75) w.temp[w.idx(x, y)] = 313.15f
        repeat(30) { s.step() }
        val p = s.air.pressure(w.idx(50, 50)) / 1000.0
        repeat(600) { s.step() }
        line("   …через 10 с (стенки остужают газ), кПа", s.air.pressure(w.idx(50, 50)) / 1000.0, 101.325 * (avgAir(w, 24, 75) + 273.15) / 293.15, "кПа")
        line("Газ: давление после нагрева T×2, кПа", p, 202.65, "кПа") }
    // 2.2 Теплота: смешение 1:1 воды 80 и 20 °C → 50 °C
    run { val w = World(60, 60); val s = Simulator(w); box(w)
        fill(w, "water", 1, 10, 58, 33, 80f); fill(w, "water", 1, 34, 58, 58, 20f)
        repeat(60 * 60) { s.step() }
        var sum = 0.0; var n = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.WATER) { sum += w.temp[i]; n++ }
        line("Смешение воды 80 + 20 °C (без потерь — 50)", sum / n, 50.0, "°C") }
    // 3.2 Закон Ома I = U/R: батарея 12 В, резистор 10 клеток последовательно (100 Ом/клетка) — 1000 Ом
    run { val w = World(60, 30); val s = Simulator(w)
        val plus = Mat.id("battery_plus"); val minus = Mat.id("battery_minus"); val cu = Mat.id("copper"); val r = Mat.id("resistor")
        w.put(w.idx(5, 10), plus); for (x in 6..14) w.put(w.idx(x, 10), cu); for (x in 15..24) w.put(w.idx(x, 10), r)
        for (x in 25..34) w.put(w.idx(x, 10), cu); w.put(w.idx(35, 10), minus)
        repeat(30) { s.step() }
        val i = s.electric.currentAt(w.idx(20, 10)).toDouble()
        line("Ом: ток через 1 кОм от 12 В, мА", i * 1000, 12.0, "мА")
        // Джоуль — Ленц: Q = I²R t — нагрев резистора за 10 с
        val c0 = (15..24).sumOf { w.heatCapacity(w.idx(it, 10)).toDouble() }
        val t0 = (15..24).map { w.temp[w.idx(it, 10)] }.average()
        repeat(600) { s.step() }
        val t1 = (15..24).map { w.temp[w.idx(it, 10)] }.average()
        println("Джоуль — Ленц: резистор получил %.3f Дж, I²Rt = %.3f Дж (часть уже ушла в воздух)".format((t1 - t0) * c0, 0.012 * 0.012 * 1000 * 10)) }
    // Упругость: резиновый мяч отскакивает выше стального
    for (key in listOf("rubber", "steel", "clay")) run {
        val w = World(100, 200); val s = Simulator(w); box(w); fill(w, "stone", 1, 190, 98, 198)
        val id = body(w, s, key, 45, 50, 54, 59); val b = s.bodies.get(id)?.body ?: return@run
        var minY = 1e9f; var hit = false
        for (k in 0 until 300) { s.step(); val y = b.position.y / B; if (b.linearVelocity.y < 0) hit = true; if (hit) minY = minOf(minY, y) }
        val drop = 185f - 55f; val up = 185f - minY
        println("Отскок «${Mat[Mat.id(key)].name}»: поднялся на %.0f %% высоты (e² ≈ %.0f %%)".format(if (hit) 100 * up / drop else 0f, 100 * Mat.bounce[Mat.id(key)] * Mat.bounce[Mat.id(key)]))
    }
}
