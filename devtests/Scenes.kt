import ru.stikhiya.sim.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Все сцены-примеры строятся и живут 10 с без NaN; сохранение → загрузка возвращает тот же мир. */
fun main() {
    val w = World(480, 200); val s = Simulator(w)
    for (ex in Examples.list) {
        val tip = ex.build(s)
        val t0 = System.nanoTime()
        run(s, 600)
        val ms = (System.nanoTime() - t0) / 1e6 / 600
        val m = HashMap<String, Int>(); for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t != 0) m[Mat[t].key] = (m[Mat[t].key] ?: 0) + 1 }
        val top = m.entries.sortedByDescending { it.value }.take(7).joinToString(" ") { "${it.key}=${it.value}" }
        println("${ex.title}: ${"%.2f".format(ms)} мс/шаг, тел ${s.bodies.count}, NaN=${nanCheck(w)}  $top")
    }
    // сохранение
    Examples.list[1].build(s); run(s, 120)
    val out = ByteArrayOutputStream(); SceneIO.save(s, out, "проба")
    val bytes = out.toByteArray()
    val types = w.type.copyOf(); val temps = w.temp.copyOf(); val bodies = s.bodies.count
    s.clear()
    val h = SceneIO.header(ByteArrayInputStream(bytes))
    val ok = SceneIO.load(s, ByteArrayInputStream(bytes))
    var diff = 0; for (i in 0 until w.n) if (types[i] != w.type[i] || temps[i] != w.temp[i]) diff++
    println("Сохранение: ${bytes.size / 1024} КБ, имя='${h?.name}', загружено=$ok, отличий клеток=$diff, тел было $bodies стало ${s.bodies.count}")
    run(s, 120); println("после загрузки 2 с: NaN=${nanCheck(w)}")
    // шарнир печи сохраняется
    Examples.list[0].build(s); run(s, 60)
    val o2 = ByteArrayOutputStream(); SceneIO.save(s, o2, "печь"); val j0 = s.bodies.joints.size
    SceneIO.load(s, ByteArrayInputStream(o2.toByteArray()))
    println("Печь: шарниров было $j0, после загрузки ${s.bodies.joints.size}, тел ${s.bodies.count}")
}
