package fr.lilian.quantum

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.scale
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import java.io.File

const val U = 40f
typealias V = Triple<Float, Float, Float>
data class Atom(val el: String, val x: Float, val y: Float, val z: Float = 0f)
data class Bond(val a: Int, val b: Int, val o: Int = 1)
data class Mol(val atoms: List<Atom> = emptyList(), val bonds: List<Bond> = emptyList())
enum class Mode { DRAW, DELETE, VIEW, MEASURE }

val cpk = mapOf(
    "H" to 0xFFFFFFFF, "C" to 0xFF909090, "N" to 0xFF3050F8, "O" to 0xFFFF0D0D,
    "S" to 0xFFFFFF30, "B" to 0xFFFFB5B5, "F" to 0xFF90E050, "Cl" to 0xFF1FF01F,
    "Br" to 0xFFA62929, "I" to 0xFF940094, "P" to 0xFFFF8000, "Si" to 0xFFF0C8A0
)
val rad = mapOf("H" to 0.31f, "C" to 0.76f, "N" to 0.71f, "O" to 0.66f, "S" to 1.05f, "B" to 0.84f,
    "F" to 0.57f, "Cl" to 1.02f, "Br" to 1.2f, "I" to 1.39f, "P" to 1.07f, "Si" to 1.11f)
val tgt = mapOf("C" to 4, "N" to 3, "O" to 2, "S" to 2, "B" to 3, "F" to 1, "Cl" to 1, "Br" to 1, "I" to 1, "P" to 3, "Si" to 4)
val lonePairs = mapOf("N" to 1, "O" to 2, "S" to 2, "P" to 1)
fun bl(a: String, b: String) = (rad[a] ?: 0.8f) + (rad[b] ?: 0.8f)
val fib: List<V> = (0 until 80).map { i ->
    val y = 1f - 2f * (i + 0.5f) / 80f
    val r = sqrt(1f - y * y)
    val th = i * 2.39996f
    Triple(cos(th) * r, y, sin(th) * r)
}
val ptRows: List<List<String>> = listOf(
    "H" + " .".repeat(16) + " He",
    "Li Be" + " .".repeat(10) + " B C N O F Ne",
    "Na Mg" + " .".repeat(10) + " Al Si P S Cl Ar",
    "K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr",
    "Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe",
    "Cs Ba * Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn",
    ". . La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm Yb Lu ."
).map { it.split(" ") }

fun removeH(m: Mol): Mol {
    val keep = m.atoms.indices.filter { m.atoms[it].el != "H" }
    val map = keep.withIndex().associate { (n, o) -> o to n }
    return Mol(keep.map { m.atoms[it] },
        m.bonds.filter { map.containsKey(it.a) && map.containsKey(it.b) }.map { Bond(map[it.a]!!, map[it.b]!!, it.o) })
}

fun delAt(m: Mol, h: Int): Mol = Mol(m.atoms.filterIndexed { i, _ -> i != h },
    m.bonds.filter { it.a != h && it.b != h }.map { Bond(if (it.a > h) it.a - 1 else it.a, if (it.b > h) it.b - 1 else it.b, it.o) })

fun addH(m: Mol): Mol {
    val atoms = m.atoms.toMutableList()
    val bonds = m.bonds.toMutableList()
    for (i in m.atoms.indices) {
        val a = m.atoms[i]
        val t = tgt[a.el] ?: continue
        val nb = bonds.filter { it.a == i || it.b == i }
        val miss = t - nb.sumOf { it.o }
        if (miss <= 0) continue
        val fixed = nb.map {
            val b = atoms[if (it.a == i) it.b else it.a]
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val d = sqrt(dx * dx + dy * dy + dz * dz) + 1e-6f
            floatArrayOf(dx / d, dy / d, dz / d)
        }
        val free = List(miss + (lonePairs[a.el] ?: 0)) { k -> fib[(i * 7 + k * 23 + 5) % 80].let { floatArrayOf(it.first, it.second, it.third) } }
        repeat(120) {
            for (f in free) {
                var fx = 0f; var fy = 0f; var fz = 0f
                for (g in fixed + free) {
                    if (g === f) continue
                    val dx = f[0] - g[0]; val dy = f[1] - g[1]; val dz = f[2] - g[2]
                    val r2 = dx * dx + dy * dy + dz * dz + 1e-3f
                    val k = 1f / (r2 * sqrt(r2))
                    fx += dx * k; fy += dy * k; fz += dz * k
                }
                val x = f[0] + 0.05f * fx; val y = f[1] + 0.05f * fy; val z = f[2] + 0.05f * fz
                val n = sqrt(x * x + y * y + z * z) + 1e-6f
                f[0] = x / n; f[1] = y / n; f[2] = z / n
            }
        }
        val len = bl(a.el, "H")
        for (k in 0 until miss) {
            val c = free[k]
            atoms.add(Atom("H", a.x + c[0] * len, a.y + c[1] * len, a.z + c[2] * len))
            bonds.add(Bond(i, atoms.size - 1))
        }
    }
    return Mol(atoms, bonds)
}

fun relax(m: Mol, iters: Int = 1): Mol {
    val n = m.atoms.size
    if (n < 2) return m
    val p = m.atoms.map { floatArrayOf(it.x, it.y, it.z) }
    if (p.all { abs(it[2]) < 1e-4f }) p.forEachIndexed { i, v -> v[2] += if (i % 2 == 0) 0.03f else -0.03f }
    val cons = HashMap<Int, FloatArray>()
    fun key(i: Int, j: Int) = if (i < j) i * n + j else j * n + i
    fun ln(a: Atom, b: Atom, o: Int) = bl(a.el, b.el) * (if (o == 2) 0.9f else if (o == 3) 0.8f else 1f)
    val nbs = List(n) { ArrayList<Bond>() }
    for (b in m.bonds) {
        nbs[b.a].add(b); nbs[b.b].add(b)
        cons[key(b.a, b.b)] = floatArrayOf(ln(m.atoms[b.a], m.atoms[b.b], b.o), 0.5f)
    }
    val sp2 = ArrayList<IntArray>()
    for (k in 0 until n) {
        val nb = nbs[k]
        if (nb.size < 2) continue
        val el = m.atoms[k].el
        val used = nb.sumOf { it.o }
        val sn = nb.size + maxOf(0, (tgt[el] ?: used) - used) + (lonePairs[el] ?: 0)
        val c = if (sn >= 4) -0.334f else if (sn == 3) -0.5f else -1f
        val others = nb.map { if (it.a == k) it.b else it.a }
        if (nb.size == 3 && sn == 3) sp2.add(intArrayOf(k, others[0], others[1], others[2]))
        for (x in 0 until nb.size) for (y in x + 1 until nb.size) {
            val l1 = ln(m.atoms[k], m.atoms[others[x]], nb[x].o); val l2 = ln(m.atoms[k], m.atoms[others[y]], nb[y].o)
            val kk = key(others[x], others[y])
            if (!cons.containsKey(kk)) cons[kk] = floatArrayOf(sqrt(maxOf(0f, l1 * l1 + l2 * l2 - 2f * l1 * l2 * c)), 0.3f)
        }
    }
    repeat(iters) {
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = p[j][0] - p[i][0]; val dy = p[j][1] - p[i][1]; val dz = p[j][2] - p[i][2]
            val d = sqrt(dx * dx + dy * dy + dz * dz) + 1e-6f
            val c = cons[key(i, j)]
            val th = if (m.atoms[i].el == "H" || m.atoms[j].el == "H") 1.8f else 2.4f
            val t = c?.get(0) ?: th
            val k = c?.get(1) ?: 0.1f
            if (c != null || d < t) {
                val s = k * (d - t) / d / 2f
                p[i][0] += dx * s; p[i][1] += dy * s; p[i][2] += dz * s
                p[j][0] -= dx * s; p[j][1] -= dy * s; p[j][2] -= dz * s
            }
        }
        for (q in sp2) {
            val k = q[0]; val a = p[q[1]]; val b = p[q[2]]; val c = p[q[3]]
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val nl = sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f
            nx /= nl; ny /= nl; nz /= nl
            val cx = (a[0] + b[0] + c[0]) / 3f; val cy = (a[1] + b[1] + c[1]) / 3f; val cz = (a[2] + b[2] + c[2]) / 3f
            val dd = (p[k][0] - cx) * nx + (p[k][1] - cy) * ny + (p[k][2] - cz) * nz
            p[k][0] -= nx * dd * 0.3f; p[k][1] -= ny * dd * 0.3f; p[k][2] -= nz * dd * 0.3f
            for (o in listOf(a, b, c)) { o[0] += nx * dd * 0.1f; o[1] += ny * dd * 0.1f; o[2] += nz * dd * 0.1f }
        }
    }
    var mv = 0f
    m.atoms.forEachIndexed { i, a -> mv = maxOf(mv, abs(p[i][0] - a.x) + abs(p[i][1] - a.y) + abs(p[i][2] - a.z)) }
    if (mv < 5e-4f) return m
    return Mol(m.atoms.mapIndexed { i, a -> a.copy(x = p[i][0], y = p[i][1], z = p[i][2]) }, m.bonds)
}

fun toXyz(m: Mol): String = m.atoms.size.toString() + "\n\n" +
    m.atoms.joinToString("\n") { String.format(java.util.Locale.US, "%s %.6f %.6f %.6f", it.el, it.x, -it.y, it.z) } + "\n"

fun fromXyz(m: Mol, s: String): Mol? {
    val l = s.lines().drop(2).filter { it.isNotBlank() }
    if (l.size != m.atoms.size) return null
    return try {
        m.copy(atoms = m.atoms.mapIndexed { i, a ->
            val q = l[i].trim().split(Regex("\\s+"))
            a.copy(x = q[1].toFloat(), y = -q[2].toFloat(), z = q[3].toFloat())
        })
    } catch (e: Exception) { null }
}

