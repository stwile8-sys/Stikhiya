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

/** Кнопка интерфейса: прямоугольник, подпись и действие. scroll — кнопка в прокручиваемой левой панели. */
class Button(
    val rect: Rect,
    val title: () -> String,
    val value: (() -> String)? = null,
    val selected: () -> Boolean = { false },
    val scroll: Boolean = false,
    val action: () -> Unit
)

/** Пункт всплывающего меню. header — просто подпись строки. */
class MenuEntry(
    val title: () -> String,
    val selected: () -> Boolean = { false },
    val header: Boolean = false,
    val keepOpen: Boolean = true,
    val action: () -> Unit = {}
)

/**
 * Интерфейс: слева — инструменты, справа — время и вид, снизу — вещества по категориям.
 * Поверх мира может открываться меню (сцены, настройки).
 * Координаты — экранные, ось Y вниз.
 */
class Ui(private val st: GameState, private val callbacks: Callbacks) {
    interface Callbacks {
        fun hudText(): String
        fun inspectText(): String?
        fun soundOn(): Boolean
        fun toggleSound()
        fun openMenu()
        fun askSearch()
    }

    var u = 3f
        private set
    var screenW = 0f
        private set
    var screenH = 0f
        private set

    val world = Rect()
    private val left = Rect()
    private val leftTools = Rect()
    private val right = Rect()
    private val bottom = Rect()
    private val tabsRow = Rect()
    private val chipsRow = Rect()
    private val buttons = ArrayList<Button>()

    // прокрутки: вещества, вкладки, инструменты, меню
    private var scrollX = 0f
    private var tabsX = 0f
    private var leftY = 0f
    private var leftMax = 0f
    private var menuY = 0f
    private var dragPointer = -1
    private var dragKind = 0      // 1 — вещества, 2 — вкладки, 3 — инструменты, 4 — меню
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStart = 0f
    private var dragMoved = false

    // всплывающее меню
    private var menuTitle = ""
    private var menuRows: List<List<MenuEntry>>? = null
    private val menuRect = Rect()
    val menuOpen: Boolean get() = menuRows != null

    private val layoutText = GlyphLayout()

    // подсказка поверх мира (после загрузки примера)
    private var toastText: String? = null
    private var toastUntil = 0L
    private var toastLines: List<String> = emptyList()
    private var toastFont: BitmapFont? = null

    fun toast(text: String, seconds: Float = 9f) {
        toastText = text
        toastUntil = System.currentTimeMillis() + (seconds * 1000).toLong()
        toastFont = null
    }

    /** Перенос по словам под ширину. */
    private fun wrap(font: BitmapFont, text: String, width: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.split(' ')) {
            val trial = if (cur.isEmpty()) word else "$cur $word"
            layoutText.setText(font, trial)
            if (layoutText.width > width && cur.isNotEmpty()) { lines += cur.toString(); cur = StringBuilder(word) }
            else { cur = StringBuilder(trial) }
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        return lines
    }

    private fun toastRect(font: BitmapFont): Rect? {
        val t = toastText ?: return null
        if (System.currentTimeMillis() > toastUntil) { toastText = null; return null }
        val mw = minOf(world.w - 16f * u, 520f * u)
        if (toastFont !== font) { toastLines = wrap(font, t, mw - 16f * u); toastFont = font }
        val h = toastLines.size * font.lineHeight + 10f * u
        return Rect(world.x + (world.w - mw) / 2f, world.y + world.h - h - 8f * u, mw, h)
    }

    // палитра интерфейса
    private val cPanel = Color(0x161A21FF)
    private val cButton = Color(0x232934FF)
    private val cSelected = Color(0x2F6FD8FF)
    private val cText = Color(0xE8ECF1FF.toInt())
    private val cDim = Color(0x9AA4B2FF.toInt())
    private val cShade = Color(0f, 0f, 0f, 0.55f)

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
        tabsRow.set(0f, bottom.y, width, 23f * u)
        chipsRow.set(0f, height - 37f * u, width, 37f * u)

        buttons.clear()
        // ---- левая панель: инструменты (прокручиваются, если не влезают), внизу — размер кисти ----
        val pad = 4f * u
        val brushH = 31f * u
        val brushY = left.h - brushH - 4f * u
        leftTools.set(0f, 0f, side, brushY - 16f * u)
        var y = 4f * u
        val th = 33f * u
        for (t in Tool.values().filter { it.inPanel }) {
            buttons += Button(Rect(pad, y, side - 2 * pad, th), { t.title }, null, { st.tool == t }, scroll = true) { st.tool = t }
            y += th + 2f * u
        }
        leftMax = maxOf(0f, y - leftTools.h)
        leftY = leftY.coerceIn(0f, leftMax)
        val half = (side - 2 * pad - 2f * u) / 2f
        buttons += Button(Rect(pad, brushY, half, brushH), { "−" }) { st.brush = maxOf(0, st.brush - 1) }
        buttons += Button(Rect(pad + half + 2f * u, brushY, half, brushH), { "+" }) { st.brush = minOf(24, st.brush + 1) }

