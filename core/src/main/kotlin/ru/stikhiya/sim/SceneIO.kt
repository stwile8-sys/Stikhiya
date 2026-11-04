package ru.stikhiya.sim

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Сохранение и загрузка сцены: все свойства клеток и воздух.
 * Вещества записываются по ключам, поэтому старые сохранения открываются и после добавления новых веществ.
 * Тела после загрузки собираются заново из своих клеток (стоят на месте, без скорости).
 */
object SceneIO {
    private const val MAGIC = 0x53544B31 // "STK1"
    private const val VERSION = 1

    class Header(val name: String, val time: Long, val w: Int, val h: Int)

    fun save(sim: Simulator, out: OutputStream, name: String) {
        val w = sim.world
        val air = sim.air
        DataOutputStream(GZIPOutputStream(out.buffered(1 shl 16))).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(VERSION)
            d.writeUTF(name)
            d.writeLong(System.currentTimeMillis())
            d.writeInt(w.w)
            d.writeInt(w.h)
            // таблица веществ: номер → ключ
            d.writeInt(Mat.count)
            for (k in 0 until Mat.count) d.writeUTF(Mat[k].key)
            val n = w.n
            for (i in 0 until n) d.writeShort(w.type[i].toInt())
            floats(d, w.temp, n); floats(d, w.vy, n); floats(d, w.vx, n); floats(d, w.amt, n)
            for (i in 0 until n) d.writeShort(w.mixId[i].toInt())
            floats(d, w.mix, n); floats(d, w.latent, n); floats(d, w.cure, n); floats(d, w.burn, n); floats(d, w.life, n)
            d.write(w.shade, 0, n)
            d.write(w.flags, 0, n)
            for (i in 0 until n) d.writeShort(w.owner[i].toInt())
            d.writeInt(air.an)
            floats(d, air.pv, air.an); floats(d, air.vx, air.an); floats(d, air.vy, air.an); floats(d, air.o2, air.an)
        }
    }

    private val byKey: Map<String, Int> by lazy { Mat.list.indices.associateBy { Mat[it].key } }
    private fun idOrAir(key: String): Int = byKey[key] ?: Mat.AIR

    private fun floats(d: DataOutputStream, a: FloatArray, n: Int) { for (i in 0 until n) d.writeFloat(a[i]) }
    private fun floats(d: DataInputStream, a: FloatArray, n: Int) { for (i in 0 until n) a[i] = d.readFloat() }

    fun header(inp: InputStream): Header? = try {
        DataInputStream(GZIPInputStream(inp.buffered(1 shl 12))).use { d ->
            if (d.readInt() != MAGIC) return null
            d.readInt()
            Header(d.readUTF(), d.readLong(), d.readInt(), d.readInt())
        }
    } catch (e: Exception) { null }

    /** true — загружено. При ошибке мир не трогается. */
    fun load(sim: Simulator, inp: InputStream): Boolean {
        val w = sim.world
        val air = sim.air
        try {
            DataInputStream(GZIPInputStream(inp.buffered(1 shl 16))).use { d ->
                if (d.readInt() != MAGIC) return false
                if (d.readInt() > VERSION) return false
                d.readUTF(); d.readLong()
                val ww = d.readInt(); val hh = d.readInt()
                if (ww != w.w || hh != w.h) return false
                val keys = d.readInt()
                val map = IntArray(keys) { _ -> idOrAir(d.readUTF()) }
                val n = w.n
                // читаем во временные массивы — мир меняем, только если файл целый
                val type = ShortArray(n) { (map.getOrElse(d.readShort().toInt()) { 0 }).toShort() }
                val temp = FloatArray(n); val vy = FloatArray(n); val vx = FloatArray(n); val amt = FloatArray(n)
                floats(d, temp, n); floats(d, vy, n); floats(d, vx, n); floats(d, amt, n)
                val mixId = ShortArray(n) { (map.getOrElse(d.readShort().toInt()) { 0 }).toShort() }
                val mix = FloatArray(n); val latent = FloatArray(n); val cure = FloatArray(n); val burn = FloatArray(n); val life = FloatArray(n)
                floats(d, mix, n); floats(d, latent, n); floats(d, cure, n); floats(d, burn, n); floats(d, life, n)
                val shade = ByteArray(n); d.readFully(shade)
                val flags = ByteArray(n); d.readFully(flags)
                val owner = ShortArray(n) { d.readShort() }
                val an = d.readInt()
                val pv = FloatArray(an); val avx = FloatArray(an); val avy = FloatArray(an); val o2 = FloatArray(an)
                floats(d, pv, an); floats(d, avx, an); floats(d, avy, an); floats(d, o2, an)

                sim.clear()
                System.arraycopy(type, 0, w.type, 0, n)
                System.arraycopy(temp, 0, w.temp, 0, n)
                System.arraycopy(vy, 0, w.vy, 0, n)
                System.arraycopy(vx, 0, w.vx, 0, n)
                System.arraycopy(amt, 0, w.amt, 0, n)
                System.arraycopy(mixId, 0, w.mixId, 0, n)
                System.arraycopy(mix, 0, w.mix, 0, n)
                System.arraycopy(latent, 0, w.latent, 0, n)
                System.arraycopy(cure, 0, w.cure, 0, n)
                System.arraycopy(burn, 0, w.burn, 0, n)
                System.arraycopy(life, 0, w.life, 0, n)
                System.arraycopy(shade, 0, w.shade, 0, n)
                System.arraycopy(flags, 0, w.flags, 0, n)
                if (an == air.an) {
                    System.arraycopy(pv, 0, air.pv, 0, an); System.arraycopy(avx, 0, air.vx, 0, an)
                    System.arraycopy(avy, 0, air.vy, 0, an); System.arraycopy(o2, 0, air.o2, 0, an)
                }
                // клетки тел: исчезнувшие вещества, не твёрдые — снимаем; остальное собираем в тела заново
                val groups = HashMap<Int, IntList>()
                for (i in 0 until n) {
                    val o = owner[i].toInt()
                    w.owner[i] = 0
                    if (o == 0) continue
                    if (!Mat.isSolid(w.type[i].toInt())) continue
                    groups.getOrPut(o) { IntList(64) }.add(i)
                }
                for (g in groups.values) sim.bodies.create(g)
                return true
            }
        } catch (e: Exception) {
            return false
        }
    }
}
