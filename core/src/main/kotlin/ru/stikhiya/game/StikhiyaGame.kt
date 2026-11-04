package ru.stikhiya.game

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import ru.stikhiya.sim.Brush
import ru.stikhiya.sim.IntList
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Simulator
import ru.stikhiya.sim.Tool
import ru.stikhiya.sim.Units
import ru.stikhiya.sim.World
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class StikhiyaGame : ApplicationAdapter(), Ui.Callbacks {
    companion object {
        const val WORLD_W = 480
        const val WORLD_H = 200
        private val RU = Locale("ru")
    }

    private lateinit var world: World
    private lateinit var sim: Simulator
    private lateinit var renderer: WorldRenderer
    private lateinit var batch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var font: BitmapFont
    private lateinit var small: BitmapFont
    private val st = GameState()
    private lateinit var ui: Ui
    private val sound = SoundEngine()
    private var prevMaxP = 0f

    // вид мира на экране
    private var baseScale = 1f
    private var zoom = 1f
    private var originX = 0f
    private var originY = 0f

    private var acc = 0f
    private var simMs = 0f

    // касания
    private var drawPointer = -1
    private val stroke = IntList(1024) // твёрдые клетки текущего мазка
    private var lastCellX = 0f
    private var lastCellY = 0f
    private var curScreenX = 0f
    private var curScreenY = 0f
    private val pointerX = FloatArray(10)
    private val pointerY = FloatArray(10)
    private val pointerDown = BooleanArray(10)
    private var panPointer = -1
    private var panStartX = 0f
    private var panStartY = 0f
    private var panOriginX = 0f
    private var panOriginY = 0f
    private var pinching = false
    private var pinchDist0 = 1f
    private var pinchZoom0 = 1f
    private var pinchWorldX = 0f
    private var pinchWorldY = 0f

    override fun create() {
        world = World(WORLD_W, WORLD_H)
        sim = Simulator(world)
        renderer = WorldRenderer(world, sim.air)
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        ui = Ui(st, this)
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = Touch()
        resize(Gdx.graphics.width, Gdx.graphics.height)
        buildStartScene()
        sound.muted = !prefs().getBoolean("sound", true)
        renderer.glowOn = prefs().getBoolean("glow", true)
        sound.start()
    }

    private fun prefs() = Gdx.app.getPreferences("stikhiya")

    override fun pause() { sound.stop() }
    override fun resume() { sound.start() }

    override fun soundOn() = !sound.muted

    override fun toggleSound() {
        sound.muted = !sound.muted
        prefs().putBoolean("sound", !sound.muted).flush()
    }

    override fun askSearch() {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) {
                // ответ приходит не из потока отрисовки
                Gdx.app.postRunnable {
                    val q = text.trim()
                    st.search = if (q.isEmpty()) null else q
                    ui.resetChipsScroll()
                }
            }
            override fun canceled() {
                Gdx.app.postRunnable { st.search = null }
            }
        }, "Поиск вещества", st.search ?: "", "например: кислота, медь, газ")
    }

    override fun openMenu() {
        if (ui.menuOpen) { ui.closeMenu(); return }
        ui.showMenu("Меню", menuRows())
    }

    private fun menuRows(): List<List<MenuEntry>> {
        val rows = ArrayList<List<MenuEntry>>()
        rows += listOf(MenuEntry({ "Скорость химии (сушка, застывание, схватывание)" }, header = true))
        rows += st.chemNames.indices.map { k ->
            MenuEntry({ st.chemNames[k] }, { st.chemIndex == k }) { st.chemIndex = k }
        }
        rows += listOf(MenuEntry({ "Вид" }, header = true))
        rows += listOf(
            MenuEntry({ if (renderer.glowOn) "Свечение огня: вкл" else "Свечение огня: выкл" }, { renderer.glowOn }) {
                renderer.glowOn = !renderer.glowOn
                prefs().putBoolean("glow", renderer.glowOn).flush()
            },
            MenuEntry({ if (sound.muted) "Звук: выкл" else "Звук: вкл" }, { !sound.muted }) { toggleSound() }
        )
        rows += listOf(MenuEntry({ "Мир" }, header = true))
        rows += listOf(
            MenuEntry({ if (System.currentTimeMillis() < st.clearArmedUntil) "Точно очистить?" else "Очистить мир" },
                { System.currentTimeMillis() < st.clearArmedUntil }) {
                val now = System.currentTimeMillis()
                if (now < st.clearArmedUntil) { clearWorld(); st.clearArmedUntil = 0L; ui.closeMenu() }
                else st.clearArmedUntil = now + 2500L
            }
        )
        return rows
    }

    private fun makeFonts() {
        if (this::font.isInitialized) { font.dispose(); small.dispose() }
        val chars = FreeTypeFontGenerator.DEFAULT_CHARS +
            "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя°×½¼·−±→…«»—–№³²"
        val gen = FreeTypeFontGenerator(Gdx.files.internal("fonts/Carlito-Regular.ttf"))
        val p = FreeTypeFontGenerator.FreeTypeFontParameter()
        p.characters = chars
        p.size = max(12, (13.5f * ui.u).toInt())
        font = gen.generateFont(p)
        p.size = max(10, (11f * ui.u).toInt())
        small = gen.generateFont(p)
        gen.dispose()
    }

    override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        ui.layout(width.toFloat(), height.toFloat())
        makeFonts()
        batch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        shapes.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        val a = ui.world
        baseScale = minOf(a.w / WORLD_W, a.h / WORLD_H)
        setZoomAround(zoom, a.x + a.w / 2f, a.y + a.h / 2f, WORLD_W / 2f, WORLD_H / 2f)
    }

    /** Простая стартовая сцена: дно из камня, чтобы было на что сыпать. */
    private fun buildStartScene() {
        sim.clear()
        val stone = Mat.id("stone")
        for (y in WORLD_H - 3 until WORLD_H) for (x in 0 until WORLD_W) world.put(world.idx(x, y), stone)
    }

    fun clearWorld() {
        buildStartScene()
    }

    override fun render() {
        val dt = Gdx.graphics.deltaTime
        sim.chemSpeed = st.chemSpeeds[st.chemIndex]

        applyInput()

        // фиксированный шаг 1/60 с
        val t0 = System.nanoTime()
        var steps = 0
        if (!st.paused) {
            acc += minOf(dt, 0.1f) * st.speeds[st.speedIndex]
            // тяжёлая сцена: лучше замедлить время, чем рвать картинку
            val maxSteps = if (simMs > 10f) 1 else 2
            while (acc >= Units.DT && steps < maxSteps) { sim.step(); acc -= Units.DT; steps++ }
            if (acc > Units.DT * 2) acc = 0f // телефон не успевает — замедляем время, а не копим долг
        } else if (st.stepRequested) {
            sim.step(); steps = 1
            st.stepRequested = false
        }
        if (steps > 0) {
            val ms = (System.nanoTime() - t0) / 1e6f / steps
            simMs = simMs * 0.9f + ms * 0.1f
        }

        renderer.mode = st.view
        renderer.update()
        updateSound(steps > 0)

        Gdx.gl.glClearColor(0.055f, 0.067f, 0.086f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        val sc = baseScale * zoom
        batch.begin()
        renderer.draw(batch, originX, ui.screenH - originY - WORLD_H * sc, WORLD_W * sc, WORLD_H * sc)
        batch.end()

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        ui.drawShapes(shapes)
        ui.drawInspectBackground(shapes, font.lineHeight)
        shapes.end()
        drawBrushOutline()

        batch.begin()
        ui.drawText(batch, font, small)
        batch.end()
    }

    /** Громкость звуков — по тому, что сейчас происходит в мире. */
    private fun updateSound(running: Boolean) {
        if (sound.muted || !running) {
            sound.fire = 0f; sound.splash = 0f; sound.hiss = 0f; sound.wind = 0f
            return
        }
        val w = world
        val type = w.type
        var flames = 0
        var falling = 0
        var steam = 0
        val burnChem = sim.chemistry
        for (i in 0 until w.n) {
            val id = type[i].toInt()
            if (id == Mat.AIR) continue
            when {
                id == Mat.FLAME -> flames++
                Mat.isLiquid(id) -> if (w.vy[i] > 1.2f || w.vy[i] < -0.4f || abs(w.vx[i]) > 2f) falling++
                id == Mat.STEAM -> if (w.amt[i] > 1.3f) steam++
                Mat.flammable[id] && burnChem.isBurning(i) -> flames++
            }
        }
        sound.fire = sound.level(flames.toFloat(), 160f)
        sound.splash = sound.level(falling.toFloat(), 90f)
        sound.hiss = sound.level(steam.toFloat(), 40f)
        val air = sim.air
        var sum = 0f
        var maxV = 0f
        var maxP = 0f
        var open = 0
        for (c in 0 until air.an) {
            if (air.blocked[c]) continue
            val v = abs(air.vx[c]) + abs(air.vy[c])
            sum += v; open++
            if (v > maxV) maxV = v
            if (air.pv[c] > maxP) maxP = air.pv[c]
        }
        val mean = if (open > 0) sum / open else 0f
        sound.wind = ((mean - 0.05f) * 0.6f + (maxV - 2f) * 0.025f).coerceIn(0f, 1f)
        // резкий скачок давления — взрыв
        if (maxP > 10f && maxP > prevMaxP * 1.4f + 4f) sound.boom(0.25f + maxP / 150f)
        prevMaxP = maxP
    }

    private fun drawBrushOutline() {
        if (drawPointer < 0 || st.tool == Tool.INSPECT) return
        val sc = baseScale * zoom
        val cx = originX + (lastCellX + 0.5f) * sc
        val cy = originY + (lastCellY + 0.5f) * sc
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(1f, 1f, 1f, 0.5f)
        shapes.circle(cx, ui.screenH - cy, (st.brush + 0.5f) * sc, 32)
        shapes.end()
    }

    /** Пока палец на экране — кисть действует каждый кадр (сыплет, льёт, греет). */
    private fun applyInput() {
        if (drawPointer < 0 || pinching) return
        val sc = baseScale * zoom
        val cx = (curScreenX - originX) / sc
        val cy = (curScreenY - originY) / sc
        if (st.tool == Tool.INSPECT) {
            st.inspectX = cx.toInt(); st.inspectY = cy.toInt()
            lastCellX = cx; lastCellY = cy
            return
        }
        val air = sim.air
        when (st.tool) {
            Tool.WIND -> {
                // ветер от пальца: скорость движения пальца → скорость воздуха (клеток за кадр → м/с)
                val k = Units.CELL / Units.DT * 1.5f
                val ux = ((cx - lastCellX) * k).coerceIn(-30f, 30f)
                val uy = ((cy - lastCellY) * k).coerceIn(-30f, 30f)
                if (abs(ux) + abs(uy) > 0.2f) air.brushWind(cx.toInt(), cy.toInt(), st.brush + 2, ux, uy)
                lastCellX = cx; lastCellY = cy
                return
            }
            Tool.PRESSURE_UP, Tool.PRESSURE_DOWN -> {
                val dp = if (st.tool == Tool.PRESSURE_UP) 15f else -15f
                air.brushPressure(cx.toInt(), cy.toInt(), st.brush + 2, dp)
                lastCellX = cx; lastCellY = cy
                return
            }
            else -> {}
        }
        // протягиваем мазок от прошлой точки к новой, чтобы линия была сплошной
        val dist = hypot(cx - lastCellX, cy - lastCellY)
        val step = maxOf(1f, st.brush * 0.7f)
        val n = maxOf(1, (dist / step).toInt())
        for (k in 1..n) {
            val f = k / n.toFloat()
            val x = lastCellX + (cx - lastCellX) * f
            val y = lastCellY + (cy - lastCellY) * f
            val rec = if (st.tool == Tool.DRAW && st.solidFalls && Mat.isSolid(st.substance)) stroke else null
            Brush.apply(world, st.tool, st.substance, x.toInt(), y.toInt(), st.brush, rec)
        }
        lastCellX = cx
        lastCellY = cy
    }

    // ---------------- Масштаб ----------------

    private fun setZoomAround(z: Float, screenX: Float, screenY: Float, worldX: Float, worldY: Float) {
        zoom = z.coerceIn(1f, 10f)
        val sc = baseScale * zoom
        originX = screenX - worldX * sc
        originY = screenY - worldY * sc
        clampView()
    }

    private fun clampView() {
        val a = ui.world
        val sc = baseScale * zoom
        val ww = WORLD_W * sc
        val wh = WORLD_H * sc
        originX = if (ww <= a.w) a.x + (a.w - ww) / 2f else originX.coerceIn(a.x + a.w - ww, a.x)
        originY = if (wh <= a.h) a.y + (a.h - wh) / 2f else originY.coerceIn(a.y + a.h - wh, a.y)
    }

    // ---------------- Подписи ----------------

    override fun hudText(): String {
        val name = if (st.tool == Tool.DRAW) Mat[st.substance].name else st.tool.title
        val tSec = sim.time.toInt()
        val time = "%d:%02d".format(tSec / 60, tSec % 60)
        return "$name   ·   ${Gdx.graphics.framesPerSecond} к/с   ·   шаг ${f1(simMs)} мс   ·   мир $time"
    }

    override fun inspectText(): String? {
        if (st.tool != Tool.INSPECT) return null
        val x = st.inspectX
        val y = st.inspectY
        if (!world.inside(x, y)) return "Коснитесь мира, чтобы посмотреть клетку"
        val i = world.idx(x, y)
        val id = world.type[i].toInt()
        val s = Mat[id]
        val sb = StringBuilder()
        sb.append(s.name)
        val own = world.owner[i].toInt()
        if (own != 0) sim.bodies.get(own)?.let { sb.append(" — тело, ${f0(it.mass * 1000f)} г") }
        if (sim.chemistry.isBurning(i) && Mat.flammable[id]) sb.append(" — горит, сгорело ${pct(world.burn[i])}")
        sb.append('\n').append("Температура ${f1(world.temp[i])} °C")
        sb.append('\n').append("Плотность ${f0(world.cellDensity(i))} кг/м³")
        val m = world.mixId[i].toInt()
        if (m != 0) {
            if (Mat.isPowder(id)) {
                val por = Mat.porosity[id] * world.amt[i]
                sb.append('\n').append("В порах: ${Mat[m].name.lowercase(RU)}, заполнено ${pct(world.mix[i] / por)}")
            } else if (Mat.isLiquid(id)) {
                val sat = Mat.saturation(m, id, world.temp[i])
                sb.append('\n').append("Растворено: ${Mat[m].name.lowercase(RU)} ${f2(world.mix[i])} кг/л")
                if (sat > 0f) sb.append(" (предел ${f2(sat)})")
            }
        }
        if (world.cure[i] > 0f) {
            val what = when {
                Mat.isBinder[id] || (m != 0 && Mat.isBinder[m]) -> "Застывание"
                !Mat.setTime[id].isNaN() -> "Схватывание"
                else -> "Обугливание"
            }
            sb.append('\n').append("$what ${pct(world.cure[i])}")
        }
        if (Mat.isGas(id) && id != Mat.AIR && world.amt[i] > 1.05f) sb.append('\n').append("Сжат в ${f1(world.amt[i])} раза")
        val air = sim.air
        val p = air.pressure(i) / 1000f
        val wx = air.windX(i)
        val wy = air.windY(i)
        sb.append('\n').append("Давление ${f1(p)} кПа · ветер ${f1(hypot(wx, wy))} м/с · кислород ${f1(air.oxygen(i) * 100f)} %")
        val boilTo = Mat.hotTo[id]
        if (Mat.isLiquid(id) && boilTo >= 0 && Mat.isGas(boilTo)) {
            sb.append('\n').append("Закипит при ${f1(air.boilPoint(id, Mat.hotAt[id], i))} °C")
        }
        if ((Mat.isLiquid(id) || Mat.isPowder(id)) && world.amt[i] < 0.98f) sb.append('\n').append("Заполнено ${pct(world.amt[i])} клетки")
        return sb.toString()
    }

    private fun f0(v: Float) = String.format(RU, "%,.0f", v)
    private fun f1(v: Float) = String.format(RU, "%.1f", v)
    private fun f2(v: Float) = String.format(RU, "%.2f", v)
    private fun pct(v: Float) = String.format(RU, "%.0f %%", (v * 100f).coerceIn(0f, 100f))

    override fun dispose() {
        sound.stop()
        renderer.dispose()
        batch.dispose()
        shapes.dispose()
        font.dispose()
        small.dispose()
    }

    // ---------------- Касания ----------------

    private inner class Touch : InputAdapter() {
        override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (pointer >= 10) return false
            val x = screenX.toFloat()
            val y = screenY.toFloat()
            pointerX[pointer] = x; pointerY[pointer] = y; pointerDown[pointer] = true
            if (ui.isUi(x, y)) { ui.touchDown(x, y, pointer); return true }
            val down = pointerDown.count { it }
            if (down >= 2) {
                // второй палец — масштаб, рисование и перетаскивание отменяются
                finishStroke()
                drawPointer = -1
                panPointer = -1
                startPinch()
                return true
            }
            if (st.tool == Tool.HAND) {
                // пустая рука: палец двигает карту
                panPointer = pointer
                panStartX = x; panStartY = y
                panOriginX = originX; panOriginY = originY
                return true
            }
            drawPointer = pointer
            curScreenX = x; curScreenY = y
            val sc = baseScale * zoom
            lastCellX = (x - originX) / sc
            lastCellY = (y - originY) / sc
            return true
        }

        override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
            if (pointer >= 10) return false
            val x = screenX.toFloat()
            val y = screenY.toFloat()
            pointerX[pointer] = x; pointerY[pointer] = y
            if (ui.touchDragged(x, y, pointer)) return true
            if (pinching) { updatePinch(); return true }
            if (pointer == panPointer) {
                originX = panOriginX + (x - panStartX)
                originY = panOriginY + (y - panStartY)
                clampView()
                return true
            }
            if (pointer == drawPointer) { curScreenX = x; curScreenY = y }
            return true
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (pointer >= 10) return false
            pointerDown[pointer] = false
            ui.touchUp(screenX.toFloat(), screenY.toFloat(), pointer)
            if (pointer == drawPointer) { finishStroke(); drawPointer = -1 }
            if (pointer == panPointer) panPointer = -1
            if (pinching && pointerDown.count { it } < 2) pinching = false
            return true
        }

        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.BACK) {
                if (ui.menuOpen) ui.closeMenu() else st.paused = !st.paused
                return true
            }
            return false
        }
    }

    /** Мазок закончен: нарисованное твёрдое становится телами и начинает падать. */
    private fun finishStroke() {
        if (stroke.n > 0) {
            sim.bodies.create(stroke)
            stroke.clear()
        }
    }

    private fun twoPointers(): IntArray? {
        val ids = (0 until 10).filter { pointerDown[it] }
        return if (ids.size >= 2) intArrayOf(ids[0], ids[1]) else null
    }

    private fun startPinch() {
        val p = twoPointers() ?: return
        pinching = true
        pinchDist0 = maxOf(1f, hypot(pointerX[p[0]] - pointerX[p[1]], pointerY[p[0]] - pointerY[p[1]]))
        pinchZoom0 = zoom
        val mx = (pointerX[p[0]] + pointerX[p[1]]) / 2f
        val my = (pointerY[p[0]] + pointerY[p[1]]) / 2f
        val sc = baseScale * zoom
        pinchWorldX = (mx - originX) / sc
        pinchWorldY = (my - originY) / sc
    }

    private fun updatePinch() {
        val p = twoPointers() ?: return
        val d = maxOf(1f, hypot(pointerX[p[0]] - pointerX[p[1]], pointerY[p[0]] - pointerY[p[1]]))
        val mx = (pointerX[p[0]] + pointerX[p[1]]) / 2f
        val my = (pointerY[p[0]] + pointerY[p[1]]) / 2f
        setZoomAround(pinchZoom0 * d / pinchDist0, mx, my, pinchWorldX, pinchWorldY)
    }
}
