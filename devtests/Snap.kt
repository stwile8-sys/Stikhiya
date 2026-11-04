import ru.stikhiya.sim.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
/** Снимок мира в PNG (цвет вещества, огонь — по температуре), масштаб k. */
fun snap(w: World, path: String, k: Int = 2) {
    val img = BufferedImage(w.w * k, w.h * k, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until w.h) for (x in 0 until w.w) {
        val i = w.idx(x, y); val id = w.type[i].toInt(); val t = w.temp[i]
        var c = if (id == 0) { val h = ((t - 20f) / 300f).coerceIn(0f, 1f); ((14 + h * 120).toInt() shl 16) or ((17 + h * 30).toInt() shl 8) or 22 } else Mat[id].color
        if (id == Mat.FLAME) c = 0xFFA020
        for (dy in 0 until k) for (dx in 0 until k) img.setRGB(x * k + dx, y * k + dy, c)
    }
    ImageIO.write(img, "png", File(path))
}
