package fr.lilian.quantum

import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val U = 40f
data class Atom(val el: String, val x: Float, val y: Float)
data class Mol(val atoms: List<Atom> = emptyList(), val bonds: List<Pair<Int, Int>> = emptyList())
enum class Mode { DRAW, DELETE, MOVE, VIEW, MEASURE }

val cpk = mapOf(
    "H" to 0xFFFFFFFF, "C" to 0xFF909090, "N" to 0xFF3050F8, "O" to 0xFFFF0D0D,
    "S" to 0xFFFFFF30, "B" to 0xFFFFB5B5, "F" to 0xFF90E050, "Cl" to 0xFF1FF01F,
    "Br" to 0xFFA62929, "I" to 0xFF940094
)

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
    var scale by remember { mutableStateOf(1f) }
    var off by remember { mutableStateOf(Offset(500f, 400f)) }
    var picked by remember { mutableStateOf(listOf<Int>()) }
    var dialog by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    fun t(f: String, e: String) = if (fr) f else e
    fun commit(m: Mol) { undo.add(mol); redo.clear(); mol = m }
    fun sc(a: Atom) = Offset(a.x * U * scale + off.x, a.y * U * scale + off.y)
    fun hit(p: Offset): Int? {
        val i = mol.atoms.indexOfFirst { val s = sc(it); (s - p).getDistance() < 32f }
        return if (i >= 0) i else null
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF111418)).systemBarsPadding()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Menu(t("Fichier", "File"), listOf(
                t("Sauvegarder", "Save") to { msg = t("Étape 2 : sauvegarde à venir", "Step 2: saving coming") },
                t("Supprimer sauvegardes", "Delete saves") to { msg = t("Étape 2", "Step 2") },
                t("Exemple : benzène", "Example: benzene") to {
                    val a = (0..5).map { Atom("C", 1.4f * cos(it * PI / 3).toFloat(), 1.4f * sin(it * PI / 3).toFloat()) }
                    commit(Mol(a, (0..5).map { it to (it + 1) % 6 })); active = null
                },
                t("Exemple : eau", "Example: water") to {
                    commit(Mol(listOf(Atom("O", 0f, 0f), Atom("H", 0.96f, 0.3f), Atom("H", -0.96f, 0.3f)), listOf(0 to 1, 0 to 2))); active = null
                }
            ))
            Menu("Tool", listOf(
                t("Estimer pKa", "Estimate pKa") to { msg = t("Étape 5", "Step 5") },
                t("Modifier les H", "Edit H") to { msg = t("Étape 2", "Step 2") }
            ))
            Menu("Shaping", listOf(t("On / Off", "On / Off") to { msg = t("Étape 2", "Step 2") }))
            Menu(t("Paramètres", "Settings"), listOf("Français" to { fr = true }, "English" to { fr = false }))
            TextButton(onClick = { dialog = "info" }) { Text("Info") }
            TextButton(onClick = { msg = t("Réactivité : étapes 3 et 4", "Reactivity: steps 3-4") }) { Text(t("Réactivité", "Reactivity")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(
                Mode.DRAW to t("Dessin", "Draw"), Mode.DELETE to t("Suppr.", "Erase"),
                Mode.MOVE to t("Déplacer", "Move"), Mode.VIEW to t("Vue/Zoom", "View/Zoom"),
                Mode.MEASURE to t("Mesure", "Measure")
            ).forEach { (m, n) ->
                FilterChip(selected = mode == m, onClick = { mode = m; active = null; picked = emptyList() },
                    label = { Text(n) }, modifier = Modifier.padding(horizontal = 3.dp))
            }
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
                        val a = active
                        if (h != null) {
                            if (a != null && a != h && !mol.bonds.contains(a to h) && !mol.bonds.contains(h to a))
                                commit(mol.copy(bonds = mol.bonds + (a to h)))
                            active = h
                        } else {
                            val n = Atom(el, (p.x - off.x) / (U * scale), (p.y - off.y) / (U * scale))
                            val i = mol.atoms.size
                            commit(Mol(mol.atoms + n, if (a != null) mol.bonds + (a to i) else mol.bonds))
                            active = i
                        }
                    }
                    Mode.DELETE -> detectTapGestures { p ->
                        val h = hit(p)
                        if (h != null) {
                            val b = mol.bonds.filter { it.first != h && it.second != h }
                                .map { (if (it.first > h) it.first - 1 else it.first) to (if (it.second > h) it.second - 1 else it.second) }
                            commit(Mol(mol.atoms.filterIndexed { i, _ -> i != h }, b))
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
                            onDrag = { change, d ->
                                change.consume()
                                val k = idx
                                if (k != null) {
                                    val l = mol.atoms.toMutableList()
                                    l[k] = l[k].copy(x = l[k].x + d.x / (U * scale), y = l[k].y + d.y / (U * scale))
                                    mol = mol.copy(atoms = l)
                                }
                            }
                        )
                    }
                    Mode.VIEW -> detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.3f, 5f); off += pan
                    }
                }
            }) {
                mol.bonds.forEach { (a, b) ->
                    drawLine(Color.White, sc(mol.atoms[a]), sc(mol.atoms[b]), strokeWidth = 5f * scale.coerceAtLeast(0.5f))
                }
                val paint = Paint().apply { textSize = 30f; textAlign = Paint.Align.CENTER; color = android.graphics.Color.BLACK; isFakeBoldText = true }
                mol.atoms.forEachIndexed { i, a ->
                    val s = sc(a)
                    val r = (24f * scale).coerceAtLeast(10f)
                    drawCircle(Color(cpk[a.el] ?: 0xFFFF69B4), r, s)
                    if (i == active || picked.contains(i)) drawCircle(Color(0xFF00B0FF), r + 6f, s, style = Stroke(6f))
                    drawContext.canvas.nativeCanvas.drawText(a.el, s.x, s.y + 10f, paint)
                }
            }
            Column(Modifier.padding(4.dp)) {
                FilledTonalButton(onClick = { if (undo.isNotEmpty()) { redo.add(mol); mol = undo.removeLast(); active = null } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↶") }
                FilledTonalButton(onClick = { if (redo.isNotEmpty()) { undo.add(mol); mol = redo.removeLast(); active = null } },
                    modifier = Modifier.width(64.dp), contentPadding = PaddingValues(0.dp)) { Text("↷") }
            }
        }
        val dist = if (mode == Mode.MEASURE && picked.size == 2) {
            val a = mol.atoms[picked[0]]; val b = mol.atoms[picked[1]]
            val d = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
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
            text = { Text("Lilian Rouillé\n\n" + t("Étape 1 : éditeur moléculaire. Calculs xtb (GFN2-xTB) et Open Babel : à venir.", "Step 1: molecular editor. xtb (GFN2-xTB) and Open Babel calculations: coming.")) })
    }
}