fun xtbJob(ctx: android.content.Context, args: List<String>, xyz: String): Triple<Int, String, String?> {
    val exe = File(ctx.applicationInfo.nativeLibraryDir, "libxtb.so")
    val dir = File(ctx.cacheDir, "xtb").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    File(dir, "mol.xyz").writeText(xyz)
    if (!exe.exists()) return Triple(-1, "xtb absent", null)
    val pb = ProcessBuilder(listOf(exe.absolutePath, "mol.xyz") + args).directory(dir).redirectErrorStream(true)
    pb.environment()["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
    pb.environment()["OMP_STACKSIZE"] = "256M"
    pb.environment()["OMP_NUM_THREADS"] = "1"
    pb.environment()["OPENBLAS_NUM_THREADS"] = "1"
    pb.environment()["GOTO_NUM_THREADS"] = "1"
    pb.environment()["HOME"] = dir.absolutePath
    return try {
        val pr = pb.start()
        val out = pr.inputStream.bufferedReader().readText()
        val c = pr.waitFor()
        Triple(c, out, File(dir, "xtbopt.xyz").takeIf { it.exists() }?.readText())
    } catch (e: Exception) { Triple(-2, "Erreur: " + e.message, null) }
}

class Vib(val freq: Float, val ir: Float, val d: FloatArray)

fun readXtbFile(ctx: android.content.Context, name: String): String? =
    File(File(ctx.cacheDir, "xtb"), name).takeIf { it.exists() }?.readText()

val fl = Regex("-?\\d+\\.\\d+")

fun parseG98(t: String, n: Int): List<Vib> {
    val lines = t.lines()
    val res = ArrayList<Vib>()
    var i = 0
    while (i < lines.size) {
        val l = lines[i]
        if (l.contains("Frequencies --")) {
            val fr = fl.findAll(l.substringAfter("--")).map { it.value.toFloat() }.toList()
            var ir = listOf<Float>()
            var j = i + 1
            while (j < lines.size && !lines[j].contains("Atom")) {
                if (lines[j].contains("IR Inten")) ir = fl.findAll(lines[j].substringAfter("--")).map { it.value.toFloat() }.toList()
                j++
            }
            val d = List(fr.size) { FloatArray(3 * n) }
            for (a in 0 until n) {
                val row = lines.getOrNull(j + 1 + a) ?: break
                val v = fl.findAll(row).map { it.value.toFloat() }.toList()
                for (k in fr.indices) for (c in 0 until 3) d[k][3 * a + c] = (if (c == 1) -1f else 1f) * v.getOrElse(3 * k + c) { 0f }
            }
            for (k in fr.indices) {
                val dd = d[k]
                var mx = 1e-6f
                for (a in 0 until n) mx = maxOf(mx, sqrt(dd[3 * a] * dd[3 * a] + dd[3 * a + 1] * dd[3 * a + 1] + dd[3 * a + 2] * dd[3 * a + 2]))
                for (q in dd.indices) dd[q] /= mx
                if (fr[k] != 0f) res.add(Vib(fr[k], ir.getOrElse(k) { 0f }, dd))
            }
            i = j + 1 + n
        } else i++
    }
    return res
}

fun parseVibspectrum(t: String): List<Vib> = t.lines().filter { !it.trim().startsWith("#") && !it.trim().startsWith("$") }
    .mapNotNull { l ->
        val v = fl.findAll(l).map { it.value.toFloat() }.toList()
        if (v.size >= 2 && v[0] != 0f) Vib(v[0], v[1], FloatArray(0)) else null
    }

fun segD(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val l2 = ab.x * ab.x + ab.y * ab.y + 1e-6f
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

fun runXtb(ctx: android.content.Context, args: List<String>, xyz: String): String {
    val exe = File(ctx.applicationInfo.nativeLibraryDir, "libxtb.so")
    if (!exe.exists()) return "xtb absent de l'APK / xtb missing from the APK"
    val dir = File(ctx.cacheDir, "xtb").apply { mkdirs() }
    File(dir, "mol.xyz").writeText(xyz)
    val pb = ProcessBuilder(listOf(exe.absolutePath, "mol.xyz") + args).directory(dir).redirectErrorStream(true)
    pb.environment()["GLIBC_TUNABLES"] = "glibc.pthread.rseq=0"
    pb.environment()["OMP_STACKSIZE"] = "256M"
    pb.environment()["OMP_NUM_THREADS"] = "1"
    pb.environment()["OPENBLAS_NUM_THREADS"] = "1"
    pb.environment()["GOTO_NUM_THREADS"] = "1"
    pb.environment()["HOME"] = dir.absolutePath
    return try {
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val c = p.waitFor()
        "exit=" + c + "\n" + out.lines().takeLast(25).joinToString("\n")
    } catch (e: Exception) { "Erreur: " + e.message }
}

fun runLib(ctx: android.content.Context, lib: String, args: List<String>, env: Map<String, String> = emptyMap()): Pair<Int, String> {
    val exe = File(ctx.applicationInfo.nativeLibraryDir, lib)
    if (!exe.exists()) return Pair(-1, "absent")
    return try {
        val pb = ProcessBuilder(listOf(exe.absolutePath) + args).redirectErrorStream(true)
        pb.environment().putAll(env)
        val pr = pb.start()
        val out = pr.inputStream.bufferedReader().readText()
        Pair(pr.waitFor(), out.trim())
    } catch (e: Exception) { Pair(-2, e.message ?: "?") }
}

fun entryInfo(ctx: android.content.Context, lib: String): String {
    val f = File(ctx.applicationInfo.nativeLibraryDir, lib)
    if (!f.exists()) return "absent"
    return try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val le = java.nio.ByteOrder.LITTLE_ENDIAN
            val h = ByteArray(64); r.readFully(h)
            val bb = java.nio.ByteBuffer.wrap(h).order(le)
            val entry = bb.getLong(24)
            val phoff = bb.getLong(32)
            val phsz = bb.getShort(54).toInt()
            val phnum = bb.getShort(56).toInt()
            var off = -1L
            for (i in 0 until phnum) {
                r.seek(phoff + i.toLong() * phsz)
                val q = ByteArray(56); r.readFully(q)
                val pb = java.nio.ByteBuffer.wrap(q).order(le)
                val type = pb.getInt(0); val poff = pb.getLong(8); val vaddr = pb.getLong(16); val fsz = pb.getLong(32)
                if (type == 1 && entry >= vaddr && entry < vaddr + fsz) { off = poff + (entry - vaddr); break }
            }
            if (off < 0) "entrée introuvable" else {
                r.seek(off)
                val w = ByteArray(4); r.readFully(w)
                val word = java.nio.ByteBuffer.wrap(w).order(le).getInt(0).toLong() and 0xFFFFFFFFL
                "entrée=0x" + entry.toString(16) + " mot=0x" + word.toString(16) + (if (word == 0xD100C3FFL) " -> STUB PRESENT" else " -> pas de stub")
            }
        }
    } catch (e: Exception) { "erreur " + e.message }
}

fun diag(ctx: android.content.Context): String {
    val sb = StringBuilder()
    sb.append("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n")
    val tun = mapOf("GLIBC_TUNABLES" to "glibc.pthread.rseq=0")
    val h = runLib(ctx, "libhello.so", emptyList(), tun)
    sb.append("hello glibc : exit=" + h.first + " " + h.second + "\n")
    val h2 = runLib(ctx, "libhello.so", emptyList())
    sb.append("hello sans tunable : exit=" + h2.first + "\n")
    sb.append("hello " + entryInfo(ctx, "libhello.so") + "\n")
    sb.append("xtb " + entryInfo(ctx, "libxtb.so") + "\n")
    val list = listOf("set_tid_address" to 96, "set_robust_list" to 99, "futex" to 98, "rt_sigaction" to 134,
        "prlimit64" to 261, "readlinkat" to 78, "getrandom" to 278, "brk" to 214, "mmap" to 222, "munmap" to 215,
        "mprotect" to 226, "openat" to 56, "close" to 57, "read" to 63, "lseek" to 62, "ioctl" to 29, "fstat" to 80,
        "newfstatat" to 79, "faccessat" to 48, "getrusage" to 165, "sched_getaffinity" to 123, "rseq" to 293,
        "clone3" to 435, "statx" to 291, "faccessat2" to 439, "mbind(temoin)" to 235)
    for ((n, nr) in list) {
        val r = runLib(ctx, "libsysprobe.so", listOf(nr.toString()))
        sb.append(n + " : " + (if (r.first == 159) "BLOQUE" else if (r.first == 94) "ok" else "exit " + r.first) + "\n")
    }
    val x = runLib(ctx, "libxtb.so", listOf("--version"), tun)
    sb.append("xtb --version : exit=" + x.first + "\n")
    return sb.toString()
}

