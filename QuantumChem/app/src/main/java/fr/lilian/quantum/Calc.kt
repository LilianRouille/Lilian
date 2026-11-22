package fr.lilian.quantum

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

@Volatile var curProc: Process? = null

class XR(val code: Int, val out: String, val dir: File)

fun xtbRun(ctx: Context, args: List<String>, xyz: String, files: Map<String, String> = emptyMap(), cores: Int = 1, tick: (File) -> Unit = {}): XR {
    val dir = File(ctx.cacheDir, "xtb").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    File(dir, "mol.xyz").writeText(xyz)
    for ((k, v) in files) File(dir, k).writeText(v)
    val exe = File(ctx.applicationInfo.nativeLibraryDir, "libxtb.so")
    if (!exe.exists()) return XR(-1, "xtb absent", dir)
    val pb = ProcessBuilder(listOf(exe.absolutePath, "mol.xyz") + args).directory(dir).redirectErrorStream(true)
    val env = pb.environment()
    env["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
    env["OMP_STACKSIZE"] = "256M"
    env["OMP_NUM_THREADS"] = cores.toString()
    env["HOME"] = dir.absolutePath
    return try {
        val pr = pb.start()
        curProc = pr
        var out = ""
        val th = Thread { out = pr.inputStream.bufferedReader().readText() }
        th.start()
        while (th.isAlive) { tick(dir); Thread.sleep(600) }
        th.join()
        val c = pr.waitFor()
        curProc = null
        XR(c, out, dir)
    } catch (e: Exception) { XR(-2, "Erreur: " + e.message, dir) }
}

fun parseCharges(out: String, dir: File, n: Int): Pair<List<Float>, List<Float>> {
    var mull = File(dir, "charges").takeIf { it.exists() }?.readLines()?.mapNotNull { it.trim().toFloatOrNull() } ?: emptyList()
    var cm5 = emptyList<Float>()
    val lines = out.lines()
    val h = lines.indexOfLast { it.contains("Mulliken/CM5 charges") }
    if (h >= 0 && h + n < lines.size) {
        val rows = (1..n).map { fl.findAll(lines[h + it]).map { m -> m.value.toFloat() }.toList() }
        if (rows.all { it.size >= 2 }) {
            if (mull.size != n) mull = rows.map { it[0] }
            cm5 = rows.map { it[1] }
        }
    }
    return Pair(mull, cm5)
}

fun saveTxt(ctx: Context, name: String, text: String): String {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri == null) "Échec de l'enregistrement" else {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                "Enregistré dans Téléchargements : " + name
            }
        } else {
            val f = File(ctx.getExternalFilesDir(null), name)
            f.writeText(text)
            "Enregistré : " + f.absolutePath
        }
    } catch (e: Exception) { "Échec : " + e.message }
}

class Ao(val cx: Double, val cy: Double, val cz: Double, val l: Int, val m: Int, val cart: Boolean, val al: DoubleArray, val co: DoubleArray)
class Orb(val ene: Double, val occ: Double, val c: DoubleArray)
class MolDen(val aos: List<Ao>, val orbs: List<Orb>, val atoms: List<DoubleArray>)
class Mesh(val pos: FloatArray, val neg: FloatArray)

fun gam(l: Int): Double = when (l) { 0 -> 0.886226925; 1 -> 1.329340388; 2 -> 3.323350970; else -> 11.63172840 }

fun n2(l: Int, al: DoubleArray, co: DoubleArray): Double {
    var s = 0.0
    for (a in al.indices) for (b in al.indices) s += co[a] * co[b] * gam(l) / (2 * (al[a] + al[b]).pow(l + 1.5))
    return s
}

fun coB(l: Int, al: DoubleArray, co: DoubleArray) = DoubleArray(al.size) { co[it] * sqrt(2 * (2 * al[it]).pow(l + 1.5) / gam(l)) }

fun parseMolden(t: String): MolDen? = try { parseMolden0(t) } catch (e: Exception) { null }

