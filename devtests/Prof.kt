import ru.stikhiya.sim.*
fun main() {
    val w = World(480, 200); val s = Simulator(w)
    fill(w, "sand", 50, 20, 150, 80); fill(w, "water", 200, 20, 330, 120); fill(w, "stone", 0, 190, 479, 199)
    fill(w, "wood", 360, 150, 420, 189); fill(w, "fan_r", 10, 100, 11, 150)
    run(s, 300)
    var a = 0L; var b = 0L; var c = 0L; var d = 0L
    repeat(600) { k ->
        val t0 = System.nanoTime(); s.heat.step(); val t1 = System.nanoTime(); s.chemistry.step(); val t2 = System.nanoTime(); s.air.step(); val t3 = System.nanoTime(); s.motion.step(1000 + k); val t4 = System.nanoTime()
        a += t1 - t0; b += t2 - t1; c += t3 - t2; d += t4 - t3 }
    println("тепло ${"%.2f".format(a / 6e8)} химия ${"%.2f".format(b / 6e8)} воздух ${"%.2f".format(c / 6e8)} движение ${"%.2f".format(d / 6e8)} мс")
}
