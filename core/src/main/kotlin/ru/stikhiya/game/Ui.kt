package ru.stikhiya.game

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import ru.stikhiya.sim.Category
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Tool
import kotlin.math.abs

class Rect(var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f) {
    fun set(x: Float, y: Float, w: Float, h: Float) { this.x = x; this.y = y; this.w = w; this.h = h }
    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

/** Кнопка интерфейса: прямоугольник, подпись и действие. */
class Button(
    val rect: Rect,
    val title: () -> String,
    val value: (() -> String)? = null,
    val selected: () -> Boolean = { false },
    val action: () -> Unit
)

/**
 * Интерфейс: слева — инструменты, справа — время и вид, снизу — вещества по категориям.
 * Координаты — экранные, ось Y вниз.
 */
class Ui(private val st: GameState, private val callbacks: Callbacks) {
    interface Callbacks {
        fun clearWorld()
        fun hudText(): String
        fun inspectText(): String?
    }

    var u = 3f
        private set
    var screenW = 0f
        private set
    var screenH = 0f
        private set

    val world = Rect()
    private val left = Rect()
    private val right = Rect()
    private val bottom = Rect()
    private val chipsRow = Rect()
    private val buttons = ArrayList<Button>()
    private val tabs = ArrayList<Pair<Rect, Category>>()

    private var scrollX = 0f
    private var scrollPointer = -1
    private var scrollStartX = 0f
    private var scrollStart = 0f
    private var scrollMoved = false

    private val layoutText = GlyphLayout()

    // палитра интерфейса
    private val cPanel = Color(0x161A21FF)
    private val cButton = Color(0x232934FF)
    private val cSelected = Color(0x2F6FD8FF)
    private val cText = Color(0xE8ECF1FF.toInt())
    private val cDim = Color(0x9AA4B2FF.toInt())
    private val cWarn = Color(0xD8572FFF.toInt())

