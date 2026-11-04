package ru.stikhiya.sim

import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.joints.Joint
import org.jbox2d.dynamics.joints.MouseJoint
import org.jbox2d.dynamics.joints.MouseJointDef
import org.jbox2d.dynamics.joints.RevoluteJointDef
import org.jbox2d.dynamics.joints.RopeJointDef
import org.jbox2d.dynamics.joints.WeldJointDef
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
    var wetPos = 0
    var wetFraction = 0f

    // положение, в котором тело последний раз впечатано в сетку
    var stampX = 0f
    var stampY = 0f
    var stampA = 0f
    var hasStamp = false

    /** Масса, кг (плотности в Box2D подобраны так, что масса тела — настоящая). */
    val mass: Float get() = body.mass
}

/** Перевод единиц: в Box2D одна клетка = 0,1 единицы (чтобы «кожа» многоугольников была тоньше клетки). */
const val B = 0.1f
val BOX_G = Units.GRAVITY / Units.CELL * B          // ускорение свободного падения в единицах Box2D
const val BOX_FORCE = B / Units.CELL                 // Н → сила Box2D (масса та же, ускорение ×10)
const val HINGE = 1
const val FIX = 2
const val ROPE = 3
/** Сколько силы одна клетка песка под телом выдерживает при ударе (≈ 40 кПа на 1 см²). */
const val POWDER_STOP_FORCE = 4f * BOX_FORCE

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
        release()
        joints.clear()
        for (rb in bodies) if (rb != null) physics.destroyBody(rb.body)
        bodies.clear()
        terrainHash = Long.MIN_VALUE
    }

    /** Подъёмная сила тёплого газа под телом, Н (для проверки и подсказок). */
    fun liftOf(id: Int): Float {
        val rb = get(id) ?: return 0f
        var f = 0f
        for (k in 0 until rb.stamped.n) {
            val i = rb.stamped.a[k]
            if (w.owner[i].toInt() != id) continue
            val j = i + w.w
            if (j < w.n && w.owner[j].toInt() != id && Mat.phase[w.type[j].toInt()] <= Phase.FLAME.ordinal) f += ceilingLift(j) * Units.AREA
        }
        return f
    }

    /**
     * Подъёмная сила тёплого (или лёгкого) газа под потолком тела, Па: каждая клетка газа в столбце под
     * потолком легче наружного воздуха комнаты на Δρ и толкает потолок вверх с силой Δρ·g·V (закон Архимеда).
     * Так держится шар с открытым низом: тёплый воздух сам собирается под куполом.
     */
    private fun ceilingLift(j0: Int): Float {
        val type = w.type
        val t0 = type[j0].toInt()
        val room = air.roomT
        if (t0 == Mat.AIR && w.temp[j0] < room + 8f) return 0f
        val rhoOut = Units.gasDensity(Units.AIR_MOLAR, room)
        val ww = w.w
        var j = j0
        var sum = 0f
        var n = 0
        while (j < w.n && n < 160) {
            val t = type[j].toInt()
            if (Mat.phase[t] > Phase.FLAME.ordinal || w.owner[j].toInt() != 0) break
            val rho = w.massDensity(t, w.temp[j]) * (if (t == Mat.AIR) 1f else maxOf(w.amt[j], 0.05f))
            if (rho < rhoOut) sum += rhoOut - rho
            n++
            j += ww
        }
        val p = sum * Units.GRAVITY * Units.CELL
        return if (p > 0f) p else 0f
    }

    // ================= Шарниры, крепления, рука =================

    /** Неподвижная опора без формы — к ней крепятся шарниры и крепления «к стене». */
    private val ground: Body = physics.createBody(BodyDef())

    /** Соединение: a — номер тела, b — номер второго тела (0 — стена/фон), точка в клетках мира. */
    class Link(val kind: Int, val a: Int, val b: Int, val x: Float, val y: Float, val joint: Joint,
               val segments: IntArray? = null, val x2: Float = 0f, val y2: Float = 0f)

    val joints = ArrayList<Link>()
    private var mouse: MouseJoint? = null
    var grabbed = 0
        private set

    /** Номер тела в клетке (0 — нет). */
    fun bodyAt(x: Int, y: Int): Int = if (w.inside(x, y)) w.owner[w.idx(x, y)].toInt() else 0

    private fun bodyOrGround(id: Int): Body = if (id == 0) ground else (get(id)?.body ?: ground)

    /**
     * Шарнир: тело a вращается вокруг точки (x, y) относительно тела b (0 — неподвижная стена).
     * Небольшое трение в оси — дверца не болтается вечно.
     */
    fun hinge(a: Int, b: Int, x: Float, y: Float): Boolean {
        val ra = get(a) ?: return false
        if (a == b) return false
        val def = RevoluteJointDef()
        v1.set(x * B, y * B)
        def.initialize(ra.body, bodyOrGround(b), v1)
        def.collideConnected = false
        def.enableMotor = true
        def.motorSpeed = 0f
        def.maxMotorTorque = ra.body.mass * BOX_G * 0.02f
        ra.body.isAwake = true
        val j = physics.createJoint(def)
        joints += Link(HINGE, a, b, x, y, j)
        return true
    }

    /** Крепление: тело намертво держится за стену (или за другое тело) в точке (x, y). Повторно — снять. */
    fun toggleFix(a: Int, b: Int, x: Float, y: Float): Boolean {
        val ra = get(a) ?: return false
        val old = joints.firstOrNull { it.kind == FIX && it.a == a }
        if (old != null) { physics.destroyJoint(old.joint); joints.remove(old); ra.body.isAwake = true; return false }
        val def = WeldJointDef()
        v1.set(x * B, y * B)
        def.initialize(ra.body, bodyOrGround(if (b == a) 0 else b), v1)
        val j = physics.createJoint(def)
        joints += Link(FIX, a, if (b == a) 0 else b, x, y, j)
        return true
    }

    fun isFixed(a: Int) = joints.any { it.kind == FIX && it.a == a }

    /**
     * Верёвка (или цепь) по пути пальца: звенья по ~3 см, соседние звенья на шарнирах,
     * концы цепляются за тело или стену, которых касаются. Возвращает число звеньев.
     */
    fun createRope(px: FloatArray, py: FloatArray, n: Int, mat: Int, seg: Float = 3f): Int {
        if (n < 1) return 0
        // точки через равные промежутки вдоль пути
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        xs += px[0]; ys += py[0]
        var acc = 0f
        for (k in 1 until n) {
            var ax = px[k - 1]; var ay = py[k - 1]
            val bx = px[k]; val by = py[k]
            var d = kotlin.math.hypot(bx - ax, by - ay)
            while (acc + d >= seg) {
                val f = (seg - acc) / d
                ax += (bx - ax) * f; ay += (by - ay) * f
                xs += ax; ys += ay
                d = kotlin.math.hypot(bx - ax, by - ay); acc = 0f
            }
            acc += d
        }
        if (acc > 0.8f || xs.size == 1) { xs += px[n - 1]; ys += py[n - 1] }
        // что было под концами до верёвки
        val startTarget = attachAt(xs[0], ys[0])
        val endTarget = attachAt(xs[xs.size - 1], ys[ys.size - 1])
        val cells = IntList(8)
        var prev = 0
        var made = 0
        for (k in 0 until xs.size - 1) {
            cells.clear()
            val x0 = xs[k]; val y0 = ys[k]; val x1 = xs[k + 1]; val y1 = ys[k + 1]
            // звено — связная по сторонам цепочка клеток (по диагонали клетки не держатся друг за друга)
            val steps = maxOf(1, (kotlin.math.hypot(x1 - x0, y1 - y0) * 3f).toInt())
            var lx = Int.MIN_VALUE; var ly = 0
            for (s2 in 0..steps) {
                val f = s2 / steps.toFloat()
                val cx = (x0 + (x1 - x0) * f).toInt(); val cy = (y0 + (y1 - y0) * f).toInt()
                if (cx == lx && cy == ly) continue
                if (lx != Int.MIN_VALUE && cx != lx && cy != ly) ropeCell(lx, cy, mat, cells)
                ropeCell(cx, cy, mat, cells)
                lx = cx; ly = cy
            }
            if (cells.n == 0) { prev = 0; continue }
            create(cells)
            val id = w.owner[cells.a[0]].toInt()
            if (id == 0) { prev = 0; continue }
            made++
            segIds.add(id)
            if (prev != 0) hinge(id, prev, x0, y0)
            else if (k == 0 && startTarget >= 0) hinge(id, startTarget, x0, y0)
            prev = id
        }
        if (prev != 0 && endTarget >= 0) hinge(prev, endTarget, xs[xs.size - 1], ys[ys.size - 1])
        // звенья лёгкие, груз тяжёлый — цепочка шарниров тянулась бы как резина.
        // Настоящая верёвка не растягивается: держим концы не дальше длины верёвки (RopeJoint).
        if (made > 0 && startTarget >= 0 && endTarget >= 0 && startTarget != endTarget && segIds.n == made) {
            var len = 0f
            for (k in 1 until xs.size) len += kotlin.math.hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            val def = RopeJointDef()
            def.bodyA = bodyOrGround(startTarget)
            def.bodyB = bodyOrGround(endTarget)
            v1.set(xs[0] * B, ys[0] * B); def.bodyA.getLocalPointToOut(v1, def.localAnchorA)
            v1.set(xs[xs.size - 1] * B, ys[ys.size - 1] * B); def.bodyB.getLocalPointToOut(v1, def.localAnchorB)
            def.maxLength = len * B * 1.01f
            def.collideConnected = true
            val j = physics.createJoint(def)
            joints += Link(ROPE, startTarget, endTarget, xs[0], ys[0], j, IntArray(segIds.n) { segIds.a[it] },
                xs[xs.size - 1], ys[ys.size - 1])
        }
        segIds.clear()
        return made
    }

    private fun ropeCell(x: Int, y: Int, mat: Int, cells: IntList) {
        if (!w.inside(x, y)) return
        val i = w.idx(x, y)
        if (w.type[i].toInt() != Mat.AIR) return
        w.put(i, mat)
        cells.add(i)
    }

    private val segIds = IntList(32)

    /** За что зацепится конец верёвки в точке: номер тела, 0 — стена/пол, −1 — ни за что (висит свободно). */
    private fun attachAt(x: Float, y: Float): Int {
        val cx = x.toInt(); val cy = y.toInt()
        for (r in 0..1) for (dy in -r..r) for (dx in -r..r) {
            val xx = cx + dx; val yy = cy + dy
            if (!w.inside(xx, yy)) continue
            val i = w.idx(xx, yy)
            val o = w.owner[i].toInt()
            if (o != 0) return o
            if (Mat.isSolid(w.type[i].toInt())) return 0
        }
        return -1
    }

    /** Рука берёт тело пальцем: пружинная связь тянет точку тела к пальцу. */
    fun grab(id: Int, x: Float, y: Float): Boolean {
        release()
        val rb = get(id) ?: return false
        val def = MouseJointDef()
        def.bodyA = ground
        def.bodyB = rb.body
        def.target.set(x * B, y * B)
        // держит в 40 раз больше веса: тяжёлое тянется медленнее лёгкого, но поднимается
        def.maxForce = rb.body.mass * BOX_G * 40f
        def.frequencyHz = 5f
        def.dampingRatio = 0.7f
        rb.body.isAwake = true
        mouse = physics.createJoint(def) as MouseJoint
        grabbed = id
        return true
    }

    fun drag(x: Float, y: Float) {
        val m = mouse ?: return
        v1.set(x * B, y * B)
        m.target = v1
    }

    fun release() {
        mouse?.let { physics.destroyJoint(it) }
        mouse = null
        grabbed = 0
    }

    /** Тело исчезло (сгорело, стёрто): его соединения пропадают вместе с ним. */
    private fun forgetJoints(id: Int) {
        // перегорело звено — верёвка больше не держит
        val it2 = joints.iterator()
        while (it2.hasNext()) {
            val l = it2.next()
            if (l.kind == ROPE && l.segments != null && id in l.segments && l.a != id && l.b != id) {
                physics.destroyJoint(l.joint); it2.remove()
            }
        }
        joints.removeAll { it.a == id || it.b == id }
        if (grabbed == id) { mouse = null; grabbed = 0 }
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
        physics.step(Units.DT, 10, 10)
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
                    if (d == 1) {
                        // под потолком тела — тёплый или лёгкий газ: подъёмная сила (воздушный шар, крышка над огнём)
                        val lift = ceilingLift(j)
                        if (lift != 0f) {
                            val f = lift * Units.AREA * BOX_FORCE
                            val px = (x + 0.5f) * B - wc.x
                            val py = (y + 1f) * B - wc.y
                            fy -= f
                            torque += px * (-f)
                        }
                    }
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
        // точнее — по ватерлинии: уровень жидкости в столбцах слева и справа от тела.
        // Вода, вытесненная телу на макушку, на плавучесть не влияет.
        var subF = submerged.toFloat()
        if (submerged > 0 && waterLevel(rb)) {
            // доля каждой клетки ниже уровня воды (уровень дробный — усреднён по столбцам)
            subF = 0f; wcx = 0f; wcy = 0f
            for (k in 0 until rb.stamped.n) {
                val i = rb.stamped.a[k]
                if (w.owner[i] != id) continue
                val y = i / ww
                val part = (y + 1f - levelY).coerceIn(0f, 1f)
                if (part > 0f) { subF += part; wcx += (i % ww) * part; wcy += (y + 1f - part * 0.5f - 0.5f) * part }
            }
            wetDensity = levelRho * subF
        }
        val body = rb.body
        if (fx != 0f || fy != 0f) {
            v1.set(fx, fy); body.applyForceToCenter(v1); body.applyTorque(torque)
        }
        // плавучесть: вес вытесненной жидкости, приложенный в центре подводной части
        // доля под водой сглажена: тело не дёргается от перескоков уровня на одну клетку
        val frac = if (rb.count > 0) subF / rb.count else 0f
        rb.wetFraction += (frac - rb.wetFraction) * 0.7f
        if (subF > 0.01f) {
            val rho = wetDensity / subF
            val fb = rb.wetFraction * rb.count * Units.VOL * rho * Units.GRAVITY * BOX_FORCE
            v1.set(0f, -fb)
            v2.set((wcx / subF + 0.5f) * B, (wcy / subF + 0.5f) * B)
            body.applyForce(v1, v2)
            // сопротивление воды: лёгкое тело тормозит сильно, тяжёлое (бетон, железо) пробивает воду
            val bodyRho = body.mass / (rb.count * Units.VOL)
            val ratio = (rho / bodyRho).coerceIn(0.05f, 3f)
            val lin = minOf(0.5f, 0.15f * ratio * rb.wetFraction)
            val lv = body.linearVelocity
            v3.set(lv.x * (1f - lin), lv.y * (1f - minOf(0.6f, lin * 1.4f)))
            body.linearVelocity = v3
            body.angularVelocity = body.angularVelocity * (1f - minOf(0.5f, 0.2f * ratio * rb.wetFraction))
        }
        airDrag(rb)
        // опора на песке и других порошках. Сила вязкая и симметричная: тормозит и падение, и подскок,
        // поэтому покачивание не превращается в «всплытие» тяжёлого тела из песка.
        if (contactN > 0) {
            val share = powderBottom.toFloat() / (powderBottom + solidBottom)
            val m = body.mass / contactN
            val vyc = body.linearVelocity.y
            val weight = m * BOX_G * share
            // песок держит вес, но удар гасит не сразу: тяжёлое тело проминает его глубже
            val damp = (m * vyc / Units.DT * 0.9f).coerceIn(-weight, POWDER_STOP_FORCE)
            val fyc = -(weight + damp)
            for (c in 0 until contactN) {
                v2.set(contactX[c] * B, contactY[c] * B)
                body.getLinearVelocityFromWorldPointToOut(v2, v3)
                val fxc = -m * v3.x / Units.DT * 0.15f
                v1.set(fxc, fyc)
                body.applyForce(v1, v2)
            }
            // трение в песке гасит раскачивание
            body.angularVelocity = body.angularVelocity * 0.9f
        }
    }

    private var levelY = 0f
    private var levelRho = 0f

    /**
     * Сопротивление воздуха: заметно только для маленьких лёгких кусков.
     * Бревно и бетон падают одинаково быстро — как в жизни.
     */
    private fun airDrag(rb: RigidBody) {
        val body = rb.body
        // лёгкие тела (оболочка шара, бумага) воздух не даёт раскрутить
        val rho = body.mass / (maxOf(rb.count, 1) * Units.VOL)
        if (rho < 100f) body.angularVelocity = body.angularVelocity * (0.97f + 0.03f * rho / 100f)
        val lv = body.linearVelocity
        // скорость относительно воздуха: ветер тянет ткань, флаг, лёгкие куски
        var wx = 0f; var wy = 0f
        val wc = body.worldCenter
        val cx = (wc.x / B).toInt(); val cy = (wc.y / B).toInt()
        if (w.inside(cx, cy)) { val ci = w.idx(cx, cy); wx = air.windX(ci); wy = air.windY(ci) }
        val vx = lv.x / B * Units.CELL - wx   // м/с
        val vy = lv.y / B * Units.CELL - wy
        val v2 = vx * vx + vy * vy
        if (v2 < 1f) return
        val v = kotlin.math.sqrt(v2)
        val area = kotlin.math.sqrt(rb.count.toFloat()) * Units.AREA     // лобовая площадь, м²
        // лёгкое звено ткани не рвём: сопротивление не больше нескольких весов (иначе цепочка «плывёт»)
        val f = minOf(0.5f * 1.2f * 1.0f * area * v2 * BOX_FORCE, body.mass * BOX_G * 3f)
        v1.set(-vx / v * f, -vy / v * f)
        body.applyForceToCenter(v1)
    }

    /** Найти уровень жидкости рядом с телом (в levelY/levelRho). false — рядом нет открытой жидкости. */
    private fun waterLevel(rb: RigidBody): Boolean {
        val ww = w.w
        var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        for (k in 0 until rb.stamped.n) {
            val i = rb.stamped.a[k]
            val x = i % ww; val y = i / ww
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return false
        var sum = 0f; var n = 0; var rho = 0f
        for (side in 0 until 2) {
            for (off in 6..14) {
                val cx = if (side == 0) x0 - off else x1 + off
                if (cx < 0 || cx >= ww) continue
                val yTop = maxOf(0, y0 - 40)
                val yBot = minOf(w.h - 1, y1 + 2)
                for (y in yTop..yBot) {
                    val j = y * ww + cx
                    val t = w.type[j].toInt()
                    if (Mat.isLiquid(t) && w.owner[j].toInt() == 0) {
                        // поверхность: под ней ещё жидкость (не случайная капля)
                        if (y + 1 < w.h && !Mat.isLiquid(w.type[j + ww].toInt())) continue
                        // неполная верхняя клетка поднимает уровень на свою долю
                        sum += y + (1f - w.amt[j]); n++; rho += w.cellDensity(j)
                        break
                    }
                }
            }
        }
        if (n == 0) return false
        levelY = sum / n
        levelRho = rho / n
        return true
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
        rb.freed.clear(); rb.freedPos = 0; rb.wetPos = 0
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
        var px0 = body.position.x
        var py0 = body.position.y
        var ang = body.angle
        // почти не сдвинулось — остаётся в тех же клетках: лежащее тело не дрожит и не расталкивает воду
        val hx = rb.lw * 0.5f * B
        val hy = rb.lh * 0.5f * B
        val reach = kotlin.math.sqrt(hx * hx + hy * hy)
        if (rb.hasStamp && kotlin.math.abs(px0 - rb.stampX) < 0.3f * B && kotlin.math.abs(py0 - rb.stampY) < 0.3f * B &&
            kotlin.math.abs(ang - rb.stampA) * reach < 0.3f * B) {
            px0 = rb.stampX; py0 = rb.stampY; ang = rb.stampA
        } else {
            rb.stampX = px0; rb.stampY = py0; rb.stampA = ang; rb.hasStamp = true
        }
        val c = cos(ang)
        val s = sin(ang)
        val ex = kotlin.math.abs(c) * hx + kotlin.math.abs(s) * hy
        val ey = kotlin.math.abs(s) * hx + kotlin.math.abs(c) * hy
        val x0 = max(0, floor((px0 - ex) / B).toInt() - 1)
        val x1 = min(w.w - 1, floor((px0 + ex) / B).toInt() + 1)
        val y0 = max(0, floor((py0 - ey) / B).toInt() - 1)
        val y1 = min(w.h - 1, floor((py0 + ey) / B).toInt() + 1)
        val id = rb.id.toShort()
        boxX0 = x0; boxX1 = x1; boxY1 = y1
        surfacesReady = false
        for (y in y0..y1) {
            for (x in x0..x1) {
                val px = (x + 0.5f) * B - px0
                val py = (y + 0.5f) * B - py0
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
                        // жидкость: под водой затекает в место, которое тело освободило; на входе в воду поднимает уровень
                        var f = if (ph == Phase.LIQUID.ordinal) wetFreedCell(rb) else -1
                        if (f < 0) f = findFree(x, y)
                        if (f < 0 && ph == Phase.LIQUID.ordinal) f = surfaceCell(x, y)
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

    private var boxX0 = 0
    private var boxX1 = 0
    private var boxY1 = 0
    private var surfacesReady = false
    private var surfFrom = 0
    private val surf = IntArray(1024)

    /**
     * Вытесненная жидкость поднимает уровень рядом с телом и, как в жизни, сначала заполняет самое низкое место:
     * среди соседних столбцов жидкости выбираем тот, где поверхность ниже всего, и кладём каплю на неё.
     */
    private fun surfaceCell(x: Int, y: Int): Int {
        val ww = w.w
        if (!surfacesReady) prepareSurfaces()
        var best = -1
        var bestY = -1
        var bestD = Int.MAX_VALUE
        val n = minOf(surf.size, ww - surfFrom)
        for (k in 0 until n) {
            val sy = surf[k]
            if (sy < 0) continue
            val sx = surfFrom + k
            val d = kotlin.math.abs(sx - x)
            if (sy > bestY || (sy == bestY && d < bestD)) { best = k; bestY = sy; bestD = d }
        }
        if (best < 0) return -1
        val j = bestY * ww + surfFrom + best
        surf[best] = bestY - 1  // столбец поднялся на клетку
        if (bestY - 1 >= 0) {
            val above = (bestY - 1) * ww + surfFrom + best
            if (w.type[above].toInt() != Mat.AIR && !Mat.isGas(w.type[above].toInt()) || w.owner[above].toInt() != 0) surf[best] = -1
        } else surf[best] = -1
        return j
    }

    /** Поверхность жидкости в столбцах вокруг тела (−1 — столбец не подходит). */
    private fun prepareSurfaces() {
        surfacesReady = true
        val ww = w.w
        val margin = 24
        surfFrom = maxOf(0, boxX0 - margin)
        val to = minOf(ww - 1, boxX1 + margin, surfFrom + surf.size - 1)
        val startY = minOf(w.h - 1, boxY1)
        for (sx in surfFrom..to) {
            val k = sx - surfFrom
            surf[k] = -1
            // внутри самого тела вода не поднимается
            if (sx in boxX0..boxX1) continue
            var yy = startY
            if (!Mat.isLiquid(w.type[yy * ww + sx].toInt()) || w.owner[yy * ww + sx].toInt() != 0) continue
            while (yy >= 0) {
                val j = yy * ww + sx
                val t = w.type[j].toInt()
                if (w.owner[j].toInt() != 0) { yy = -1; break }
                if (Mat.isLiquid(t)) { yy--; continue }
                break
            }
            if (yy < 0) continue
            val j = yy * ww + sx
            val t = w.type[j].toInt()
            if (t == Mat.AIR || Mat.isGas(t)) surf[k] = yy
        }
        for (k in (to - surfFrom + 1) until surf.size) surf[k] = -1
    }

    /** Освобождённая телом клетка, к которой примыкает жидкость (вода смыкается за телом). */
    private fun wetFreedCell(rb: RigidBody): Int {
        val ww = w.w
        var k = rb.wetPos
        while (k < rb.freed.n) {
            val j = rb.freed.a[k++]
            if (w.owner[j].toInt() != 0 || w.type[j].toInt() != Mat.AIR) continue
            val x = j % ww
            val up = j - ww
            val wet = (up >= 0 && Mat.isLiquid(w.type[up].toInt()) && w.owner[up].toInt() == 0) ||
                (x > 0 && Mat.isLiquid(w.type[j - 1].toInt()) && w.owner[j - 1].toInt() == 0) ||
                (x < ww - 1 && Mat.isLiquid(w.type[j + 1].toInt()) && w.owner[j + 1].toInt() == 0)
            if (wet) { rb.wetPos = k; return j }
        }
        rb.wetPos = k
        return -1
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
        forgetJoints(rb.id)
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