@Composable
fun ScanPlot(e: List<Double>, d0: Float, d1: Float, tt: Float, modifier: Modifier) {
    val rel = e.map { (it - e[0]) * 2625.499638 }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val l = 100f; val b = 44f; val tp = 24f; val r = 24f
        val mn = minOf(0.0, rel.min()); val mx = maxOf(rel.max(), mn + 1e-3)
        val span = mx - mn
        val step = if (span <= 600.0) 50.0 else if (span <= 1500.0) 100.0 else 250.0
        fun px(i: Int) = l + (w - l - r) * (if (rel.size > 1) i.toFloat() / (rel.size - 1) else 0f)
        fun py(v: Double) = (h - b - (h - b - tp) * ((v - mn) / (mx - mn))).toFloat()
        val pt = Paint().apply { color = android.graphics.Color.LTGRAY; textSize = 26f }
        val nc = drawContext.canvas.nativeCanvas
        var tv = Math.ceil(mn / step) * step
        while (tv <= mx + 1e-6) {
            val yy = py(tv)
            drawLine(if (tv == 0.0) Color(0x66FFFFFF) else Color(0x28FFFFFF), Offset(l, yy), Offset(w - r, yy), if (tv == 0.0) 2f else 1f)
            nc.drawText(String.format("%.0f", tv), 6f, yy + 9f, pt)
            tv += step
        }
        drawLine(Color.Gray, Offset(l, tp), Offset(l, h - b), 2f)
        drawLine(Color.Gray, Offset(l, h - b), Offset(w - r, h - b), 2f)
        for (i in 1 until rel.size) drawLine(Color(0xFF4FC3F7), Offset(px(i - 1), py(rel[i - 1])), Offset(px(i), py(rel[i])), 4f)
        rel.forEachIndexed { i, v -> drawCircle(Color(0xFF4FC3F7), 5f, Offset(px(i), py(v))) }
        val i0 = tt.toInt().coerceIn(0, rel.size - 1); val i1 = minOf(i0 + 1, rel.size - 1); val fr = tt - i0
        val mxp = px(i0) + (px(i1) - px(i0)) * fr
        val myp = py(rel[i0]) + (py(rel[i1]) - py(rel[i0])) * fr
        drawLine(Color(0x88FFD54F), Offset(mxp, tp), Offset(mxp, h - b), 2f)
        drawCircle(Color(0xFFFFD54F), 11f, Offset(mxp, myp))
        nc.drawText("kJ/mol", l + 8f, tp + 22f, pt)
        nc.drawText(String.format("%.2f Å", d0), l, h - 10f, pt)
        nc.drawText(String.format("%.2f Å", d1), w - r - 120f, h - 10f, pt)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun Menu(label: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (n, f) -> DropdownMenuItem(text = { Text(n) }, onClick = { open = false; f() }) }
        }
    }
}

