package ru.stikhiya.game

import ru.stikhiya.sim.Category
import ru.stikhiya.sim.Mat
import ru.stikhiya.sim.Tool

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

    /** Клетка под пальцем в режиме «Смотреть». */
    var inspectX = -1
    var inspectY = -1

    var clearArmedUntil = 0L

    /** Последнее выбранное вещество в каждой категории. */
    val lastInCategory = HashMap<Category, Int>()

    fun selectCategory(c: Category) {
        lastInCategory[category] = substance
        category = c
        val list = Mat.palette[c].orEmpty()
        substance = lastInCategory[c] ?: list.firstOrNull() ?: substance
        tool = Tool.DRAW
    }
}
