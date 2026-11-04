package ru.stikhiya.sim

/**
 * Готовые сцены-примеры. Собраны из тех же веществ, что доступны игроку в палитре.
 * Каждая возвращает подсказку — что попробовать.
 */
object Examples {
    class Example(val title: String, val build: (Simulator) -> String)

    val list: List<Example> = listOf(
        Example("Печь с дровами") { oven(it) },
        Example("Прорыв плотины") { dam(it) },
        Example("Сообщающиеся сосуды") { vessels(it) },
        Example("Сода и уксус") { soda(it) },
        Example("Термит прожигает сталь") { thermite(it) },
        Example("Натрий в воде") { sodium(it) },
        Example("Вулкан и озеро") { volcano(it) },
        Example("Варим яйцо") { egg(it) },
        Example("Пылевой взрыв муки") { dust(it) },
        Example("Морская вода → соль") { seaSalt(it) },
        Example("Брожение браги") { ferment(it) },
        Example("Кислота и металлы") { acids(it) },
        Example("Огород: семена и трава") { garden(it) }
    )

    // ---------------- помощники ----------------

    private fun ground(s: Simulator) {
        s.clear()
        val w = s.world
        val stone = Mat.id("stone")
        for (y in w.h - 3 until w.h) for (x in 0 until w.w) w.put(w.idx(x, y), stone)
    }

    private fun rect(s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int, t: Float? = null) {
        val w = s.world
        val id = Mat.id(key)
        for (y in maxOf(0, y0)..minOf(w.h - 1, y1)) for (x in maxOf(0, x0)..minOf(w.w - 1, x1)) {
            val i = w.idx(x, y)
            w.put(i, id)
            if (t != null) w.temp[i] = t
            Reactions.onSpawn(w, i)
        }
    }

    private fun clearRect(s: Simulator, x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = s.world
        for (y in y0..y1) for (x in x0..x1) if (w.inside(x, y)) w.toAir(w.idx(x, y))
    }

    /** Порошок насыпается неплотно, как кистью. */
    private fun pour(s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = s.world
        val id = Mat.id(key)
        for (y in y0..y1) for (x in x0..x1) if (w.rnd() < 0.8f) { val i = w.idx(x, y); if (w.type[i].toInt() == Mat.AIR) w.put(i, id) }
    }

    /** Твёрдый кусок, который падает и плавает (тело). */
    private fun body(s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int, t: Float? = null) {
        rect(s, key, x0, y0, x1, y1, t)
        val w = s.world
        val cells = IntList(64)
        for (y in y0..y1) for (x in x0..x1) cells.add(w.idx(x, y))
        s.bodies.create(cells)
    }

    /** Сосуд: дно и две стенки. */
    private fun vessel(s: Simulator, key: String, x0: Int, y0: Int, x1: Int, y1: Int, wall: Int = 2) {
        rect(s, key, x0, y1 - wall + 1, x1, y1)
        rect(s, key, x0, y0, x0 + wall - 1, y1)
        rect(s, key, x1 - wall + 1, y0, x1, y1)
    }

    // ---------------- сцены ----------------

    private fun oven(s: Simulator): String {
        ground(s)
        // кирпичная печь: топка, дверца слева, дымоход справа
        rect(s, "brick", 170, 110, 310, 196)
        clearRect(s, 176, 128, 304, 190)
        clearRect(s, 170, 166, 175, 190)            // дверца открыта
        rect(s, "brick", 282, 40, 300, 110)          // труба
        clearRect(s, 287, 40, 295, 127)
        // дрова и растопка
        rect(s, "pine", 190, 180, 270, 190)
        rect(s, "oak", 200, 172, 260, 179)
        pour(s, "sawdust", 205, 168, 255, 171)
        rect(s, "pine", 205, 166, 225, 167, 520f)
        // на плите — железная кастрюля с водой
        vessel(s, "iron", 190, 86, 250, 109)
        rect(s, "water", 192, 92, 248, 107)
        return "Печь растапливается. Заложите дверцу кирпичом — огонь задохнётся без воздуха; сотрите — разгорится снова. Вода в кастрюле закипит."
    }

