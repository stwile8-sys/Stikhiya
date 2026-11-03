import ru.stikhiya.sim.*
fun main() {
    val w = World(60, 40); box(w); val s = Simulator(w)
    fill(w, "charcoal", 20, 30, 40, 38, t = 800f)
    val i = w.idx(30, 30)
    for (k in 0 until 5) {
        println("шаг $k: T=${"%.0f".format(w.temp[i])} горит=${s.chemistry.isBurning(i)} O2=${"%.3f".format(s.air.oxygen(i))} open=${s.air.open[s.air.cellOf(i)]} кислород рядом=${Neighbors.hasOxygen(w, i)} flags=${w.flags[i]}")
        s.step()
    }
    run(s, 600)
    println("через 10 с: горит ${(0 until w.n).count { s.chemistry.isBurning(it) }}, пламени ${count(w, "flame")}, T=${"%.0f".format(w.temp[i])}")
}
