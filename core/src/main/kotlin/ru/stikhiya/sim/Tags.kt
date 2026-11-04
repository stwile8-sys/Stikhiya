package ru.stikhiya.sim

/**
 * Теги веществ. Вместо ~250 000 пар реакций — правила по тегам (кислота + карбонат → CO₂ и т. д.).
 * Теги веществ, описанных раньше тегов, задаются здесь по ключу.
 */
object Tag {
    const val ACID = 1                 // кислота
    const val STRONG_ACID = 1 shl 1    // сильная кислота (растворяет металлы)
    const val BASE = 1 shl 2           // щёлочь / основание
    const val OXIDIZER = 1 shl 3       // окислитель: горючее рядом вспыхивает
    const val ALKALI = 1 shl 4         // щелочной / щёлочноземельный металл: с водой → H₂ + тепло
    const val CARBONATE = 1 shl 5      // карбонат: с кислотой → CO₂
    const val SULFIDE = 1 shl 6        // сульфид: с кислотой → H₂S
    const val DUST = 1 shl 7           // горючая пыль: облако вспыхивает
    const val CRYOGEN = 1 shl 8        // криоген
    const val TOXIC = 1 shl 9
    const val POLYMER = 1 shl 10       // полимер: растворители размягчают
    const val FOOD = 1 shl 11
    const val BIO = 1 shl 12
    const val INERT = 1 shl 13
    const val METAL = 1 shl 14         // реактивный металл (растворяется кислотой с H₂)
    const val NOBLE = 1 shl 15         // благородный: только царская водка
    const val PASSIVE = 1 shl 16       // пассивируется (Al, Cr, Ti в азотной кислоте)
    const val FAT = 1 shl 17           // жир / масло: со щёлочью → мыло
    const val SILICATE = 1 shl 18      // стекло, кварц: плавиковая кислота растворяет
    const val ORGANIC = 1 shl 19       // органика: конц. серная кислота обугливает
    const val SOLVENT = 1 shl 20       // органический растворитель
    const val COLORANT = 1 shl 21      // окрашивает пламя
    const val SURFACTANT = 1 shl 22    // ПАВ: пена
    const val AMPHOTERIC = 1 shl 23    // растворяется и в щёлочи с H₂ (Al, Zn)

    private val extra = mapOf(
        "vinegar" to (ACID), "citric_acid" to (ACID),
        "hcl" to (ACID or STRONG_ACID), "dilute_h2so4" to (ACID or STRONG_ACID),
        "h2so4" to (ACID or STRONG_ACID or OXIDIZER),
        "lye" to BASE, "slaked_lime" to BASE, "quicklime" to BASE, "soda" to (BASE or CARBONATE),
        "saltpeter" to OXIDIZER, "permanganate" to (OXIDIZER or TOXIC), "h2o2" to OXIDIZER,
        "liquid_oxygen" to (OXIDIZER or CRYOGEN), "oxygen" to OXIDIZER, "chlorine" to (OXIDIZER or TOXIC),
        "liquid_chlorine" to (OXIDIZER or TOXIC or CRYOGEN), "iodine" to OXIDIZER,
        "lithium" to (ALKALI or METAL or COLORANT), "sodium" to (ALKALI or METAL or COLORANT),
        "potassium" to (ALKALI or METAL or COLORANT), "calcium" to (ALKALI or METAL or COLORANT),
        "magnesium" to METAL, "molten_sodium" to (ALKALI or METAL), "molten_potassium" to (ALKALI or METAL),
        "molten_lithium" to (ALKALI or METAL),
        "chalk" to CARBONATE, "limestone" to CARBONATE, "marble" to CARBONATE, "malachite" to (CARBONATE or TOXIC),
        "flour" to (DUST or FOOD or ORGANIC), "starch" to (DUST or FOOD or ORGANIC), "sawdust" to (DUST or ORGANIC),
        "al_powder" to (DUST or METAL or AMPHOTERIC), "iron_filings" to (DUST or METAL), "cocoa" to (DUST or FOOD),
        "coal" to ORGANIC, "sugar" to (FOOD or ORGANIC),
        "liquid_nitrogen" to CRYOGEN, "liquid_helium" to CRYOGEN, "dry_ice" to CRYOGEN, "liquid_butane" to CRYOGEN,
        "mercury" to TOXIC, "mercury_vapor" to TOXIC, "co" to TOXIC, "h2s" to TOXIC, "ammonia" to (TOXIC or BASE),
        "so2" to (TOXIC or ACID), "copper_sulfate" to (TOXIC or COLORANT), "lead" to TOXIC,
        "plastic" to POLYMER, "styrofoam" to POLYMER, "rubber" to POLYMER, "nylon" to POLYMER, "pvc" to POLYMER,
        "plexiglass" to POLYMER, "pet" to POLYMER,
        "iron" to METAL, "steel" to METAL, "cast_iron" to METAL, "zinc" to (METAL or AMPHOTERIC), "tin" to METAL,
        "aluminum" to (METAL or PASSIVE or AMPHOTERIC), "nickel" to (METAL or PASSIVE), "cobalt" to METAL,
        "manganese" to METAL, "chromium" to (METAL or PASSIVE), "titanium" to PASSIVE, "copper" to COLORANT,
        "brass" to METAL, "bronze" to METAL,
        "gold" to NOBLE, "platinum" to NOBLE, "silver" to NOBLE,
        "helium" to INERT, "neon" to INERT, "argon" to INERT, "nitrogen" to INERT,
        "oil" to (FAT or ORGANIC), "butter" to (FAT or FOOD), "motor_oil" to FAT,
        "glass" to SILICATE, "quartz" to SILICATE, "glass_wool" to SILICATE, "sand" to SILICATE, "flint" to SILICATE,
        "acetone" to SOLVENT, "gasoline" to SOLVENT,
        "salt" to COLORANT, "seawater" to COLORANT, "liquid_soap" to SURFACTANT,
        "wood" to ORGANIC, "paper" to ORGANIC, "cardboard" to ORGANIC, "wool" to ORGANIC, "bread" to (FOOD or ORGANIC),
        "cotton" to ORGANIC, "cloth" to ORGANIC, "leather" to ORGANIC,
    )

