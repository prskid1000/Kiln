package app.kiln.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.kiln.agent.Turns
import app.kiln.ui.theme.N
import app.kiln.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one turn changed, file by file, with a diff and per-file revert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(vm: KilnVM, ps: ProjectState, index: Int, onDismiss: () -> Unit) {
    var changes by remember { mutableStateOf<List<Turns.Change>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(index, tick) { changes = withContext(Dispatchers.IO) { vm.turnChanges(ps.project.name, index) } }
    ModalBottomSheet(onDismiss, containerColor = N.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Changes in this turn", style = T.cardTitle)
            val c = changes
            when {
                c == null -> Text("Loading…", style = T.label)
                c.isEmpty() -> Text("This turn changed no files.", style = T.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                else -> LazyColumn(Modifier.heightIn(max = 560.dp)) {
                    items(c, key = { it.path }) { ch ->
                        Row(Modifier.fillMaxWidth().clickable { open = if (open == ch.path) null else ch.path }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(when (ch.kind) { Turns.Change.Kind.ADDED -> "+"; Turns.Change.Kind.DELETED -> "−"; else -> "~" },
                                style = T.mono.copy(color = when (ch.kind) { Turns.Change.Kind.ADDED -> N.ok; Turns.Change.Kind.DELETED -> N.danger; else -> N.warn }),
                                modifier = Modifier.padding(end = 10.dp))
                            Text(ch.path, style = T.mono.copy(color = N.text), modifier = Modifier.weight(1f))
                            KButton("Revert") { vm.revertFile(ps.project.name, index, ch.path); tick++ }
                        }
                        if (open == ch.path) {
                            // Reads both versions and runs an LCS: computed off the main thread, once per open/revert.
                            val diff by androidx.compose.runtime.produceState<List<Pair<Char, String>>?>(null, ch.path, tick) {
                                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.turnDiff(ps.project.name, index, ch.path) }
                            }
                            diff?.let { DiffView(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffView(lines: List<Pair<Char, String>>) {
    Column(Modifier.fillMaxWidth().clip(N.shapeMd).background(N.bg).horizontalScroll(rememberScrollState()).padding(8.dp)) {
        lines.forEach { (k, l) ->
            Text("$k $l", style = T.monoSmall.copy(color = when (k) { '+' -> N.ok; '-' -> N.danger; else -> N.textMuted }), softWrap = false)
        }
    }
}

/** Line diff (LCS) with up to [context] unchanged lines around changes; big files are summarised. */
fun lineDiff(old: List<String>, new: List<String>, context: Int = 2): List<Pair<Char, String>> {
    if (old.size * new.size > 4_000_000) return listOf(' ' to "(file too large to diff: ${old.size} → ${new.size} lines)")
    val dp = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed())
        dp[i][j] = if (old[i] == new[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    val all = mutableListOf<Pair<Char, String>>()
    var i = 0; var j = 0
    while (i < old.size || j < new.size) when {
        i < old.size && j < new.size && old[i] == new[j] -> { all += ' ' to old[i]; i++; j++ }
        j < new.size && (i == old.size || dp[i][j + 1] >= dp[i + 1][j]) -> { all += '+' to new[j]; j++ }
        else -> { all += '-' to old[i]; i++ }
    }
    val keep = BooleanArray(all.size)
    all.forEachIndexed { k, (c, _) -> if (c != ' ') for (x in maxOf(0, k - context)..minOf(all.size - 1, k + context)) keep[x] = true }
    val out = mutableListOf<Pair<Char, String>>()
    var skipped = false
    all.forEachIndexed { k, p -> if (keep[k]) { out += p; skipped = false } else if (!skipped) { out += ' ' to "…"; skipped = true } }
    return out
}
