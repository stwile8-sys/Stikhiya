import ru.stikhiya.sim.*
// Костёр в комнате и печь с трубой: столб дыма, тяга
fun main() { val w = World(240, 120); val s = Simulator(w); fill(w, "stone", 0, 117, 239, 119)
  fill(w, "wood", 30, 105, 60, 116); for (k in 0 until 240) { for (x in 32..58) { val i = w.idx(x, 104); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 3f } }; s.step()
    if (k % 60 == 59) println("костёр ${k / 60 + 1} с: над костром ${"%.2f".format(s.air.windY(w.idx(45, 80)))} м/с, T там ${w.temp[w.idx(45, 80)].toInt()}, комната ${s.air.roomT.toInt()}") }
  // печь с трубой справа
  fill(w, "brick", 140, 90, 200, 92); fill(w, "brick", 140, 90, 142, 116); fill(w, "brick", 198, 40, 200, 116); fill(w, "brick", 186, 40, 188, 90)
  for (y in 90..92) for (x in 189..197) w.put(w.idx(x, y), 0)
  for (y in 108..116) w.put(w.idx(141, y), 0); for (y in 108..116) w.put(w.idx(140, y), 0); for (y in 108..116) w.put(w.idx(142, y), 0)
  fill(w, "wood", 150, 108, 180, 116); for (k in 0 until 240) { for (x in 152..178) { val i = w.idx(x, 107); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 3f } }; s.step() }
  for (t in listOf(5, 15, 30)) { run(s, if (t == 5) 0 else if (t == 15) 600 else 900); snap(w, "/tmp/plume_$t.png", 4)
    println("t+$t: ветер в трубе ${"%.2f".format(s.air.windY(w.idx(193, 60)))} м/с (− вверх), над костром ${"%.2f".format(s.air.windY(w.idx(45, 80)))}, в топку ${"%.2f".format(s.air.windX(w.idx(138, 112)))}, горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}") } }
