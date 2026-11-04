import ru.stikhiya.sim.*
fun main(args: Array<String>) {
    val names = if (args.isEmpty()) listOf("Реактивная струя") else args.toList()
    for (ex in Examples.list.filter { it.title in names }) {
        val w = World(480, 200); val s = Simulator(w); println("== ${ex.title}: ${ex.build(s)}")
        for (t in listOf(5, 15, 30)) { while (s.tick < t * 60) s.step()
            val lamp = Mat.id("lamp"); var lt = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == lamp) lt = maxOf(lt, w.temp[i])
            var flameR = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.FLAME && i % w.w > 175) flameR++
            val nc = Mat.id("nichrome"); var nt = 0f; for (i in 0 until w.n) if (w.type[i].toInt() == nc) nt = maxOf(nt, w.temp[i])
            val env = s.bodies.get(1); print(" спираль ${nt.toInt()}°C, тело1 y=${env?.let { "%.1f".format(it.body.position.y / B) }} подъём ${"%.2f".format(s.bodies.liftOf(1) / Units.GRAVITY * 1000)} г;")
            println(" t=$t: лампа ${lt.toInt()}°C, бумаги ${count(w, "paper")}, H2 ${count(w, "hydrogen")}, O2 ${count(w, "oxygen")}, пламени справа от сопла $flameR, NaN=${nanCheck(w)}")
            snap(w, "/tmp/ex_${ex.title.length}_$t.png") }
    }
}
