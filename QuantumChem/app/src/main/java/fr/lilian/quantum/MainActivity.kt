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
    m.atoms.joinToString("\n") { String.format(java.util.Locale.US, "%s %.6f %.6f %.6f", it.el, it.x, it.y, it.z) } + "\n"

fun fromXyz(m: Mol, s: String): Mol? {
    val l = s.lines().drop(2).filter { it.isNotBlank() }
    if (l.size != m.atoms.size) return null
    return try {
        m.copy(atoms = m.atoms.mapIndexed { i, a ->
            val q = l[i].trim().split(Regex("\\s+"))
            a.copy(x = q[1].toFloat(), y = q[2].toFloat(), z = q[3].toFloat())
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
    pb.environment()["OMP_NUM_THREADS"] = Runtime.getRuntime().availableProcessors().toString()
    pb.environment()["HOME"] = dir.absolutePath
    return try {
        val pr = pb.start()
        val out = pr.inputStream.bufferedReader().readText()
        val c = pr.waitFor()
        Triple(c, out, File(dir, "xtbopt.xyz").takeIf { it.exists() }?.readText())
    } catch (e: Exception) { Triple(-2, "Erreur: " + e.message, null) }
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
    pb.environment()["OMP_NUM_THREADS"] = Runtime.getRuntime().availableProcessors().toString()
    pb.environment()["HOME"] = dir.absolutePath
    return try {
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val c = p.waitFor()
        "exit=" + c + "\n" + out.lines().takeLast(25).joinToString("\n")
    } catch (e: Exception) { "Erreur: " + e.message }
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

    LaunchedEffect(shape) { while (shape) { mol = relax(mol, 6); delay(33) } }

    fun t(f: String, e: String) = if (fr) f else e
    fun commit(m: Mol) { undo.add(mol); redo.clear(); mol = m }
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
    fun xtbOpt() {
        if (running || mol.atoms.isEmpty()) return
        running = true
        msg = t("xtb : optimisation GFN2 en cours…", "xtb: GFN2 optimization running…")
        val snap = mol
        scope.launch {
            val r = withContext(Dispatchers.IO) { xtbJob(ctx, listOf("--gfn", "2", "--opt"), toXyz(snap)) }
            running = false
            val nm = r.third?.let { fromXyz(snap, it) }
            val e = Regex("TOTAL ENERGY\\s+(-?[0-9.]+)").find(r.second)?.groupValues?.get(1) ?: "?"
            if (r.first == 0 && nm != null) {
                shape = false
                commit(nm)
                msg = t("Optimisation xtb terminée (E = ", "xtb optimization done (E = ") + e + " Eh)"
                calcLog = "Optimisation de géométrie – xtb 6.7.1, GFN2-xTB, phase gaz (pas de solvant ALPB), charge 0 – commande : xtb mol.xyz --gfn 2 --opt – énergie : " + e + " Eh\nxyz d'entrée :\n" + toXyz(snap)
            } else msg = "xtb : exit=" + r.first + " " + r.second.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" | ")
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
                t("Exemple : éthanol", "Example: ethanol") to {
                    example(Mol(listOf(Atom("C", 0f, 0f), Atom("C", 1.52f, 0f), Atom("O", 2.2f, 1.2f)), listOf(Bond(0, 1), Bond(1, 2))))
                }
            ))
            Menu("Tool", listOf(
                t("Ajouter les H", "Add H") to { commit(addH(mol)) },
                t("Retirer les H", "Remove H") to { commit(removeH(mol)) },
                t("Optimiser (xtb GFN2)", "Optimize (xtb GFN2)") to { xtbOpt() },
                "Focus" to { focus() },
                t("Estimer pKa", "Estimate pKa") to { msg = t("pKa : étape suivante", "pKa: next step") }
            ))
            Text("Shaping", color = Color.White, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
            Switch(checked = shape, onCheckedChange = { shape = it; if (it) mol = relax(mol, 300) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF2ECC71),
                    uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF777777), uncheckedBorderColor = Color(0xFF777777)))
            Menu(t("Paramètres", "Settings"), listOf("Français" to { fr = true }, "English" to { fr = false }))
            TextButton(onClick = { dialog = "info" }) { Text("Info") }
            TextButton(onClick = { msg = t("Réactivité : avec xtb, prochaine étape", "Reactivity: with xtb, next step") }) { Text(t("Réactivité", "Reactivity")) }
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
                val pr = mol.atoms.map { rot(it) }
                fun scr(i: Int) = Offset(pr[i].first * U * scale + (off.x + cs.width / 2f), pr[i].second * U * scale + (off.y + cs.height / 2f))
                fun cyl(c: Color, a: Offset, b: Offset, w: Float) {
                    drawLine(lerp(c, Color.Black, 0.45f), a, b, strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(c, a, b, strokeWidth = w * 0.65f, cap = StrokeCap.Round)
                    drawLine(lerp(c, Color.White, 0.6f), a, b, strokeWidth = w * 0.25f, cap = StrokeCap.Round)
                }
                val items = mutableListOf<Pair<Float, () -> Unit>>()
                mol.bonds.forEach { bd ->
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
                    })
                }
                items.sortedBy { it.first }.forEach { it.second() }
                val df = dragFrom
                if (df != null && df < pr.size) drawLine(Color(0xFF00B0FF), scr(df), dragPos, strokeWidth = 6f, cap = StrokeCap.Round)
            }
            Column(Modifier.padding(4.dp)) {
                FilledTonalButton(onClick = { if (undo.isNotEmpty()) { redo.add(mol); mol = undo.removeLast() } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↶") }
                FilledTonalButton(onClick = { if (redo.isNotEmpty()) { undo.add(mol); mol = redo.removeLast() } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↷") }
                FilledTonalButton(onClick = { focus() },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("Focus") }
            }
        }
        val dist = if (mode == Mode.MEASURE && picked.size == 2 && picked.all { it < mol.atoms.size }) {
            val a = mol.atoms[picked[0]]; val b = mol.atoms[picked[1]]
            val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            t("Distance : ", "Distance: ") + String.format("%.2f Å", d)
        } else ""
        Text(
            if (dist.isNotEmpty()) dist else if (msg.isNotEmpty()) msg else
                t("Élément : $el – touchez pour poser, glissez depuis un atome pour lier", "Element: $el – tap to place, drag from an atom to bond"),
            color = Color.LightGray, modifier = Modifier.padding(10.dp)
        )
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
            title = { Text("Adylase") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Lilian Rouillé", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(t("Développement", "Development"), fontSize = 16.sp)
                    Text(t("Application entièrement programmée par Claude (Anthropic), sur la demande de Lilian Rouillé.", "App entirely programmed by Claude (Anthropic), at the request of Lilian Rouillé."))
                    Spacer(Modifier.height(10.dp))
                    Text(t("Méthodes : xtb 6.7.1 (GFN2-xTB), calculs en phase gaz sauf mention contraire (le solvant ALPB est indiqué quand il est utilisé). Open Babel : à venir.", "Methods: xtb 6.7.1 (GFN2-xTB), gas phase unless stated (ALPB solvent shown when used). Open Babel: coming."))
                    if (calcLog.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(calcLog, fontSize = 10.sp) }
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
                    if (xtbOut.isNotEmpty()) Text(xtbOut, fontSize = 10.sp)
                }
            })
    }
}
