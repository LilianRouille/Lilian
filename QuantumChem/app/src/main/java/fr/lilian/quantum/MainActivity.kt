package fr.lilian.quantum

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val U = 40f
typealias V = Triple<Float, Float, Float>
data class Atom(val el: String, val x: Float, val y: Float, val z: Float = 0f)
data class Mol(val atoms: List<Atom> = emptyList(), val bonds: List<Pair<Int, Int>> = emptyList())
enum class Mode { DRAW, DELETE, MOVE, VIEW, MEASURE }

val cpk = mapOf(
    "H" to 0xFFFFFFFF, "C" to 0xFF909090, "N" to 0xFF3050F8, "O" to 0xFFFF0D0D,
    "S" to 0xFFFFFF30, "B" to 0xFFFFB5B5, "F" to 0xFF90E050, "Cl" to 0xFF1FF01F,
    "Br" to 0xFFA62929, "I" to 0xFF940094
)
val rad = mapOf("H" to 0.31f, "C" to 0.76f, "N" to 0.71f, "O" to 0.66f, "S" to 1.05f, "B" to 0.84f,
    "F" to 0.57f, "Cl" to 1.02f, "Br" to 1.2f, "I" to 1.39f)
val tgt = mapOf("C" to 4, "N" to 3, "O" to 2, "S" to 2, "B" to 3, "F" to 1, "Cl" to 1, "Br" to 1, "I" to 1)
val lonePairs = mapOf("N" to 1, "O" to 2, "S" to 2)
fun bl(a: String, b: String) = (rad[a] ?: 0.8f) + (rad[b] ?: 0.8f)
val fib: List<V> = (0 until 80).map { i ->
    val y = 1f - 2f * (i + 0.5f) / 80f
    val r = sqrt(1f - y * y)
    val th = i * 2.39996f
    Triple(cos(th) * r, y, sin(th) * r)
}

fun removeH(m: Mol): Mol {
    val keep = m.atoms.indices.filter { m.atoms[it].el != "H" }
    val map = keep.withIndex().associate { (n, o) -> o to n }
    return Mol(keep.map { m.atoms[it] },
        m.bonds.filter { map.containsKey(it.first) && map.containsKey(it.second) }.map { map[it.first]!! to map[it.second]!! })
}

fun delAt(m: Mol, h: Int): Mol {
    val b = m.bonds.filter { it.first != h && it.second != h }
        .map { (if (it.first > h) it.first - 1 else it.first) to (if (it.second > h) it.second - 1 else it.second) }
    return Mol(m.atoms.filterIndexed { i, _ -> i != h }, b)
}

fun addH(m: Mol): Mol {
    val atoms = m.atoms.toMutableList()
    val bonds = m.bonds.toMutableList()
    for (i in m.atoms.indices) {
        val a = m.atoms[i]
        val t = tgt[a.el] ?: continue
        val nb = bonds.filter { it.first == i || it.second == i }.map { if (it.first == i) it.second else it.first }
        val miss = t - nb.size
        if (miss <= 0) continue
        val fixed = nb.map {
            val b = atoms[it]
            val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
            val d = sqrt(dx * dx + dy * dy + dz * dz) + 1e-6f
            floatArrayOf(dx / d, dy / d, dz / d)
        }
        val nFree = miss + (lonePairs[a.el] ?: 0)
        val free = List(nFree) { k -> fib[(i * 7 + k * 23 + 5) % 80].let { floatArrayOf(it.first, it.second, it.third) } }
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
            bonds.add(i to atoms.size - 1)
        }
    }
    return Mol(atoms, bonds)
}

