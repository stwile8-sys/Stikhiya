import ru.stikhiya.sim.*
import kotlin.math.*
fun mean(s: Simulator): String { val a = s.air; var sum = 0.0; var mx = 0f; var n = 0; var pm = 0f
    for (c in 0 until a.an) { if (a.blocked[c]) continue; val v = abs(a.vx[c]) + abs(a.vy[c]); sum += v; mx = max(mx, v); pm = max(pm, abs(a.pv[c])); n++ }
    return "ср ${"%.2f".format(sum / n)} макс ${"%.1f".format(mx)} м/с, |p| макс ${"%.0f".format(pm)}" }
fun main() {
    // A: взрыв давления в закрытой комнате
    run { val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
        s.air.brushPressure(240, 100, 30, 400f)
        for (sec in listOf(1, 3, 10, 30)) { run(s, if (sec == 1) 60 else if (sec == 3) 120 else if (sec == 10) 420 else 1200); println("A взрыв, $sec с: ${mean(s)}") } }
    // B: вентилятор дует в стену
    run { val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
        fill(w, "fan_r", 400, 100, 401, 140); fill(w, "brick", 410, 80, 415, 160)
        for (sec in listOf(1, 10, 30, 60)) { run(s, if (sec == 1) 60 else if (sec == 10) 540 else if (sec == 30) 1200 else 1800); println("B вентилятор в стену, $sec с: ${mean(s)}") } }
    // C: много вентиляторов в разные стороны
    run { val w = World(480, 200); val s = Simulator(w); fill(w, "stone", 0, 197, 479, 199)
        fill(w, "fan_r", 50, 100, 52, 140); fill(w, "fan_l", 430, 100, 432, 140); fill(w, "fan_u", 200, 180, 260, 182); fill(w, "fan_d", 200, 20, 260, 22)
        for (sec in listOf(1, 10, 30, 60)) { run(s, if (sec == 1) 60 else if (sec == 10) 540 else if (sec == 30) 1200 else 1800); println("C 4 вентилятора, $sec с: ${mean(s)}") } }
}