    private fun dam(s: Simulator): String {
        ground(s)
        rect(s, "brick", 150, 50, 156, 196)
        rect(s, "water", 0, 60, 149, 196)
        // внизу по течению — песок и деревянные домики
        pour(s, "sand", 230, 186, 330, 196)
        body(s, "pine", 300, 160, 312, 180)
        body(s, "pine", 360, 165, 375, 182)
        body(s, "brick", 420, 170, 432, 185)
        return "Сотрите стенку плотины ластиком: вода хлынет со скоростью около √(2gH)."
    }

    private fun vessels(s: Simulator): String {
        ground(s)
        // U-трубка из стекла
        rect(s, "glass", 140, 40, 143, 186)
        rect(s, "glass", 166, 40, 169, 170)
        rect(s, "glass", 300, 40, 303, 170)
        rect(s, "glass", 326, 40, 329, 186)
        rect(s, "glass", 140, 183, 329, 186)
        rect(s, "glass", 166, 167, 303, 170)
        rect(s, "water", 144, 60, 165, 182)
        rect(s, "water", 166, 171, 325, 182)
        rect(s, "water", 304, 160, 325, 170)
        return "Вода в сообщающихся сосудах выравнивается. Налейте масло в одно колено — уровни станут разными."
    }

    private fun soda(s: Simulator): String {
        ground(s)
        vessel(s, "glass", 190, 110, 290, 196)
        rect(s, "vinegar", 192, 160, 288, 194)
        pour(s, "soda", 225, 70, 255, 95)
        return "Сода сыплется в уксус: пена и пузыри углекислого газа. В режиме «Вид → кислород» видно, как CO₂ вытесняет воздух."
    }

    private fun thermite(s: Simulator): String {
        ground(s)
        rect(s, "brick", 180, 150, 195, 196)
        rect(s, "brick", 285, 150, 300, 196)
        rect(s, "steel", 180, 144, 300, 149)
        pour(s, "thermite", 215, 120, 265, 143)
        rect(s, "magnesium", 238, 105, 241, 125)
        rect(s, "magnesium", 238, 104, 241, 105, 700f)
        return "Магниевый запал поджигает термит: 2500 °C, жидкое железо прожигает стальной лист."
    }

    private fun sodium(s: Simulator): String {
        ground(s)
        vessel(s, "glass", 160, 100, 320, 196)
        rect(s, "water", 162, 140, 318, 194)
        body(s, "sodium", 236, 128, 239, 130)
        return "Натрий легче воды: плавает, бурлит, выделяет водород, и тот вспыхивает. Вода становится щёлочью."
    }

    private fun volcano(s: Simulator): String {
        ground(s)
        // гора с жерлом
        val w = s.world
        val stone = Mat.id("basalt")
        for (y in 80..196) {
            val half = (y - 80) * 1.3f
            for (x in (200 - half).toInt()..(200 + half).toInt()) if (w.inside(x, y)) w.put(w.idx(x, y), stone)
        }
        clearRect(s, 196, 80, 204, 170)
        rect(s, "lava", 196, 120, 204, 170)
        rect(s, "lava", 170, 160, 230, 185)
        // озеро справа
        rect(s, "granite", 340, 150, 345, 196)
        rect(s, "granite", 470, 150, 475, 196)
        rect(s, "water", 346, 158, 469, 196)
        return "Лава течёт по склону. Направьте её в озеро — застынет обсидианом, вода закипит."
    }

    private fun egg(s: Simulator): String {
        ground(s)
        rect(s, "brick", 180, 175, 300, 196)
        clearRect(s, 186, 175, 294, 190)
        rect(s, "charcoal", 195, 182, 285, 190, 700f)
        vessel(s, "iron", 190, 120, 290, 174, wall = 3)
        rect(s, "water", 193, 135, 287, 171)
        rect(s, "raw_egg", 232, 160, 248, 168)
        return "Угли греют кастрюлю. Когда вода нагреется выше 70 °C, белок свернётся — яйцо сварится (Вид → температура)."
    }

