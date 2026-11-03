import ru.stikhiya.sim.*

fun count(w: World, key: String): Int { val id = Mat.id(key); var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) c++; return c }
fun avgY(w: World, key: String): Float { val id = Mat.id(key); var s = 0L; var c = 0; for (i in 0 until w.n) if (w.type[i].toInt() == id) { s += i / w.w; c++ }; return if (c == 0) -1f else s.toFloat() / c }
fun fill(w: World, key: String, x0: Int, y0: Int, x1: Int, y1: Int, t: Float? = null) { val id = Mat.id(key); for (y in y0..y1) for (x in x0..x1) { val i = w.idx(x, y); w.put(i, id); if (t != null) w.temp[i] = t } }
fun box(w: World) { for (x in 0 until w.w) { w.put(w.idx(x, w.h - 1), Mat.id("glass")) }; for (y in 0 until w.h) { w.put(w.idx(0, y), Mat.id("glass")); w.put(w.idx(w.w - 1, y), Mat.id("glass")) } }
fun nanCheck(w: World): Boolean { for (i in 0 until w.n) if (w.temp[i].isNaN() || w.amt[i].isNaN() || w.mix[i].isNaN()) { println("NaN at $i type=${Mat[w.type[i].toInt()].key} t=${w.temp[i]} amt=${w.amt[i]} mix=${w.mix[i]}"); return true }; return false }
fun run(s: Simulator, ticks: Int) { repeat(ticks) { s.step() } }
fun stats(w: World, vararg keys: String) = keys.joinToString("  ") { "$it=${count(w, it)}" }
fun liquidVolume(w: World, key: String): Float { val id = Mat.id(key); var v = 0f; for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t == id) v += w.amt[i]; if (Mat.isPowder(t) && w.mixId[i].toInt() == id) v += w.mix[i] }; return v }
fun soluteMass(w: World, key: String): Float { val id = Mat.id(key); var m = 0f; for (i in 0 until w.n) { val t = w.type[i].toInt(); if (t == id) m += Mat.density[id] * Units.VOL * w.amt[i]; if (Mat.isLiquid(t) && w.mixId[i].toInt() == id) m += w.mix[i] * 1e-3f * w.amt[i] }; return m }
fun maxT(w: World): Float { var m = -1000f; for (i in 0 until w.n) if (w.temp[i] > m) m = w.temp[i]; return m }

