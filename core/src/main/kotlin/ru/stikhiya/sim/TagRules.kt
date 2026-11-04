package ru.stikhiya.sim

import ru.stikhiya.sim.Reactions.R
import ru.stikhiya.sim.Tag as T

/**
 * Общие правила химии по тегам (вместо ~250 000 пар):
 * кислота + карбонат → CO₂, кислота + металл → H₂, кислота + щёлочь → вода и тепло,
 * щелочной металл + вода → H₂, окислитель + горючее → вспышка, растворитель + полимер → размягчение…
 * Пары, описанные вручную в [Reactions], здесь пропускаются.
 */
object TagRules {

    fun generate(manual: List<R>): List<R> {
        val have = HashSet<String>()
        for (r in manual) have += r.a + "|" + (r.b ?: "")
        val out = ArrayList<R>()
        fun add(r: R) { if (have.add(r.a + "|" + (r.b ?: ""))) out += r }

        val all = Mat.list
        fun tags(s: Substance) = T.of(s)
        fun has(s: Substance, t: Int) = (tags(s) and t) != 0
        val liquids = all.filter { it.phase == Phase.LIQUID }
        val acids = all.filter { has(it, T.ACID) && it.phase == Phase.LIQUID }
        val gasesAcid = all.filter { has(it, T.ACID) && it.phase == Phase.GAS }
        val bases = all.filter { has(it, T.BASE) && (it.phase == Phase.LIQUID) }
        val waters = listOf("water", "seawater", "distilled_water", "heavy_water", "brine", "soapy_water", "mud")
            .filter { k -> all.any { it.key == k } }

        for (s in all) {
            val k = s.key
            if (k == "air" || s.phase == Phase.FLAME) continue
            val tg = tags(s)
            val condensed = s.phase != Phase.GAS
            // ---- карбонат + кислота → CO₂ ----
            if ((tg and T.CARBONATE) != 0 && condensed) for (a in acids) {
                val strong = has(a, T.STRONG_ACID)
                val rate = if (strong) 0.8f else 0.12f
                add(R(k, a.key, rate, toA = "air", partA = 0.2f, toB = "water", probB = 0.15f, heat = 40f,
                    gas = "co2", extra = "foam", extraProb = if (s.phase == Phase.POWDER) 0.4f else 0.15f))
            }
            // ---- сульфид + кислота → H₂S ----
            if ((tg and T.SULFIDE) != 0) for (a in acids) if (has(a, T.STRONG_ACID))
                add(R(k, a.key, 0.2f, toA = "air", partA = 0.1f, gas = "h2s", heat = 20f))
            // ---- металл + кислота ----
            if (condensed && (tg and (T.METAL or T.ALKALI or T.NOBLE or T.PASSIVE)) != 0) for (a in acids) {
                val oxid = has(a, T.OXIDIZER)
                val strong = has(a, T.STRONG_ACID)
                val aqua = a.key == "aqua_regia"
                val noble = (tg and T.NOBLE) != 0
                val passive = (tg and T.PASSIVE) != 0 && (tg and T.METAL) == 0
                val fine = if (s.phase == Phase.POWDER) 5f else 1f
                when {
                    noble && !aqua -> {}
                    noble && aqua -> add(R(k, a.key, 1f, toA = "air", partA = 0.25f, gas = "no2", heat = 100f))
                    passive && !strong -> {}
                    a.key == "hno3" && ((tg and T.PASSIVE) != 0) -> {}   // пассивация в азотной кислоте
                    oxid && a.key != "h2so4" -> add(R(k, a.key, 0.3f * fine, toA = "air", partA = 0.12f, toB = "water",
                        probB = 0.1f, gas = "no2", heat = 200f))
                    strong -> add(R(k, a.key, 0.3f * fine, toA = "air", partA = 0.12f, toB = "water", probB = 0.12f,
                        heat = 120f, gas = "hydrogen", gasAmt = 2f))
                    (tg and (T.METAL or T.ALKALI)) != 0 -> add(R(k, a.key, 0.02f * fine, toA = "air", partA = 0.1f,
                        gas = "hydrogen", heat = 30f))
                }
            }
            // ---- медь, серебро и их сплавы: только кислоты-окислители (азотная, царская водка) ----
            if (k in listOf("copper", "silver", "brass", "bronze", "copper_dust", "cupronickel", "molten_copper"))
                for (a in acids) if (has(a, T.OXIDIZER) && a.key != "h2so4")
                    add(R(k, a.key, if (s.phase == Phase.POWDER) 3f else 0.8f, toA = "air", partA = 0.25f, toB = "water",
                        probB = 0.05f, gas = "no2", heat = 150f))
            // ---- щелочной металл + вода → водород и тепло (Cs, Rb, K, Ba воспламеняются) ----
            if ((tg and T.ALKALI) != 0 && condensed) for (wk in waters) {
                val violent = k in listOf("cesium", "rubidium", "potassium", "molten_cesium", "molten_rubidium",
                    "molten_potassium")
                val rate = when { violent -> 6f; k.contains("barium") || k.contains("strontium") -> 0.8f; else -> 0.4f }
                add(R(k, wk, rate, toA = "air", partA = if (violent) 0.25f else 0.08f, toB = "lye",
                    probB = 0.12f, heat = if (violent) 2500f else 500f, gas = "hydrogen", gasAmt = 2f,
                    flame = if (violent) 0.6f else 0.05f))
            }
            // ---- амфотерные (Al, Zn) + щёлочь → водород ----
            if ((tg and T.AMPHOTERIC) != 0 && condensed) for (b in bases) if (b.key == "lye" || b.key == "bleach")
                add(R(k, b.key, if (s.phase == Phase.POWDER) 1.2f else 0.2f, toA = "air", partA = 0.12f,
                    heat = 150f, gas = "hydrogen", gasAmt = 2f))
            // ---- хлорка + кислота → хлор ----
            if (k == "bleach") for (a in acids) add(R(k, a.key, 1f, toA = "water", partA = 0.3f, gas = "chlorine", heat = 30f))
            // ---- нейтрализация: щёлочь (жидкая/твёрдая) + кислота ----
            if ((tg and T.BASE) != 0 && condensed) for (a in acids) {
                val h = if (has(a, T.STRONG_ACID)) 400f else 120f
                add(R(k, a.key, 2f, toA = if (s.phase == Phase.LIQUID) "water" else "air",
                    partA = if (s.phase == Phase.LIQUID) 1f else 0.3f, toB = "water", probB = 0.5f, heat = h))
            }
            // ---- аммиак + кислотный газ / кислота → белый дым соли ----
            if (k == "ammonia" || k == "ammonia_water") {
                for (g in gasesAcid) add(R(k, g.key, 3f, toA = "smoke", toB = "air", heat = 50f))
                add(R(k, "hcl", 2f, toA = "smoke", heat = 50f))
            }
            // ---- конц. серная кислота обугливает органику ----
            if ((tg and T.ORGANIC) != 0 && condensed && s.phase != Phase.LIQUID)
                add(R(k, "h2so4", 0.15f, toA = "charcoal", toB = "dilute_h2so4", probB = 0.1f, heat = 300f, gas = "steam"))
            // ---- плавиковая кислота растворяет стекло ----
            if ((tg and T.SILICATE) != 0 && condensed)
                add(R(k, "hf", 0.15f, toA = "air", partA = 0.15f, toB = "water", probB = 0.1f, heat = 40f))
            // ---- омыление: жир + щёлочь → мыло ----
            if ((tg and T.FAT) != 0 && condensed) for (b in listOf("lye", "caustic_soda"))
                add(R(k, b, 0.5f, toA = "liquid_soap", heat = 60f))
            // ---- растворитель размягчает полимеры ----
            if ((tg and T.POLYMER) != 0 && condensed && k != "teflon" && k != "silicone" && k != "kevlar")
                for (sv in all.filter { has(it, T.SOLVENT) && it.phase == Phase.LIQUID })
                    add(R(k, sv.key, 0.02f, toA = "molten_plastic", partA = 0.2f, warm = true))
            // ---- окислитель + горючее: от тепла вспыхивает (бертолетова соль с сахаром, жидкий O₂ с маслом) ----
            if (s.burnHeat > 0f && condensed && s.oxygenNeed > 0f && (tg and T.OXIDIZER) == 0)
                for (o in all.filter { has(it, T.OXIDIZER) && it.phase != Phase.GAS && it.key != "h2o2" &&
                    it.key != "h2so4" && it.key != "iodine" }) {
                    val ign = maxOf(60f, (if (s.flashPoint.isNaN()) 300f else s.flashPoint) * 0.6f)
                    add(R(k, o.key, 4f, toA = "air", partA = 0.25f, toB = "air", probB = 0.15f, heat = 2000f,
                        flame = 0.8f, gas = "co2", minT = if (o.key == "liquid_oxygen") -250f else ign, warm = false))
                }
            // ---- горючее + горючий окислитель-газ (фтор жжёт всё) ----
            if (condensed && (s.burnHeat > 0f || (tg and T.ORGANIC) != 0))
                add(R(k, "fluorine", 3f, toA = "ash", partA = 0.3f, toB = "air", heat = 1500f, flame = 0.7f, warm = false))
            // ---- ртуть: амальгама ----
            if (k == "gold" || k == "silver") add(R(k, "mercury", 0.05f, toA = "air", partA = 0.1f))
            if (k == "aluminum" || k == "al_powder") add(R(k, "mercury", 0.05f, toA = "alumina", partA = 1f, heat = 300f))
        }

        // ---- отдельные реакции новых веществ ----
        add(R("calcium_carbide", "water", 1.5f, toA = "slaked_lime", toB = "air", probB = 0.3f, heat = 600f,
            gas = "acetylene", gasAmt = 3f))
        add(R("caustic_soda", "water", 2f, toA = "lye", toB = "lye", probB = 0.3f, heat = 900f))
        add(R("cacl2", "water", 1f, toA = "air", toB = "brine", probB = 0.3f, heat = 300f))
        add(R("ammonium_nitrate", "water", 1f, toA = "air", toB = "water", probB = 0f, heat = -500f))
        add(R("urea", "water", 1f, toA = "air", heat = -300f))
        add(R("rock_salt", "water", 1f / 2000f, toA = "salt", slow = true))
        add(R("pyrite", null, 0.5f, toA = "rust", gas = "so2", minT = 500f, heat = -100f))
        add(R("ozone", null, 1f / 2e4f, toA = "oxygen", slow = true))
        add(R("soda_water", null, 0.05f, gas = "co2", gasAmt = 0.3f, toA = "water", partA = 0.02f))
        add(R("cola", "mints", 6f, toA = "foam", gas = "co2", gasAmt = 2f, extra = "foam", extraProb = 1f))
        add(R("mints", "cola", 3f, gas = "co2", extra = "foam", extraProb = 1f, toB = "foam"))
        add(R("epoxy", "hardener", 0.3f, toA = "plastic", toB = "plastic", heat = 300f))
        add(R("no2", "water", 1f, toA = "air", toB = "hno3", probB = 0.2f, heat = 50f))
        add(R("hcl_gas", "water", 2f, toA = "air", toB = "hcl", probB = 0.2f, heat = 80f))
        add(R("detergent", "water", 0.5f, toA = "soapy_water", extra = "foam", extraProb = 0.6f))
        add(R("soapy_water", null, 0.02f, extra = "foam", extraProb = 0.3f, minT = 50f))
        add(R("magnesium", "co2", 2f, toB = "soot", heat = 400f, flame = 0.5f, burningA = true))
        add(R("mg_shavings", "co2", 2f, toB = "soot", heat = 400f, flame = 0.5f, burningA = true))
        add(R("silica_gel", "water", 0f))
        add(R("nitroglycerin", null, 0f))
        add(R("orange", null, 0.4f, extra = "fuel_vapor", extraProb = 1f, minT = 120f, warm = false))
        add(R("bentonite", "water", 1f, toA = "mud", toB = "air", probB = 0.5f))
        add(R("kaolin", "water", 0.5f, toA = "clay", toB = "air", probB = 0.5f))
        add(R("cement", "water", 0f))
        return out.filter { it.rate > 0f }
    }
}
