import ru.stikhiya.sim.*
import kotlin.math.*
fun main() {
    for (T in listOf(150f, 300f, 600f)) {
        val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
        for (i in 0 until w.n) if (w.type[i].toInt() == 0) w.temp[i] = T
        var line = "комната $T °C:"
        for (sec in listOf(2, 10, 30)) { run(s, if (sec == 2) 120 else if (sec == 10) 480 else 1200); line += "  $sec с ${mean(s)};" }
        println(line)
    }
}
