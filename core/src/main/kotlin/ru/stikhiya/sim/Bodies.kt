package ru.stikhiya.sim

import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.jbox2d.dynamics.World as B2World

/** Растущий список чисел без лишних объектов. */
class IntList(cap: Int = 256) {
    var a = IntArray(cap)
    var n = 0
    fun add(v: Int) {
        if (n == a.size) a = a.copyOf(n * 2)
        a[n++] = v
    }
    fun clear() { n = 0 }
}

/**
 * Твёрдое тело: клетки в собственной сетке (как нарисованы) и тело физического движка Box2D.
 * Каждый шаг тело «впечатывается» в мир под своим углом — частицы видят его как стену,
 * огонь и тепло действуют на его клетки как на любые другие.
 */
class RigidBody(val id: Int, val lw: Int, val lh: Int) {
    val ln = lw * lh
    val type = ShortArray(ln)
    val temp = FloatArray(ln)
    val burn = FloatArray(ln)
    val cure = FloatArray(ln)
    val latent = FloatArray(ln)
    val flags = ByteArray(ln)
    val shade = ByteArray(ln)
    var count = 0
    var dirty = false
    lateinit var body: Body
    val stamped = IntList(64)
    val stampedLocal = IntList(64)
    val freed = IntList(64)          // клетки, которые тело освободило в этом шаге (сюда уходит вытесненная вода)
    var freedPos = 0
    var wetFraction = 0f

    /** Масса, кг (плотности в Box2D подобраны так, что масса тела — настоящая). */
    val mass: Float get() = body.mass
}

/** Перевод единиц: в Box2D одна клетка = 0,1 единицы (чтобы «кожа» многоугольников была тоньше клетки). */
const val B = 0.1f
val BOX_G = Units.GRAVITY / Units.CELL * B          // ускорение свободного падения в единицах Box2D
const val BOX_FORCE = B / Units.CELL                 // Н → сила Box2D (масса та же, ускорение ×10)

class Bodies(private val w: World, private val air: Air) {
    val physics = B2World(Vec2(0f, BOX_G))
    private val bodies = ArrayList<RigidBody?>()
    private var terrain: Body? = null
    private var terrainHash = Long.MIN_VALUE
    private var tick = 0

    private val v1 = Vec2()
    private val v2 = Vec2()
    private val v3 = Vec2()

    /** Смещения для поиска свободной клетки при вытеснении частиц: ближние и верхние — первыми. */
    private val freeOffsets: IntArray = run {
        val list = ArrayList<IntArray>()
        for (dy in -3..3) for (dx in -3..3) if (dx != 0 || dy != 0) list += intArrayOf(dx, dy)
        list.sortWith(compareBy({ it[0] * it[0] + it[1] * it[1] }, { it[1] }))
        IntArray(list.size * 2) { k -> list[k / 2][k % 2] }
    }

    init {
        physics.setAllowSleep(true)
    }

    val count: Int get() = bodies.count { it != null }

    fun get(id: Int): RigidBody? = bodies.getOrNull(id - 1)

    fun clear() {
        for (rb in bodies) if (rb != null) physics.destroyBody(rb.body)
        bodies.clear()
        terrainHash = Long.MIN_VALUE
    }

    // ================= Шаг =================

    fun step() {
        tick++
        if (tick % 10 == 1) checkTerrain()
        for (rb in bodies) if (rb != null) readBack(rb)
        for (rb in bodies) if (rb != null) unstamp(rb)
        for (k in bodies.indices) {
            val rb = bodies[k] ?: continue
            if (rb.dirty) rebuild(rb)
        }
        physics.step(Units.DT, 8, 3)
        for (rb in bodies) if (rb != null) stamp(rb)
    }

    // ================= Создание =================

    /** Нарисованные за один мазок твёрдые клетки становятся телами (по связным кускам). */
    fun create(cells: IntList) {
        val mark = HashSet<Int>(cells.n * 2)
        for (k in 0 until cells.n) {
            val i = cells.a[k]
            val t = w.type[i].toInt()
            if (Mat.isSolid(t) && w.owner[i].toInt() == 0) mark += i
        }
        val queue = IntList(64)
        while (mark.isNotEmpty()) {
            val start = mark.first()
            mark.remove(start)
            queue.clear(); queue.add(start)
            var head = 0
            while (head < queue.n) {
                val i = queue.a[head++]
                for (d in 0 until 4) {
                    val j = Neighbors.at(w, i, d)
                    if (j >= 0 && mark.remove(j)) queue.add(j)
                }
            }
            fromWorldCells(queue)
        }
    }