fun parseMolden0(t: String): MolDen? {
    val L = t.lines()
    fun idx(tag: String) = L.indexOfFirst { it.trim().startsWith(tag, ignoreCase = true) }
    val ia = idx("[Atoms]"); val ig = idx("[GTO]"); val im = idx("[MO]")
    if (ia < 0 || ig < 0 || im < 0) return null
    val au = L[ia].contains("AU", true)
    val bohr = 0.52917721
    val sph = L.any { it.trim().uppercase().startsWith("[5D") }
    val atomsB = ArrayList<DoubleArray>()
    val atomsA = ArrayList<DoubleArray>()
    var k = ia + 1
    while (k < L.size && !L[k].trim().startsWith("[")) {
        val p = L[k].trim().split(Regex("\\s+"))
        if (p.size >= 6) {
            val v = doubleArrayOf(p[3].toDouble(), p[4].toDouble(), p[5].toDouble())
            if (au) { atomsB.add(v); atomsA.add(doubleArrayOf(v[0] * bohr, v[1] * bohr, v[2] * bohr)) }
            else { atomsA.add(v); atomsB.add(doubleArrayOf(v[0] / bohr, v[1] / bohr, v[2] / bohr)) }
        }
        k++
    }
    class Sh(val atom: Int, val l: Int, val al: DoubleArray, val co: DoubleArray)
    val shells = ArrayList<Sh>()
    k = ig + 1
    var cur = -1
    while (k < L.size && !L[k].trim().startsWith("[")) {
        val p = L[k].trim().split(Regex("\\s+"))
        if (p.size >= 2 && p[0].toIntOrNull() != null && p[1] == "0") cur = p[0].toInt() - 1
        else if (p.size >= 2 && p[0].length <= 2 && p[0][0].isLetter() && p[1].toIntOrNull() != null) {
            val np = p[1].toInt()
            val al = DoubleArray(np); val co = DoubleArray(np)
            for (q in 0 until np) {
                k++
                val z = L[k].trim().split(Regex("\\s+"))
                al[q] = z[0].replace('D', 'E').replace('d', 'e').toDouble()
                co[q] = z[1].replace('D', 'E').replace('d', 'e').toDouble()
            }
            val l = when (p[0].lowercase()) { "s" -> 0; "p" -> 1; "d" -> 2; else -> 3 }
            shells.add(Sh(cur, l, al, co))
        }
        k++
    }
    var eA = 0.0; var eB = 0.0
    for (s in shells) if (s.l <= 1) {
        eA += abs(n2(s.l, s.al, s.co) - 1)
        eB += abs(n2(s.l, s.al, coB(s.l, s.al, s.co)) - 1)
    }
    val useB = eB < eA
    val aos = ArrayList<Ao>()
    for (s in shells) {
        val c0 = if (useB) coB(s.l, s.al, s.co) else s.co
        val nn = sqrt(n2(s.l, s.al, c0))
        val kap = when (s.l) { 0 -> sqrt(1 / (4 * PI)); 1 -> sqrt(3 / (4 * PI)); else -> sqrt(5 / (4 * PI)) }
        val c = DoubleArray(c0.size) { c0[it] / nn * kap }
        val ctr = atomsB[s.atom]
        val cnt = when (s.l) { 0 -> 1; 1 -> 3; 2 -> if (sph) 5 else 6; else -> 7 }
        for (m in 0 until cnt) aos.add(Ao(ctr[0], ctr[1], ctr[2], s.l, m, !sph, s.al, c))
    }
    val nao = aos.size
    val orbs = ArrayList<Orb>()
    k = im + 1
    var ene = 0.0; var occ = 0.0; var coef: DoubleArray? = null; var beta = false
    while (k < L.size) {
        val line = L[k].trim()
        if (line.startsWith("Sym", true)) {
            val c = coef
            if (c != null && !beta) orbs.add(Orb(ene, occ, c))
            coef = DoubleArray(nao); beta = false
        } else if (line.startsWith("Ene", true)) ene = line.substringAfter("=").trim().toDouble()
        else if (line.startsWith("Spin", true)) beta = line.substringAfter("=").trim().startsWith("Beta", true)
        else if (line.startsWith("Occup", true)) occ = line.substringAfter("=").trim().toDouble()
        else {
            val p = line.split(Regex("\\s+"))
            val c = coef
            if (p.size >= 2 && c != null) {
                val i = p[0].toIntOrNull(); val v = p[1].replace('D', 'E').toDoubleOrNull()
                if (i != null && v != null && i in 1..nao) c[i - 1] = v
            }
        }
        k++
    }
    val c = coef
    if (c != null && !beta) orbs.add(Orb(ene, occ, c))
    if (orbs.isEmpty() || aos.isEmpty()) return null
    return MolDen(aos, orbs, atomsA)
}

