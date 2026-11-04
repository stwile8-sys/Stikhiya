import ru.stikhiya.sim.*
// Проверка всех веществ: справочные данные согласованы, тонет/плавает по плотности, плавится/кипит при своей температуре
fun main() {
    val bad = ArrayList<String>()
    for (id in 1 until Mat.count) {
        val s = Mat[id]
        fun err(m: String) { bad += "${s.key} (${s.name}): $m" }
        if (s.phase != Phase.GAS && s.phase != Phase.FLAME && s.density <= 0f) err("плотность ${s.density}")
        if (s.phase == Phase.GAS && s.molarMass <= 0f) err("нет молярной массы")
        if (s.heatCapacity <= 0f) err("теплоёмкость")
        if (!s.hotAt.isNaN() && !s.coldAt.isNaN() && s.hotAt < s.coldAt) err("hotAt ${s.hotAt} < coldAt ${s.coldAt}")
        if (!s.flashPoint.isNaN() && !s.autoIgnition.isNaN() && s.flashPoint > s.autoIgnition) err("вспышка выше самовоспламенения")
        val ht = Mat.hotTo[id]
        if (ht >= 0) { val t = Mat[ht]; if (!t.coldAt.isNaN() && Mat.coldTo[ht] == id && kotlin.math.abs(t.coldAt - s.hotAt) > 1f) err("переход туда ${s.hotAt}, обратно ${t.coldAt}") }
        if (ht >= 0 && Mat.isGas(ht) && s.phase == Phase.LIQUID && s.hotLatent <= 0f) err("нет теплоты кипения")
        if (!s.spawnTemp.isNaN() && !s.hotAt.isNaN() && ht >= 0 && s.spawnTemp > s.hotAt + 1f && !Mat.isGas(id)) err("рисуется горячее точки перехода ${s.spawnTemp} > ${s.hotAt}")
        if (!s.spawnTemp.isNaN() && !s.coldAt.isNaN() && Mat.coldTo[id] >= 0 && s.spawnTemp < s.coldAt - 1f) err("рисуется холоднее точки застывания ${s.spawnTemp} < ${s.coldAt}")
    }
    // тонет или плавает в воде (порошки и жидкости, рисуемые игроком)
    val water = Mat.WATER
    for (id in 1 until Mat.count) {
        val s = Mat[id]
        if (!s.inPalette || id == water) continue
        if (s.phase != Phase.POWDER && s.phase != Phase.LIQUID) continue
        if (Mat.dissolves(id, water)) continue
        val w = World(30, 60); val sim = Simulator(w); box(w)
        for (y in 25..58) for (x in 1..28) w.put(w.idx(x, y), water)
        for (y in 10..14) for (x in 10..19) { val i = w.idx(x, y); w.put(i, id); Reactions.onSpawn(w, i) }
        repeat(600) { sim.step() }
        if (count(w, s.key) == 0) continue // прореагировало, испарилось
        val y = avgY(w, s.key)
        val g = if (s.grainDensity > 0f) s.grainDensity else s.density
        val sinks = y > 45f; val floats = y < 30f
        if (g > 1080f && !sinks && s.phase == Phase.LIQUID) bad += "${s.key} (${s.name}): плотность ${g.toInt()} — должна тонуть, а ср.y=${"%.1f".format(y)}"
        if (g > 1150f && floats && s.phase == Phase.POWDER) bad += "${s.key} (${s.name}): зерно ${g.toInt()} — должно тонуть, а плавает (y=${"%.1f".format(y)})"
        if (g < 950f && sinks && s.phase == Phase.LIQUID) bad += "${s.key} (${s.name}): плотность ${g.toInt()} — должна плавать, а утонула"
    }
    println("Замечаний: ${bad.size}"); bad.forEach { println(" - $it") }
}
