import ru.stikhiya.sim.*
fun main() {
    val w = World(160, 60); val s = Simulator(w); fill(w, "stone", 0, 57, 159, 59)
    repeat(180) { for (x in 78..81) { val i = w.idx(x, 2); if (w.type[i].toInt() == 0) w.put(i, Mat.WATER) }; s.step() }
    run(s, 1200)
    for (y in 40..56) { val sb = StringBuilder("$y: "); for (x in 66..94) { val i = w.idx(x, y); val t = w.type[i].toInt()
        sb.append(when { t == 0 -> '.'; t == Mat.WATER -> if (w.amt[i] > 0.99f) 'W' else if (w.amt[i] > 0.5f) 'w' else ','; else -> Mat[t].key[0] }) }; println(sb) }
    var air = 0; for (x in 66..94) { val i = w.idx(x, 45); println("x=$x y=45 тип=${Mat[w.type[i].toInt()].key} amt=${"%.3f".format(w.amt[i])} T=${"%.1f".format(w.temp[i])} vy=${w.vy[i]}") ; if (x > 70) break }
}