    private fun dust(s: Simulator): String {
        ground(s)
        // костёр в очаге
        rect(s, "brick", 180, 186, 300, 196)
        rect(s, "pine", 200, 178, 280, 185)
        rect(s, "pine", 200, 176, 280, 177, 520f)
        // облако муки высыпается сверху
        val w = s.world
        val flour = Mat.id("flour")
        for (y in 40..110) for (x in 205..275) if (w.rnd() < 0.3f) w.put(w.idx(x, y), flour)
        // кучка муки в стороне
        pour(s, "flour", 360, 175, 400, 196)
        return "Облако мучной пыли над огнём вспыхивает и хлопает, а кучка муки справа только тлеет. Сыпьте муку кистью сверху на огонь."
    }

    private fun seaSalt(s: Simulator): String {
        ground(s)
        rect(s, "brick", 150, 175, 330, 196)
        clearRect(s, 156, 175, 324, 190)
        rect(s, "coal", 165, 183, 315, 190, 600f)
        vessel(s, "iron", 170, 140, 310, 174, wall = 3)
        rect(s, "seawater", 173, 150, 307, 171)
        return "Морская вода выкипает, на дне остаётся соль. Подождите пару минут; «Вид → смеси» показывает, сколько соли в воде."
    }

    private fun ferment(s: Simulator): String {
        ground(s)
        vessel(s, "glass", 180, 90, 300, 196)
        rect(s, "water", 182, 120, 298, 194, 30f)
        val w = s.world
        val sugar = Mat.id("sugar")
        for (i in 0 until w.n) if (w.type[i].toInt() == Mat.WATER) { w.mixId[i] = sugar.toShort(); w.mix[i] = 0.3f }
        pour(s, "yeast", 220, 100, 260, 112)
        return "Тёплая сахарная вода и дрожжи: включите «Меню → химия ×3600» — пойдут пузыри CO₂ и получится брага. Нагрейте её выше 78 °C — выкипит спирт."
    }

    private fun garden(s: Simulator): String {
        ground(s)
        val w = s.world
        val soil = Mat.id("soil")
        for (y in 170..196) for (x in 0 until w.w) {
            val i = w.idx(x, y)
            w.put(i, soil)
            // левая грядка полита, правая сухая
            if (x < 300) { w.mixId[i] = Mat.WATER.toShort(); w.mix[i] = Mat.porosity[soil] * 0.6f }
        }
        for (x in 20 until 280 step 18) w.put(w.idx(x, 169), Mat.id("seeds"))
        for (x in 330 until 460 step 30) w.put(w.idx(x, 169), Mat.id("seeds"))
        val grass = Mat.id("grass")
        for (x in 140..150) { val i = w.idx(x, 169); w.put(i, grass); w.mix[i] = 0.8f; w.life[i] = 3f }
        for (x in 380..390) { val i = w.idx(x, 169); w.put(i, grass); w.mix[i] = 0.8f; w.life[i] = 3f }
        rect(s, "brick", 300, 150, 303, 196)
        return "Включите «Меню → химия ×3600»: на политой земле семена прорастут и трава расползётся, на сухой справа всё засохнет. Сухое легко поджечь."
    }

    private fun acids(s: Simulator): String {
        ground(s)
        val metals = listOf("zinc", "iron", "copper", "magnesium", "aluminum", "gold")
        var x = 20
        for (m in metals) {
            vessel(s, "glass", x, 120, x + 60, 196)
            rect(s, "hcl", x + 2, 150, x + 58, 194)
            body(s, m, x + 22, 128, x + 38, 136)
            x += 75
        }
        return "Соляная кислота: цинк и магний бурно дают водород, железо — медленно, медь и золото не растворяются."
    }
}