fun relax(m: Mol): Mol {
    val n = m.atoms.size
    if (n < 2) return m
    val p = m.atoms.map { floatArrayOf(it.x, it.y, it.z) }
    if (p.all { abs(it[2]) < 1e-3f }) p.forEachIndexed { i, v -> v[2] += if (i % 2 == 0) 0.05f else -0.05f }
    val bs = m.bonds.map { minOf(it.first, it.second) to maxOf(it.first, it.second) }.toSet()
    repeat(150) {
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = p[j][0] - p[i][0]; val dy = p[j][1] - p[i][1]; val dz = p[j][2] - p[i][2]
            val d = sqrt(dx * dx + dy * dy + dz * dz) + 1e-6f
            val b = bs.contains(i to j)
            val t = if (b) bl(m.atoms[i].el, m.atoms[j].el) else 1.9f
            if (b || d < t) {
                val k = (if (b) 0.3f else 0.1f) * (d - t) / d / 2f
                p[i][0] += dx * k; p[i][1] += dy * k; p[i][2] += dz * k
                p[j][0] -= dx * k; p[j][1] -= dy * k; p[j][2] -= dz * k
            }
        }
    }
    return Mol(m.atoms.mapIndexed { i, a -> a.copy(x = p[i][0], y = p[i][1], z = p[i][2]) }, m.bonds)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App() {
    var fr by remember { mutableStateOf(true) }
    var mol by remember { mutableStateOf(Mol()) }
    val undo = remember { mutableStateListOf<Mol>() }
    val redo = remember { mutableStateListOf<Mol>() }
    var mode by remember { mutableStateOf(Mode.DRAW) }
    var el by remember { mutableStateOf("C") }
    var active by remember { mutableStateOf<Int?>(null) }
    var scale by remember { mutableStateOf(1.6f) }
    var yaw by remember { mutableStateOf(0.4f) }
    var pitch by remember { mutableStateOf(0.3f) }
    var off by remember { mutableStateOf(Offset(450f, 350f)) }
    var picked by remember { mutableStateOf(listOf<Int>()) }
    var dialog by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var shape by remember { mutableStateOf(false) }
    var autoH by remember { mutableStateOf(true) }

    fun t(f: String, e: String) = if (fr) f else e
    fun commit(m: Mol) { undo.add(mol); redo.clear(); mol = m }
    fun fin(m: Mol): Mol { val h = if (autoH) addH(m) else m; return if (shape) relax(h) else h }
    fun rot(a: Atom): V {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val x1 = a.x * cy + a.z * sy
        val z1 = -a.x * sy + a.z * cy
        return Triple(x1, a.y * cp - z1 * sp, a.y * sp + z1 * cp)
    }
    fun unrot(x1: Float, y1: Float, z2: Float): V {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val y = y1 * cp + z2 * sp
        val z1 = -y1 * sp + z2 * cp
        return Triple(x1 * cy - z1 * sy, y, x1 * sy + z1 * cy)
    }
    fun hit(p: Offset): Int? {
        var best: Int? = null
        var bz = -1e9f
        mol.atoms.forEachIndexed { i, a ->
            val r = rot(a)
            val s = Offset(r.first * U * scale + off.x, r.second * U * scale + off.y)
            if ((s - p).getDistance() < 34f && r.third > bz) { bz = r.third; best = i }
        }
        return best
    }
    fun example(m: Mol) { commit(relax(addH(m))); active = null }

    Column(Modifier.fillMaxSize().background(Color(0xFF111418)).systemBarsPadding()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Menu(t("Fichier", "File"), listOf(
                t("Sauvegarder", "Save") to { msg = t("Sauvegarde : prochaine étape", "Saving: next step") },
                t("Supprimer sauvegardes", "Delete saves") to { msg = t("Prochaine étape", "Next step") },
                t("Exemple : benzène", "Example: benzene") to {
                    example(Mol((0..5).map { Atom("C", 1.4f * cos(it * (PI / 3).toFloat()), 1.4f * sin(it * (PI / 3).toFloat())) }, (0..5).map { it to (it + 1) % 6 }))
                },
                t("Exemple : eau", "Example: water") to { example(Mol(listOf(Atom("O", 0f, 0f, 0f)))) },
                t("Exemple : éthanol", "Example: ethanol") to {
                    example(Mol(listOf(Atom("C", 0f, 0f), Atom("C", 1.52f, 0f), Atom("O", 2.2f, 1.2f)), listOf(0 to 1, 1 to 2)))
                }
            ))
            Menu("Tool", listOf(
                t("Ajouter les H", "Add H") to { commit(addH(mol)); active = null },
                t("Retirer les H", "Remove H") to { commit(removeH(mol)); active = null },
                t("Estimer pKa", "Estimate pKa") to { msg = t("pKa : étape 5", "pKa: step 5") }
            ))
            Text("Shaping", color = Color.White, modifier = Modifier.padding(start = 8.dp, end = 6.dp))
            Switch(checked = shape, onCheckedChange = { shape = it; if (it) commit(relax(mol)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF2ECC71),
                    uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF777777), uncheckedBorderColor = Color(0xFF777777)))
            Menu(t("Paramètres", "Settings"), listOf("Français" to { fr = true }, "English" to { fr = false }))
            TextButton(onClick = { dialog = "info" }) { Text("Info") }
            TextButton(onClick = { msg = t("Réactivité : prochaines étapes", "Reactivity: next steps") }) { Text(t("Réactivité", "Reactivity")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                Mode.DRAW to t("Dessin", "Draw"), Mode.DELETE to t("Suppr.", "Erase"),
                Mode.MOVE to t("Déplacer", "Move"), Mode.VIEW to t("Rotation/Zoom", "Rotate/Zoom"),
                Mode.MEASURE to t("Mesure", "Measure")
            ).forEach { (m, n) ->
                FilterChip(selected = mode == m, onClick = { mode = m; active = null; picked = emptyList() },
                    label = { Text(n) }, modifier = Modifier.padding(horizontal = 3.dp))
            }
            FilterChip(selected = autoH, onClick = { autoH = !autoH; active = null },
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
            Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(mode) {
                when (mode) {
                    Mode.DRAW -> detectTapGestures { p ->
                        val h = hit(p)
                        val base = if (autoH) removeH(mol) else mol
                        val hi = if (h == null) null else if (autoH) {
                            val k = mol.atoms.indices.filter { mol.atoms[it].el != "H" }.indexOf(h)
                            if (k < 0) null else k
                        } else h
                        val a = active?.takeIf { it < base.atoms.size }
                        if (h != null) {
                            if (hi != null) {
                                if (a != null && a != hi && !base.bonds.contains(a to hi) && !base.bonds.contains(hi to a))
                                    commit(fin(base.copy(bonds = base.bonds + (a to hi))))
                                active = hi
                            }
                        } else {
                            val vx = (p.x - off.x) / (U * scale); val vy = (p.y - off.y) / (U * scale)
                            val i = base.atoms.size
                            if (a == null) {
                                val w = unrot(vx, vy, 0f)
                                commit(fin(Mol(base.atoms + Atom(el, w.first, w.second, w.third), base.bonds)))
                            } else {
                                val at = base.atoms[a]
                                val w = unrot(vx, vy, rot(at).third)
                                var dx = w.first - at.x; var dy = w.second - at.y; var dz = w.third - at.z
                                var d = sqrt(dx * dx + dy * dy + dz * dz)
                                if (d < 1e-3f) { dx = 1f; dy = 0f; dz = 0f; d = 1f }
                                val len = bl(at.el, el)
                                val n = Atom(el, at.x + dx / d * len, at.y + dy / d * len, at.z + dz / d * len)
                                commit(fin(Mol(base.atoms + n, base.bonds + (a to i))))
                            }
                            active = i
                        }
                    }
                    Mode.DELETE -> detectTapGestures { p ->
                        val h = hit(p)
                        if (h != null) {
                            val r = delAt(mol, h)
                            commit(fin(if (autoH) removeH(r) else r))
                        }
                    }
                    Mode.MEASURE -> detectTapGestures { p ->
                        val h = hit(p)
                        picked = if (h != null) (picked + h).takeLast(2) else emptyList()
                    }
                    Mode.MOVE -> {
                        var idx: Int? = null
                        detectDragGestures(
                            onDragStart = { p -> idx = hit(p); if (idx != null) { undo.add(mol); redo.clear() } },
                            onDragEnd = { if (shape) mol = relax(mol) },
                            onDrag = { change, d ->
                                change.consume()
                                val k = idx
                                if (k != null) {
                                    val w = unrot(d.x / (U * scale), d.y / (U * scale), 0f)
                                    val l = mol.atoms.toMutableList()
                                    l[k] = l[k].copy(x = l[k].x + w.first, y = l[k].y + w.second, z = l[k].z + w.third)
                                    mol = mol.copy(atoms = l)
                                }
                            }
                        )
                    }
                    Mode.VIEW -> detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom == 1f) { yaw += pan.x * 0.01f; pitch += pan.y * 0.01f }
                        else scale = (scale * zoom).coerceIn(0.4f, 6f)
                    }
                }
            }) {
                val pr = mol.atoms.map { rot(it) }
                fun scr(i: Int) = Offset(pr[i].first * U * scale + off.x, pr[i].second * U * scale + off.y)
                val items = mutableListOf<Pair<Float, () -> Unit>>()
                mol.bonds.forEach { (a, b) ->
                    if (a < pr.size && b < pr.size) {
                        val sa = scr(a); val sb = scr(b); val mid = (sa + sb) / 2f
                        val w = 0.11f * U * scale
                        val ca = Color(cpk[mol.atoms[a].el] ?: 0xFFFF69B4)
                        val cb = Color(cpk[mol.atoms[b].el] ?: 0xFFFF69B4)
                        items.add(((pr[a].third + pr[b].third) / 2f - 0.05f) to {
                            drawLine(ca, sa, mid, strokeWidth = w)
                            drawLine(cb, mid, sb, strokeWidth = w)
                        })
                    }
                }
                mol.atoms.forEachIndexed { i, a ->
                    val s = scr(i)
                    val base = Color(cpk[a.el] ?: 0xFFFF69B4)
                    val r = ((if (a.el == "H") 0.2f else 0.3f) * U * scale * (1f + pr[i].third * 0.06f)).coerceAtLeast(6f)
                    items.add(pr[i].third to {
                        drawCircle(
                            Brush.radialGradient(listOf(lerp(base, Color.White, 0.55f), base, lerp(base, Color.Black, 0.5f)),
                                center = s - Offset(r * 0.3f, r * 0.3f), radius = r * 1.4f),
                            r, s)
                        if (i == active || picked.contains(i)) drawCircle(Color(0xFF00B0FF), r + 5f, s, style = Stroke(5f))
                        if (a.el != "C" && a.el != "H" && r > 12f) {
                            val paint = Paint().apply { textSize = r; textAlign = Paint.Align.CENTER; color = android.graphics.Color.BLACK; isFakeBoldText = true }
                            drawContext.canvas.nativeCanvas.drawText(a.el, s.x, s.y + r * 0.35f, paint)
                        }
                    })
                }
                items.sortedBy { it.first }.forEach { it.second() }
            }
            Column(Modifier.padding(4.dp)) {
                FilledTonalButton(onClick = { if (undo.isNotEmpty()) { redo.add(mol); mol = undo.removeLast(); active = null } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↶") }
                FilledTonalButton(onClick = { if (redo.isNotEmpty()) { undo.add(mol); mol = redo.removeLast(); active = null } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↷") }
            }
        }
        val dist = if (mode == Mode.MEASURE && picked.size == 2 && picked.all { it < mol.atoms.size }) {
            val a = mol.atoms[picked[0]]; val b = mol.atoms[picked[1]]
            val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            t("Distance : ", "Distance: ") + String.format("%.2f Å", d)
        } else ""
        Text(
            if (dist.isNotEmpty()) dist else if (msg.isNotEmpty()) msg else
                t("Élément : $el – touchez pour dessiner", "Element: $el – tap to draw"),
            color = Color.LightGray, modifier = Modifier.padding(10.dp)
        )
    }

    if (dialog == "hal" || dialog == "tab") {
        val list = if (dialog == "hal") listOf("F", "Cl", "Br", "I") else
            "H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Ti Fe Co Ni Cu Zn Se Br Pd Ag I Pt Au".split(" ")
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = {},
            title = { Text(if (dialog == "hal") t("Halogènes", "Halogens") else t("Tableau périodique", "Periodic table")) },
            text = {
                FlowRow {
                    list.forEach { e -> OutlinedButton(onClick = { el = e; dialog = "" }, contentPadding = PaddingValues(4.dp), modifier = Modifier.padding(2.dp)) { Text(e) } }
                }
            })
    }
    if (dialog == "info") {
        AlertDialog(onDismissRequest = { dialog = "" }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("OK") } },
            title = { Text("Info") },
            text = { Text("Lilian Rouillé\n\n" + t("Éditeur moléculaire 3D. Calculs xtb (GFN2-xTB) et Open Babel : à venir.", "3D molecular editor. xtb (GFN2-xTB) and Open Babel calculations: coming.")) })
    }
}
