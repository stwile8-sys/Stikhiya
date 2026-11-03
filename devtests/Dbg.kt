import ru.stikhiya.sim.*
fun main() {
    val w = World(40, 30); box(w); val s = Simulator(w)
    fill(w, "sand", 1, 15, 38, 28); fill(w, "gunpowder", 19, 15, 20, 22)
    for (k in 0 until 6) {
        for (x in 18..21) { val i = w.idx(x, 14); if (w.type[i].toInt() == 0) { w.put(i, Mat.FLAME); w.amt[i] = 6f } }
        val g = w.idx(19, 15)
        println("до шага $k: тип=${Mat[w.type[g].toInt()].key} T=${"%.0f".format(w.temp[g])} горит=${s.chemistry.isBurning(g)} пламя над T=${"%.0f".format(w.temp[w.idx(19,14)])} тип над=${Mat[w.type[w.idx(19,14)].toInt()].key}")
        s.step()
    }
}
