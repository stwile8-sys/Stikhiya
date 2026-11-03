package ru.stikhiya.sim

/**
 * Физические единицы мира. Одна клетка — кубик 1 × 1 × 1 см.
 * Все свойства веществ — в СИ: кг/м³, Дж/(кг·К), Вт/(м·К), °C.
 */
object Units {
    const val CELL = 0.01f                 // м, сторона клетки
    const val VOL = 1e-6f                  // м³, объём клетки
    const val AREA = 1e-4f                 // м², площадь грани
    const val DT = 1f / 60f                // с, шаг симуляции
    const val GRAVITY = 9.81f              // м/с²
    const val G = GRAVITY * DT * DT / CELL // клеток/тик², прирост скорости падения
    const val MAX_FALL = 9f                // клеток/тик (≈ 5,4 м/с)
    const val AMBIENT = 20f                // °C, температура комнаты
    const val P_ATM = 101325f              // Па
    const val R_GAS = 8.314f               // Дж/(моль·К)
    const val SIGMA = 5.67e-8f             // Вт/(м²·К⁴), постоянная Стефана — Больцмана
    const val KELVIN = 273.15f
    const val ABS_ZERO = -273.15f
    const val AIR_MOLAR = 0.02897f         // кг/моль

    /** Плотность идеального газа при атмосферном давлении, кг/м³. */
    fun gasDensity(molar: Float, tempC: Float): Float {
        val t = maxOf(tempC + KELVIN, 1f)
        return P_ATM * molar / (R_GAS * t)
    }
}

enum class Phase { GAS, FLAME, LIQUID, POWDER, SOLID }

enum class Category(val title: String) {
    SOLID("Твёрдые"),
    POWDER("Порошки"),
    LIQUID("Жидкости"),
    GAS("Газы"),
    SPECIAL("Особые")
}
