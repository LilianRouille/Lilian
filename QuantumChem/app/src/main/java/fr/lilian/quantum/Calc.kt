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
class Mesh(val p: FloatArray, val n: FloatArray, val col: IntArray) {
    val nt = col.size
    val sx = FloatArray(nt * 3); val sy = FloatArray(nt * 3); val sz = FloatArray(nt * 3); val sh = FloatArray(nt * 3)
    val zc = FloatArray(nt)
    val verts = FloatArray(nt * 6); val colors = IntArray(nt * 3)
    val key = IntArray(nt); val order = IntArray(nt); val cnts = IntArray(1025)
}

class Surf(val p: FloatArray, val n: FloatArray)

val zTable = mapOf("H" to 1, "Li" to 3, "Be" to 4, "B" to 5, "C" to 6, "N" to 7, "O" to 8, "F" to 9, "Na" to 11, "Mg" to 12, "Al" to 13,
    "Si" to 14, "P" to 15, "S" to 16, "Cl" to 17, "K" to 19, "Ca" to 20, "Sc" to 21, "Ti" to 22, "V" to 23, "Cr" to 24, "Mn" to 25,
    "Fe" to 26, "Co" to 27, "Ni" to 28, "Cu" to 29, "Zn" to 30, "Ga" to 31, "Ge" to 32, "As" to 33, "Se" to 34, "Br" to 35, "I" to 53)

fun zOf(el: String): Int = zTable[el] ?: 0

fun parseXyzAtoms(s: String): List<Atom> = s.lines().mapNotNull { l ->
    val p = l.trim().split(Regex("\\s+"))
    if (p.size >= 4 && p[1].toFloatOrNull() != null) Atom(p[0], p[1].toFloat(), -p[2].toFloat(), p[3].toFloat()) else null
}

fun perceive(atoms: List<Atom>): Mol {
    val bo = ArrayList<Bond>()
    for (i in atoms.indices) for (j in i + 1 until atoms.size) {
        val dx = atoms[i].x - atoms[j].x; val dy = atoms[i].y - atoms[j].y; val dz = atoms[i].z - atoms[j].z
        if (sqrt(dx * dx + dy * dy + dz * dz) < 1.25f * bl(atoms[i].el, atoms[j].el)) bo.add(Bond(i, j, 1))
    }
    val ord = IntArray(atoms.size)
    for (b in bo) { ord[b.a] += 1; ord[b.b] += 1 }
    repeat(3) {
        for (k in bo.indices) {
            val b = bo[k]
            val ta = tgt[atoms[b.a].el]; val tb = tgt[atoms[b.b].el]
            if (ta != null && tb != null && ord[b.a] < ta && ord[b.b] < tb && b.o < 3) {
                bo[k] = b.copy(o = b.o + 1); ord[b.a]++; ord[b.b]++
            }
        }
    }
    return Mol(atoms, bo)
}

