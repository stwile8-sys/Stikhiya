import ru.stikhiya.sim.*
import kotlin.math.*
fun main() {
    for (strat in listOf(false, true)) {
        val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
        if (strat) for (i in 0 until w.n) if (w.type[i].toInt() == 0) w.temp[i] = 20f + (200 - i / w.w) * 0.3f
        run(s, 60)
        // порыв ветра пальцем: 10 кадров ведём вправо
        repeat(10) { k -> s.air.brushWind(150 + k * 8, 100, 26, 15f, 0f); s.step() }
        var line = if (strat) "тёплая слоистая комната:" else "ровная комната:"
        for (sec in listOf(1, 3, 10, 30, 60)) { run(s, when (sec) { 1 -> 60; 3 -> 120; 10 -> 420; 30 -> 1200; else -> 1800 }); line += "  $sec с ${mean(s)};" }
        println(line)
    }
}
