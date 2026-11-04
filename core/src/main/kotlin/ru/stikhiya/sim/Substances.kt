package ru.stikhiya.sim

import ru.stikhiya.sim.Category as C
import ru.stikhiya.sim.Phase as P

/**
 * Таблица веществ. Значения — справочные (при 20 °C и нормальном давлении).
 * Вещество с id 0 — воздух, им заполнены пустые клетки.
 */
object Substances {

    val base: List<Substance> = listOf(
        // ---------- Воздух ----------
        Substance("air", "Воздух", P.GAS, C.GAS, 0x0E1116, molarMass = Units.AIR_MOLAR,
            heatCapacity = 1005f, conductivity = 0.026f, emissivity = 0f, inPalette = false),

        // ---------- Твёрдые ----------
        Substance("stone", "Камень", P.SOLID, C.STONE, 0x7A7C82, density = 2600f, heatCapacity = 790f,
            conductivity = 2.5f, colorVar = 0.07f, hotAt = 1200f, hotTo = "lava", hotLatent = 4.0e5f),
        Substance("brick", "Кирпич", P.SOLID, C.SOLID, 0xA04A32, density = 1900f, heatCapacity = 840f,
            conductivity = 0.7f, colorVar = 0.06f),
        Substance("iron", "Железо", P.SOLID, C.METAL, 0x8E959E, density = 7870f, heatCapacity = 450f,
            conductivity = 80f, colorVar = 0.03f, hotAt = 1538f, hotTo = "molten_iron", hotLatent = 2.47e5f),
        Substance("copper", "Медь", P.SOLID, C.METAL, 0xC57B4F, density = 8960f, heatCapacity = 385f,
            conductivity = 400f, colorVar = 0.03f, hotAt = 1085f, hotTo = "molten_copper", hotLatent = 2.05e5f),
        Substance("glass", "Стекло", P.SOLID, C.SOLID, 0xBFE3EE, density = 2500f, heatCapacity = 840f,
            conductivity = 1.0f, alpha = 0.35f, colorVar = 0.01f, hotAt = 1400f, hotTo = "molten_glass"),
        Substance("wood", "Дерево", P.SOLID, C.ORGANIC, 0x8B5A2E, density = 700f, heatCapacity = 1700f,
            conductivity = 0.17f, colorVar = 0.08f, flashPoint = 300f, autoIgnition = 400f, burnTime = 40f,
            burnHeat = 1.6e7f, burnTo = "ash", burnResidue = 0.25f, smoke = 0.08f, flameTemp = 1000f,
            charAt = 350f, charTo = "charcoal", oxygenNeed = 1.4f, fineness = 0.3f),
        Substance("charcoal", "Древесный уголь", P.SOLID, C.ORGANIC, 0x2A2826, density = 400f, heatCapacity = 1000f,
            conductivity = 0.1f, colorVar = 0.05f, flashPoint = 350f, autoIgnition = 450f, burnTime = 80f,
            burnHeat = 2.9e7f, burnTo = "ash", burnResidue = 0.15f, smoke = 0f, flameTemp = 1100f, oxygenNeed = 2.67f,
            fineness = 0.15f),
        Substance("ice", "Лёд", P.SOLID, C.SOLID, 0xCFE9F7, density = 917f, heatCapacity = 2100f,
            conductivity = 2.2f, alpha = 0.85f, colorVar = 0.02f, spawnTemp = -10f,
            hotAt = 0f, hotTo = "water", hotLatent = 3.34e5f),
        Substance("dried_glue", "Засохший клей", P.SOLID, C.SOLID, 0xEDE6C8, density = 1200f, heatCapacity = 1500f,
            conductivity = 0.2f, alpha = 0.9f, colorVar = 0.02f, flashPoint = 350f, autoIgnition = 450f,
            burnTime = 30f, burnHeat = 2.2e7f, smoke = 0.3f, oxygenNeed = 1.6f, fineness = 0.2f),
        Substance("concrete", "Бетон", P.SOLID, C.SOLID, 0x9C9C97, density = 2400f, heatCapacity = 880f,
            conductivity = 1.7f, colorVar = 0.06f),
        Substance("gypsum_set", "Гипс застывший", P.SOLID, C.SOLID, 0xEDEAE2, density = 1100f, heatCapacity = 1090f,
            conductivity = 0.4f, colorVar = 0.03f, hotAt = 150f, hotTo = "gypsum", hotLatent = 3.0e5f),
        Substance("hard_candy", "Леденец", P.SOLID, C.FOOD, 0xC27A2A, density = 1500f, heatCapacity = 1300f,
            conductivity = 0.3f, alpha = 0.85f, colorVar = 0.02f, hotAt = 150f, hotTo = "caramel"),

        // ---------- Порошки ----------
        Substance("sand", "Песок", P.POWDER, C.POWDER, 0xDCC38D, density = 1600f, grainDensity = 2650f,
            heatCapacity = 830f, conductivity = 0.27f, friction = 0.42f, porosity = 0.38f,
            fieldCapacity = 0.3f, permeability = 0.6f, hotAt = 1713f, hotTo = "molten_glass", hotLatent = 1.56e5f),
        Substance("soil", "Земля", P.POWDER, C.POWDER, 0x6E4B2F, density = 1300f, heatCapacity = 800f,
            conductivity = 0.3f, friction = 0.6f, porosity = 0.5f, fieldCapacity = 0.55f, permeability = 0.25f),
        Substance("salt", "Соль", P.POWDER, C.POWDER, 0xF1F1EE, density = 1200f, grainDensity = 2160f,
            heatCapacity = 880f, conductivity = 0.6f, friction = 0.45f, colorVar = 0.03f,
            hotAt = 801f, hotTo = "molten_salt", hotLatent = 4.82e5f),
        Substance("sugar", "Сахар", P.POWDER, C.FOOD, 0xFBF8F0, density = 850f, grainDensity = 1590f,
            heatCapacity = 1250f, conductivity = 0.15f, friction = 0.45f, colorVar = 0.02f,
            hotAt = 186f, hotTo = "caramel", hotLatent = 1.34e5f),
        Substance("sawdust", "Опилки", P.POWDER, C.POWDER, 0xCDA36C, density = 200f, heatCapacity = 1700f,
            conductivity = 0.08f, friction = 0.7f, porosity = 0.7f, fieldCapacity = 0.65f, permeability = 0.4f,
            colorVar = 0.09f, flashPoint = 250f, autoIgnition = 300f, burnTime = 2f, burnHeat = 1.6e7f,
            burnTo = "ash", burnResidue = 0.3f, smoke = 0.15f, flameTemp = 900f, charAt = 300f, charTo = "charcoal", oxygenNeed = 1.4f, fineness = 0.5f),
        Substance("cement", "Цемент", P.POWDER, C.POWDER, 0xA5A8AA, density = 1440f, heatCapacity = 840f,
            conductivity = 0.3f, friction = 0.55f, porosity = 0.5f, fieldCapacity = 0.85f, permeability = 0.15f,
            colorVar = 0.03f, setTime = 10800f, setTo = "concrete", setHeat = 1.2e5f),
        Substance("gypsum", "Гипс", P.POWDER, C.POWDER, 0xF2F0E8, density = 900f, heatCapacity = 1090f,
            conductivity = 0.25f, friction = 0.55f, porosity = 0.55f, fieldCapacity = 0.85f, permeability = 0.2f,
            colorVar = 0.02f, setTime = 900f, setTo = "gypsum_set", setHeat = 1.0e5f),
        Substance("ash", "Пепел", P.POWDER, C.POWDER, 0x8E8A85, density = 500f, heatCapacity = 1000f,
            conductivity = 0.1f, friction = 0.6f, porosity = 0.6f, fieldCapacity = 0.6f, permeability = 0.3f),
        Substance("snow", "Снег", P.POWDER, C.POWDER, 0xF3F7FA, density = 300f, heatCapacity = 2100f,
            conductivity = 0.15f, friction = 0.75f, porosity = 0.5f, fieldCapacity = 0.5f, permeability = 0.3f,
            colorVar = 0.02f, spawnTemp = -5f, hotAt = 0f, hotTo = "water", hotLatent = 3.34e5f),

        // ---------- Жидкости ----------
        Substance("water", "Вода", P.LIQUID, C.LIQUID, 0x2E6FD8, density = 1000f, heatCapacity = 4186f,
            conductivity = 0.6f, expansion = 2.1e-4f, alpha = 0.75f, colorVar = 0.02f, spread = 5,
            volatility = 3e-6f, hotAt = 100f, hotTo = "steam", hotLatent = 2.26e6f,
            coldAt = 0f, coldTo = "ice", coldLatent = 3.34e5f),
        Substance("oil", "Масло", P.LIQUID, C.LIQUID, 0xC9A227, density = 920f, heatCapacity = 2000f,
            conductivity = 0.17f, expansion = 7.2e-4f, alpha = 0.85f, colorVar = 0.02f, spread = 3,
            viscosity = 0.3f, flashPoint = 315f, autoIgnition = 405f, burnTime = 20f, burnHeat = 3.7e7f,
            smoke = 0.25f, flameTemp = 1000f, oxygenNeed = 2.9f),
        Substance("alcohol", "Спирт", P.LIQUID, C.LIQUID, 0xCFEAF5, density = 789f, heatCapacity = 2440f,
            conductivity = 0.17f, expansion = 1.1e-3f, alpha = 0.35f, colorVar = 0.01f, spread = 6,
            volatility = 7.5e-6f, flashPoint = 13f, autoIgnition = 363f, burnTime = 15f, burnHeat = 2.68e7f,
            smoke = 0f, flameTemp = 900f, oxygenNeed = 2.1f, hotAt = 78f, hotTo = "alcohol_vapor", hotLatent = 8.46e5f),
        Substance("glue", "Клей ПВА", P.LIQUID, C.LIQUID, 0xF2EFE6, density = 1100f, heatCapacity = 2900f,
            conductivity = 0.4f, alpha = 0.95f, colorVar = 0.01f, spread = 1, viscosity = 0.6f,
            cureTime = 1800f, curedTo = "dried_glue", cureNeedsAir = true,
            hotAt = 100f, hotTo = "dried_glue", hotLatent = 1.1e6f),
        Substance("lava", "Лава", P.LIQUID, C.LIQUID, 0xFF5A14, density = 2600f, heatCapacity = 1000f,
            conductivity = 1.5f, spawnTemp = 1200f, spread = 2, viscosity = 0.8f, wets = false,
            coldAt = 1000f, coldTo = "stone", coldLatent = 4.0e5f),
        Substance("molten_iron", "Жидкое железо", P.LIQUID, C.LIQUID, 0xFF8C2A, density = 6980f, heatCapacity = 820f,
            conductivity = 35f, spawnTemp = 1600f, spread = 4, viscosity = 0.1f, wets = false,
            coldAt = 1538f, coldTo = "iron", coldLatent = 2.47e5f),
        Substance("molten_copper", "Жидкая медь", P.LIQUID, C.LIQUID, 0xFF7A30, density = 8020f, heatCapacity = 530f,
            conductivity = 160f, spawnTemp = 1150f, spread = 4, viscosity = 0.1f, wets = false,
            coldAt = 1085f, coldTo = "copper", coldLatent = 2.05e5f),
        Substance("molten_glass", "Жидкое стекло", P.LIQUID, C.LIQUID, 0xFFA040, density = 2350f, heatCapacity = 1100f,
            conductivity = 1.4f, spawnTemp = 1500f, spread = 1, viscosity = 0.9f, wets = false,
            coldAt = 1000f, coldTo = "glass"),
        Substance("molten_salt", "Расплав соли", P.LIQUID, C.LIQUID, 0xF4E6C8, density = 1556f, heatCapacity = 1100f,
            conductivity = 0.5f, spawnTemp = 850f, spread = 4, wets = false,
            coldAt = 801f, coldTo = "salt", coldLatent = 4.82e5f),
        Substance("caramel", "Карамель", P.LIQUID, C.FOOD, 0xB8651C, density = 1400f, heatCapacity = 1600f,
            conductivity = 0.3f, alpha = 0.9f, spawnTemp = 190f, spread = 1, viscosity = 0.85f, wets = false,
            coldAt = 70f, coldTo = "hard_candy", hotAt = 250f, hotTo = "charcoal"),

        // ---------- Газы ----------
        Substance("steam", "Пар", P.GAS, C.GAS, 0xD8E2EA, molarMass = 0.018f, heatCapacity = 2010f,
            conductivity = 0.025f, alpha = 0.35f, emissivity = 0.2f, spawnTemp = 105f,
            coldAt = 100f, coldTo = "water", coldLatent = 2.26e6f),
        Substance("smoke", "Дым", P.GAS, C.GAS, 0x4A4A4A, molarMass = 0.0295f, heatCapacity = 1005f,
            conductivity = 0.026f, alpha = 0.55f, emissivity = 0.4f, lifetime = 25f),
        Substance("co2", "Углекислый газ", P.GAS, C.GAS, 0xB8C4CC, molarMass = 0.044f, heatCapacity = 840f,
            conductivity = 0.017f, alpha = 0.15f, emissivity = 0.15f, lifetime = 180f),
        Substance("helium", "Гелий", P.GAS, C.GAS, 0xE7DAFF, molarMass = 0.004f, heatCapacity = 5193f,
            conductivity = 0.15f, alpha = 0.15f, emissivity = 0f),
        Substance("alcohol_vapor", "Пары спирта", P.GAS, C.GAS, 0xD6EEF7, molarMass = 0.046f, heatCapacity = 1420f,
            conductivity = 0.015f, alpha = 0.1f, emissivity = 0.1f, spawnTemp = 80f,
            flashPoint = -100f, autoIgnition = 363f, burnTime = 0.2f, burnHeat = 2.68e7f, flameTemp = 900f, oxygenNeed = 2.1f,
            coldAt = 78f, coldTo = "alcohol", coldLatent = 8.46e5f),

        // ---------- Особые ----------
        Substance("flame", "Огонь", P.FLAME, C.SPECIAL, 0xFF8A20, molarMass = 0.028f, heatCapacity = 1200f,
            conductivity = 0.08f, emissivity = 0.5f, spawnTemp = 1000f, lifetime = 0.35f),
        // Чёрный порох: свой окислитель, горит без воздуха, даёт много горячего газа
        Substance("gunpowder", "Порох", P.POWDER, C.SPECIAL, 0x2B2B2E, density = 950f, heatCapacity = 900f,
            conductivity = 0.2f, friction = 0.45f, colorVar = 0.15f, porosity = 0.4f, fieldCapacity = 0.6f,
            permeability = 0.3f, flashPoint = 300f, autoIgnition = 330f,
            burnTime = 0.08f, burnHeat = 3.0e6f, smoke = 0.6f, flameTemp = 2000f, oxygenNeed = 0f, gasYield = 0.28f,
            fineness = 0.9f),
        Substance("fan_r", "Вентилятор →", P.SOLID, C.SPECIAL, 0x5A6B7D, density = 2700f, heatCapacity = 900f,
            conductivity = 50f, colorVar = 0.02f, fanX = 1),
        Substance("fan_l", "Вентилятор ←", P.SOLID, C.SPECIAL, 0x5A6B7D, density = 2700f, heatCapacity = 900f,
            conductivity = 50f, colorVar = 0.02f, fanX = -1),
        Substance("fan_u", "Вентилятор ↑", P.SOLID, C.SPECIAL, 0x5A6B7D, density = 2700f, heatCapacity = 900f,
            conductivity = 50f, colorVar = 0.02f, fanY = -1),
        Substance("fan_d", "Вентилятор ↓", P.SOLID, C.SPECIAL, 0x5A6B7D, density = 2700f, heatCapacity = 900f,
            conductivity = 50f, colorVar = 0.02f, fanY = 1)
    )

