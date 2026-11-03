import ru.stikhiya.sim.*
fun profile(w: World, key: String): String { val id = Mat.id(key); val sb = StringBuilder()
    for (x in 0 until w.w step 4) { var top = w.h; for (y in 0 until w.h) if (w.type[w.idx(x, y)].toInt() == id) { top = y; break }; sb.append(if (top == w.h) "." else ('0' + minOf(35, (w.h - top)) / 1).let { (w.h - top).toString(36) }) }
    return sb.toString() }
fun main() {
    for (key in listOf("water", "oil", "lava")) {
        val w = World(160, 60); val s = Simulator(w); fill(w, "stone", 0, 57, 159, 59)
        repeat(180) { for (x in 78..81) { val i = w.idx(x, 2); if (w.type[i].toInt() == 0) w.put(i, Mat.id(key)) }; s.step() }
        println("$key после налива 3 с: ${profile(w, key)}")
        run(s, 300); println("$key через 5 с:        ${profile(w, key)}")
        run(s, 900); println("$key через 20 с:       ${profile(w, key)}")
    }
}
