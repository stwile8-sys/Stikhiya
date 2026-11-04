package ru.stikhiya.game

import ru.stikhiya.sim.Category
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Tool
import ru.stikhiya.sim.Brush

/** Выбор игрока: инструмент, вещество, размер кисти, скорость времени. */
class GameState {
    var tool = Tool.DRAW
    var category = Category.POWDER
    var substance = Mat.id("sand")
    var brush = 4
    var paused = false
    var stepRequested = false

    /** Скорость времени: 1, 1/2, 1/4. */
    val speeds = floatArrayOf(1f, 0.5f, 0.25f)
    val speedNames = arrayOf("×1", "×½", "×¼")
    var speedIndex = 0

    /** Ускорение медленных процессов: сушка, застывание клея, схватывание цемента. */
    val chemSpeeds = floatArrayOf(1f, 60f, 600f, 3600f)
    val chemNames = arrayOf("×1", "×60", "×600", "×3600")
    var chemIndex = 2

    var view = ViewMode.NORMAL

    /** Нарисованное твёрдое падает (становится телом) или стоит на месте как постройка. */
    var solidFalls = true

    /** Клетка под пальцем в режиме «Смотреть». */
    var inspectX = -1
    var inspectY = -1

    var clearArmedUntil = 0L

    /** Последнее выбранное вещество в каждой категории. */
    val lastInCategory = HashMap<Category, Int>()

    /** Что будет лить кран: последнее выбранное обычное вещество. */
    var tapSource = Mat.id("water")

    fun pick(id: Int) {
        substance = id
        tool = Tool.DRAW
        if (!Brush.isFixed(id) && !Mat[id].key.startsWith("fan")) tapSource = id
    }

    /** Поиск вещества по названию (null — показываем категорию). */
    var search: String? = null

    fun searchResults(q: String): List<Int> {
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        return Mat.list.indices.filter { id ->
            val s = Mat[id]
            if (!s.inPalette) return@filter false
            val name = s.name.lowercase()
            words.all { w -> name.contains(w) || s.description.lowercase().contains(w) }
        }
    }

    fun selectCategory(c: Category) {
        lastInCategory[category] = substance
        category = c
        val list = Mat.palette[c].orEmpty()
        substance = lastInCategory[c] ?: list.firstOrNull() ?: substance
        tool = Tool.DRAW
    }
}
