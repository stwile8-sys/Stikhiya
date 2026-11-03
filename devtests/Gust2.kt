import ru.stikhiya.sim.*
import kotlin.math.*
fun main() {
    val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
    fill(w, "sand", 150, 150, 330, 196); fill(w, "wood", 220, 120, 240, 149)
    run(s, 60)
    // энергично машем пальцем: 30 м/с, туда-сюда, большой кистью
    repeat(150) { k -> val dir = if ((k / 6) % 2 == 0) 1f else -1f; s.air.brushWind(100 + (k % 40) * 6, 60 + (k % 7) * 10, 26, 30f * dir, 12f * -dir); s.step() }
    var line = "после махания:"
    for (sec in listOf(0, 1, 3, 10, 30, 60)) { if (sec > 0) run(s, when (sec) { 1 -> 60; 3 -> 120; 10 -> 420; 30 -> 1200; else -> 1800 }); line += "  $sec с ${mean(s)};" }
    println(line)
}
