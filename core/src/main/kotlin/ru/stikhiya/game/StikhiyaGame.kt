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
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Simulator
import ru.stikhiya.sim.Tool
import ru.stikhiya.sim.Units
import ru.stikhiya.sim.World
import java.util.Locale
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

    // вид мира на экране
    private var baseScale = 1f
    private var zoom = 1f
    private var originX = 0f
    private var originY = 0f

    private var acc = 0f
    private var simMs = 0f

    // касания
    private var drawPointer = -1
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
        renderer = WorldRenderer(world)
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        ui = Ui(st, this)
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = Touch()
        resize(Gdx.graphics.width, Gdx.graphics.height)
        buildStartScene()
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
        world.clear()
        val stone = Mat.id("stone")
        for (y in WORLD_H - 3 until WORLD_H) for (x in 0 until WORLD_W) world.put(world.idx(x, y), stone)
    }

    override fun clearWorld() {
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
            while (acc >= Units.DT && steps < 2) { sim.step(); acc -= Units.DT; steps++ }
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
        // протягиваем мазок от прошлой точки к новой, чтобы линия была сплошной
        val dist = hypot(cx - lastCellX, cy - lastCellY)
        val step = maxOf(1f, st.brush * 0.7f)
        val n = maxOf(1, (dist / step).toInt())
        for (k in 1..n) {
            val f = k / n.toFloat()
            val x = lastCellX + (cx - lastCellX) * f
            val y = lastCellY + (cy - lastCellY) * f
            Brush.apply(world, st.tool, st.substance, x.toInt(), y.toInt(), st.brush)
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
        if ((Mat.isLiquid(id) || Mat.isPowder(id)) && world.amt[i] < 0.98f) sb.append('\n').append("Заполнено ${pct(world.amt[i])} клетки")
        return sb.toString()
    }

    private fun f0(v: Float) = String.format(RU, "%,.0f", v)
    private fun f1(v: Float) = String.format(RU, "%.1f", v)
    private fun f2(v: Float) = String.format(RU, "%.2f", v)
    private fun pct(v: Float) = String.format(RU, "%.0f %%", (v * 100f).coerceIn(0f, 100f))

    override fun dispose() {
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
            if (pointer == drawPointer) drawPointer = -1
            if (pointer == panPointer) panPointer = -1
            if (pinching && pointerDown.count { it } < 2) pinching = false
            return true
        }

        override fun keyDown(keycode: Int): Boolean {
            if (keycode == Input.Keys.BACK) { st.paused = !st.paused; return true }
            return false
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