    fun layout(width: Float, height: Float) {
        screenW = width
        screenH = height
        u = height / 360f
        val side = 62f * u
        val bottomH = 60f * u
        left.set(0f, 0f, side, height - bottomH)
        right.set(width - side, 0f, side, height - bottomH)
        bottom.set(0f, height - bottomH, width, bottomH)
        world.set(side, 0f, width - 2 * side, height - bottomH)
        chipsRow.set(0f, height - 37f * u, width, 37f * u)

        buttons.clear()
        // ---- левая панель: инструменты ----
        var y = 4f * u
        val th = 33f * u
        val pad = 4f * u
        for (t in Tool.values().filter { it.inPanel }) {
            buttons += Button(Rect(pad, y, side - 2 * pad, th), { t.title }, null, { st.tool == t }) { st.tool = t }
            y += th + 2f * u
        }
        y += 16f * u // место под подпись «Кисть Ø…»
        val half = (side - 2 * pad - 2f * u) / 2f
        buttons += Button(Rect(pad, y, half, 31f * u), { "−" }) { st.brush = maxOf(0, st.brush - 1) }
        buttons += Button(Rect(pad + half + 2f * u, y, half, 31f * u), { "+" }) { st.brush = minOf(24, st.brush + 1) }
        val bh = 38f * u

        // ---- правая панель: время и вид ----
        val rx = width - side + pad
        val rw = side - 2 * pad
        y = 4f * u
        val rbh = 34f * u
        buttons += Button(Rect(rx, y, rw, rbh), { if (st.paused) "Пуск" else "Пауза" }, null, { st.paused }) {
            st.paused = !st.paused
        }
        y += rbh + 2f * u
        buttons += Button(Rect(rx, y, rw, rbh), { "Шаг" }) { st.paused = true; st.stepRequested = true }
        y += rbh + 2f * u
        val bh2 = 40f * u
        buttons += Button(Rect(rx, y, rw, bh2), { "Твёрдое" }, { if (st.solidFalls) "падает" else "стоит" }) {
            st.solidFalls = !st.solidFalls
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, bh2), { "Время" }, { st.speedNames[st.speedIndex] }) {
            st.speedIndex = (st.speedIndex + 1) % st.speeds.size
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, bh2), { "Химия" }, { st.chemNames[st.chemIndex] }) {
            st.chemIndex = (st.chemIndex + 1) % st.chemSpeeds.size
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, bh2), { "Вид" }, { st.view.title }) {
            st.view = ViewMode.values()[(st.view.ordinal + 1) % ViewMode.values().size]
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, rbh), {
            if (System.currentTimeMillis() < st.clearArmedUntil) "Точно?" else "Очистить"
        }, null, { System.currentTimeMillis() < st.clearArmedUntil }) {
            val now = System.currentTimeMillis()
            if (now < st.clearArmedUntil) { callbacks.clearWorld(); st.clearArmedUntil = 0L }
            else st.clearArmedUntil = now + 2500L
        }

        // ---- вкладки категорий ----
        tabs.clear()
        val tw = 78f * u
        var tx = 4f * u
        for (c in Category.values()) {
            tabs += Rect(tx, bottom.y + 2f * u, tw - 4f * u, 19f * u) to c
            tx += tw
        }
    }

    // ---------------- Отрисовка ----------------

    fun drawShapes(sh: ShapeRenderer) {
        fill(sh, left, cPanel)
        fill(sh, right, cPanel)
        fill(sh, bottom, cPanel)
        for (b in buttons) fill(sh, b.rect, if (b.selected()) cSelected else cButton)
        for ((r, c) in tabs) fill(sh, r, if (st.category == c) cSelected else cButton)
        // фишки веществ
        forEachChip { r, id ->
            val sel = if (id < 0) st.tool == chipTool(id) else st.substance == id && st.tool == Tool.DRAW
            fill(sh, r, if (sel) cSelected else cButton)
            val col = if (id < 0) toolColor(chipTool(id)) else Mat[id].color
            sh.setColor(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f, 1f)
            val sw = Rect(r.x + 4f * u, r.y + 7f * u, 9f * u, r.h - 14f * u)
            rectGl(sh, sw)
        }
        // рамка кисти размера
    }

    fun drawText(batch: SpriteBatch, font: BitmapFont, small: BitmapFont) {
        for (b in buttons) {
            val v = b.value
            if (v == null) {
                centered(batch, font, b.title(), b.rect, b.rect.y + b.rect.h / 2f, cText)
            } else {
                centered(batch, small, b.title(), b.rect, b.rect.y + b.rect.h * 0.32f, cDim)
                centered(batch, font, v(), b.rect, b.rect.y + b.rect.h * 0.68f, cText)
            }
        }
        // размер кисти над кнопками − +
        val minus = buttons.first { it.title() == "−" }.rect
        centered(batch, small, "Кисть Ø${st.brush * 2 + 1} см", Rect(left.x, minus.y - 14f * u, left.w, 12f * u),
            minus.y - 8f * u, cDim)
        for ((r, c) in tabs) centered(batch, small, c.title, r, r.y + r.h / 2f, cText)
        forEachChip { r, id ->
            small.color = cText
            text(batch, small, if (id < 0) chipTool(id).title else Mat[id].name, r.x + 16f * u, r.y + r.h / 2f)
        }
        // строка состояния справа от вкладок
        val hud = callbacks.hudText()
        small.color = cDim
        layoutText.setText(small, hud)
        text(batch, small, hud, screenW - layoutText.width - 8f * u, bottom.y + 12f * u)

        // инспектор
        val info = callbacks.inspectText()
        if (info != null) {
            font.color = cText
            text(batch, font, info, world.x + 8f * u, world.y + 12f * u)
        }
    }

    fun drawInspectBackground(sh: ShapeRenderer, fontLineH: Float) {
        val info = callbacks.inspectText() ?: return
        val lines = info.count { it == '\n' } + 1
        sh.setColor(0f, 0f, 0f, 0.6f)
        rectGl(sh, Rect(world.x + 4f * u, world.y + 3f * u, minOf(world.w - 8f * u, 430f * u), lines * fontLineH + 8f * u))
    }

    /** Фишки категории: вещества, а в «Особых» сначала кисти воздуха (номера меньше нуля). */
    private fun chips(): List<Int> {
        val list = Mat.palette[st.category].orEmpty()
        if (st.category != Category.SPECIAL) return list
        return airTools.map { -(it.ordinal + 1) } + list
    }

    private val airTools = Tool.values().filter { !it.inPanel }

    private fun chipTool(id: Int) = Tool.values()[-id - 1]

    private fun toolColor(t: Tool) = when (t) {
        Tool.WIND -> 0x7FB8E6
        Tool.PRESSURE_UP -> 0xE07A5F
        Tool.PRESSURE_DOWN -> 0x5F8FE0
        else -> 0x888888
    }

    private inline fun forEachChip(f: (Rect, Int) -> Unit) {
        val list = chips()
        val cw = 82f * u
        var x = 4f * u - scrollX
        val r = Rect()
        for (id in list) {
            r.set(x, chipsRow.y + 2f * u, cw - 4f * u, chipsRow.h - 5f * u)
            if (r.x + r.w > 0f && r.x < screenW) f(r, id)
            x += cw
        }
    }

    private fun chipsWidth(): Float = chips().size * 82f * u + 8f * u

    private fun fill(sh: ShapeRenderer, r: Rect, c: Color) {
        sh.color = c
        rectGl(sh, r)
    }

    private fun rectGl(sh: ShapeRenderer, r: Rect) {
        sh.rect(r.x, screenH - r.y - r.h, r.w, r.h)
    }

    private fun centered(batch: SpriteBatch, font: BitmapFont, s: String, r: Rect, cy: Float, c: Color) {
        font.color = c
        layoutText.setText(font, s)
        val x = r.x + (r.w - layoutText.width) / 2f
        font.draw(batch, s, x, screenH - cy + layoutText.height / 2f)
    }

    /** Текст, у которого cy — центр первой строки (экранные координаты). */
    private fun text(batch: SpriteBatch, font: BitmapFont, s: String, x: Float, cy: Float) {
        font.draw(batch, s, x, screenH - cy + font.capHeight / 2f)
    }

    // ---------------- Касания ----------------

    /** true — касание попало в интерфейс. */
    fun touchDown(x: Float, y: Float, pointer: Int): Boolean {
        if (chipsRow.contains(x, y)) {
            scrollPointer = pointer
            scrollStartX = x
            scrollStart = scrollX
            scrollMoved = false
            return true
        }
        for (b in buttons) if (b.rect.contains(x, y)) { b.action(); return true }
        for ((r, c) in tabs) if (r.contains(x, y)) { st.selectCategory(c); scrollX = 0f; return true }
        return left.contains(x, y) || right.contains(x, y) || bottom.contains(x, y)
    }

    fun touchDragged(x: Float, y: Float, pointer: Int): Boolean {
        if (pointer != scrollPointer) return false
        val dx = x - scrollStartX
        if (abs(dx) > 8f * u) scrollMoved = true
        val maxScroll = maxOf(0f, chipsWidth() - screenW)
        scrollX = (scrollStart - dx).coerceIn(0f, maxScroll)
        return true
    }

    fun touchUp(x: Float, y: Float, pointer: Int): Boolean {
        if (pointer != scrollPointer) return false
        scrollPointer = -1
        if (!scrollMoved) {
            forEachChip { r, id ->
                if (r.contains(x, y)) {
                    if (id < 0) st.tool = chipTool(id) else { st.substance = id; st.tool = Tool.DRAW }
                }
            }
        }
        return true
    }

    fun isUi(x: Float, y: Float) = !world.contains(x, y)
}
