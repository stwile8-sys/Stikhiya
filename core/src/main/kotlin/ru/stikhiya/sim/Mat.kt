package ru.stikhiya.sim

/**
 * Быстрый доступ к свойствам веществ по номеру: параллельные массивы вместо объектов,
 * чтобы игровой цикл не создавал мусора.
 */
object Mat {
    val list: List<Substance> = Substances.all
    val count = list.size
    private val byKey = HashMap<String, Int>()

    init {
        list.forEachIndexed { i, s ->
            require(byKey.put(s.key, i) == null) { "Повтор вещества ${s.key}" }
        }
        require(list[0].key == "air") { "Вещество 0 должно быть воздухом" }
    }

    fun id(key: String): Int = byKey[key] ?: error("Нет вещества $key")
    fun idOrNeg(key: String?): Int = if (key == null) -1 else id(key)
    operator fun get(id: Int): Substance = list[id]

    const val AIR = 0
    val WATER = id("water")
    val STEAM = id("steam")
    val FLAME = id("flame")
    val SMOKE = id("smoke")
    val ASH = id("ash")

    // --- свойства ---
    val phase = IntArray(count) { list[it].phase.ordinal }
    val density = FloatArray(count) { list[it].density }
    val molar = FloatArray(count) { list[it].molarMass }
    val heatCap = FloatArray(count) { list[it].heatCapacity }
    val cond = FloatArray(count) { list[it].conductivity }
    val expansion = FloatArray(count) { list[it].expansion }
    val emissivity = FloatArray(count) { list[it].emissivity }

    val hotAt = FloatArray(count) { list[it].hotAt }
    val hotTo = IntArray(count) { idOrNeg(list[it].hotTo) }
    val hotLatent = FloatArray(count) { list[it].hotLatent }
    val coldAt = FloatArray(count) { list[it].coldAt }
    val coldTo = IntArray(count) { idOrNeg(list[it].coldTo) }
    val coldLatent = FloatArray(count) { list[it].coldLatent }

    val friction = FloatArray(count) { list[it].friction }
    val spread = IntArray(count) { list[it].spread }
    val viscosity = FloatArray(count) { list[it].viscosity }

    val porosity = FloatArray(count) { list[it].porosity }
    val fieldCap = FloatArray(count) { list[it].fieldCapacity }
    val permeability = FloatArray(count) { list[it].permeability }
    val grainDensity = FloatArray(count) { list[it].grainDensity }
    val wets = BooleanArray(count) { list[it].wets }
    val volatility = FloatArray(count) { list[it].volatility }

    val flashPoint = FloatArray(count) { list[it].flashPoint }
    val autoIgnition = FloatArray(count) { list[it].autoIgnition }
    val burnTime = FloatArray(count) { list[it].burnTime }
    val burnHeat = FloatArray(count) { list[it].burnHeat }
    val burnTo = IntArray(count) { idOrNeg(list[it].burnTo) }
    val burnResidue = FloatArray(count) { list[it].burnResidue }
    val smoke = FloatArray(count) { list[it].smoke }
    val flameTemp = FloatArray(count) { list[it].flameTemp }
    val charAt = FloatArray(count) { list[it].charAt }
    val charTo = IntArray(count) { idOrNeg(list[it].charTo) }
    val flammable = BooleanArray(count) { list[it].burnHeat > 0f }

    val cureTime = FloatArray(count) { list[it].cureTime }
    val curedTo = IntArray(count) { idOrNeg(list[it].curedTo) }
    val cureNeedsAir = BooleanArray(count) { list[it].cureNeedsAir }
    val isBinder = BooleanArray(count) { list[it].curedTo != null }

    val setTime = FloatArray(count) { list[it].setTime }
    val setTo = IntArray(count) { idOrNeg(list[it].setTo) }
    val setHeat = FloatArray(count) { list[it].setHeat }

    val lifetime = FloatArray(count) { list[it].lifetime }

    /** Склеенный вариант порошка (или -1). */
    val gluedOf = IntArray(count) { i -> byKey["glued_" + list[i].key] ?: -1 }

    // --- растворимость: индекс solute * count + solvent ---
    val solSat = FloatArray(count * count)
    val solCoef = FloatArray(count * count)
    val solTime = FloatArray(count * count)

    init {
        for (s in Substances.solubility) {
            val k = id(s.solute) * count + id(s.solvent)
            solSat[k] = s.sat20
            solCoef[k] = s.tempCoef
            solTime[k] = s.time
        }
    }

    fun isGas(id: Int) = phase[id] <= Phase.FLAME.ordinal          // GAS или FLAME
    fun isLiquid(id: Int) = phase[id] == Phase.LIQUID.ordinal
    fun isPowder(id: Int) = phase[id] == Phase.POWDER.ordinal
    fun isSolid(id: Int) = phase[id] == Phase.SOLID.ordinal
    fun isCondensed(id: Int) = phase[id] >= Phase.LIQUID.ordinal

    /** Насыщение раствора при температуре, кг/л. 0 — не растворяется. */
    fun saturation(solute: Int, solvent: Int, tempC: Float): Float {
        val k = solute * count + solvent
        val s = solSat[k]
        if (s <= 0f) return 0f
        return s * maxOf(0.2f, 1f + solCoef[k] * (tempC - Units.AMBIENT))
    }

    fun dissolves(solute: Int, solvent: Int) = solSat[solute * count + solvent] > 0f

    val palette: Map<Category, List<Int>> = Category.values().associateWith { cat ->
        list.indices.filter { list[it].inPalette && list[it].category == cat }
    }
}