    /** Склеенные варианты пористых порошков — получаются, когда клей в порах засыхает. */
    private val gluedNames = mapOf(
        "sand" to "Склеенный песок",
        "soil" to "Склеенная земля",
        "sawdust" to "Клеёные опилки",
        "ash" to "Склеенный пепел",
        "cement" to "Склеенный цемент",
        "gypsum" to "Склеенный гипс"
    )

    val glued: List<Substance> = gluedNames.map { (key, name) ->
        val b = base.first { it.key == key }
        Substance(
            "glued_$key", name, P.SOLID, C.SOLID, darken(b.color, 0.82f),
            density = b.density * 1.25f, heatCapacity = b.heatCapacity, conductivity = b.conductivity * 1.5f,
            colorVar = b.colorVar, inPalette = false,
            flashPoint = b.flashPoint, autoIgnition = b.autoIgnition, burnTime = maxOf(b.burnTime * 4f, 20f),
            burnHeat = b.burnHeat, burnTo = b.burnTo, burnResidue = b.burnResidue, smoke = b.smoke,
            flameTemp = b.flameTemp, charAt = b.charAt, charTo = b.charTo, oxygenNeed = b.oxygenNeed,
            fineness = b.fineness * 0.5f
        )
    }

    val solubility: List<Solubility> = listOf(
        Solubility("salt", "water", sat20 = 0.36f, tempCoef = 0.0011f, time = 3f),
        Solubility("sugar", "water", sat20 = 2.0f, tempCoef = 0.018f, time = 5f),
        Solubility("soda", "water", sat20 = 0.096f, tempCoef = 0.02f, time = 6f),
        Solubility("coffee", "water", sat20 = 0.03f, tempCoef = 0.05f, time = 4f),
        Solubility("saltpeter", "water", sat20 = 0.32f, tempCoef = 0.04f, time = 4f),
        Solubility("salt", "seawater", sat20 = 0.36f, tempCoef = 0.0011f, time = 3f),
        Solubility("sugar", "milk", sat20 = 1.5f, tempCoef = 0.018f, time = 6f),
        Solubility("salt", "vinegar", sat20 = 0.3f, tempCoef = 0.001f, time = 4f),
        Solubility("sugar", "braga", sat20 = 1.8f, tempCoef = 0.018f, time = 6f)
    )

    val all: List<Substance> = base + glued + MoreSubstances.list

    private fun darken(c: Int, k: Float): Int {
        val r = ((c shr 16 and 0xFF) * k).toInt()
        val g = ((c shr 8 and 0xFF) * k).toInt()
        val b = ((c and 0xFF) * k).toInt()
        return (r shl 16) or (g shl 8) or b
    }
}