fun aoVal(a: Ao, x: Double, y: Double, z: Double): Double {
    val dx = x - a.cx; val dy = y - a.cy; val dz = z - a.cz
    val r2 = dx * dx + dy * dy + dz * dz
    if (r2 > 400.0) return 0.0
    var rad = 0.0
    for (i in a.al.indices) rad += a.co[i] * exp(-a.al[i] * r2)
    val ang = when (a.l) {
        0 -> 1.0
        1 -> when (a.m) { 0 -> dx; 1 -> dy; else -> dz }
        2 -> if (a.cart) when (a.m) { 0 -> dx * dx; 1 -> dy * dy; 2 -> dz * dz; 3 -> dx * dy; 4 -> dx * dz; else -> dy * dz }
             else when (a.m) { 0 -> (3 * dz * dz - r2) / 2; 1 -> 1.7320508 * dx * dz; 2 -> 1.7320508 * dy * dz; 3 -> 0.8660254 * (dx * dx - dy * dy); else -> 1.7320508 * dx * dy }
        else -> 0.0
    }
    return rad * ang
}

fun surf(v: FloatArray, nx: Int, ny: Int, nz: Int, org: DoubleArray, h: Double, iso: Float, sg: Float, sh: DoubleArray): FloatArray {
    var buf = FloatArray(1 shl 15)
    var cnt = 0
    fun add(p: DoubleArray) {
        if (cnt + 3 > buf.size) buf = buf.copyOf(buf.size * 2)
        buf[cnt++] = p[0].toFloat(); buf[cnt++] = p[1].toFloat(); buf[cnt++] = p[2].toFloat()
    }
    val ox = intArrayOf(0, 1, 1, 0, 0, 1, 1, 0)
    val oy = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
    val oz = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
    val tet = arrayOf(intArrayOf(0, 5, 1, 6), intArrayOf(0, 1, 2, 6), intArrayOf(0, 2, 3, 6), intArrayOf(0, 3, 7, 6), intArrayOf(0, 7, 4, 6), intArrayOf(0, 4, 5, 6))
    val f = FloatArray(8)
    val pos = Array(8) { DoubleArray(3) }
    fun edge(i: Int, j: Int): DoubleArray {
        val t = (f[i] / (f[i] - f[j])).toDouble()
        return doubleArrayOf(pos[i][0] + t * (pos[j][0] - pos[i][0]) + sh[0], pos[i][1] + t * (pos[j][1] - pos[i][1]) + sh[1], pos[i][2] + t * (pos[j][2] - pos[i][2]) + sh[2])
    }
    for (ix in 0 until nx - 1) for (iy in 0 until ny - 1) for (iz in 0 until nz - 1) {
        var any = false; var all = true
        for (c in 0 until 8) {
            f[c] = sg * v[((ix + ox[c]) * ny + iy + oy[c]) * nz + iz + oz[c]] - iso
            if (f[c] >= 0f) any = true else all = false
        }
        if (!any || all) continue
        for (c in 0 until 8) {
            pos[c][0] = org[0] + (ix + ox[c]) * h
            pos[c][1] = org[1] + (iy + oy[c]) * h
            pos[c][2] = org[2] + (iz + oz[c]) * h
        }
        for (t4 in tet) {
            val ins = ArrayList<Int>(); val outs = ArrayList<Int>()
            for (q in t4) if (f[q] >= 0f) ins.add(q) else outs.add(q)
            when (ins.size) {
                1 -> for (o in outs) add(edge(ins[0], o))
                3 -> for (i2 in ins) add(edge(outs[0], i2))
                2 -> {
                    val p1 = edge(ins[0], outs[0]); val p2 = edge(ins[0], outs[1])
                    val p3 = edge(ins[1], outs[1]); val p4 = edge(ins[1], outs[0])
                    add(p1); add(p2); add(p3); add(p1); add(p3); add(p4)
                }
            }
        }
    }
    return buf.copyOf(cnt)
}

