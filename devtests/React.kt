import ru.stikhiya.sim.*

/** Реакции: каждая сцена — маленький опыт, печатаем, что получилось. */
fun scene(): Simulator { val w = World(160, 100); val s = Simulator(w); box(w); return s }
fun cup(w: World, x0: Int, x1: Int, y0: Int) { fill(w, "glass", x0, y0, x0, 98); fill(w, "glass", x1, y0, x1, 98) }
fun gasSum(w: World, key: String): Float { val id = Mat.id(key); var a = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == id) a += w.amt[i]; return a }
fun maxP(s: Simulator): Float = s.air.pv.maxOrNull() ?: 0f

fun main() {
    var fails = 0
    fun check(name: String, ok: Boolean, info: String) { println((if (ok) "OK   " else "FAIL ") + name + ": " + info); if (!ok) fails++ }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "vinegar", 61, 85, 99, 98); fill(w, "soda", 75, 70, 85, 74)
        var co2 = 0; for (k in 0 until 6) { run(s, 60); co2 = maxOf(co2, count(w, "co2") + count(w, "foam")) }
        check("сода+уксус", co2 > 20 && count(w, "soda") < 40, "co2+пена макс=$co2 соды осталось=${count(w, "soda")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "hcl", 61, 85, 99, 98); fill(w, "zinc", 75, 95, 85, 98)
        var h = 0; for (k in 0 until 5) { run(s, 60); h = maxOf(h, count(w, "hydrogen")) }
        check("цинк+соляная", h > 5 && count(w, "zinc") < 40, "водород макс=$h цинка=${count(w, "zinc")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "hcl", 61, 85, 99, 98); fill(w, "copper", 75, 95, 85, 98); run(s, 300)
        check("медь+соляная не реагирует", count(w, "copper") == 44 && count(w, "hydrogen") == 0, "меди=${count(w, "copper")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "water", 61, 85, 99, 98); fill(w, "sodium", 78, 86, 82, 89)
        var h = 0; var fl = 0; var tm = 0f; for (k in 0 until 6) { run(s, 60); h = maxOf(h, count(w, "hydrogen")); fl = maxOf(fl, count(w, "flame")); tm = maxOf(tm, maxT(w)) }
        check("натрий+вода", h + fl > 5 && count(w, "sodium") + count(w, "molten_sodium") < 20, "H2=$h огонь=$fl Tmax=$tm натрия=${count(w, "sodium") + count(w, "molten_sodium")} щёлочь=${count(w, "lye")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "quicklime", 61, 90, 99, 98); fill(w, "water", 61, 80, 99, 89)
        var tm = 0f; for (k in 0 until 10) { run(s, 60); tm = maxOf(tm, maxT(w)) }
        check("гашение извести", tm > 60f && count(w, "slaked_lime") > 50, "Tmax=$tm гашёной=${count(w, "slaked_lime")} пар=${count(w, "steam")}") }

    run { val s = scene(); val w = s.world; s.chemSpeed = 3600f; cup(w, 60, 100, 60)
        fill(w, "water", 61, 85, 99, 98); for (i in 0 until w.n) if (w.type[i].toInt() == Mat.WATER) { w.mixId[i] = Mat.id("sugar").toShort(); w.mix[i] = 0.3f }
        for (k in 0 until w.n) { w.temp[k] = 30f }
        fill(w, "yeast", 70, 84, 90, 84, 30f)
        var co2 = 0; for (k in 0 until 20) { run(s, 60); co2 = maxOf(co2, count(w, "co2")) }
        check("брожение", count(w, "braga") > 20, "браги=${count(w, "braga")} co2 макс=$co2") }

    run { val s = scene(); val w = s.world; fill(w, "brick", 50, 90, 110, 98)
        fill(w, "thermite", 70, 80, 90, 89); fill(w, "iron", 50, 70, 50, 70)
        fill(w, "thermite", 70, 79, 90, 79, 1600f)
        var molten = 0; var tm = 0f; for (k in 0 until 6) { run(s, 60); molten = maxOf(molten, count(w, "molten_iron")); tm = maxOf(tm, maxT(w)) }
        check("термит", molten > 30, "жидкого железа макс=$molten Tmax=$tm термита=${count(w, "thermite")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "water", 61, 90, 99, 98); fill(w, "lava", 75, 75, 85, 80)
        run(s, 300)
        check("лава в воде → обсидиан", count(w, "obsidian") > 10, "обсидиана=${count(w, "obsidian")} камня=${count(w, "stone")} лавы=${count(w, "lava")}") }

    run { val s = scene(); val w = s.world; fill(w, "styrofoam", 60, 90, 100, 98)
        fill(w, "acetone", 70, 80, 90, 85); run(s, 300)
        check("ацетон+пенопласт", count(w, "styrofoam") < 300, "пенопласта=${count(w, "styrofoam")} из 369") }

    run { val s = scene(); val w = s.world
        val sw = Mat.id("seawater"); for (y in 90..98) for (x in 61..99) { val i = w.idx(x, y); w.put(i, sw); Reactions.onSpawn(w, i) }
        check("морская вода — это вода с солью", count(w, "water") == 351 && w.mixId[w.idx(70, 95)].toInt() == Mat.id("salt"), "воды=${count(w, "water")}") }

    run { val s = scene(); val w = s.world; fill(w, "brick", 40, 95, 120, 98)
        fill(w, "clay", 70, 85, 90, 94, 950f); fill(w, "brick", 60, 70, 100, 70)
        for (k in 0 until 600) { for (y in 85..94) for (x in 70..90) { val i = w.idx(x, y); if (w.temp[i] < 1000f) w.temp[i] = 1000f }; s.step() }
        check("обжиг глины", count(w, "ceramic") > 100, "керамики=${count(w, "ceramic")} глины=${count(w, "clay")}") }

    run { val s = scene(); val w = s.world; fill(w, "hydrogen", 60, 40, 90, 70); fill(w, "flame", 75, 71, 76, 72)
        var p = 0f; var fl = 0; for (k in 0 until 60) { s.step(); p = maxOf(p, maxP(s)); fl = maxOf(fl, count(w, "flame")) }
        check("водород взрывается", p > 20f && count(w, "hydrogen") < 300, "давление макс=$p кПа огонь=$fl водорода=${count(w, "hydrogen")}") }

    run { val s = scene(); val w = s.world
        // облако муки падает на огонь
        for (y in 20..60) for (x in 70..90) if ((x + y) % 2 == 0) w.put(w.idx(x, y), Mat.id("flour"))
        fill(w, "wood", 70, 92, 90, 98, 600f)
        var p = 0f; var fl = 0; for (k in 0 until 120) { s.step(); p = maxOf(p, maxP(s)); fl = maxOf(fl, count(w, "flame")) }
        check("мучная пыль", fl > 50, "огонь макс=$fl давление=$p муки=${count(w, "flour")}") }

    run { val s = scene(); val w = s.world; fill(w, "brick", 40, 95, 120, 98)
        fill(w, "magnesium", 70, 90, 90, 94); fill(w, "magnesium", 70, 89, 90, 89, 700f)
        var tm = 0f; for (k in 0 until 8) { run(s, 60); tm = maxOf(tm, maxT(w)) }
        check("магний горит", tm > 2000f && count(w, "magnesium") < 100, "Tmax=$tm оксида=${count(w, "magnesia")} магния=${count(w, "magnesium")}") }

    run { val s = scene(); val w = s.world; Brush.tapSource = Mat.id("water")
        Brush.apply(w, Tool.DRAW, Mat.id("tap"), 80, 20, 0)
        fill(w, "drain", 40, 97, 120, 97)
        run(s, 300); val water = count(w, "water")
        check("кран льёт, сток забирает", water in 5..400, "воды в мире=$water") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "flour", 61, 92, 99, 98); fill(w, "water", 61, 85, 99, 91); run(s, 300)
        check("тесто", count(w, "dough") > 50, "теста=${count(w, "dough")} муки=${count(w, "flour")}") }

    run { val s = scene(); val w = s.world; fill(w, "brick", 40, 95, 120, 98)
        fill(w, "wood", 70, 88, 90, 94, 500f); fill(w, "oxygen", 70, 80, 90, 87)
        var fl = 0; for (k in 0 until 60) { s.step(); fl = maxOf(fl, count(w, "flame")) }
        check("кислород раздувает", fl > 20, "огонь=$fl") }

    run { val s = scene(); val w = s.world; s.chemSpeed = 3600f; cup(w, 60, 100, 60)
        fill(w, "water", 61, 90, 99, 98); fill(w, "iron", 61, 89, 99, 89)
        s.chemSpeed = 3600f * 20f; run(s, 1200)
        check("ржавление", count(w, "rust") > 0, "ржавчины=${count(w, "rust")}") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "h2o2", 61, 85, 99, 98); fill(w, "yeast", 70, 80, 90, 82)
        var o = 0; var fo = 0; for (k in 0 until 4) { run(s, 60); o = maxOf(o, count(w, "oxygen")); fo = maxOf(fo, count(w, "foam")) }
        check("перекись+дрожжи → кислород и пена", o + fo > 6, "кислород=$o пена=$fo") }

    run { val s = scene(); val w = s.world; cup(w, 60, 100, 60)
        fill(w, "water", 61, 85, 99, 98); for (i in 0 until w.n) if (w.type[i].toInt() == Mat.WATER) { w.mixId[i] = Mat.id("copper_sulfate").toShort(); w.mix[i] = 0.2f }
        fill(w, "iron", 75, 90, 85, 94); run(s, 600)
        check("железо в медном купоросе", count(w, "copper") > 5, "меди=${count(w, "copper")} железа=${count(w, "iron")}") }

    run { val s = scene(); val w = s.world; fill(w, "brick", 40, 95, 120, 98)
        fill(w, "popcorn_kernel", 70, 90, 90, 94, 200f); run(s, 120)
        check("попкорн", count(w, "popcorn") > 100, "попкорна=${count(w, "popcorn")} зёрен=${count(w, "popcorn_kernel")}") }

    run { val s = scene(); val w = s.world; fill(w, "glass", 60, 95, 100, 98)
        fill(w, "glycerin", 70, 92, 90, 94); fill(w, "permanganate", 72, 88, 88, 91)
        var fl = 0; for (k in 0 until 20) { run(s, 60); fl = maxOf(fl, count(w, "flame")) }
        check("марганцовка+глицерин вспыхивают", fl > 5, "огонь=$fl") }

    var bad = false
    println(if (fails == 0) "Все реакции работают" else "Провалено: $fails")
}
