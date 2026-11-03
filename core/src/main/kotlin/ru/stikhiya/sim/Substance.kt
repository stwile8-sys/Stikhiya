package ru.stikhiya.sim

/**
 * Описание вещества. Новое вещество = новая запись в [Substances], без нового кода.
 * Плотность порошков — насыпная (с воздухом между зёрнами).
 */
class Substance(
    val key: String,
    val name: String,
    val phase: Phase,
    val category: Category,
    val color: Int,                         // 0xRRGGBB
    val density: Float = 1000f,             // кг/м³ при 20 °C
    val molarMass: Float = 0f,              // кг/моль (газы)
    val heatCapacity: Float = 1000f,        // Дж/(кг·К)
    val conductivity: Float = 0.5f,         // Вт/(м·К)
    val expansion: Float = 0f,              // 1/К, объёмное расширение жидкости
    val spawnTemp: Float = Units.AMBIENT,   // температура при рисовании
    val colorVar: Float = 0.05f,            // разброс оттенка зёрен
    val alpha: Float = 1f,                  // непрозрачность
    val inPalette: Boolean = true,

    // Фазовые переходы. Теплота перехода — Дж/кг.
    val hotAt: Float = Float.NaN,           // при нагреве выше — превращается в hotTo
    val hotTo: String? = null,
    val hotLatent: Float = 0f,
    val coldAt: Float = Float.NaN,          // при охлаждении ниже — превращается в coldTo
    val coldTo: String? = null,
    val coldLatent: Float = 0f,

    // Движение
    val friction: Float = 0.5f,             // порошок: 0 — течёт как жидкость, 1 — держит отвесный склон
    val spread: Int = 4,                    // жидкость: на сколько клеток растекается за тик
    val viscosity: Float = 0f,              // 0..1: доля тиков, когда жидкость стоит на месте

    // Поры (порошки)
    val porosity: Float = 0f,               // доля объёма, которую может занять жидкость
    val fieldCapacity: Float = 0f,          // доля пор, удерживаемая против силы тяжести
    val permeability: Float = 0.5f,         // 0..1: скорость стекания жидкости через порошок
    val grainDensity: Float = 0f,           // плотность самого кристалла (растворимые порошки)

    // Жидкости
    val wets: Boolean = true,               // впитывается в пористые порошки
    val volatility: Float = 0f,             // испарение открытой поверхности при 20 °C, доля клетки в секунду

    // Горение
    val flashPoint: Float = Float.NaN,      // загорается от пламени рядом
    val autoIgnition: Float = Float.NaN,    // загорается сам
    val burnTime: Float = 10f,              // с, сгорание клетки, открытой воздуху одной гранью
    val burnHeat: Float = 0f,               // Дж/кг, теплота сгорания
    val burnTo: String? = null,             // остаток
    val burnResidue: Float = 0f,            // вероятность оставить остаток
    val smoke: Float = 0f,                  // вероятность дыма за тик
    val flameTemp: Float = 1000f,           // температура пламени, °C
    val charAt: Float = Float.NaN,          // обугливание без доступа воздуха
    val charTo: String? = null,
    val oxygenNeed: Float = 1.5f,           // кг кислорода на кг топлива; 0 — свой окислитель (порох)
    val fineness: Float = 0f,               // 0..1: насколько мелкие частицы — поверхность вспыхивает от пламени раньше, чем прогреется вся клетка
    val gasYield: Float = 0f,               // м³ газа (при н. у.) на кг — взрывчатка

    // Вентилятор: куда дует
    val fanX: Int = 0,
    val fanY: Int = 0,

    // Клей: застывает сам (испарение воды или химия)
    val cureTime: Float = Float.NaN,        // с при 20 °C на открытом воздухе
    val curedTo: String? = null,
    val cureNeedsAir: Boolean = true,

    // Вяжущий порошок: схватывается с водой (цемент, гипс)
    val setTime: Float = Float.NaN,         // с при 20 °C
    val setTo: String? = null,
    val setHeat: Float = 0f,                // Дж/кг, тепло схватывания

    // Газы
    val lifetime: Float = Float.NaN,        // с, после — растворяется в воздухе
    val emissivity: Float = 0.9f,           // излучательная способность

    val description: String = ""
)

/** Растворимость: сколько кг вещества растворяется в литре растворителя. */
class Solubility(
    val solute: String,
    val solvent: String,
    val sat20: Float,       // кг/л при 20 °C
    val tempCoef: Float,    // относительный прирост на 1 К
    val time: Float         // с, характерное время растворения зерна на контакте
)
