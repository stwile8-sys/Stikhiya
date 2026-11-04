import ru.stikhiya.sim.*
// Порошки в воде: тонет ли по плотности частиц
fun main() { for (k in listOf("flour", "rice", "pepper", "clay", "snow", "sawdust", "cement", "popcorn", "sugar")) {
    val w = World(30, 60); val s = Simulator(w); box(w); for (y in 25..58) for (x in 1..28) w.put(w.idx(x, y), Mat.WATER)
    val id = Mat.id(k); for (y in 10..14) for (x in 10..19) w.put(w.idx(x, y), id); run(s, 600)
    println("$k: ${count(w, k)} кл., ср. y ${"%.1f".format(avgY(w, k))} (вода с 25 до 58)") } }
