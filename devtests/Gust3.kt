import ru.stikhiya.sim.*
import kotlin.math.*
fun main() {
    val w = World(480, 200); val s = Simulator(w)
    fill(w, "stone", 0, 197, 479, 199)
    fill(w, "brick", 150, 120, 300, 124); fill(w, "brick", 150, 120, 154, 196); fill(w, "brick", 296, 120, 300, 180)
    fill(w, "wood", 170, 170, 280, 185); fill(w, "sawdust", 160, 186, 290, 196)
    fill(w, "charcoal", 200, 160, 250, 169, t = 800f)
    for (k in 0 until 120) { for (x in 160..290) { val i = w.idx(x, 159); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() }
    run(s, 3600)
    println("огонь 1 мин: ${mean(s)}")
    repeat(30) { k -> s.air.brushWind(80 + k * 6, 80, 14, 20f, 0f); s.step() }
    var line = "после порыва:"
    for (sec in listOf(0, 1, 3, 10, 30, 60)) { if (sec > 0) run(s, when (sec) { 1 -> 60; 3 -> 120; 10 -> 420; 30 -> 1200; else -> 1800 }); line += "  $sec с ${mean(s)};" }
    println(line)
}
