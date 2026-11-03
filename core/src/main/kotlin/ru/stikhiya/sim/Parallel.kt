package ru.stikhiya.sim

import java.util.stream.IntStream

/**
 * Параллельный расчёт по полосам. Используется только там, где полосы не пишут в общие клетки:
 * подготовка тепла, теплопроводность по чётным/нечётным парам строк, перенос тепла ветром, отрисовка.
 */
object Parallel {
    val cores = maxOf(1, Runtime.getRuntime().availableProcessors())
    val bands = if (cores > 1) minOf(16, cores * 2) else 1

    /** Выполнить body(b) для b = 0 until count — параллельно, если ядер больше одного. */
    inline fun forBands(count: Int, crossinline body: (Int) -> Unit) {
        if (count <= 1 || cores <= 1) {
            for (b in 0 until count) body(b)
            return
        }
        IntStream.range(0, count).parallel().forEach { body(it) }
    }

    /** Границы полосы b из count для диапазона 0 until total. */
    fun from(b: Int, count: Int, total: Int) = (total.toLong() * b / count).toInt()
    fun to(b: Int, count: Int, total: Int) = (total.toLong() * (b + 1) / count).toInt()
}