@Composable
fun PeriodicTable(cur: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(6.dp)) {
                Row { (1..18).forEach { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Text("$it", fontSize = 10.sp, color = Color.Gray) } } }
                ptRows.forEach { row ->
                    Row {
                        row.forEach { s ->
                            val known = s != "." && s != "*"
                            val bg = if (s == cur) Color(0xFF3A6FD8) else Color(cpk[s] ?: 0xFF607D8B)
                            val m = Modifier.size(36.dp).padding(1.dp)
                            Box(if (known) m.background(bg).clickable { onPick(s) } else m, contentAlignment = Alignment.Center) {
                                if (s != ".") Text(s, fontSize = 12.sp, color = if (cpk[s] != null && s != cur) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App() {
    var fr by remember { mutableStateOf(true) }
    var mol by remember { mutableStateOf(Mol()) }
    val undo = remember { mutableStateListOf<Mol>() }
    val redo = remember { mutableStateListOf<Mol>() }
    var mode by remember { mutableStateOf(Mode.DRAW) }
    var el by remember { mutableStateOf("C") }
    var scale by remember { mutableStateOf(1.6f) }
    var yaw by remember { mutableStateOf(0.4f) }
    var pitch by remember { mutableStateOf(0.3f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var picked by remember { mutableStateOf(listOf<Int>()) }
    var dialog by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(false) }
    var autoH by remember { mutableStateOf(true) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var xtbOut by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var cs by remember { mutableStateOf(IntSize.Zero) }
    var pv by remember { mutableStateOf(Triple(0f, 0f, 0f)) }
    var running by remember { mutableStateOf(false) }
    var calcLog by remember { mutableStateOf("") }
    var vibs by remember { mutableStateOf(listOf<Vib>()) }
    var vibSel by remember { mutableStateOf(-1) }
    var vphase by remember { mutableStateOf(0f) }
    var charges by remember { mutableStateOf(listOf<Float>()) }
    var showCh by remember { mutableStateOf(true) }
    var freqSummary by remember { mutableStateOf("") }
    var solvent by remember { mutableStateOf("") }
    var cores by remember { mutableStateOf(1) }
    var busyMsg by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1f) }
    var eta by remember { mutableStateOf("") }
    var react by remember { mutableStateOf("") }
    var chType by remember { mutableStateOf("Mulliken") }
    var qC by remember { mutableStateOf(listOf<Float>()) }
    var fuMode by remember { mutableStateOf("") }
    var fP by remember { mutableStateOf(listOf<Float>()) }
    var fM by remember { mutableStateOf(listOf<Float>()) }
    var molden by remember { mutableStateOf<MolDen?>(null) }
    var orbSel by remember { mutableStateOf(99) }
    var orbMesh by remember { mutableStateOf<Mesh?>(null) }
    var scanFrames by remember { mutableStateOf(listOf<FloatArray>()) }
    var scanE by remember { mutableStateOf(listOf<Double>()) }
    var scanIdx by remember { mutableStateOf(0) }
    var scanPlay by remember { mutableStateOf(false) }
    var scanD by remember { mutableStateOf(Pair(0f, 0f)) }
    var scanEnd by remember { mutableStateOf("") }
    var scanN by remember { mutableStateOf("20") }
    var freeArgs by remember { mutableStateOf("--gfn 2 --opt") }
    var freeInp by remember { mutableStateOf("") }
    var freeOut by remember { mutableStateOf("") }
    var freeOpt by remember { mutableStateOf<Mol?>(null) }
    var charge by remember { mutableStateOf(0) }
    var scanT by remember { mutableStateOf(0f) }
    var fuLobes by remember { mutableStateOf(true) }
    var fuVals by remember { mutableStateOf(true) }
    var scanPreOpt by remember { mutableStateOf(true) }
    var scanTight by remember { mutableStateOf(false) }
    var scanWarn by remember { mutableStateOf("") }
    var fuMeshM by remember { mutableStateOf<Mesh?>(null) }
    var fuMeshP by remember { mutableStateOf<Mesh?>(null) }
    val vpaint = remember { Paint() }

    LaunchedEffect(scanPlay) { while (scanPlay && scanFrames.size > 1) { scanT += 0.07f; if (scanT >= scanFrames.size - 1) scanT = 0f; delay(33) } }
    LaunchedEffect(vibSel) { while (vibSel >= 0) { vphase += 0.15f; delay(33) } }
    LaunchedEffect(shape) { while (shape) { mol = relax(mol, 6); delay(33) } }

    fun t(f: String, e: String) = if (fr) f else e
    fun commit(m: Mol) { undo.add(mol); redo.clear(); mol = m; vibs = emptyList(); vibSel = -1; charges = emptyList(); qC = emptyList(); fP = emptyList(); fM = emptyList(); molden = null; orbSel = 99; orbMesh = null; fuMeshM = null; fuMeshP = null; scanFrames = emptyList(); scanPlay = false }
    fun fin(m: Mol): Mol = if (autoH) addH(m) else m
    fun rot(a: Atom): V {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val ax = a.x - pv.first; val ay = a.y - pv.second; val az = a.z - pv.third
        val x1 = ax * cy + az * sy
        val z1 = -ax * sy + az * cy
        return Triple(x1, ay * cp - z1 * sp, ay * sp + z1 * cp)
    }
    fun unrot(x1: Float, y1: Float, z2: Float): V {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val y = y1 * cp + z2 * sp
        val z1 = -y1 * sp + z2 * cp
        return Triple(x1 * cy - z1 * sy + pv.first, y + pv.second, x1 * sy + z1 * cy + pv.third)
    }
    fun scrOf(a: Atom): Offset { val r = rot(a); return Offset(r.first * U * scale + (off.x + cs.width / 2f), r.second * U * scale + (off.y + cs.height / 2f)) }
    fun hit(p: Offset): Int? {
        var best: Int? = null
        var bz = -1e9f
        mol.atoms.forEachIndexed { i, a ->
            val z = rot(a).third
            if ((scrOf(a) - p).getDistance() < 34f && z > bz) { bz = z; best = i }
        }
        return best
    }
    fun bondHit(p: Offset): Int? {
        val i = mol.bonds.indexOfFirst { segD(p, scrOf(mol.atoms[it.a]), scrOf(mol.atoms[it.b])) < 14f }
        return if (i >= 0) i else null
    }
    fun baseMol() = if (autoH) removeH(mol) else mol
    fun hIdx(h: Int): Int? = if (!autoH) h else mol.atoms.indices.filter { mol.atoms[it].el != "H" }.indexOf(h).takeIf { it >= 0 }
    fun focus(m: Mol = mol) {
        if (m.atoms.isEmpty()) return
        val cx = m.atoms.map { it.x }.average().toFloat()
        val cy = m.atoms.map { it.y }.average().toFloat()
        val cz = m.atoms.map { it.z }.average().toFloat()
        pv = Triple(cx, cy, cz); off = Offset.Zero
        val r = m.atoms.maxOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) + (it.z - cz) * (it.z - cz)) } + 1.5f
        if (cs.width > 0) scale = (minOf(cs.width, cs.height) / 2f / (U * r)).coerceIn(0.4f, 6f)
    }
    fun example(m: Mol) { val n = relax(addH(m), 300); commit(n); picked = emptyList(); focus(n) }
    fun xargs(dc: Int = 0): List<String> {
        val tot = charge + dc
        val known = mol.atoms.all { zOf(it.el) > 0 }
        val ne = mol.atoms.sumOf { zOf(it.el) } - tot
        return listOf("--gfn", "2") + (if (solvent.isNotEmpty()) listOf("--alpb", solvent) else emptyList()) +
            (if (tot != 0) listOf("--chrg", tot.toString()) else emptyList()) + (if (known && ne % 2 != 0) listOf("--uhf", "1") else emptyList())
    }
    fun toast(s: String) { android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show() }
    fun saveToast(name: String, text: String) { msg = saveTxt(ctx, name, text); toast(msg) }
    fun logCalc(title: String, args: List<String>, snap: Mol) {
        val e = "• " + title + " – xtb 6.7.1, GFN2-xTB, " + (if (solvent.isEmpty()) "phase gaz (pas de solvant ALPB)" else "solvant ALPB : " + solvent) +
            ", charge " + charge + ", " + cores + " cœur(s) – commande : xtb mol.xyz " + args.joinToString(" ") + "\nxyz d'entrée :\n" + toXyz(snap)
        calcLog = (calcLog + "\n\n" + e).takeLast(9000)
    }
    fun grab(out: String, k: String) = Regex("(?i)" + k + "\\s+(-?[0-9.]+)").findAll(out).lastOrNull()?.groupValues?.get(1) ?: "?"
    fun failMsg(r: XR) = (if (r.code == 159) "xtb bloqué par Android – " else "") + "xtb : exit=" + r.code + " " + r.out.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" | ")

    suspend fun optStep(label: String = "Optimisation"): XR? {
        val snap = mol
        busyMsg = t("Optimisation GFN2 en cours…", "GFN2 optimization running…"); progress = -1f; eta = ""
        val args = xargs() + listOf("--opt")
        val r = withContext(Dispatchers.IO) { xtbRun(ctx, args, toXyz(snap), emptyMap(), cores) }
        val nm = File(r.dir, "xtbopt.xyz").takeIf { it.exists() }?.readText()?.let { fromXyz(snap, it) }
        if (r.code != 0 || nm == null) { msg = failMsg(r); return null }
        shape = false
        commit(nm)
        val q = parseCharges(r.out, r.dir, snap.atoms.size)
        charges = q.first; qC = q.second
        logCalc(label, args, snap)
        return r
    }

    fun xtbOpt() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        scope.launch {
            try {
                val r = optStep() ?: return@launch
                msg = t("Optimisation xtb terminée (E = ", "xtb optimization done (E = ") + grab(r.out, "TOTAL ENERGY") + " Eh)"
            } finally { running = false }
        }
    }

    fun xtbFreq() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        scope.launch {
            try {
                val snap = mol
                busyMsg = t("Optimisation + fréquences en cours…", "Optimization + frequencies running…"); progress = -1f; eta = ""
                val args = xargs() + listOf("--ohess")
                val r = withContext(Dispatchers.IO) { xtbRun(ctx, args, toXyz(snap), emptyMap(), cores) }
                val nm = File(r.dir, "xtbopt.xyz").takeIf { it.exists() }?.readText()?.let { fromXyz(snap, it) }
                if (r.code != 0 || nm == null) { msg = failMsg(r); return@launch }
                shape = false
                commit(nm)
                val g98 = File(r.dir, "g98.out").takeIf { it.exists() }?.readText()
                var vb = if (g98 != null) parseG98(g98, snap.atoms.size) else emptyList()
                if (vb.isEmpty()) vb = File(r.dir, "vibspectrum").takeIf { it.exists() }?.readText()?.let { parseVibspectrum(it) } ?: emptyList()
                vibs = vb
                val q = parseCharges(r.out, r.dir, snap.atoms.size)
                charges = q.first; qC = q.second
                val imag = vb.count { it.freq < -5f }
                val e = grab(r.out, "TOTAL ENERGY"); val h = grab(r.out, "TOTAL ENTHALPY"); val g = grab(r.out, "TOTAL FREE ENERGY")
                freqSummary = "E = " + e + " Eh | H = " + h + " Eh | G = " + g + " Eh (298,15 K) | " + t("fréquences imaginaires (< −5 cm⁻¹) : ", "imaginary frequencies (< −5 cm⁻¹): ") + imag
                msg = "G = " + g + " Eh – " + t("fréq. imaginaires : ", "imaginary freq.: ") + imag
                if (vb.isEmpty()) msg += t(" (liste des fréquences non lue)", " (frequency list not read)")
                logCalc("Optimisation + fréquences", args, snap)
                calcLog += "\n" + freqSummary
            } finally { running = false }
        }
    }

    fun molShift(md: MolDen) = doubleArrayOf(
        mol.atoms.map { it.x.toDouble() }.average() - md.atoms.map { it[0] }.average(),
        -mol.atoms.map { it.y.toDouble() }.average() - md.atoms.map { it[1] }.average(),
        mol.atoms.map { it.z.toDouble() }.average() - md.atoms.map { it[2] }.average())

    fun selectOrb(o: Int) {
        val md = molden ?: return
        val homo = md.orbs.indexOfLast { it.occ > 0.5 }
        orbSel = o; orbMesh = null
        val sh = molShift(md)
        scope.launch {
            running = true; busyMsg = t("Surface de l'orbitale…", "Building the orbital surface…"); progress = -1f; eta = ""
            try { orbMesh = withContext(Dispatchers.Default) { buildMesh(md, homo + o, sh) } } finally { running = false }
        }
    }

    fun runOrb() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        scope.launch {
            try {
                optStep("Orbitales – optimisation") ?: return@launch
                busyMsg = t("Orbitales : point simple…", "Orbitals: single point…"); progress = -1f; eta = ""
                val snap = mol
                val args = xargs() + listOf("--molden")
                val r = withContext(Dispatchers.IO) { xtbRun(ctx, args, toXyz(snap), emptyMap(), cores) }
                val txt = File(r.dir, "molden.input").takeIf { it.exists() }?.readText()
                val md = if (txt != null) withContext(Dispatchers.Default) { parseMolden(txt) } else null
                if (r.code != 0 || md == null) {
                    msg = t("Orbitales : fichier molden illisible", "Orbitals: unreadable molden file") + " (exit " + r.code + ", " + (txt?.length ?: 0) + ")"
                    return@launch
                }
                molden = md
                logCalc("Orbitales – point simple (molden)", args, snap)
                msg = t("Orbitales prêtes : choisissez HOMO / LUMO", "Orbitals ready: choose HOMO / LUMO")
                selectOrb(0)
            } finally { running = false }
        }
    }

    fun runFukui() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        scope.launch {
            try {
                optStep("Fukui – optimisation (neutre)") ?: return@launch
                val n = mol.atoms.size
                val snap = mol
                val xyz = toXyz(snap)
                fun pick(q: Pair<List<Float>, List<Float>>) = if (chType == "CM5") q.second else q.first
                val q0 = pick(Pair(charges, qC))
                busyMsg = t("Fukui : point simple du cation (N−1)…", "Fukui: cation single point (N−1)…"); progress = -1f; eta = ""
                val a1 = xargs(1)
                val ra = withContext(Dispatchers.IO) { xtbRun(ctx, a1, xyz, emptyMap(), cores) }
                if (ra.code != 0) { msg = failMsg(ra); return@launch }
                val qa = pick(parseCharges(ra.out, ra.dir, n))
                busyMsg = t("Fukui : point simple de l'anion (N+1)…", "Fukui: anion single point (N+1)…")
                val a2 = xargs(-1)
                val rb = withContext(Dispatchers.IO) { xtbRun(ctx, a2, xyz, emptyMap(), cores) }
                if (rb.code != 0) { msg = failMsg(rb); return@launch }
                val qb = pick(parseCharges(rb.out, rb.dir, n))
                if (q0.size != n || qa.size != n || qb.size != n) {
                    msg = t("Fukui : charges ", "Fukui: charges ") + chType + t(" indisponibles", " unavailable"); return@launch
                }
                fM = List(n) { qa[it] - q0[it] }
                fP = List(n) { q0[it] - qb[it] }
                fuMode = "both"
                logCalc("Fukui – point simple cation (N−1), charges " + chType, a1, snap)
                logCalc("Fukui – point simple anion (N+1), charges " + chType, a2, snap)
                busyMsg = t("Fukui : orbitales frontières (lobes)…", "Fukui: frontier orbitals (lobes)…")
                val a3 = xargs() + listOf("--molden")
                val rc = withContext(Dispatchers.IO) { xtbRun(ctx, a3, xyz, emptyMap(), cores) }
                val txt = File(rc.dir, "molden.input").takeIf { it.exists() }?.readText()
                val md = if (rc.code == 0 && txt != null) withContext(Dispatchers.Default) { parseMolden(txt) } else null
                if (md != null) {
                    val homo = md.orbs.indexOfLast { it.occ > 0.5 }
                    val sh = molShift(md)
                    withContext(Dispatchers.Default) {
                        fuMeshM = buildMesh(md, homo, sh, true, 0xFF39B54A.toInt(), 0)
                        fuMeshP = buildMesh(md, homo + 1, sh, true, 0xFFFF9800.toInt(), 0)
                    }
                    logCalc("Fukui – lobes (HOMO² ≈ f−, LUMO² ≈ f+)", a3, snap)
                }
                msg = t("Fukui calculées (", "Fukui computed (") + chType + ")"
            } finally { running = false }
        }
    }

    fun runScan() {
        val i = picked.getOrNull(0); val j = picked.getOrNull(1)
        val d1 = scanEnd.replace(',', '.').toFloatOrNull()
        val nImg = scanN.toIntOrNull()
        if (running || i == null || j == null || d1 == null || nImg == null || nImg < 2) {
            msg = t("Scan : vérifiez la distance finale et le nombre d'images", "Scan: check final distance and number of images"); return
        }
        running = true
        scope.launch {
            try {
                if (scanPreOpt) { optStep("Scan – optimisation initiale") ?: return@launch }
                val pa = mol.atoms[i]; val pb = mol.atoms[j]
                val d0 = sqrt((pa.x - pb.x) * (pa.x - pb.x) + (pa.y - pb.y) * (pa.y - pb.y) + (pa.z - pb.z) * (pa.z - pb.z))
                val inp = "\$opt\n  maxcycle=500\n\$constrain\n  force constant=1.0\n  distance: " + (i + 1) + ", " + (j + 1) + ", auto\n\$scan\n  1: " +
                    String.format(java.util.Locale.US, "%.3f, %.3f, %d", d0, d1, nImg) + "\n\$end\n"
                val snap = mol
                val args = xargs() + (if (scanTight) listOf("--opt", "tight") else listOf("--opt")) + listOf("--input", "scan.inp")
                busyMsg = t("Scan en cours…", "Scan running…"); progress = 0f; eta = t("Démarrage…", "Starting…")
                val t0 = System.currentTimeMillis()
                val r = withContext(Dispatchers.IO) {
                    xtbRun(ctx, args, toXyz(snap), mapOf("scan.inp" to inp), cores) { dir ->
                        val f = File(dir, "xtbscan.log")
                        if (f.exists()) {
                            val k = f.readLines().count { it.contains("energy:") }
                            progress = (k.toFloat() / nImg).coerceIn(0f, 1f)
                            val el = (System.currentTimeMillis() - t0) / 1000f
                            if (k > 0) eta = t("Temps restant estimé : ", "Estimated time left: ") + fmtTime(el / k * (nImg - k)) + "  (" + k + "/" + nImg + ")"
                        }
                    }
                }
                val ps = parseScan(File(r.dir, "xtbscan.log").takeIf { it.exists() }?.readLines() ?: emptyList(), snap.atoms.size)
                if (ps.first.isEmpty()) { msg = failMsg(r); return@launch }
                scanFrames = ps.first; scanE = ps.second; scanT = 0f; scanPlay = false
                scanD = Pair(frameDist(ps.first.first(), i, j), frameDist(ps.first.last(), i, j))
                val conv = Regex("GEOMETRY OPTIMIZATION CONVERGED").findAll(r.out).count()
                val fail = Regex("FAILED TO CONVERGE").findAll(r.out).count()
                scanWarn = if (fail > 0) "⚠ " + fail + t(" optimisation(s) non convergée(s) sur ", " optimization(s) not converged out of ") + (conv + fail) + t(" : barrière peu fiable", ": barrier unreliable")
                    else "✓ " + conv + t(" optimisations convergées", " optimizations converged")
                logCalc("Scan 1D (atomes " + (i + 1) + "–" + (j + 1) + ", " + ps.first.size + " images)", args, snap)
                calcLog += "\nConvergence des optimisations : " + scanWarn + "\nFichier d'entrée scan.inp :\n" + inp
                msg = t("Scan terminé : ", "Scan done: ") + ps.first.size + t(" images", " images")
            } finally { running = false }
        }
    }

    fun runFree() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        scope.launch {
            try {
                busyMsg = t("Calcul libre en cours…", "Custom run in progress…"); progress = -1f; eta = ""
                val a0 = freeArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val files = if (freeInp.isNotBlank()) mapOf("xc.inp" to freeInp) else emptyMap()
                val args = if (freeInp.isNotBlank()) a0 + listOf("--input", "xc.inp") else a0
                val snap = mol
                val r = withContext(Dispatchers.IO) { xtbRun(ctx, args, toXyz(snap), files, cores) }
                freeOut = "commande : xtb mol.xyz " + args.joinToString(" ") + "\ncœurs : " + cores + "\n\n--- xyz d'entrée ---\n" + toXyz(snap) +
                    (if (freeInp.isNotBlank()) "\n--- fichier d'entrée ---\n" + freeInp else "") + "\n--- sortie xtb (exit " + r.code + ") ---\n" + r.out
                freeOpt = File(r.dir, "xtbopt.xyz").takeIf { it.exists() }?.readText()?.let { fromXyz(snap, it) }
                logCalc("Calcul libre", args, snap)
            } finally { running = false }
        }
    }

    fun finishBond(sa: Int, p: Offset) {
        val a = hIdx(sa) ?: return
        val base = baseMol()
        val h2 = hit(p)
        if (h2 != null) {
            val b = hIdx(h2) ?: return
            if (b != a && base.bonds.none { (it.a == a && it.b == b) || (it.a == b && it.b == a) })
                commit(fin(base.copy(bonds = base.bonds + Bond(a, b))))
        } else {
            val at = base.atoms[a]
            val w = unrot((p.x - (off.x + cs.width / 2f)) / (U * scale), (p.y - (off.y + cs.height / 2f)) / (U * scale), rot(at).third)
            var dx = w.first - at.x; var dy = w.second - at.y; var dz = w.third - at.z
            var d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 1e-3f) { dx = 1f; dy = 0f; dz = 0f; d = 1f }
            val len = bl(at.el, el)
            val n = Atom(el, at.x + dx / d * len, at.y + dy / d * len, at.z + dz / d * len)
            commit(fin(Mol(base.atoms + n, base.bonds + Bond(a, base.atoms.size))))
        }
    }
    fun tapAt(p: Offset) {
        val h = hit(p)
        run {
            val gcx = 44f; val gcy = cs.height - 40f
            val cyw = cos(yaw); val syw = sin(yaw); val cpt = cos(pitch); val spt = sin(pitch)
            val ax3 = listOf(Triple(1f, 0f, 0f), Triple(0f, -1f, 0f), Triple(0f, 0f, 1f))
            val half = (PI / 2).toFloat()
            val tg = listOf(Pair(half, -half), Pair(0f, 0f), Pair(0f, half))
            for (ai in 0 until 3) {
                val e = ax3[ai]
                val x1 = e.first * cyw + e.third * syw; val z1 = -e.first * syw + e.third * cyw
                val y1 = e.second * cpt - z1 * spt
                if ((Offset(gcx + x1 * 32f, gcy + y1 * 32f) - p).getDistance() < 26f) { yaw = tg[ai].first; pitch = tg[ai].second; return }
            }
        }
        if (react == "scan" && scanFrames.isEmpty()) { if (h != null) picked = (picked + h).takeLast(2); return }
        when (mode) {
            Mode.DRAW -> if (h != null) {
                val a = hIdx(h) ?: return
                val base = baseMol()
                if (base.atoms[a].el != el) commit(fin(base.copy(atoms = base.atoms.mapIndexed { i, x -> if (i == a) x.copy(el = el) else x })))
            } else {
                val bi = bondHit(p)
                if (bi != null) {
                    val b = mol.bonds[bi]
                    if (autoH && (mol.atoms[b.a].el == "H" || mol.atoms[b.b].el == "H")) return
                    val nm = mol.copy(bonds = mol.bonds.mapIndexed { i, x -> if (i == bi) x.copy(o = x.o % 3 + 1) else x })
                    commit(relax(if (autoH) fin(removeH(nm)) else nm, 200))
                } else {
                    val w = unrot((p.x - (off.x + cs.width / 2f)) / (U * scale), (p.y - (off.y + cs.height / 2f)) / (U * scale), 0f)
                    commit(fin(Mol(baseMol().let { it.atoms + Atom(el, w.first, w.second, w.third) }, baseMol().bonds)))
                }
            }
            Mode.DELETE -> if (h != null) {
                val r = delAt(mol, h)
                commit(if (autoH) fin(removeH(r)) else r)
            } else bondHit(p)?.let { bi ->
                val r = mol.copy(bonds = mol.bonds.filterIndexed { i, _ -> i != bi })
                commit(if (autoH) fin(removeH(r)) else r)
            }
            Mode.MEASURE -> picked = if (h != null) (picked + h).takeLast(2) else emptyList()
            Mode.VIEW -> {}
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111418)).systemBarsPadding()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Menu(t("Fichier", "File"), listOf(
                t("Sauvegarder", "Save") to { msg = t("Sauvegarde : prochaine étape", "Saving: next step") },
                t("Supprimer sauvegardes", "Delete saves") to { msg = t("Prochaine étape", "Next step") },
                t("Exemple : benzène", "Example: benzene") to {
                    val k = (PI / 3).toFloat()
                    example(Mol((0..5).map { Atom("C", 1.4f * cos(it * k), 1.4f * sin(it * k)) }, (0..5).map { Bond(it, (it + 1) % 6, if (it % 2 == 0) 2 else 1) }))
                },
                t("Exemple : eau", "Example: water") to { example(Mol(listOf(Atom("O", 0f, 0f, 0f)))) },
                t("Exemple : CAT", "Example: CAT") to { val m = perceive(parseXyzAtoms(catXyz)); commit(m); picked = emptyList(); focus(m) },
                t("Exemple : éthanol", "Example: ethanol") to {
                    example(Mol(listOf(Atom("C", 0f, 0f), Atom("C", 1.52f, 0f), Atom("O", 2.2f, 1.2f)), listOf(Bond(0, 1), Bond(1, 2))))
                }
            ))
            Menu("Tool", listOf(
                t("Ajouter les H", "Add H") to { commit(addH(mol)) },
                t("Retirer les H", "Remove H") to { commit(removeH(mol)) },
                t("Optimiser (xtb GFN2)", "Optimize (xtb GFN2)") to { xtbOpt() },
                t("Optimiser + fréquences (xtb)", "Optimize + frequencies (xtb)") to { xtbFreq() },
                t("Liste des fréquences", "Frequency list") to { if (vibs.isEmpty()) msg = t("Pas encore de fréquences : lance « Optimiser + fréquences »", "No frequencies yet: run « Optimize + frequencies »") else dialog = "freq" },
                t("Charges (Mulliken) on/off", "Charges (Mulliken) on/off") to { showCh = !showCh },
                t("Mode libre (xtb)", "Free mode (xtb)") to { dialog = "free" },
                "Focus" to { focus() },
            ))
            Menu(t("Paramètres", "Settings"), listOf(
                "Français" to { fr = true }, "English" to { fr = false },
                t("Solvant : aucun (gaz)", "Solvent: none (gas)") to { solvent = ""; msg = t("Phase gaz", "Gas phase") },
                t("Solvant ALPB : eau", "ALPB solvent: water") to { solvent = "water"; msg = "ALPB : water" },
                "ALPB : ethanol" to { solvent = "ethanol"; msg = "ALPB : ethanol" },
                "ALPB : dmso" to { solvent = "dmso"; msg = "ALPB : dmso" },
                "ALPB : chloroform" to { solvent = "chloroform"; msg = "ALPB : chloroform" },
                "ALPB : acetonitrile" to { solvent = "acetonitrile"; msg = "ALPB : acetonitrile" },
                "ALPB : toluene" to { solvent = "toluene"; msg = "ALPB : toluene" },
                t("Charge totale : −1", "Total charge: −1") to { charge = -1; msg = t("Charge totale −1", "Total charge −1") },
                t("Charge totale : 0", "Total charge: 0") to { charge = 0; msg = t("Charge totale 0", "Total charge 0") },
                t("Charge totale : +1", "Total charge: +1") to { charge = 1; msg = t("Charge totale +1", "Total charge +1") },
                t("Charge totale : +2", "Total charge: +2") to { charge = 2; msg = t("Charge totale +2", "Total charge +2") },
                t("Cœurs xtb : 1", "xtb cores: 1") to { cores = 1 },
                t("Cœurs xtb : 2 (expérimental)", "xtb cores: 2 (experimental)") to { cores = 2 },
                t("Cœurs xtb : 4 (expérimental)", "xtb cores: 4 (experimental)") to { cores = 4 }
            ))
            TextButton(onClick = { dialog = "info" }) { Text("Info") }
            Menu(t("Réactivité", "Reactivity"), listOf(
                t("Charges partielles", "Partial charges") to { react = "charges" },
                t("Orbitales frontières", "Frontier orbitals") to { react = "orb" },
                t("Fonctions de Fukui", "Fukui functions") to { react = "fukui" },
                "Scan" to { react = "scan"; picked = emptyList() }
            ))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                Mode.DRAW to t("Dessin", "Draw"), Mode.DELETE to t("Suppr.", "Erase"),
                Mode.VIEW to t("Vue / Déplacer", "View / Move"), Mode.MEASURE to t("Mesure", "Measure")
            ).forEach { (m, n) ->
                FilterChip(selected = mode == m, onClick = { mode = m; picked = emptyList() },
                    label = { Text(n) }, modifier = Modifier.padding(horizontal = 3.dp))
            }
            FilterChip(selected = autoH, onClick = { autoH = !autoH },
                label = { Text(t("H auto", "Auto H")) }, modifier = Modifier.padding(horizontal = 3.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp)) {
                listOf("C", "H", "O", "N", "S", "B").forEach { e ->
                    FilledTonalButton(onClick = { el = e }, modifier = Modifier.width(64.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = if (el == e) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3A6FD8)) else ButtonDefaults.filledTonalButtonColors()
                    ) { Text(e) }
                }
                FilledTonalButton(onClick = { dialog = "hal" }, modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("X") }
                FilledTonalButton(onClick = { dialog = "tab" }, modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("TAB") }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().onSizeChanged { cs = it }.pointerInput(mode) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val sa = hit(down.position)
                    var moved = false; var multi = false; var pushed = false
                    var cur = down.position
                    do {
                        val ev = awaitPointerEvent()
                        val pr = ev.changes.filter { it.pressed }
                        if (pr.size >= 2) {
                            multi = true; moved = true; dragFrom = null
                            scale = (scale * ev.calculateZoom()).coerceIn(0.4f, 6f); off += ev.calculatePan()
                        } else if (pr.size == 1 && !multi) {
                            val c = pr[0]; cur = c.position
                            if (!moved && (cur - down.position).getDistance() > 16f) moved = true
                            if (moved) {
                                val d = c.positionChange()
                                if (sa != null && mode == Mode.VIEW) {
                                    if (!pushed) { undo.add(mol); redo.clear(); pushed = true }
                                    val w = unrot(d.x / (U * scale), d.y / (U * scale), 0f)
                                    val l = mol.atoms.toMutableList()
                                    l[sa] = l[sa].copy(x = l[sa].x + w.first - pv.first, y = l[sa].y + w.second - pv.second, z = l[sa].z + w.third - pv.third)
                                    mol = mol.copy(atoms = l)
                                } else if (sa != null && mode == Mode.DRAW) { dragFrom = sa; dragPos = cur }
                                else { yaw -= d.x * 0.01f; pitch -= d.y * 0.01f }
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    dragFrom = null
                    if (!moved) tapAt(down.position)
                    else if (!multi && mode == Mode.DRAW && sa != null) finishBond(sa, cur)
                }
            }) {
                val vm = vibs.getOrNull(vibSel)
                val sf = if (react == "scan" && scanFrames.isNotEmpty()) {
                    val i0 = scanT.toInt().coerceIn(0, scanFrames.size - 1); val i1 = minOf(i0 + 1, scanFrames.size - 1); val fr = scanT - i0
                    val a0 = scanFrames[i0]; val a1 = scanFrames[i1]
                    FloatArray(a0.size) { a0[it] + (a1[it] - a0[it]) * fr }
                } else null
                val vat = if (sf != null && sf.size == 3 * mol.atoms.size) {
                    mol.atoms.mapIndexed { i, a -> a.copy(x = sf[3 * i], y = sf[3 * i + 1], z = sf[3 * i + 2]) }
                } else if (vm != null && vm.d.size == 3 * mol.atoms.size) {
                    val sv = 0.45f * sin(vphase)
                    mol.atoms.mapIndexed { i, a -> a.copy(x = a.x + sv * vm.d[3 * i], y = a.y + sv * vm.d[3 * i + 1], z = a.z + sv * vm.d[3 * i + 2]) }
                } else mol.atoms
                val pr = vat.map { rot(it) }
                val bondsV = if (sf != null && sf.size == 3 * mol.atoms.size) dynBonds(vat, mol.bonds) else mol.bonds
                val qShow = if (chType == "CM5" && qC.size == mol.atoms.size) qC else charges
                fun scr(i: Int) = Offset(pr[i].first * U * scale + (off.x + cs.width / 2f), pr[i].second * U * scale + (off.y + cs.height / 2f))
                fun cyl(c: Color, a: Offset, b: Offset, w: Float) {
                    drawLine(lerp(c, Color.Black, 0.45f), a, b, strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(c, a, b, strokeWidth = w * 0.65f, cap = StrokeCap.Round)
                    drawLine(lerp(c, Color.White, 0.6f), a, b, strokeWidth = w * 0.25f, cap = StrokeCap.Round)
                }
                val items = mutableListOf<Pair<Float, () -> Unit>>()
                bondsV.forEach { bd ->
                    if (bd.a < pr.size && bd.b < pr.size) {
                        val sa = scr(bd.a); val sb = scr(bd.b); val mid = (sa + sb) / 2f
                        val dir = sb - sa; val len = dir.getDistance() + 1e-3f
                        val nrm = Offset(-dir.y / len, dir.x / len)
                        val w = (if (bd.o == 1) 0.12f else 0.085f) * U * scale
                        val sep = 0.13f * U * scale
                        val offs = when (bd.o) { 1 -> listOf(0f); 2 -> listOf(-0.5f, 0.5f); else -> listOf(-1f, 0f, 1f) }
                        val ca = Color(cpk[mol.atoms[bd.a].el] ?: 0xFFFF69B4)
                        val cb = Color(cpk[mol.atoms[bd.b].el] ?: 0xFFFF69B4)
                        items.add((pr[bd.a].third - 0.02f) to { offs.forEach { f -> val o = nrm * (f * sep); cyl(ca, sa + o, mid + o, w) } })
                        items.add((pr[bd.b].third - 0.02f) to { offs.forEach { f -> val o = nrm * (f * sep); cyl(cb, mid + o, sb + o, w) } })
                    }
                }
                mol.atoms.forEachIndexed { i, a ->
                    val s = scr(i)
                    val base = Color(cpk[a.el] ?: 0xFFFF69B4)
                    val r = ((if (a.el == "H") 0.2f else 0.3f) * U * scale * (1f + pr[i].third * 0.06f)).coerceAtLeast(6f)
                    items.add(pr[i].third to {
                        drawCircle(
                            Brush.radialGradient(listOf(lerp(base, Color.White, 0.4f), base, lerp(base, Color.Black, 0.4f)),
                                center = s - Offset(r * 0.3f, r * 0.3f), radius = r * 1.6f),
                            r, s)
                        if (i == dragFrom || picked.contains(i)) drawCircle(Color(0xFF00B0FF), r + 5f, s, style = Stroke(5f))
                        if (a.el != "C" && a.el != "H" && r > 12f) {
                            val paint = Paint().apply { textSize = r; textAlign = Paint.Align.CENTER; color = android.graphics.Color.BLACK; isFakeBoldText = true }
                            drawContext.canvas.nativeCanvas.drawText(a.el, s.x, s.y + r * 0.35f, paint)
                        }
                        if (showCh && qShow.size == mol.atoms.size) {
                            val p2 = Paint().apply { textSize = 26f; textAlign = Paint.Align.CENTER; color = if (qShow[i] < 0) 0xFFFF8A80.toInt() else 0xFF80D8FF.toInt(); isFakeBoldText = true }
                            drawContext.canvas.nativeCanvas.drawText(String.format("%+.2f", qShow[i]), s.x, s.y - r - 6f, p2)
                        }
                        if (fuVals && fuMode.isNotEmpty() && fP.size == mol.atoms.size && fM.size == mol.atoms.size) {
                            val p3 = Paint().apply { textSize = 24f; textAlign = Paint.Align.CENTER; color = 0xFFFFD180.toInt(); isFakeBoldText = true }
                            var yy = s.y + r + 22f
                            if (fuMode == "f-" || fuMode == "both") { drawContext.canvas.nativeCanvas.drawText(String.format("f− %.2f", fM[i]), s.x, yy, p3); yy += 26f }
                            if (fuMode == "f+" || fuMode == "both") drawContext.canvas.nativeCanvas.drawText(String.format("f+ %.2f", fP[i]), s.x, yy, p3)
                        }
                    })
                }
                items.sortedBy { it.first }.forEach { it.second() }
                val meshes = ArrayList<Mesh>()
                val om = orbMesh
                if (om != null && orbSel != 99) meshes.add(om)
                if (fuLobes && fuMode.isNotEmpty()) {
                    if (fuMode == "f-" || fuMode == "both") fuMeshM?.let { meshes.add(it) }
                    if (fuMode == "f+" || fuMode == "both") fuMeshP?.let { meshes.add(it) }
                }
                if (meshes.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 29) {
                    val nc = drawContext.canvas.nativeCanvas
                    val sc = nc.saveLayerAlpha(0f, 0f, size.width, size.height, 135)
                    val mox = off.x + cs.width / 2f; val moy = off.y + cs.height / 2f
                    for (m in meshes) renderMesh(m, nc, vpaint, yaw, pitch, pv, mox, moy, U * scale)
                    nc.restoreToCount(sc)
                }
                run {
                    val gcx = 44f; val gcy = size.height - 40f
                    val gy = cos(yaw); val gys = sin(yaw); val gp = cos(pitch); val gps = sin(pitch)
                    val axs = listOf(Triple(1f, 0f, 0f), Triple(0f, -1f, 0f), Triple(0f, 0f, 1f))
                    val acol = listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5))
                    val albl = listOf("X", "Y", "Z")
                    val aproj = axs.map { e ->
                        val x1 = e.first * gy + e.third * gys; val z1 = -e.first * gys + e.third * gy
                        Triple(x1, e.second * gp - z1 * gps, e.second * gps + z1 * gp)
                    }
                    for (ai in aproj.indices.sortedBy { aproj[it].third }) {
                        val q3 = aproj[ai]; val ex = gcx + q3.first * 32f; val ey = gcy + q3.second * 32f
                        drawLine(acol[ai], Offset(gcx, gcy), Offset(ex, ey), 6f, StrokeCap.Round)
                        drawCircle(acol[ai], 12f, Offset(ex, ey))
                        val pa = Paint().apply { textSize = 18f; textAlign = Paint.Align.CENTER; color = android.graphics.Color.WHITE; isFakeBoldText = true }
                        drawContext.canvas.nativeCanvas.drawText(albl[ai], ex, ey + 6f, pa)
                    }
                }
                val df = dragFrom
                if (df != null && df < pr.size) drawLine(Color(0xFF00B0FF), scr(df), dragPos, strokeWidth = 6f, cap = StrokeCap.Round)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = { if (undo.isNotEmpty()) { redo.add(mol); mol = undo.removeLast() } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↶") }
                FilledTonalButton(onClick = { if (redo.isNotEmpty()) { undo.add(mol); mol = redo.removeLast() } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↷") }
                FilledTonalButton(onClick = { focus() },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("Focus") }
                Text("Shaping", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                Switch(checked = shape, onCheckedChange = { shape = it; if (it) mol = relax(mol, 300) }, modifier = Modifier.scale(0.7f),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF2ECC71),
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF777777), uncheckedBorderColor = Color(0xFF777777)))
            }
        }
        val dist = if (mode == Mode.MEASURE && picked.size == 2 && picked.all { it < mol.atoms.size }) {
            val a = mol.atoms[picked[0]]; val b = mol.atoms[picked[1]]
            val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            t("Distance : ", "Distance: ") + String.format("%.2f Å", d)
        } else ""
        if (react.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Color(0xFF1B2229)).padding(8.dp).heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (react) { "charges" -> t("Charges partielles", "Partial charges"); "orb" -> t("Orbitales frontières", "Frontier orbitals"); "fukui" -> t("Fonctions de Fukui", "Fukui functions"); else -> "Scan" },
                    color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { react = ""; picked = emptyList(); scanPlay = false }) { Text(t("Fermer", "Close")) }
            }
            when (react) {
                "charges" -> {
                    FlowRow {
                        FilterChip(selected = chType == "Mulliken", onClick = { chType = "Mulliken" }, label = { Text("Mulliken") }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = chType == "CM5", onClick = { chType = "CM5" }, label = { Text(t("CM5 (type Hirshfeld)", "CM5 (Hirshfeld-type)")) }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = showCh, onClick = { showCh = !showCh }, label = { Text(t("Afficher", "Show")) }, modifier = Modifier.padding(2.dp))
                    }
                    Button(onClick = { xtbOpt() }) { Text(t("Calculer (optimisation + charges)", "Compute (optimization + charges)")) }
                    Text(t("xtb fournit les charges de Mulliken et CM5. Löwdin et Hirshfeld ne sont pas disponibles : CM5, dérivée de Hirshfeld, les remplace.", "xtb provides Mulliken and CM5 charges. Löwdin and Hirshfeld are not available: CM5, derived from Hirshfeld, replaces them."), fontSize = 11.sp, color = Color.Gray)
                }
                "orb" -> {
                    Button(onClick = { runOrb() }) { Text(t("Calculer les orbitales (optimisation + point simple)", "Compute orbitals (optimization + single point)")) }
                    val md = molden
                    if (md != null) {
                        val homo = md.orbs.indexOfLast { it.occ > 0.5 }
                        FlowRow {
                            listOf(-2 to "HOMO−2", -1 to "HOMO−1", 0 to "HOMO", 1 to "LUMO", 2 to "LUMO+1", 3 to "LUMO+2").forEach { (o, nm) ->
                                val kk = homo + o
                                if (kk in md.orbs.indices) FilterChip(selected = orbSel == o, onClick = { selectOrb(o) },
                                    label = { Text(nm + "  " + String.format("%.2f eV", md.orbs[kk].ene * 27.2114)) }, modifier = Modifier.padding(2.dp))
                            }
                            FilterChip(selected = orbSel == 99, onClick = { orbSel = 99; orbMesh = null }, label = { Text(t("Masquer", "Hide")) }, modifier = Modifier.padding(2.dp))
                        }
                        Text(t("Bleu : phase +, rouge : phase −", "Blue: phase +, red: phase −"), fontSize = 11.sp, color = Color.Gray)
                    }
                }
                "fukui" -> {
                    FlowRow {
                        listOf("f-" to "f−", "f+" to "f+", "both" to t("les deux", "both"), "" to t("aucun", "none")).forEach { (k2, nm) ->
                            FilterChip(selected = fuMode == k2, onClick = { fuMode = k2 }, label = { Text(nm) }, modifier = Modifier.padding(2.dp))
                        }
                        FilterChip(selected = chType == "Mulliken", onClick = { chType = "Mulliken" }, label = { Text("Mulliken") }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = chType == "CM5", onClick = { chType = "CM5" }, label = { Text("CM5") }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = fuLobes, onClick = { fuLobes = !fuLobes }, label = { Text(t("Lobes 3D", "3D lobes")) }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = fuVals, onClick = { fuVals = !fuVals }, label = { Text(t("Valeurs", "Values")) }, modifier = Modifier.padding(2.dp))
                        FilterChip(selected = showCh, onClick = { showCh = !showCh }, label = { Text(t("Charges", "Charges")) }, modifier = Modifier.padding(2.dp))
                    }
                    Button(onClick = { runFukui() }) { Text(t("Calculer (optimisation + 2 points simples)", "Compute (optimization + 2 single points)")) }
                    Text(t("Valeurs par atome : f− = q(N−1) − q(N) ; f+ = q(N) − q(N+1). Lobes 3D (vert f−, orange f+) : approximation par orbitales frontières, f− ≈ HOMO², f+ ≈ LUMO².", "Per-atom values: f− = q(N−1) − q(N); f+ = q(N) − q(N+1). 3D lobes (green f−, orange f+): frontier-orbital approximation, f− ≈ HOMO², f+ ≈ LUMO²."), fontSize = 11.sp, color = Color.Gray)
                }
                else -> {
                    if (scanFrames.isEmpty()) {
                        if (picked.size < 2) {
                            Text(t("Sélectionnez deux atomes qui réagiront (touchez-les dans la molécule)", "Select the two atoms that will react (tap them in the molecule)"), color = Color.White, fontSize = 13.sp)
                        } else {
                            val a = mol.atoms[picked[0]]; val b = mol.atoms[picked[1]]
                            val d0 = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
                            Text(t("Distance actuelle : ", "Current distance: ") + String.format("%.3f Å", d0), color = Color.White, fontSize = 13.sp)
                            Row {
                                OutlinedTextField(value = scanEnd, onValueChange = { scanEnd = it }, label = { Text(t("Distance finale (Å)", "Final distance (Å)")) }, singleLine = true, modifier = Modifier.weight(1f).padding(end = 4.dp))
                                OutlinedTextField(value = scanN, onValueChange = { scanN = it }, label = { Text("Images") }, singleLine = true, modifier = Modifier.weight(0.6f))
                            }
                            FlowRow { listOf(1, 2, 4).forEach { c -> FilterChip(selected = cores == c, onClick = { cores = c }, label = { Text(t("Cœurs : ", "Cores: ") + c) }, modifier = Modifier.padding(2.dp)) } }
                            FilterChip(selected = scanPreOpt, onClick = { scanPreOpt = !scanPreOpt }, label = { Text(t("Optimiser d'abord", "Optimize first")) }, modifier = Modifier.padding(2.dp))
                            FilterChip(selected = scanTight, onClick = { scanTight = !scanTight }, label = { Text(t("Précis (tight)", "Tight convergence")) }, modifier = Modifier.padding(2.dp))
                            Button(onClick = { runScan() }) { Text(t("Lancer le scan", "Run scan")) }
                        }
                        if (picked.isNotEmpty()) TextButton(onClick = { picked = emptyList() }) { Text(t("Annuler la sélection", "Clear selection")) }
                    } else {
                        val nF = scanFrames.size
                        ScanPlot(scanE, scanD.first, scanD.second, scanT, Modifier.fillMaxWidth().height(150.dp))
                        val i0 = scanT.toInt().coerceIn(0, nF - 1); val i1 = minOf(i0 + 1, nF - 1); val fr = scanT - i0
                        val rel = ((scanE[i0] + (scanE[i1] - scanE[i0]) * fr) - scanE[0]) * 2625.499638
                        val dNow = if (picked.size >= 2) frameDist(scanFrames[i0], picked[0], picked[1]) * (1f - fr) + frameDist(scanFrames[i1], picked[0], picked[1]) * fr else 0f
                        Text(String.format("%.1f/%d – d = %.3f Å – ΔE = %.1f kJ/mol", scanT + 1f, nF, dNow, rel), color = Color.White, fontSize = 13.sp)
                        val emax = scanE.maxOrNull() ?: scanE[0]
                        Text(String.format("Max : %.1f kJ/mol", (emax - scanE[0]) * 2625.499638), color = Color.Gray, fontSize = 12.sp)
                        if (scanWarn.isNotEmpty()) Text(scanWarn, color = if (scanWarn.startsWith("⚠")) Color(0xFFFF8A80) else Color(0xFF81C784), fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { scanPlay = !scanPlay }) { Text(if (scanPlay) "⏸" else "▶", fontSize = 22.sp) }
                            Slider(value = scanT.coerceIn(0f, (nF - 1).toFloat()), onValueChange = { scanT = it; scanPlay = false },
                                valueRange = 0f..(nF - 1).toFloat().coerceAtLeast(1f), modifier = Modifier.weight(1f))
                        }
                        TextButton(onClick = { scanFrames = emptyList(); scanPlay = false; picked = emptyList() }) { Text(t("Nouveau scan", "New scan")) }
                    }
                }
            }
        }
        if (vibSel >= 0 && vibSel < vibs.size) Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("Mode %d : %.1f cm⁻¹ (%.1f km/mol)", vibSel + 1, vibs[vibSel].freq, vibs[vibSel].ir), color = Color.White, fontSize = 13.sp)
            TextButton(onClick = { vibSel = -1 }) { Text(t("Arrêter", "Stop")) }
            TextButton(onClick = { dialog = "freq" }) { Text(t("Liste", "List")) }
        }
        Text(
            if (dist.isNotEmpty()) dist else if (msg.isNotEmpty()) msg else
                t("Élément : $el – touchez pour poser, glissez depuis un atome pour lier", "Element: $el – tap to place, drag from an atom to bond"),
            color = Color.LightGray, modifier = Modifier.padding(10.dp)
        )
    }

    if (running) {
        AlertDialog(onDismissRequest = {}, confirmButton = { TextButton(onClick = { curProc?.destroy() }) { Text(t("Annuler", "Cancel")) } },
            title = { Text(t("Calcul en cours", "Calculation running")) },
            text = {
                Column {
                    Text(busyMsg)
                    Spacer(Modifier.height(10.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(String.format("%.0f %%", progress * 100), fontSize = 12.sp)
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (eta.isNotEmpty()) Text(eta, fontSize = 12.sp)
                }
            })
    }
    if (dialog == "free") {
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text(t("Fermer", "Close")) } },
            title = { Text(t("Mode libre xtb", "Free xtb mode")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = freeArgs, onValueChange = { freeArgs = it }, label = { Text(t("Mots-clés xtb", "xtb keywords")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = freeInp, onValueChange = { freeInp = it }, label = { Text(t("Fichier d'entrée xcontrol (optionnel)", "xcontrol input file (optional)")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                    Row {
                        Button(onClick = { runFree() }) { Text(t("Lancer", "Run")) }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { saveToast("lilian_xtb_" + System.currentTimeMillis() / 1000 + ".txt", freeOut) }, enabled = freeOut.isNotEmpty()) { Text(t("Télécharger .txt", "Download .txt")) }
                    }
                    freeOpt?.let { o -> TextButton(onClick = { commit(o); dialog = "" }) { Text(t("Appliquer la géométrie optimisée", "Apply optimized geometry")) } }
                    if (freeOut.isNotEmpty()) Text(freeOut.takeLast(6000), fontSize = 9.sp)
                }
            })
    }
    if (dialog == "freq") {
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("OK") } },
            title = { Text(t("Fréquences", "Frequencies")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(freqSummary, fontSize = 12.sp)
                    Text(t("Touchez une ligne pour voir la vibration.", "Tap a line to see the vibration."), fontSize = 11.sp, color = Color.Gray)
                    vibs.forEachIndexed { k, v ->
                        Text(String.format("%d   %.1f cm⁻¹   %.1f km/mol", k + 1, v.freq, v.ir), fontSize = 14.sp,
                            color = if (v.freq < -5f) Color(0xFFFF8A80) else Color.White,
                            modifier = Modifier.fillMaxWidth().clickable { vibSel = k; dialog = "" }.padding(vertical = 6.dp))
                    }
                }
            })
    }
    if (dialog == "tab") PeriodicTable(el, { el = it; dialog = "" }, { dialog = "" })
    if (dialog == "hal") {
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = {},
            title = { Text(t("Halogènes", "Halogens")) },
            text = {
                FlowRow {
                    listOf("F", "Cl", "Br", "I").forEach { e -> OutlinedButton(onClick = { el = e; dialog = "" }, contentPadding = PaddingValues(4.dp), modifier = Modifier.padding(2.dp)) { Text(e) } }
                }
            })
    }
    if (dialog == "info") {
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("OK") } },
            title = { Text("Lilian") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Lilian Rouillé", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(t("Développement", "Development"), fontSize = 16.sp)
                    Text(t("Application entièrement programmée par Claude (Anthropic), sur la demande de Lilian Rouillé.", "App entirely programmed by Claude (Anthropic), at the request of Lilian Rouillé."))
                    Spacer(Modifier.height(10.dp))
                    Text(t("Méthodes : xtb 6.7.1 (GFN2-xTB), calculs en phase gaz sauf mention contraire (le solvant ALPB est indiqué quand il est utilisé). Open Babel : à venir.", "Methods: xtb 6.7.1 (GFN2-xTB), gas phase unless stated (ALPB solvent shown when used). Open Babel: coming."))
                    Spacer(Modifier.height(8.dp))
                    Text(t("Références : GFN2-xTB – C. Bannwarth, S. Ehlert, S. Grimme, J. Chem. Theory Comput. 2019, 15, 1652. Programme xtb – C. Bannwarth et al., WIREs Comput. Mol. Sci. 2021, 11, e1493. ALPB – S. Ehlert et al., J. Chem. Theory Comput. 2021, 17, 4250. CM5 – A. V. Marenich et al., J. Chem. Theory Comput. 2012, 8, 527.", "References: GFN2-xTB – C. Bannwarth, S. Ehlert, S. Grimme, J. Chem. Theory Comput. 2019, 15, 1652. xtb program – C. Bannwarth et al., WIREs Comput. Mol. Sci. 2021, 11, e1493. ALPB – S. Ehlert et al., J. Chem. Theory Comput. 2021, 17, 4250. CM5 – A. V. Marenich et al., J. Chem. Theory Comput. 2012, 8, 527."), fontSize = 10.sp)
                    if (calcLog.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(calcLog, fontSize = 10.sp) }
                    Button(onClick = { saveToast("lilian_rapport_" + System.currentTimeMillis() / 1000 + ".txt", "Lilian – logiciel de Lilian Rouillé\n\n" + calcLog + "\n\nxyz actuel :\n" + toXyz(mol)) }) { Text(t("Télécharger le rapport (.txt)", "Download report (.txt)")) }
                    Spacer(Modifier.height(8.dp))
                    Text(t("xyz actuel", "Current xyz"))
                    Text(toXyz(mol), fontSize = 10.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        xtbOut = t("Calcul en cours…", "Running…")
                        scope.launch {
                            xtbOut = withContext(Dispatchers.IO) {
                                runXtb(ctx, listOf("--gfn", "2", "--opt"), "3\n\nO 0.0 0.0 0.0\nH 0.96 0.0 0.0\nH -0.24 0.93 0.0\n")
                            }
                        }
                    }) { Text(t("Tester xtb (eau)", "Test xtb (water)")) }
                    Button(onClick = {
                        xtbOut = t("Diagnostic en cours…", "Running diagnostic…")
                        scope.launch { xtbOut = withContext(Dispatchers.IO) { diag(ctx) } }
                    }) { Text(t("Diagnostic Android", "Android diagnostic")) }
                    if (xtbOut.isNotEmpty()) Text(xtbOut, fontSize = 10.sp)
                }
            })
    }
}