    /** Цвета пламени уже существующих веществ (реальная окраска пламени). */
    private val flames = mapOf(
        "hydrogen" to 0xA8C4FF, "methane" to 0x4F7BFF, "propane" to 0x4F7BFF, "butane" to 0x4F7BFF,
        "alcohol" to 0x6A8CFF, "alcohol_vapor" to 0x6A8CFF, "methanol" to 0x7090FF, "acetone_vapor" to 0x6080FF,
        "co" to 0x3F62FF, "h2s" to 0x5A5CFF, "sulfur" to 0x4A64FF, "molten_sulfur" to 0x4A64FF,
        "acetylene" to 0xFFFFF0, "magnesium" to 0xFFFFF4, "molten_magnesium" to 0xFFFFF4, "al_powder" to 0xFFFFF4,
        "thermite" to 0xFFF4D0, "sodium" to 0xFFC830, "molten_sodium" to 0xFFC830, "salt" to 0xFFC830,
        "seawater" to 0xFFC830, "soda" to 0xFFC830, "potassium" to 0xC890FF, "molten_potassium" to 0xC890FF,
        "saltpeter" to 0xC890FF, "lithium" to 0xFF2050, "molten_lithium" to 0xFF2050, "calcium" to 0xFF7030,
        "quicklime" to 0xFF7030, "copper" to 0x40E070, "copper_sulfate" to 0x40E070, "copper_oxide" to 0x40E070,
        "iron_filings" to 0xFFA040, "gunpowder" to 0xFFB050,
        "gasoline" to 0xFFA020, "kerosene" to 0xFFA020, "diesel" to 0xFF9818, "crude_oil" to 0xFF9010,
        "bitumen" to 0xFF8810, "rubber" to 0xFF8C18, "plastic" to 0xFF9420, "pvc" to 0xC8E070,
        "candle" to 0xFFD060, "wax" to 0xFFD060, "paraffin" to 0xFFD060,
    )

    /** Упругость (отскок) твёрдых тел. */
    private val bounces = mapOf(
        "rubber" to 0.8f, "plastic" to 0.35f, "pet" to 0.4f, "nylon" to 0.3f, "pvc" to 0.3f, "glass" to 0.25f,
        "steel" to 0.3f, "iron" to 0.25f, "stone" to 0.15f, "granite" to 0.15f, "wood" to 0.3f, "oak" to 0.3f,
        "pine" to 0.3f, "balsa" to 0.25f, "cork" to 0.45f, "ice" to 0.1f, "diamond" to 0.2f, "lead" to 0.02f,
        "clay" to 0.0f, "dough" to 0.0f, "bread" to 0.05f, "styrofoam" to 0.15f,
    )

    /** Трение тел (коэффициент трения скольжения). */
    private val grips = mapOf(
        "ice" to 0.03f, "rubber" to 1.0f, "steel" to 0.5f, "iron" to 0.5f, "glass" to 0.4f, "wood" to 0.45f,
        "oak" to 0.45f, "pine" to 0.45f, "stone" to 0.6f, "granite" to 0.6f, "concrete" to 0.7f, "brick" to 0.65f,
        "plastic" to 0.3f, "pvc" to 0.3f, "nylon" to 0.25f, "aluminum" to 0.45f, "copper" to 0.5f, "lead" to 0.6f,
        "gold" to 0.5f, "leather" to 0.6f, "graphite" to 0.1f, "paraffin" to 0.2f, "wax" to 0.25f, "cork" to 0.6f,
    )

    fun of(s: Substance): Int = s.tags or (extra[s.key] ?: 0) or
        (if (s.burnHeat > 0f && s.phase == Phase.POWDER && s.fineness >= 0.6f) DUST else 0)

    fun flameOf(s: Substance): Int = if (s.flame != 0) s.flame else flames[s.key] ?: 0

    fun bounceOf(s: Substance): Float = if (!s.bounce.isNaN()) s.bounce else bounces[s.key] ?: when (s.category) {
        Category.METAL -> 0.25f; Category.STONE -> 0.15f; Category.FOOD -> 0.05f; Category.LIFE -> 0.05f
        else -> 0.1f
    }

    fun gripOf(s: Substance): Float = if (!s.grip.isNaN()) s.grip else grips[s.key] ?: when (s.category) {
        Category.METAL -> 0.5f; Category.STONE -> 0.6f; Category.ORGANIC -> 0.5f; Category.FOOD -> 0.6f
        else -> 0.55f
    }
}
