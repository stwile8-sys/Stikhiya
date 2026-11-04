import ru.stikhiya.sim.*
fun main() { val w = World(160, 60); box(w); val s = Simulator(w); fill(w, "fan_r", 5, 20, 6, 40); fill(w, "smoke", 20, 25, 30, 35)
  for (t in 1..6) { run(s, 30); var sx = 0L; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.SMOKE) { sx += i % w.w; c++ }
    println("t=${t * 0.5}с дым x=${sx / maxOf(c, 1)} n=$c  ветер: " + listOf(8, 15, 30, 60, 100).joinToString(" ") { x -> "x$x:${"%.1f".format(s.air.windX(w.idx(x, 30)))}" } + " | y10 x30: ${"%.1f".format(s.air.windX(w.idx(30, 10)))} y50: ${"%.1f".format(s.air.windX(w.idx(30, 50)))}") } }
