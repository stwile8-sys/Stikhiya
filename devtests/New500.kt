import ru.stikhiya.sim.*
// Проверка новых веществ 259–500: реакции по тегам, цвет пламени, рост, вакуум, выключатель, Пельтье
fun cnt(w: World, k: String) = count(w, k)
fun scene(block: (World, Simulator) -> Unit, ticks: Int, check: (World, Simulator) -> String) {
    val w = World(100, 80); val s = Simulator(w); box(w); block(w, s); repeat(ticks) { s.step() }; println(check(w, s))
}
fun main() {
    scene({ w, _ -> fill(w, "glass", 30, 60, 70, 78); for (y in 61..77) for (x in 31..69) w.put(w.idx(x, y), 0)
        fill(w, "hcl", 31, 66, 69, 77); fill(w, "dolomite", 45, 61, 55, 65) }, 600) { w, _ -> "доломит + кислота: доломита ${cnt(w, "dolomite")} CO₂ ${cnt(w, "co2")} пены ${cnt(w, "foam")}" }
    scene({ w, _ -> fill(w, "water", 20, 50, 80, 78); fill(w, "cesium", 48, 45, 52, 49) }, 300) { w, _ -> "цезий в воде: цезия ${cnt(w, "cesium")} водорода ${cnt(w, "hydrogen")} огня ${cnt(w, "flame")} щёлочи ${cnt(w, "lye")}" }
    scene({ w, _ -> fill(w, "water", 20, 60, 80, 78); fill(w, "calcium_carbide", 45, 55, 55, 59) }, 600) { w, _ -> "карбид + вода: ацетилена ${cnt(w, "acetylene")} извести ${cnt(w, "slaked_lime")}" }
    scene({ w, _ -> fill(w, "hf", 20, 60, 80, 78); fill(w, "glass", 45, 52, 55, 59) }, 1800) { w, _ -> "стекло в плавиковой: стекла ${cnt(w, "glass") - 356} (было 88)" }
    scene({ w, _ -> fill(w, "aqua_regia", 1, 60, 98, 78); fill(w, "gold", 45, 52, 55, 59) }, 1800) { w, _ -> "золото в царской водке: золота ${cnt(w, "gold")} (было 88)" }
    scene({ w, _ -> fill(w, "hcl", 1, 60, 98, 78); fill(w, "gold", 45, 52, 55, 59) }, 1200) { w, _ -> "золото в соляной: золота ${cnt(w, "gold")} (не растворяется)" }
    scene({ w, _ -> fill(w, "hno3", 1, 60, 98, 78); fill(w, "copper", 45, 52, 55, 59) }, 1200) { w, _ -> "медь в азотной: меди ${cnt(w, "copper")} бурого газа ${cnt(w, "no2")}" }
    scene({ w, _ -> fill(w, "lye", 20, 60, 80, 78); fill(w, "sunflower_oil", 20, 55, 80, 59) }, 1200) { w, _ -> "масло + щёлочь: мыла ${cnt(w, "liquid_soap")}" }
    scene({ w, _ -> fill(w, "bleach", 20, 60, 80, 78); fill(w, "vinegar", 20, 55, 80, 59) }, 300) { w, _ -> "хлорка + уксус: хлора ${cnt(w, "chlorine")}" }
    scene({ w, _ -> fill(w, "cola", 20, 50, 80, 78); fill(w, "mints", 48, 45, 52, 49) }, 300) { w, _ -> "кола + драже: пены ${cnt(w, "foam")} CO₂ ${cnt(w, "co2")}" }
    scene({ w, _ -> fill(w, "kclo3", 40, 70, 60, 78); fill(w, "powdered_sugar", 40, 65, 60, 69); fill(w, "stone", 40, 64, 60, 64); for (x in 40..60) w.temp[w.idx(x, 65)] = 300f }, 300) { w, _ -> "бертолетова соль + сахар (нагрев): соли ${cnt(w, "kclo3")} огня ${cnt(w, "flame")}" }
    // цвет пламени у соли стронция
    scene({ w, _ -> fill(w, "wood", 40, 70, 60, 78, 450f); fill(w, "srcl2", 40, 69, 60, 69) }, 240) { w, _ ->
        var red = 0; var all = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.FLAME) { all++; if (Mat[w.mixId[i].toInt()].key == "srcl2") red++ }; "огонь у хлорида стронция: красных $red из $all" }
    // вакуум
    scene({ w, _ -> fill(w, "glass", 30, 30, 70, 70); for (y in 31..69) for (x in 31..69) w.put(w.idx(x, y), Mat.id("vacuum")); fill(w, "water", 31, 60, 69, 69) }, 300) { w, _ -> "вакуум: вакуума ${cnt(w, "vacuum")} пара ${cnt(w, "steam")} воды ${cnt(w, "water")} T воды ${"%.1f".format((0 until w.n).filter { w.type[it].toInt() == Mat.WATER }.map { w.temp[it] }.average())}" }
    // рост: водоросли и саженец
    scene({ w, s -> s.chemSpeed = 3600f; fill(w, "water", 1, 50, 98, 78); fill(w, "algae", 50, 55, 51, 56) }, 3600) { w, _ -> "водоросли (1 мин ×3600): ${cnt(w, "algae")} кислорода ${cnt(w, "oxygen")}" }
    scene({ w, s -> s.chemSpeed = 3600f; fill(w, "soil", 1, 70, 98, 78); fill(w, "water", 1, 69, 98, 69); fill(w, "sapling", 50, 68, 50, 68) }, 3600) { w, _ -> "саженец: дерева ${cnt(w, "wood")} листьев ${cnt(w, "leaf")}" }
    // выключатель и Пельтье
    run { val w = World(60, 30); val s = Simulator(w)
        w.put(w.idx(5, 10), Mat.id("battery_plus")); for (x in 6..14) w.put(w.idx(x, 10), Mat.id("copper"))
        w.put(w.idx(15, 10), Mat.id("switch_on")); for (x in 16..24) w.put(w.idx(x, 10), Mat.id("copper"))
        w.put(w.idx(25, 10), Mat.id("peltier")); for (x in 26..34) w.put(w.idx(x, 10), Mat.id("copper")); w.put(w.idx(35, 10), Mat.id("battery_minus"))
        repeat(300) { s.step() }
        println("Пельтье: ток ${"%.2f".format(s.electric.currentAt(w.idx(20, 10)))} А, сверху ${"%.0f".format(w.temp[w.idx(25, 9)])} °C, снизу ${"%.0f".format(w.temp[w.idx(25, 11)])} °C")
        s.toggleSwitch(15, 10); repeat(30) { s.step() }
        println("выключили: ток ${"%.3f".format(s.electric.currentAt(w.idx(20, 10)))} А") }
}