fun renderMesh(m: Mesh, c: android.graphics.Canvas, paint: android.graphics.Paint, yaw: Float, pitch: Float, pv: Triple<Float, Float, Float>, ox: Float, oy: Float, k: Float) {
    val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw); val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
    val nv = m.nt * 3
    if (nv == 0) return
    for (v in 0 until nv) {
        val ax = m.p[3 * v] - pv.first; val ay = m.p[3 * v + 1] - pv.second; val az = m.p[3 * v + 2] - pv.third
        val x1 = ax * cy + az * sy; val z1 = -ax * sy + az * cy
        m.sx[v] = x1 * k + ox; m.sy[v] = (ay * cp - z1 * sp) * k + oy; m.sz[v] = ay * sp + z1 * cp
        val bx = m.n[3 * v]; val by = m.n[3 * v + 1]; val bz = m.n[3 * v + 2]
        val nx1 = bx * cy + bz * sy; val nz1 = -bx * sy + bz * cy
        val ny2 = by * cp - nz1 * sp; val nz2 = by * sp + nz1 * cp
        val sgn = if (nz2 < 0f) -1f else 1f
        val d = sgn * (-0.35f * nx1 - 0.45f * ny2 + 0.82f * nz2)
        m.sh[v] = 0.38f + 0.62f * maxOf(0f, d)
    }
    var zmin = Float.MAX_VALUE; var zmax = -Float.MAX_VALUE
    for (t in 0 until m.nt) {
        val z = (m.sz[3 * t] + m.sz[3 * t + 1] + m.sz[3 * t + 2]) / 3f
        m.zc[t] = z
        if (z < zmin) zmin = z
        if (z > zmax) zmax = z
    }
    val span = zmax - zmin + 1e-6f
    java.util.Arrays.fill(m.cnts, 0)
    for (t in 0 until m.nt) { val b = ((m.zc[t] - zmin) / span * 1023f).toInt(); m.key[t] = b; m.cnts[b + 1]++ }
    for (b in 0 until 1024) m.cnts[b + 1] += m.cnts[b]
    for (t in 0 until m.nt) { m.order[m.cnts[m.key[t]]++] = t }
    for (q in 0 until m.nt) {
        val t = m.order[q]; val cc = m.col[t]
        val r = (cc shr 16) and 255; val g = (cc shr 8) and 255; val bb = cc and 255
        for (e in 0 until 3) {
            val v = 3 * t + e
            m.verts[6 * q + 2 * e] = m.sx[v]; m.verts[6 * q + 2 * e + 1] = m.sy[v]
            val s2 = m.sh[v]
            m.colors[3 * q + e] = (255 shl 24) or ((r * s2).toInt().coerceAtMost(255) shl 16) or ((g * s2).toInt().coerceAtMost(255) shl 8) or (bb * s2).toInt().coerceAtMost(255)
        }
    }
    c.drawVertices(android.graphics.Canvas.VertexMode.TRIANGLES, nv * 2, m.verts, 0, null, 0, m.colors, 0, null, 0, 0, paint)
}

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
    if (r2 > 150.0) return 0.0
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

