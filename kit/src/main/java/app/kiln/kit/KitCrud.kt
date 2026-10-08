package app.kiln.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// ---------------------------------------------------------------- data screens

/** Open the add/edit form of a [KCrudList] from anywhere (a tab, a toolbar button): `crud.add()`, `crud.edit(row)`. */
class KCrudState<T> internal constructor() {
    internal var editing by mutableStateOf<KRow<T>?>(null)
    internal var adding by mutableStateOf(false)
    /** Open the form for a new item. */
    fun add() { editing = null; adding = true }
    /** Open the form for [row]. */
    fun edit(row: KRow<T>) { adding = false; editing = row }
}

@Composable
fun <T> rememberKCrudState(): KCrudState<T> = remember { KCrudState() }

/**
 * A complete list of saved items with add, edit, delete and search — the screen most apps need.
 * Give it the data, how a row reads, and the form; it does the rest (list, grouping, search,
 * swipe-to-delete with Undo, empty state, + button, an add/edit sheet with Save/Delete and validation).
 * ```
 * @Serializable data class Expense(val amount: Double = 0.0, val note: String = "", val category: String = "Food")
 * object Repo { val expenses = KCollection<Expense>("expenses") }
 *
 * KCrudList(
 *     items = Repo.expenses, itemName = "expense", newItem = { Expense() },
 *     title = { it.note.ifBlank { it.category } }, trailing = { KFormat.money(it.amount, "USD") },
 *     validate = { if (it.amount <= 0) "Enter an amount" else null },
 * ) { draft, set ->
 *     KTextField(if (draft.amount == 0.0) "" else draft.amount.toString(), { set(draft.copy(amount = it.toDoubleOrNull() ?: 0.0)) },
 *         label = "Amount", keyboard = KeyboardType.Decimal)
 *     KTextField(draft.note, { set(draft.copy(note = it)) }, label = "Note")
 * }
 * ```
 */
@Composable
fun <T> KCrudList(
    items: KCollection<T>,
    newItem: () -> T,
    title: (T) -> String,
    modifier: Modifier = Modifier,
    itemName: String = "item",
    subtitle: ((T) -> String?)? = null,
    trailing: ((T) -> String?)? = null,
    icon: ((T) -> ImageVector?)? = null,
    /** Show a search bar; [matches] decides what a query finds (title by default). */
    searchable: Boolean = true,
    matches: (T, String) -> Boolean = { item, q -> title(item).contains(q, ignoreCase = true) },
    /** Group rows under headings (e.g. by date): the heading for a row; null keeps one plain list. */
    groupBy: ((KRow<T>) -> String)? = null,
    /** Order of rows (default: newest first, as stored). */
    sortedBy: Comparator<KRow<T>>? = null,
    /** An error message for an invalid item (shown in the form, Save refused), or null when it's fine. */
    validate: (T) -> String? = { null },
    emptyTitle: String = "No ${itemName}s yet",
    emptyBody: String = "Tap + to add your first $itemName.",
    showAddButton: Boolean = true,
    state: KCrudState<T> = rememberKCrudState(),
    /** Called when a row is tapped; by default the row opens for editing. */
    onOpen: ((KRow<T>) -> Unit)? = null,
    form: @Composable ColumnScope.(draft: T, set: (T) -> Unit) -> Unit,
) {
    val rows by items.rows.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val toast = rememberKToast()
    val visible = rows.let { r -> if (sortedBy != null) r.sortedWith(sortedBy) else r }
        .filter { query.isBlank() || matches(it.value, query.trim()) }
    val name = itemName.replaceFirstChar { it.uppercase() }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searchable && rows.isNotEmpty()) KSearchBar(query, { query = it }, hint = "Search ${itemName}s")
            when {
                rows.isEmpty() -> KEmptyState(emptyTitle, emptyBody, actionLabel = "Add $itemName", onAction = { state.add() })
                visible.isEmpty() -> KEmptyState("Nothing matches “$query”", "Try another search.")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val groups = if (groupBy == null) listOf("" to visible) else visible.groupBy(groupBy).toList()
                    for ((heading, group) in groups) {
                        if (heading.isNotEmpty()) item(key = "h:$heading") { KSection(heading) }
                        items(group, key = { it.id }) { row ->
                            KSwipeRow(onDelete = {
                                val removed = row.value
                                items.delete(row.id)
                                toast.show("$name deleted", action = "Undo") { items.add(removed) }
                                true
                            }) {
                                KListRow(
                                    title(row.value), subtitle = subtitle?.invoke(row.value), icon = icon?.invoke(row.value),
                                    onClick = { onOpen?.invoke(row) ?: state.edit(row) },
                                    trailing = trailing?.invoke(row.value)?.let { t -> { Text(t) } },
                                )
                            }
                        }
                    }
                }
            }
        }
        // The + button moves up while a toast shows, so the toast never covers it.
        val lift by androidx.compose.animation.core.animateDpAsState(if (toast.host.currentSnackbarData != null) 72.dp else 0.dp, label = "fab")
        if (showAddButton) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = lift)) {
            KFab(Icons.Filled.Add, "Add $itemName") { state.add() }
        }
        KToastHost(toast)
    }

    // The add/edit sheet: the draft lives here until Save.
    val editing = state.editing
    if (state.adding || editing != null) {
        var draft by remember(editing?.id, state.adding) { mutableStateOf(editing?.value ?: newItem()) }
        var error by remember(editing?.id, state.adding) { mutableStateOf<String?>(null) }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        // Closing the form also puts the keyboard away.
        fun close() { focus.clearFocus(); keyboard?.hide(); state.adding = false; state.editing = null }
        KBottomSheet(true, { close() }, title = if (editing == null) "New $itemName" else "Edit $itemName") {
            form(draft) { draft = it; error = null }
            error?.let { KAlert(it, tone = KTone.Danger) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editing != null) KButton("Delete", variant = KVariant.Outline, tone = KTone.Danger) {
                    val removed = editing.value
                    items.delete(editing.id); close()
                    toast.show("$name deleted", action = "Undo") { items.add(removed) }
                }
                Spacer(Modifier.weight(1f))
                KButton("Cancel", variant = KVariant.Ghost, tone = KTone.Neutral) { close() }
                KButton("Save") {
                    val problem = validate(draft)
                    if (problem != null) { error = problem; return@KButton }
                    if (editing == null) items.add(draft) else items.update(editing.id, draft)
                    close()
                    toast.show(if (editing == null) "$name added" else "$name saved", KTone.Ok)
                }
            }
        }
    }
}
