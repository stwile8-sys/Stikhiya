import ru.stikhiya.sim.*
fun main() { val w = World(100, 80); val s = Simulator(w); box(w); fill(w, "water", 1, 30, 98, 78); fill(w, "sugar", 40, 10, 60, 20)
  for (t in listOf(60, 300, 900, 1800)) { while (s.tick < t) s.step(); println("t=${t/60}с сахар зёрен ${count(w, "sugar")} ср.y ${"%.1f".format(avgY(w, "sugar"))} растворено ${"%.3f".format(soluteMass(w, "sugar") * 1000)} г") } }