fun surf(v: FloatArray, nx: Int, ny: Int, nz: Int, org: DoubleArray, h: Double, iso: Float, sg: Float, sh: DoubleArray): Surf {
    var pb = FloatArray(1 shl 15)
    var nb = FloatArray(1 shl 15)
    var cnt = 0
    fun add(q: DoubleArray) {
        if (cnt + 3 > pb.size) { pb = pb.copyOf(pb.size * 2); nb = nb.copyOf(nb.size * 2) }
        pb[cnt] = q[0].toFloat(); pb[cnt + 1] = q[1].toFloat(); pb[cnt + 2] = q[2].toFloat()
        nb[cnt] = q[3].toFloat(); nb[cnt + 1] = q[4].toFloat(); nb[cnt + 2] = q[5].toFloat()
        cnt += 3
    }
    fun gr(ix: Int, iy: Int, iz: Int, o: DoubleArray) {
        val x0 = maxOf(ix - 1, 0); val x1 = minOf(ix + 1, nx - 1)
        val y0 = maxOf(iy - 1, 0); val y1 = minOf(iy + 1, ny - 1)
        val z0 = maxOf(iz - 1, 0); val z1 = minOf(iz + 1, nz - 1)
        o[0] = (v[(x1 * ny + iy) * nz + iz] - v[(x0 * ny + iy) * nz + iz]).toDouble() / (x1 - x0)
        o[1] = (v[(ix * ny + y1) * nz + iz] - v[(ix * ny + y0) * nz + iz]).toDouble() / (y1 - y0)
        o[2] = (v[(ix * ny + iy) * nz + z1] - v[(ix * ny + iy) * nz + z0]).toDouble() / (z1 - z0)
    }
    val ox = intArrayOf(0, 1, 1, 0, 0, 1, 1, 0)
    val oy = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
    val oz = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)
    val cix = IntArray(8); val ciy = IntArray(8); val ciz = IntArray(8)
    val tet = arrayOf(intArrayOf(0, 5, 1, 6), intArrayOf(0, 1, 2, 6), intArrayOf(0, 2, 3, 6), intArrayOf(0, 3, 7, 6), intArrayOf(0, 7, 4, 6), intArrayOf(0, 4, 5, 6))
    val f = FloatArray(8)
    val pos = Array(8) { DoubleArray(3) }
    val gi = DoubleArray(3); val gj = DoubleArray(3)
    val sgd = sg.toDouble()
    fun edge(i: Int, j: Int): DoubleArray {
        val t = (f[i] / (f[i] - f[j])).toDouble()
        gr(cix[i], ciy[i], ciz[i], gi); gr(cix[j], ciy[j], ciz[j], gj)
        val a = -sgd * ((1 - t) * gi[0] + t * gj[0])
        val b = -sgd * ((1 - t) * gi[1] + t * gj[1])
        val d = -sgd * ((1 - t) * gi[2] + t * gj[2])
        val l = sqrt(a * a + b * b + d * d) + 1e-12
        return doubleArrayOf(
            pos[i][0] + t * (pos[j][0] - pos[i][0]) + sh[0], pos[i][1] + t * (pos[j][1] - pos[i][1]) + sh[1], pos[i][2] + t * (pos[j][2] - pos[i][2]) + sh[2],
            a / l, b / l, d / l)
    }
    for (ix in 0 until nx - 1) for (iy in 0 until ny - 1) for (iz in 0 until nz - 1) {
        var any = false; var all = true
        for (cc in 0 until 8) {
            f[cc] = sg * v[((ix + ox[cc]) * ny + iy + oy[cc]) * nz + iz + oz[cc]] - iso
            if (f[cc] >= 0f) any = true else all = false
        }
        if (!any || all) continue
        for (cc in 0 until 8) {
            cix[cc] = ix + ox[cc]; ciy[cc] = iy + oy[cc]; ciz[cc] = iz + oz[cc]
            pos[cc][0] = org[0] + cix[cc] * h
            pos[cc][1] = org[1] + ciy[cc] * h
            pos[cc][2] = org[2] + ciz[cc] * h
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
    return Surf(pb.copyOf(cnt), nb.copyOf(cnt))
}

fun buildMesh(md: MolDen, oi: Int, sh: DoubleArray, density: Boolean = false, c1: Int = 0xFF4A86F5.toInt(), c2: Int = 0xFFF05050.toInt()): Mesh? {
    val orb = md.orbs.getOrNull(oi) ?: return null
    val b = 0.52917721
    val mg = 3.5
    val x0 = md.atoms.minOf { it[0] } - mg; val x1 = md.atoms.maxOf { it[0] } + mg
    val y0 = md.atoms.minOf { it[1] } - mg; val y1 = md.atoms.maxOf { it[1] } + mg
    val z0 = md.atoms.minOf { it[2] } - mg; val z1 = md.atoms.maxOf { it[2] } + mg
    val vol = (x1 - x0) * (y1 - y0) * (z1 - z0)
    val h = maxOf(0.3, Math.cbrt(vol / 60000.0))
    val nx = ((x1 - x0) / h).toInt() + 1
    val ny = ((y1 - y0) / h).toInt() + 1
    val nz = ((z1 - z0) / h).toInt() + 1
    val v = FloatArray(nx * ny * nz)
    val used = md.aos.indices.filter { abs(orb.c[it]) > 1e-4 }
    var mx = 0f
    for (ix in 0 until nx) for (iy in 0 until ny) for (iz in 0 until nz) {
        val x = (x0 + ix * h) / b; val y = (y0 + iy * h) / b; val z = (z0 + iz * h) / b
        var s = 0.0
        for (a in used) s += orb.c[a] * aoVal(md.aos[a], x, y, z)
        var f = s.toFloat()
        if (density) f *= f
        v[(ix * ny + iy) * nz + iz] = f
        if (abs(f) > mx) mx = abs(f)
    }
    if (mx < 1e-9f) return null
    val iso = if (density) 0.12f * mx else minOf(0.03f, 0.4f * mx)
    val org = doubleArrayOf(x0, y0, z0)
    val parts = ArrayList<Pair<Surf, Int>>()
    parts.add(Pair(surf(v, nx, ny, nz, org, h, iso, 1f, sh), c1))
    if (!density) parts.add(Pair(surf(v, nx, ny, nz, org, h, iso, -1f, sh), c2))
    val total = parts.sumOf { it.first.p.size }
    if (total == 0) return null
    val p = FloatArray(total); val n = FloatArray(total); val col = IntArray(total / 9)
    var o = 0; var ct = 0
    for ((s, cc) in parts) {
        s.p.copyInto(p, o); s.n.copyInto(n, o)
        for (q in 0 until s.p.size / 9) col[ct++] = cc
        o += s.p.size
    }
    var i = 1
    while (i < total) { p[i] = -p[i]; n[i] = -n[i]; i += 3 }
    return Mesh(p, n, col)
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
            a[3 * k] = row[1].toFloat(); a[3 * k + 1] = -row[2].toFloat(); a[3 * k + 2] = row[3].toFloat()
        }
        if (!ok) break
        fr.add(a); en.add(e)
        i += n + 2
    }
    return Pair(fr, en)
}
