import ru.stikhiya.sim.*
// Ткань на верёвке-перекладине: висит, ветер её отклоняет
fun main() { val w = World(200, 120); val s = Simulator(w); box(w); fill(w, "steel", 60, 20, 62, 22)
  val n = s.bodies.createRope(floatArrayOf(61f, 61f), floatArrayOf(23.5f, 70f), 2, Mat.id("cloth")); println("звеньев $n")
  run(s, 120); fun tip(): String { var mx = 0; var my = 0; for (i in 0 until w.n) if (w.type[i].toInt() == Mat.id("cloth") && i / w.w > my) { my = i / w.w; mx = i % w.w }; return "низ ткани x=$mx y=$my" }
  println("висит: ${tip()}")
  for (k in 0 until 180) { s.air.brushWind(61, 50, 30, 8f, 0f); s.step() }; println("ветер 8 м/с вправо: ${tip()}")
  println("соединений ${s.bodies.joints.size}"); run(s, 300); println("ветер стих: ${tip()}, соединений ${s.bodies.joints.size}"); println((1..17).joinToString(" ") { k -> s.bodies.get(k)?.let { "%.0f,%.0f".format(it.body.position.x / B, it.body.position.y / B) } ?: "-" }) }