    private fun fromWorldCells(cells: IntList) {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (k in 0 until cells.n) {
            val i = cells.a[k]
            val x = i % w.w; val y = i / w.w
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        val rb = RigidBody(newId(), x1 - x0 + 1, y1 - y0 + 1)
        for (k in 0 until cells.n) {
            val i = cells.a[k]
            val l = (i / w.w - y0) * rb.lw + (i % w.w - x0)
            copyFromWorld(rb, i, l)
            rb.count++
            w.owner[i] = rb.id.toShort()
            rb.stamped.add(i); rb.stampedLocal.add(l)
        }
        val def = BodyDef()
        def.type = BodyType.DYNAMIC
        def.position.set((x0 + rb.lw / 2f) * B, (y0 + rb.lh / 2f) * B)
        def.angle = 0f
        def.linearDamping = 0.05f
        def.angularDamping = 0.1f
        def.allowSleep = true
        rb.body = physics.createBody(def)
        buildFixtures(rb)
        put(rb)
    }

    private fun newId(): Int {
        for (k in bodies.indices) if (bodies[k] == null) return k + 1
        return bodies.size + 1
    }

    private fun put(rb: RigidBody) {
        while (bodies.size < rb.id) bodies += null
        bodies[rb.id - 1] = rb
    }

    private fun copyFromWorld(rb: RigidBody, i: Int, l: Int) {
        rb.type[l] = w.type[i]
        rb.temp[l] = w.temp[i]
        rb.burn[l] = w.burn[i]
        rb.cure[l] = w.cure[i]
        rb.latent[l] = w.latent[i]
        rb.flags[l] = w.flags[i]
        rb.shade[l] = w.shade[i]
    }

    /** Форма тела — прямоугольники из клеток одного вещества (жадное разбиение по строкам). */
    private fun buildFixtures(rb: RigidBody) {
        var f = rb.body.fixtureList
        while (f != null) {
            val next = f.next
            rb.body.destroyFixture(f)
            f = next
        }
        val used = BooleanArray(rb.ln)
        val shape = PolygonShape()
        val fd = FixtureDef()
        fd.friction = 0.6f
        fd.restitution = 0.05f
        for (y in 0 until rb.lh) {
            var x = 0
            while (x < rb.lw) {
                val l = y * rb.lw + x
                val t = rb.type[l]
                if (t.toInt() == 0 || used[l]) { x++; continue }
                // отрезок строки одного вещества
                var xe = x
                while (xe + 1 < rb.lw && rb.type[y * rb.lw + xe + 1] == t && !used[y * rb.lw + xe + 1]) xe++
                // растим вниз, пока ниже такой же отрезок
                var ye = y
                outer@ while (ye + 1 < rb.lh) {
                    for (xx in x..xe) {
                        val ll = (ye + 1) * rb.lw + xx
                        if (rb.type[ll] != t || used[ll]) break@outer
                    }
                    ye++
                }
                for (yy in y..ye) for (xx in x..xe) used[yy * rb.lw + xx] = true
                val hw = (xe - x + 1) * 0.5f * B
                val hh = (ye - y + 1) * 0.5f * B
                v1.set(((x + xe + 1) * 0.5f - rb.lw / 2f) * B, ((y + ye + 1) * 0.5f - rb.lh / 2f) * B)
                shape.setAsBox(hw, hh, v1, 0f)
                fd.shape = shape
                fd.density = Mat.density[t.toInt()] * Units.VOL / (B * B)
                rb.body.createFixture(fd)
                x = xe + 1
            }
        }
    }

    // ================= Связь с миром клеток =================

    /**
     * Пока тело впечатано в мир: забираем обратно изменения клеток (сгорело, обуглилось, расплавилось)
     * и считаем силы от окружения — плавучесть в жидкости, опору на порошке, давление воздуха.
     */
    private fun readBack(rb: RigidBody) {
        val id = rb.id.toShort()
        val ww = w.w
        var submerged = 0
        var wetDensity = 0f
        var wcx = 0f; var wcy = 0f
        var runStart = -1
        var prev = -2
        var powderBottom = 0
        var solidBottom = 0
        contactN = 0
        var fx = 0f; var fy = 0f; var torque = 0f
        val wc = rb.body.worldCenter
        for (k in 0 until rb.stamped.n) {
            val i = rb.stamped.a[k]
            val l = rb.stampedLocal.a[k]
            val t = w.type[i]
            if (w.owner[i] != id || (t != rb.type[l] && !Mat.isSolid(t.toInt()))) {
                // клетка больше не часть тела: сгорела, стала пеплом, расплавилась, стёрта
                if (w.owner[i] == id) w.owner[i] = 0
                if (rb.type[l].toInt() != 0) { rb.type[l] = 0; rb.count--; rb.dirty = true }
                continue
            }
            if (t != rb.type[l]) { rb.type[l] = t; rb.dirty = true } // дерево → уголь
            copyFromWorld(rb, i, l)
            val x = i % ww
            val y = i / ww
            // отрезки строк тела: если с края отрезка вода — весь отрезок под водой (закон Архимеда)
            if (i != prev + 1 || x == 0) {
                if (runStart >= 0) {
                    val r = waterRun(runStart, prev)
                    if (r > 0f) { val len = prev - runStart + 1; submerged += len; wetDensity += r * len
                        wcx += ((runStart % ww) + (prev % ww)) * 0.5f * len; wcy += (runStart / ww).toFloat() * len }
                }
                runStart = i
            }
            prev = i
            for (d in 0 until 4) {
                val j = Neighbors.at(w, i, d)
                if (j < 0 || w.owner[j] == id) continue
                val tj = w.type[j].toInt()
                val ph = Mat.phase[tj]
                if (ph == Phase.LIQUID.ordinal) {
                    // учтено отрезками
                } else if (d == 1 && ph == Phase.POWDER.ordinal) {
                    powderBottom++
                    addContact(x + 0.5f, y + 1f)
                } else if (d == 1 && (ph == Phase.SOLID.ordinal)) {
                    solidBottom++
                } else if (ph <= Phase.FLAME.ordinal) {
                    // давление воздуха давит на грань снаружи внутрь
                    val p = air.pv[air.cellOf(j)]
                    if (p > 0.5f || p < -0.5f) {
                        val f = p * 1000f * Units.AREA * BOX_FORCE
                        val nx = when (d) { 2 -> -1f; 3 -> 1f; else -> 0f }
                        val ny = when (d) { 0 -> -1f; 1 -> 1f; else -> 0f }
                        val ffx = -nx * f
                        val ffy = -ny * f
                        val px = (x + 0.5f + nx * 0.5f) * B - wc.x
                        val py = (y + 0.5f + ny * 0.5f) * B - wc.y
                        fx += ffx; fy += ffy
                        torque += px * ffy - py * ffx
                    }
                }
            }
        }
        if (runStart >= 0) {
            val r = waterRun(runStart, prev)
            if (r > 0f) { val len = prev - runStart + 1; submerged += len; wetDensity += r * len
                wcx += ((runStart % ww) + (prev % ww)) * 0.5f * len; wcy += (runStart / ww).toFloat() * len }
        }
        val body = rb.body
        if (fx != 0f || fy != 0f) {
            v1.set(fx, fy); body.applyForceToCenter(v1); body.applyTorque(torque)
        }
        // плавучесть: вес вытесненной жидкости, приложенный в центре подводной части
        rb.wetFraction = if (rb.count > 0) submerged.toFloat() / rb.count else 0f
        if (submerged > 0) {
            val rho = wetDensity / submerged
            val fb = submerged * Units.VOL * rho * Units.GRAVITY * BOX_FORCE
            v1.set(0f, -fb)
            v2.set((wcx / submerged + 0.5f) * B, (wcy / submerged + 0.5f) * B)
            body.applyForce(v1, v2)
            // вязкое сопротивление воды
            val damp = 1f - 0.06f * rb.wetFraction
            val lv = body.linearVelocity
            v3.set(lv.x * damp, lv.y * damp)
            body.linearVelocity = v3
            body.angularVelocity = body.angularVelocity * (1f - 0.08f * rb.wetFraction)
        }
        // опора на песке и других порошках: гасим падение в точках касания
        if (contactN > 0) {
            val share = powderBottom.toFloat() / (powderBottom + solidBottom)
            val m = body.mass / contactN
            for (c in 0 until contactN) {
                v2.set(contactX[c] * B, contactY[c] * B)
                body.getLinearVelocityFromWorldPointToOut(v2, v3)
                if (v3.y < -1f) continue // летит вверх — опора не нужна
                val fyc = -m * (BOX_G * share + maxOf(0f, v3.y) / Units.DT * 0.9f)
                val fxc = -m * v3.x / Units.DT * 0.15f
                v1.set(fxc, fyc)
                body.applyForce(v1, v2)
            }
        }
    }

    /** Плотность жидкости у краёв отрезка строки [a..b] или 0, если по краям не жидкость. */
    private fun waterRun(a: Int, b: Int): Float {
        val ww = w.w
        var best = 0f
        if (a % ww > 0) {
            val j = a - 1
            if (Mat.isLiquid(w.type[j].toInt()) && w.owner[j].toInt() == 0) best = w.cellDensity(j)
        }
        if (b % ww < ww - 1) {
            val j = b + 1
            if (Mat.isLiquid(w.type[j].toInt()) && w.owner[j].toInt() == 0) best = maxOf(best, w.cellDensity(j))
        }
        return best
    }

    private var contactN = 0
    private var contactX = FloatArray(64)
    private var contactY = FloatArray(64)

    private fun addContact(x: Float, y: Float) {
        if (contactN == contactX.size) {
            contactX = contactX.copyOf(contactN * 2); contactY = contactY.copyOf(contactN * 2)
        }
        contactX[contactN] = x; contactY[contactN] = y; contactN++
    }

    /** Убрать тело из мира клеток перед шагом физики (клетки становятся воздухом). */
    private fun unstamp(rb: RigidBody) {
        val id = rb.id.toShort()
        rb.freed.clear(); rb.freedPos = 0
        for (k in 0 until rb.stamped.n) {
            val i = rb.stamped.a[k]
            if (w.owner[i] == id) {
                w.owner[i] = 0
                w.toAir(i)
                rb.freed.add(i)
            }
        }
        rb.stamped.clear(); rb.stampedLocal.clear()
    }

    /** Впечатать тело в мир под новым углом; частицы на пути выталкиваются в соседние пустые клетки. */
    private fun stamp(rb: RigidBody) {
        val body = rb.body
        val pos = body.position
        val ang = body.angle
        val c = cos(ang)
        val s = sin(ang)
        val hx = rb.lw * 0.5f * B
        val hy = rb.lh * 0.5f * B
        val ex = kotlin.math.abs(c) * hx + kotlin.math.abs(s) * hy
        val ey = kotlin.math.abs(s) * hx + kotlin.math.abs(c) * hy
        val x0 = max(0, floor((pos.x - ex) / B).toInt() - 1)
        val x1 = min(w.w - 1, floor((pos.x + ex) / B).toInt() + 1)
        val y0 = max(0, floor((pos.y - ey) / B).toInt() - 1)
        val y1 = min(w.h - 1, floor((pos.y + ey) / B).toInt() + 1)
        val id = rb.id.toShort()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = (x + 0.5f) * B - pos.x
                val py = (y + 0.5f) * B - pos.y
                val lx = (c * px + s * py) / B + rb.lw * 0.5f
                val ly = (-s * px + c * py) / B + rb.lh * 0.5f
                if (lx < 0f || ly < 0f) continue
                val ix = lx.toInt()
                val iy = ly.toInt()
                if (ix >= rb.lw || iy >= rb.lh) continue
                val l = iy * rb.lw + ix
                if (rb.type[l].toInt() == 0) continue
                val i = y * w.w + x
                if (w.owner[i].toInt() != 0) continue
                val t = w.type[i].toInt()
                if (t != Mat.AIR) {
                    val ph = Mat.phase[t]
                    if (ph == Phase.SOLID.ordinal) continue
                    if (ph >= Phase.LIQUID.ordinal) {
                        // вытесняем частицу рядом, а если вокруг тесно (глубоко в воде) — туда, откуда тело ушло
                        var f = findFree(x, y)
                        if (f < 0) f = freedCell(rb)
                        if (f < 0) continue
                        w.swap(i, f)
                    }
                }
                w.type[i] = rb.type[l]
                w.temp[i] = rb.temp[l]
                w.burn[i] = rb.burn[l]
                w.cure[i] = rb.cure[l]
                w.latent[i] = rb.latent[l]
                w.flags[i] = rb.flags[l]
                w.shade[i] = rb.shade[l]
                w.amt[i] = 1f
                w.mixId[i] = 0
                w.mix[i] = 0f
                w.vy[i] = 0f
                w.life[i] = 0f
                w.owner[i] = id
                rb.stamped.add(i); rb.stampedLocal.add(l)
            }
        }
    }

    private fun freedCell(rb: RigidBody): Int {
        while (rb.freedPos < rb.freed.n) {
            val j = rb.freed.a[rb.freedPos++]
            if (w.owner[j].toInt() == 0 && w.type[j].toInt() == Mat.AIR) return j
        }
        return -1
    }

    private fun findFree(x: Int, y: Int): Int {
        val off = freeOffsets
        var k = 0
        while (k < off.size) {
            val nx = x + off[k]
            val ny = y + off[k + 1]
            k += 2
            if (nx < 0 || ny < 0 || nx >= w.w || ny >= w.h) continue
            val j = ny * w.w + nx
            if (w.owner[j].toInt() != 0) continue
            val t = w.type[j].toInt()
            if (t == Mat.AIR || Mat.isGas(t)) return j
        }
        return -1
    }

    // ================= Изменение формы =================

    /** Тело потеряло клетки: пересобрать форму, а если распалось — разделить на куски. */
    private fun rebuild(rb: RigidBody) {
        rb.dirty = false
        if (rb.count <= 0) { destroy(rb); return }
        val comp = IntArray(rb.ln) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntList(64)
        for (l0 in 0 until rb.ln) {
            if (rb.type[l0].toInt() == 0 || comp[l0] >= 0) continue
            val cid = sizes.size
            comp[l0] = cid
            queue.clear(); queue.add(l0)
            var head = 0
            while (head < queue.n) {
                val l = queue.a[head++]
                val x = l % rb.lw
                val y = l / rb.lw
                if (x > 0) visit(rb, comp, l - 1, cid, queue)
                if (x < rb.lw - 1) visit(rb, comp, l + 1, cid, queue)
                if (y > 0) visit(rb, comp, l - rb.lw, cid, queue)
                if (y < rb.lh - 1) visit(rb, comp, l + rb.lw, cid, queue)
            }
            sizes += queue.n
        }
        if (sizes.size > 1) {
            // самый большой кусок остаётся этим телом, остальные — новые тела
            var keep = 0
            for (k in sizes.indices) if (sizes[k] > sizes[keep]) keep = k
            for (k in sizes.indices) if (k != keep) split(rb, comp, k)
            rb.count = sizes[keep]
        }
        buildFixtures(rb)
    }

    private fun visit(rb: RigidBody, comp: IntArray, l: Int, cid: Int, q: IntList) {
        if (rb.type[l].toInt() == 0 || comp[l] >= 0) return
        comp[l] = cid
        q.add(l)
    }

    private fun split(rb: RigidBody, comp: IntArray, cid: Int) {
        var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
        for (l in 0 until rb.ln) if (comp[l] == cid) {
            val x = l % rb.lw; val y = l / rb.lw
            x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x); y1 = max(y1, y)
        }
        val nb = RigidBody(newId(), x1 - x0 + 1, y1 - y0 + 1)
        put(nb) // занять номер до следующего newId()
        for (l in 0 until rb.ln) if (comp[l] == cid) {
            val nl = (l / rb.lw - y0) * nb.lw + (l % rb.lw - x0)
            nb.type[nl] = rb.type[l]; nb.temp[nl] = rb.temp[l]; nb.burn[nl] = rb.burn[l]
            nb.cure[nl] = rb.cure[l]; nb.latent[nl] = rb.latent[l]; nb.flags[nl] = rb.flags[l]; nb.shade[nl] = rb.shade[l]
            nb.count++
            rb.type[l] = 0
        }
        // центр новой сетки в координатах старого тела → в мир
        v1.set(((x0 + nb.lw / 2f) - rb.lw / 2f) * B, ((y0 + nb.lh / 2f) - rb.lh / 2f) * B)
        rb.body.getWorldPointToOut(v1, v2)
        rb.body.getLinearVelocityFromWorldPointToOut(v2, v3)
        val def = BodyDef()
        def.type = BodyType.DYNAMIC
        def.position.set(v2)
        def.angle = rb.body.angle
        def.linearVelocity.set(v3)
        def.angularVelocity = rb.body.angularVelocity
        def.linearDamping = 0.05f
        def.angularDamping = 0.1f
        nb.body = physics.createBody(def)
        buildFixtures(nb)
    }

    private fun destroy(rb: RigidBody) {
        physics.destroyBody(rb.body)
        bodies[rb.id - 1] = null
    }

    // ================= Неподвижная опора =================

    /**
     * Всё твёрдое, что не принадлежит телам (пол, стены, закреплённые постройки), — неподвижная опора.
     * Пересобираем её, только когда она изменилась.
     */
    private fun checkTerrain() {
        var h = 1125899906842597L
        val n = w.n
        for (i in 0 until n) {
            val t = w.type[i].toInt()
            if (Mat.phase[t] == Phase.SOLID.ordinal && w.owner[i].toInt() == 0) h = 31 * h + i
        }
        if (h == terrainHash) return
        terrainHash = h
        terrain?.let { physics.destroyBody(it) }
        val def = BodyDef()
        def.type = BodyType.STATIC
        val tb = physics.createBody(def)
        val shape = PolygonShape()
        val fd = FixtureDef()
        fd.friction = 0.7f
        fd.shape = shape
        val used = BooleanArray(n)
        val ww = w.w
        for (y in 0 until w.h) {
            var x = 0
            while (x < ww) {
                val i = y * ww + x
                if (used[i] || !isTerrain(i)) { x++; continue }
                var xe = x
                while (xe + 1 < ww && isTerrain(i + xe + 1 - x) && !used[i + xe + 1 - x]) xe++
                var ye = y
                outer@ while (ye + 1 < w.h) {
                    for (xx in x..xe) { val j = (ye + 1) * ww + xx; if (used[j] || !isTerrain(j)) break@outer }
                    ye++
                }
                for (yy in y..ye) for (xx in x..xe) used[yy * ww + xx] = true
                v1.set((x + xe + 1) * 0.5f * B, (y + ye + 1) * 0.5f * B)
                shape.setAsBox((xe - x + 1) * 0.5f * B, (ye - y + 1) * 0.5f * B, v1, 0f)
                tb.createFixture(fd)
                x = xe + 1
            }
        }
        // края мира — стены комнаты
        wall(tb, shape, fd, -0.5f, w.h / 2f, 0.5f, w.h / 2f + 1f)
        wall(tb, shape, fd, w.w + 0.5f, w.h / 2f, 0.5f, w.h / 2f + 1f)
        wall(tb, shape, fd, w.w / 2f, w.h + 0.5f, w.w / 2f + 1f, 0.5f)
        wall(tb, shape, fd, w.w / 2f, -0.5f, w.w / 2f + 1f, 0.5f)
        terrain = tb
    }

    private fun isTerrain(i: Int): Boolean =
        Mat.phase[w.type[i].toInt()] == Phase.SOLID.ordinal && w.owner[i].toInt() == 0

    private fun wall(tb: Body, shape: PolygonShape, fd: FixtureDef, cx: Float, cy: Float, hx: Float, hy: Float) {
        v1.set(cx * B, cy * B)
        shape.setAsBox(hx * B, hy * B, v1, 0f)
        tb.createFixture(fd)
    }
}
