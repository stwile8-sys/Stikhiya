import ru.stikhiya.sim.*
import kotlin.math.*
fun stats(s: Simulator): String {
    val a = s.air; var sum = 0.0; var mx = 0f; var n = 0
    for (c in 0 until a.an) { if (a.blocked[c]) continue; val v = abs(a.vx[c]) + abs(a.vy[c]); sum += v; mx = max(mx, v); n++ }
    var tsum = 0.0; var tn = 0; for (i in 0 until s.world.n) if (s.world.type[i].toInt() == 0) { tsum += s.world.temp[i]; tn++ }
    return "ветер ср ${"%.2f".format(sum / n)} макс ${"%.1f".format(mx)} м/с, T возд ср ${"%.1f".format(tsum / tn)}"
}
fun main() {
    val w = World(480, 200); val s = Simulator(w)
    fill(w, "stone", 0, 197, 479, 199)
    // печь из кирпича с дверцей сбоку
    fill(w, "brick", 150, 120, 300, 124); fill(w, "brick", 150, 120, 154, 196); fill(w, "brick", 296, 120, 300, 180)
    fill(w, "wood", 170, 170, 280, 185); fill(w, "sawdust", 160, 186, 290, 196)
    fill(w, "water", 320, 150, 420, 196)
    // раскалённый уголь — долгий источник тепла
    fill(w, "charcoal", 200, 160, 250, 169, t = 800f)
    for (i in 0 until w.n) if (w.type[i].toInt() == 0) w.temp[i] = 45f + (200 - i / w.w) * 0.1f
    for (k in 0 until 120) { for (x in 160..290) { val i = w.idx(x, 159); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }; s.step() }
    var tHeat = 0L; var tChem = 0L; var tAir = 0L; var tMot = 0L
    for (minute in 1..6) {
        val t0 = System.nanoTime()
        repeat(3600) {
            val a = System.nanoTime(); s.heat.step(); val b = System.nanoTime(); s.chemistry.step(); val c = System.nanoTime(); s.air.step(); val d = System.nanoTime(); s.motion.step(minute * 100000 + it); val e = System.nanoTime()
            tHeat += b - a; tChem += c - b; tAir += d - c; tMot += e - d
        }
        val ms = (System.nanoTime() - t0) / 1e6 / 3600
        val gas = (0 until w.n).count { val t = w.type[it].toInt(); t != 0 && Mat.isGas(t) }
        println("мин $minute: шаг ${"%.2f".format(ms)} мс [тепло ${"%.2f".format(tHeat/3.6e6/minute)} химия ${"%.2f".format(tChem/3.6e6/minute)} воздух ${"%.2f".format(tAir/3.6e6/minute)} движение ${"%.2f".format(tMot/3.6e6/minute)}], ${stats(s)}, газовых клеток $gas, горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}")
    }
}