fun buildMesh(md: MolDen, oi: Int, sh: DoubleArray): Mesh? {
    val orb = md.orbs.getOrNull(oi) ?: return null
    val b = 0.52917721
    val mg = 3.5
    val x0 = md.atoms.minOf { it[0] } - mg; val x1 = md.atoms.maxOf { it[0] } + mg
    val y0 = md.atoms.minOf { it[1] } - mg; val y1 = md.atoms.maxOf { it[1] } + mg
    val z0 = md.atoms.minOf { it[2] } - mg; val z1 = md.atoms.maxOf { it[2] } + mg
    val vol = (x1 - x0) * (y1 - y0) * (z1 - z0)
    val h = maxOf(0.25, Math.cbrt(vol / 70000.0))
    val nx = ((x1 - x0) / h).toInt() + 1
    val ny = ((y1 - y0) / h).toInt() + 1
    val nz = ((z1 - z0) / h).toInt() + 1
    val v = FloatArray(nx * ny * nz)
    val used = md.aos.indices.filter { abs(orb.c[it]) > 1e-5 }
    var mx = 0f
    for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nz) {
        val x = (x0 + ix * h) / b; val y = (y0 + iy * h) / b; val z = (z0 + iz * h) / b
        var s = 0.0
        for (a in used) s += orb.c[a] * aoVal(md.aos[a], x, y, z)
        val f = s.toFloat()
        v[(ix * ny + iy) * nz + iz] = f
        if (abs(f) > mx) mx = abs(f)
    }
    if (mx < 1e-6f) return null
    val iso = minOf(0.03f, 0.4f * mx)
    val org = doubleArrayOf(x0, y0, z0)
    return Mesh(surf(v, nx, ny, nz, org, h, iso, 1f, sh), surf(v, nx, ny, nz, org, h, iso, -1f, sh))
}

fun dynBonds(a: List<Atom>, ref: List<Bond>): List<Bond> {
    val out = ArrayList<Bond>()
    for (i in a.indices) for (j in i + 1 until a.size) {
        val dx = a[i].x - a[j].x; val dy = a[i].y - a[j].y; val dz = a[i].z - a[j].z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1.3f * bl(a[i].el, a[j].el)) {
            val o = ref.firstOrNull { (it.a == i && it.b == j) || (it.a == j && it.b == i) }?.o ?: 1
            out.add(Bond(i, j, o))
        }
    }
    return out
}

fun frameDist(f: FloatArray, i: Int, j: Int): Float {
    val dx = f[3 * i] - f[3 * j]; val dy = f[3 * i + 1] - f[3 * j + 1]; val dz = f[3 * i + 2] - f[3 * j + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

fun fmtTime(s: Float): String = if (s < 60f) String.format("%.0f s", s) else String.format("%d min %02d s", (s / 60f).toInt(), (s % 60f).toInt())

fun parseScan(lines: List<String>, n: Int): Pair<List<FloatArray>, List<Double>> {
    val fr = ArrayList<FloatArray>()
    val en = ArrayList<Double>()
    val er = Regex("energy:\\s+(-?\\d+\\.\\d+)")
    var i = 0
    while (i + n + 1 < lines.size) {
        val c = lines[i].trim().toIntOrNull() ?: break
        if (c != n) break
        val e = er.find(lines[i + 1])?.groupValues?.get(1)?.toDoubleOrNull() ?: break
        val a = FloatArray(3 * n)
        var ok = true
        for (k in 0 until n) {
            val row = lines[i + 2 + k].trim().split(Regex("\\s+"))
            if (row.size < 4) { ok = false; break }
            a[3 * k] = row[1].toFloat(); a[3 * k + 1] = row[2].toFloat(); a[3 * k + 2] = row[3].toFloat()
        }
        if (!ok) break
        fr.add(a); en.add(e)
        i += n + 2
    }
    return Pair(fr, en)
}
