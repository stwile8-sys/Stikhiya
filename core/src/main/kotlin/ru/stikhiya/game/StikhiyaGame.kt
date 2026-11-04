package ru.stikhiya.game

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import ru.stikhiya.sim.Brush
import ru.stikhiya.sim.B
import ru.stikhiya.sim.HINGE
import ru.stikhiya.sim.ROPE
import ru.stikhiya.sim.Craft
import org.jbox2d.common.Vec2
import ru.stikhiya.sim.Examples
import ru.stikhiya.sim.SceneIO
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
    // рука держит тело
    private var grabPointer = -1
    // шарнир: ось поставлена, ждём деталь
    private var pivotX = Float.NaN
    private var pivotY = 0f
    private var pivotBody = 0
    private var pivotTapPointer = -1
    private var hingeMask: BooleanArray? = null
    private var toast = ""
    private var toastUntil = 0L

    private fun say(s: String) { toast = s; toastUntil = System.currentTimeMillis() + 2500L }

    private fun cellX(x: Float) = (x - originX) / (baseScale * zoom)
    private fun cellY(y: Float) = (y - originY) / (baseScale * zoom)
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
        renderer.electric = sim.electric
        batch = SpriteBatch()
        shapes = ShapeRenderer()
        ui = Ui(st, this)
        Gdx.input.setCatchKey(Input.Keys.BACK, true)
        Gdx.input.inputProcessor = Touch()
        resize(Gdx.graphics.width, Gdx.graphics.height)
        buildStartScene()
        sound.muted = !prefs().getBoolean("sound", true)
        renderer.glowOn = prefs().getBoolean("glow", true)
        setBackground(prefs().getInteger("background2", 0))
        ui.toast("«Меню» справа: готовые примеры (печь, плотина, термит, натрий…) и сохранение сцен. «Поиск» снизу — найти вещество.", 8f)
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

    // ---------------- Сцены: сохранение, загрузка, примеры ----------------

    private val slots = 6
    private fun slotFile(k: Int) = Gdx.files.local("scenes/slot$k.stk")
    private val slotInfo = arrayOfNulls<String>(slots + 1)

    private fun readSlots() {
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", RU)
        for (k in 1..slots) {
            val f = slotFile(k)
            slotInfo[k] = if (!f.exists()) null else try {
                f.read().use { inp -> SceneIO.header(inp) }?.let { "${it.name} · ${fmt.format(java.util.Date(it.time))}" }
            } catch (e: Exception) { null }
        }
    }

    private fun saveSlot(k: Int, name: String) {
        try {
            slotFile(k).write(false).use { out -> SceneIO.save(sim, out, name) }
            ui.toast("Сохранено в слот $k: $name", 3f)
        } catch (e: Exception) {
            ui.toast("Не удалось сохранить: ${e.message}", 5f)
        }
    }

    private fun askSave(k: Int) {
        val def = slotInfo[k]?.substringBefore(" · ") ?: "Сцена $k"
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) {
                Gdx.app.postRunnable { saveSlot(k, text.trim().ifEmpty { def }) }
            }
            override fun canceled() {}
        }, "Название сцены", def, "")
    }

    private fun loadSlot(k: Int) {
        val f = slotFile(k)
        if (!f.exists()) { ui.toast("Слот $k пуст", 2f); return }
        val ok = try { f.read().use { inp -> SceneIO.load(sim, inp) } } catch (e: Exception) { false }
        ui.toast(if (ok) "Загружено: ${slotInfo[k] ?: "слот $k"}" else "Не удалось открыть сохранение", 3f)
    }

    private fun loadExample(ex: Examples.Example) {
        val tip = ex.build(sim)
        st.paused = false
        ui.toast(ex.title + ". " + tip, 12f)
    }

    // ---------------- Фон ----------------

    private val bgNames = arrayOf("светлый", "тёмный", "лаборатория", "луг", "ночь в горах", "кухня")
    private val bgFiles = arrayOf("", "", "lab", "meadow", "night", "kitchen")
    private val bgPlain = intArrayOf(0xDAE2EA, 0x0E1116, 0xDAE2EA, 0xDAE2EA, 0x1A2030, 0xDAE2EA)
    private var bgIndex = 0

    private fun setBackground(k: Int) {
        bgIndex = k.coerceIn(0, bgNames.size - 1)
        renderer.background?.dispose()
        renderer.background = null
        renderer.setPlain(bgPlain[bgIndex])
        if (bgFiles[bgIndex].isNotEmpty()) {
            try {
                renderer.background = Texture(Gdx.files.internal("backgrounds/${bgFiles[bgIndex]}.png")).apply {
                    setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
                }
            } catch (e: Exception) { bgIndex = 0 }
        }
        prefs().putInteger("background2", bgIndex).flush()
    }

    private fun menuRows(): List<List<MenuEntry>> {
        readSlots()
        val rows = ArrayList<List<MenuEntry>>()
        rows += listOf(MenuEntry({ "Примеры" }, header = true))
        val ex = Examples.list
        for (k in ex.indices step 3) {
            rows += (k until minOf(k + 3, ex.size)).map { j ->
                MenuEntry({ ex[j].title }, keepOpen = false) { loadExample(ex[j]) }
            }
        }
        rows += listOf(MenuEntry({ "Мои сцены (хранятся в памяти телефона)" }, header = true))
        for (k in 1..slots) {
            rows += listOf(
                MenuEntry({ "$k. " + (slotInfo[k] ?: "пусто") }, header = true),
                MenuEntry({ "Сохранить" }, keepOpen = false) { askSave(k) },
                MenuEntry({ "Загрузить" }, keepOpen = false) { loadSlot(k) }
            )
        }
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
            MenuEntry({ if (sound.muted) "Звук: выкл" else "Звук: вкл" }, { !sound.muted }) { toggleSound() },
            MenuEntry({ "Фон: ${bgNames[bgIndex]}" }, { bgFiles[bgIndex].isNotEmpty() }) { setBackground((bgIndex + 1) % bgNames.size) }
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
        Brush.tapSource = st.tapSource

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
        ui.drawToastBackground(shapes, small)
        shapes.end()
        drawBrushOutline()
        drawJoints()

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

    /** Оси шарниров и крепления — кружки поверх мира. */
    private fun drawJoints() {
        val js = sim.bodies.joints
        if (js.isEmpty() && pivotX.isNaN() && ropeN < 2) return
        val sc = baseScale * zoom
        shapes.begin(ShapeRenderer.ShapeType.Line)
        val p = Vec2()
        for (l in js) {
            if (l.kind == ROPE) continue
            l.joint.getAnchorA(p)
            val x = originX + p.x / B * sc
            val y = originY + p.y / B * sc
            if (l.kind == HINGE) shapes.setColor(1f, 0.85f, 0.3f, 0.9f) else shapes.setColor(0.5f, 0.8f, 1f, 0.9f)
            shapes.circle(x, ui.screenH - y, maxOf(3f, 1.2f * sc), 16)
        }
        if (ropeN >= 2) {
            // будущая верёвка — пунктиром по пути пальца
            shapes.setColor(0.9f, 0.8f, 0.55f, 0.9f)
            for (k in 1 until ropeN) shapes.line(originX + ropeX[k - 1] * sc, ui.screenH - originY - ropeY[k - 1] * sc,
                originX + ropeX[k] * sc, ui.screenH - originY - ropeY[k] * sc)
        }
        if (!pivotX.isNaN()) {
            shapes.setColor(1f, 0.85f, 0.3f, 1f)
            val x = originX + pivotX * sc; val y = originY + pivotY * sc
            shapes.circle(x, ui.screenH - y, maxOf(5f, 2f * sc), 20)
            shapes.line(x - 8f, ui.screenH - y, x + 8f, ui.screenH - y)
            shapes.line(x, ui.screenH - y - 8f, x, ui.screenH - y + 8f)
        }
        shapes.end()
    }

    private fun drawBrushOutline() {
        if (drawPointer < 0 || st.tool == Tool.INSPECT) return
        val sc = baseScale * zoom
        val cx = originX + (lastCellX + 0.5f) * sc
        val cy = originY + (lastCellY + 0.5f) * sc
        shapes.begin(ShapeRenderer.ShapeType.Line)
        shapes.setColor(0.12f, 0.16f, 0.24f, 0.75f)
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
        // верёвка и трос: запоминаем путь пальца, звенья появятся, когда палец отпустят
        if (st.tool == Tool.DRAW && Craft.isRope(st.substance)) {
            if (ropeN == 0 || hypot(cx - ropeX[ropeN - 1], cy - ropeY[ropeN - 1]) >= 1f) {
                if (ropeN == ropeX.size) { ropeX = ropeX.copyOf(ropeN * 2); ropeY = ropeY.copyOf(ropeN * 2) }
                ropeX[ropeN] = cx; ropeY[ropeN] = cy; ropeN++
            }
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
            val hingePart = st.tool == Tool.HINGE && !pivotX.isNaN() && Mat.isSolid(st.substance)
            val rec = if ((st.tool == Tool.DRAW && st.solidFalls && Mat.isSolid(st.substance) && !Brush.isFixed(st.substance)) || hingePart) stroke else null
            val before = stroke.n
            Brush.apply(world, st.tool, st.substance, x.toInt(), y.toInt(), st.brush, rec)
            hingeMask?.let { m -> for (k in before until stroke.n) m[stroke.a[k]] = true }
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
        if (System.currentTimeMillis() < toastUntil) return toast
        var name = if (st.tool == Tool.DRAW) Mat[st.substance].name else st.tool.title
        if (st.tool == Tool.HINGE) name = if (pivotX.isNaN()) "Шарнир: коснитесь, где будет ось" else "Шарнир: рисуйте деталь или коснитесь готового предмета"
        if (st.tool == Tool.FIX) name = "Крепление: коснитесь тела"
        if (st.tool == Tool.PHYSICS) name = "Физика: коснитесь предмета — станет подвижным (или снова неподвижным)"
        if (st.tool == Tool.HAND) name = "Рука: тело — тянуть, пусто — двигать карту"
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
        if (Mat[id].category == ru.stikhiya.sim.Category.LIFE && Mat.isSolid(id) && id != sim.plants.dry)
            sb.append('\n').append("Воды в растении ${pct(world.mix[i])}")
        if (id == Mat.id("tap") && world.mixId[i].toInt() != 0) sb.append('\n').append("Льёт: ${Mat[world.mixId[i].toInt()].name.lowercase(RU)}")
        if (Mat.isGas(id) && id != Mat.AIR && world.amt[i] > 1.05f) sb.append('\n').append("Сжат в ${f1(world.amt[i])} раза")
        val air = sim.air
        val p = air.pressure(i) / 1000f
        val wx = air.windX(i)
        val wy = air.windY(i)
        if (sim.electric.active && sim.electric.isConductor(i))
            sb.append('\n').append("Напряжение ${f1(sim.electric.phi[i])} В · ток ${f2(sim.electric.currentAt(i))} А")
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
            if (st.tool != Tool.HINGE && !pivotX.isNaN()) cancelHinge()
            if (st.tool == Tool.HAND) {
                // рука на теле — берёт его пальцем, на выключателе — щёлкает, на пустом месте — двигает карту
                val id = sim.bodies.bodyAt(cellX(x).toInt(), cellY(y).toInt())
                if (id != 0 && sim.bodies.grab(id, cellX(x), cellY(y))) { grabPointer = pointer; return true }
                if (sim.toggleSwitch(cellX(x).toInt(), cellY(y).toInt())) { say("Щёлк!"); return true }
                panPointer = pointer
                panStartX = x; panStartY = y
                panOriginX = originX; panOriginY = originY
                return true
            }
            if (st.tool == Tool.LIGHTNING) {
                sim.electric.lightning(cellX(x).toInt().coerceIn(0, world.w - 1), cellY(y).toInt().coerceIn(0, world.h - 1))
                return true
            }
            if (st.tool == Tool.PHYSICS) {
                val cx = cellX(x).toInt(); val cy = cellY(y).toInt()
                val id = sim.bodies.bodyAt(cx, cy)
                if (id != 0) { sim.bodies.freeze(id); say("Предмет стал неподвижным") }
                else if (sim.bodies.makeDynamicAt(cx, cy) != 0) say("Предмет стал физичным — падает, плавает, его можно тянуть «Рукой»")
                else say("Физика: коснитесь твёрдого предмета")
                return true
            }
            if (st.tool == Tool.FIX) {
                val cx = cellX(x); val cy = cellY(y)
                val id = sim.bodies.bodyAt(cx.toInt(), cy.toInt())
                if (id == 0) say("Крепление: коснитесь тела (нарисованного при «Твёрдое: падает»)")
                else say(if (sim.bodies.toggleFix(id, 0, cx, cy)) "Тело закреплено — коснитесь ещё раз, чтобы снять" else "Крепление снято")
                return true
            }
            if (st.tool == Tool.HINGE && pivotX.isNaN()) {
                // первое касание — ось шарнира; держится за тело под пальцем или за стену
                pivotX = cellX(x); pivotY = cellY(y)
                pivotBody = sim.bodies.bodyAt(pivotX.toInt(), pivotY.toInt())
                pivotTapPointer = pointer
                val m = BooleanArray(world.n)
                hingeMask = m
                renderer.dimMask = m
                say(if (Mat.isSolid(st.substance)) "Ось поставлена. Нарисуйте деталь от оси" else "Ось поставлена. Выберите твёрдое вещество и рисуйте деталь")
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
            if (pointer == grabPointer) { sim.bodies.drag(cellX(x), cellY(y)); return true }
            if (pointer == drawPointer) { curScreenX = x; curScreenY = y }
            return true
        }

        override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
            if (pointer >= 10) return false
            pointerDown[pointer] = false
            ui.touchUp(screenX.toFloat(), screenY.toFloat(), pointer)
            if (pointer == drawPointer) { finishStroke(); drawPointer = -1 }
            if (pointer == panPointer) panPointer = -1
            if (pointer == grabPointer) { sim.bodies.release(); grabPointer = -1 }
            if (pointer == pivotTapPointer) pivotTapPointer = -1
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
    private var ropeX = FloatArray(256)
    private var ropeY = FloatArray(256)
    private var ropeN = 0

    private fun finishStroke() {
        if (ropeN > 0) {
            val n = sim.bodies.createRope(ropeX, ropeY, ropeN, st.substance)
            if (n > 0) say("Верёвка: $n звеньев — концы держатся за то, чего касались")
            ropeN = 0
            return
        }
        if (st.tool == Tool.HINGE && !pivotX.isNaN() && stroke.n == 0 && !lastCellX.isNaN()) {
            // нарисованного нет — касание по готовому предмету: он становится деталью на этой оси
            val cx = lastCellX.toInt(); val cy = lastCellY.toInt()
            var part = sim.bodies.bodyAt(cx, cy)
            if (part == 0) part = sim.bodies.makeDynamicAt(cx, cy)
            if (part != 0 && part != pivotBody && sim.bodies.hinge(part, pivotBody, pivotX, pivotY))
                say("Предмет на шарнире. Двигайте его «Рукой»")
            cancelHinge()
            return
        }
        if (st.tool == Tool.HINGE && !pivotX.isNaN()) {
            if (stroke.n > 0) {
                sim.bodies.create(stroke)
                // деталь — тело, которому принадлежит клетка мазка, ближайшая к оси
                var best = -1; var bd = Float.MAX_VALUE
                for (k in 0 until stroke.n) {
                    val i = stroke.a[k]
                    val dx = i % world.w + 0.5f - pivotX; val dy = i / world.w + 0.5f - pivotY
                    val d = dx * dx + dy * dy
                    if (d < bd && world.owner[i].toInt() != 0) { bd = d; best = i }
                }
                val part = if (best >= 0) world.owner[best].toInt() else 0
                if (part != 0 && sim.bodies.hinge(part, if (pivotBody == part) 0 else pivotBody, pivotX, pivotY))
                    say("Деталь на шарнире. Откройте её «Рукой»")
                stroke.clear()
            }
            cancelHinge()
            return
        }
        if (stroke.n > 0) {
            sim.bodies.create(stroke)
            stroke.clear()
        }
    }

    private fun cancelHinge() {
        pivotX = Float.NaN
        hingeMask = null
        renderer.dimMask = null
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