        // ---- правая панель: время, вид, звук, меню ----
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
        buttons += Button(Rect(rx, y, rw, bh2), { "Вид" }, { st.view.title }) {
            st.view = ViewMode.values()[(st.view.ordinal + 1) % ViewMode.values().size]
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, bh2), { "Звук" }, { if (callbacks.soundOn()) "вкл" else "выкл" }) {
            callbacks.toggleSound()
        }
        y += bh2 + 2f * u
        buttons += Button(Rect(rx, y, rw, minOf(rbh, right.h - y - 2f * u)), { "Меню" }, null, { menuOpen }) {
            callbacks.openMenu()
        }
        tabsX = tabsX.coerceIn(0f, maxOf(0f, tabsWidth() - screenW))
        if (menuRows != null) layoutMenu()
    }

    // ---------------- Всплывающее меню ----------------

    fun showMenu(title: String, rows: List<List<MenuEntry>>) {
        menuTitle = title
        menuRows = rows
        menuY = 0f
        layoutMenu()
    }

    fun closeMenu() { menuRows = null }

    private val rowH get() = 30f * u
    private val rowGap get() = 3f * u

    private fun layoutMenu() {
        val rows = menuRows ?: return
        val mw = minOf(world.w - 8f * u, 520f * u)
        val content = 26f * u + rows.size * (rowH + rowGap)
        val mh = minOf(world.h - 8f * u, content + 6f * u)
        menuRect.set(world.x + (world.w - mw) / 2f, world.y + (world.h - mh) / 2f, mw, mh)
        menuY = menuY.coerceIn(0f, maxOf(0f, content + 6f * u - mh))
    }

    private inline fun forEachMenuEntry(f: (Rect, MenuEntry) -> Unit) {
        val rows = menuRows ?: return
        val r = Rect()
        var y = menuRect.y + 26f * u - menuY
        val x0 = menuRect.x + 6f * u
        val ww = menuRect.w - 12f * u
        for (row in rows) {
            if (row.isNotEmpty()) {
                val gap = 3f * u
                val ew = (ww - gap * (row.size - 1)) / row.size
                var x = x0
                for (e in row) {
                    r.set(x, y, ew, rowH)
                    if (r.y + r.h > menuRect.y + 22f * u && r.y < menuRect.y + menuRect.h) f(r, e)
                    x += ew + gap
                }
            }
            y += rowH + rowGap
        }
    }

    // ---------------- Отрисовка ----------------

    private val tmp = Rect()

    private fun rectOf(b: Button): Rect {
        if (!b.scroll) return b.rect
        tmp.set(b.rect.x, b.rect.y - leftY, b.rect.w, b.rect.h)
        return tmp
    }

    private fun visible(b: Button, r: Rect) = !b.scroll || (r.y + r.h > leftTools.y && r.y < leftTools.y + leftTools.h)

    fun drawShapes(sh: ShapeRenderer) {
        fill(sh, left, cPanel)
        fill(sh, right, cPanel)
        fill(sh, bottom, cPanel)
        for (b in buttons) {
            val r = rectOf(b)
            if (!visible(b, r)) continue
            fill(sh, clipLeft(b, r), if (b.selected()) cSelected else cButton)
        }
        // полоска прокрутки инструментов
        if (leftMax > 0f) {
            sh.setColor(1f, 1f, 1f, 0.25f)
            val frac = leftTools.h / (leftTools.h + leftMax)
            val bh = leftTools.h * frac
            val by = leftTools.y + (leftTools.h - bh) * (leftY / leftMax)
            rectGl(sh, Rect(left.w - 3f * u, by, 2f * u, bh))
        }
        forEachTab { r, idx ->
            fill(sh, r, if (tabSelected(idx)) cSelected else cButton)
        }
        // фишки веществ
        forEachChip { r, id ->
            val sel = if (id < 0) st.tool == chipTool(id) else st.substance == id && st.tool == Tool.DRAW
            fill(sh, r, if (sel) cSelected else cButton)
            val col = if (id < 0) toolColor(chipTool(id)) else Mat[id].color
            sh.setColor(((col shr 16) and 0xFF) / 255f, ((col shr 8) and 0xFF) / 255f, (col and 0xFF) / 255f, 1f)
            val sw = Rect(r.x + 4f * u, r.y + 7f * u, 9f * u, r.h - 14f * u)
            rectGl(sh, sw)
        }
        // меню
        if (menuRows != null) {
            fill(sh, world, cShade)
            fill(sh, menuRect, cPanel)
            forEachMenuEntry { r, e ->
                if (!e.header) fill(sh, r, if (e.selected()) cSelected else cButton)
            }
        }
    }

    /** Кнопка левой панели обрезается по области прокрутки. */
    private fun clipLeft(b: Button, r: Rect): Rect {
        if (!b.scroll) return r
        val top = maxOf(r.y, leftTools.y)
        val bot = minOf(r.y + r.h, leftTools.y + leftTools.h)
        return Rect(r.x, top, r.w, maxOf(0f, bot - top))
    }

    fun drawText(batch: SpriteBatch, font: BitmapFont, small: BitmapFont) {
        for (b in buttons) {
            val r = rectOf(b)
            if (!visible(b, r)) continue
            val cy = r.y + r.h / 2f
            if (b.scroll && (cy < leftTools.y + 4f * u || cy > leftTools.y + leftTools.h - 4f * u)) continue
            val v = b.value
            if (v == null) {
                centered(batch, font, b.title(), r, cy, cText)
            } else {
                centered(batch, small, b.title(), r, r.y + r.h * 0.32f, cDim)
                centered(batch, font, v(), r, r.y + r.h * 0.68f, cText)
            }
        }
        // размер кисти над кнопками − +
        val minus = buttons.first { it.title() == "−" }.rect
        centered(batch, small, "Кисть Ø${st.brush * 2 + 1} см", Rect(left.x, minus.y - 14f * u, left.w, 12f * u),
            minus.y - 8f * u, cDim)
        forEachTab { r, idx -> centered(batch, small, tabTitle(idx), r, r.y + r.h / 2f, cText) }
        forEachChip { r, id ->
            small.color = cText
            val name = when {
                id < 0 -> chipTool(id).title
                Mat[id].key == "tap" -> "Кран: " + Mat[st.tapSource].name.lowercase()
                else -> Mat[id].name
            }
            layoutText.setText(small, name)
            val sp = if (layoutText.width > r.w - 18f * u) splitPoint(name) else -1
            if (sp > 0) {
                text(batch, small, name.substring(0, sp), r.x + 16f * u, r.y + r.h * 0.3f)
                text(batch, small, name.substring(sp + 1), r.x + 16f * u, r.y + r.h * 0.72f)
            } else text(batch, small, name, r.x + 16f * u, r.y + r.h / 2f)
        }
        // строка состояния — в верхнем правом углу мира
        val hud = callbacks.hudText()
        small.color = cDim
        layoutText.setText(small, hud)
        text(batch, small, hud, world.x + world.w - layoutText.width - 6f * u, world.y + 8f * u)

        // инспектор
        val info = callbacks.inspectText()
        if (info != null && menuRows == null) {
            font.color = cText
            text(batch, font, info, world.x + 8f * u, world.y + 22f * u)
        }
        // подсказка
        if (menuRows == null) {
            val tr = toastRect(small)
            if (tr != null) {
                small.color = cText
                var ty = tr.y + 5f * u + small.lineHeight / 2f
                for (line in toastLines) { text(batch, small, line, tr.x + 8f * u, ty); ty += small.lineHeight }
            }
        }
        // меню
        if (menuRows != null) {
            centered(batch, font, menuTitle, menuRect, menuRect.y + 13f * u, cText)
            forEachMenuEntry { r, e ->
                if (e.header) {
                    small.color = cDim
                    text(batch, small, e.title(), r.x + 2f * u, r.y + r.h * 0.62f)
                } else centered(batch, small, e.title(), r, r.y + r.h / 2f, cText)
            }
        }
    }

    fun drawToastBackground(sh: ShapeRenderer, font: BitmapFont) {
        if (menuRows != null) return
        val r = toastRect(font) ?: return
        sh.setColor(0.05f, 0.07f, 0.1f, 0.85f)
        rectGl(sh, r)
    }

    fun drawInspectBackground(sh: ShapeRenderer, fontLineH: Float) {
        if (menuRows != null) return
        val info = callbacks.inspectText() ?: return
        val lines = info.count { it == '\n' } + 1
        sh.setColor(0f, 0f, 0f, 0.6f)
        rectGl(sh, Rect(world.x + 4f * u, world.y + 13f * u, minOf(world.w - 8f * u, 430f * u), lines * fontLineH + 8f * u))
    }

    // ---------------- Вкладки и фишки ----------------

    /** Вкладки: 0 — поиск, дальше — категории. */
    private val categories = Category.values()
    private fun tabCount() = categories.size + 1
    private fun tabW(idx: Int) = if (idx == 0) 70f * u else 66f * u
    private fun tabsWidth(): Float { var s = 8f * u; for (k in 0 until tabCount()) s += tabW(k); return s }

    private fun tabTitle(idx: Int): String {
        if (idx == 0) { val q = st.search; return if (q != null) "«$q»" else "Поиск" }
        return categories[idx - 1].title
    }

    private fun tabSelected(idx: Int): Boolean =
        if (idx == 0) st.search != null else st.search == null && st.category == categories[idx - 1]

    private inline fun forEachTab(f: (Rect, Int) -> Unit) {
        val r = Rect()
        var x = 4f * u - tabsX
        for (k in 0 until tabCount()) {
            val tw = tabW(k)
            r.set(x, tabsRow.y + 2f * u, tw - 4f * u, 19f * u)
            if (r.x + r.w > 0f && r.x < screenW) f(r, k)
            x += tw
        }
    }

    /** Фишки категории: вещества, а в «Особых» сначала кисти воздуха (номера меньше нуля). */
    private fun chips(): List<Int> {
        val q = st.search
        if (q != null) return st.searchResults(q)
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

    private fun chipW() = 92f * u

    /** Пробел ближе всего к середине — название в две строки. */
    private fun splitPoint(s: String): Int {
        var best = -1
        for (k in s.indices) if (s[k] == ' ' && (best < 0 || abs(k - s.length / 2) < abs(best - s.length / 2))) best = k
        return best
    }

    private inline fun forEachChip(f: (Rect, Int) -> Unit) {
        val list = chips()
        val cw = chipW()
        var x = 4f * u - scrollX
        val r = Rect()
        for (id in list) {
            r.set(x, chipsRow.y + 2f * u, cw - 4f * u, chipsRow.h - 5f * u)
            if (r.x + r.w > 0f && r.x < screenW) f(r, id)
            x += cw
        }
    }

    private fun chipsWidth(): Float = chips().size * chipW() + 8f * u

    fun resetChipsScroll() { scrollX = 0f }

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

    private fun startDrag(kind: Int, pointer: Int, x: Float, y: Float, start: Float) {
        dragKind = kind; dragPointer = pointer
        dragStartX = x; dragStartY = y; dragStart = start; dragMoved = false
    }

    /** true — касание попало в интерфейс. */
    fun touchDown(x: Float, y: Float, pointer: Int): Boolean {
        if (menuRows != null) {
            if (menuRect.contains(x, y)) { startDrag(4, pointer, x, y, menuY); return true }
            if (world.contains(x, y)) { closeMenu(); return true }
        }
        if (chipsRow.contains(x, y)) { startDrag(1, pointer, x, y, scrollX); return true }
        if (tabsRow.contains(x, y) && bottom.contains(x, y)) { startDrag(2, pointer, x, y, tabsX); return true }
        if (leftTools.contains(x, y)) { startDrag(3, pointer, x, y, leftY); return true }
        for (b in buttons) if (!b.scroll && b.rect.contains(x, y)) { b.action(); return true }
        return left.contains(x, y) || right.contains(x, y) || bottom.contains(x, y)
    }

    fun touchDragged(x: Float, y: Float, pointer: Int): Boolean {
        if (pointer != dragPointer) return false
        val dx = x - dragStartX
        val dy = y - dragStartY
        if (abs(dx) > 8f * u || abs(dy) > 8f * u) dragMoved = true
        when (dragKind) {
            1 -> scrollX = (dragStart - dx).coerceIn(0f, maxOf(0f, chipsWidth() - screenW))
            2 -> tabsX = (dragStart - dx).coerceIn(0f, maxOf(0f, tabsWidth() - screenW))
            3 -> leftY = (dragStart - dy).coerceIn(0f, leftMax)
            4 -> {
                val rows = menuRows
                if (rows != null) {
                    val content = 26f * u + rows.size * (rowH + rowGap) + 6f * u
                    menuY = (dragStart - dy).coerceIn(0f, maxOf(0f, content - menuRect.h))
                }
            }
        }
        return true
    }

    fun touchUp(x: Float, y: Float, pointer: Int): Boolean {
        if (pointer != dragPointer) return false
        dragPointer = -1
        if (dragMoved) return true
        when (dragKind) {
            1 -> forEachChip { r, id ->
                if (r.contains(x, y)) {
                    if (id < 0) st.tool = chipTool(id) else st.pick(id)
                }
            }
            2 -> forEachTab { r, idx ->
                if (r.contains(x, y)) {
                    if (idx == 0) callbacks.askSearch()
                    else { st.search = null; st.selectCategory(categories[idx - 1]); scrollX = 0f }
                }
            }
            3 -> for (b in buttons) if (b.scroll && rectOf(b).contains(x, y)) { b.action(); break }
            4 -> {
                var hit: MenuEntry? = null
                forEachMenuEntry { r, e -> if (!e.header && r.contains(x, y)) hit = e }
                hit?.let { e ->
                    if (!e.keepOpen) closeMenu()
                    e.action()
                }
            }
        }
        return true
    }

    fun isUi(x: Float, y: Float) = !world.contains(x, y) || menuRows != null
}
