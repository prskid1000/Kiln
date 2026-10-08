package app.kiln.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@Serializable data class Spend(val amount: Double = 0.0, val note: String = "")

/** KCrudList end to end: add (with validation), edit, search, swipe-delete with Undo, delete from the sheet. */
@RunWith(AndroidJUnit4::class)
class CrudOnDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun addEditSearchDeleteUndo() {
        val store = KCollection<Spend>("t_crud_" + System.nanoTime())
        rule.setContent {
            KilnTheme {
                Surface(Modifier.fillMaxSize(), color = Nocturne.bg) {
                    Box(Modifier.padding(16.dp)) {
                        KCrudList(items = store, itemName = "expense", newItem = { Spend() }, title = { it.note.ifBlank { "Untitled" } },
                            trailing = { "%.2f".format(it.amount) }, validate = { if (it.amount <= 0) "Enter an amount" else null }) { draft, set ->
                            KTextField(if (draft.amount == 0.0) "" else draft.amount.toString(), { set(draft.copy(amount = it.toDoubleOrNull() ?: 0.0)) },
                                label = "Amount", keyboard = KeyboardType.Decimal)
                            KTextField(draft.note, { set(draft.copy(note = it)) }, label = "Note")
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("No expenses yet").assertExists()
        // Add: Save without an amount is refused with the validation message.
        rule.onNodeWithContentDescription("Add expense").performClick()
        rule.onNodeWithText("New expense").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Note")).performTextInput("Lunch")
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Enter an amount").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Amount")).performTextInput("12.5")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(3000) { store.size == 1 }
        rule.onNodeWithText("Lunch").assertExists(); rule.onNodeWithText("12.50").assertExists()
        // A resting row shows no swipe labels (they used to sit in the tree on every row).
        assertEquals(0, rule.onAllNodesWithContentDescription("Archive").fetchSemanticsNodes().size +
            rule.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().size)
        // Edit by tapping the row.
        rule.onNodeWithText("Lunch").performClick()
        rule.onNodeWithText("Edit expense").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Lunch")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasText("Note")).performTextInput("Team lunch")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(3000) { store.items.firstOrNull()?.note == "Team lunch" }
        // A second item, then search.
        rule.onNodeWithContentDescription("Add expense").performClick()
        // The sheet animates in: wait for it, and focus each field before typing.
        rule.waitUntil(3000) { rule.onAllNodesWithText("New expense").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Amount")).performClick().performTextInput("3")
        rule.onNode(hasSetTextAction() and hasText("Note")).performClick().performTextInput("Coffee")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(3000) { store.size == 2 }
        rule.waitUntil(3000) { rule.onAllNodesWithText("New expense").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasContentDescription("Search expenses")).performTextInput("cof")
        rule.waitUntil(3000) { rule.onAllNodesWithText("Team lunch").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Coffee").assertExists()
        rule.onNode(hasSetTextAction() and hasText("cof")).performTextClearance()
        rule.onNode(hasSetTextAction() and hasContentDescription("Search expenses")).performTextInput("zzz")
        rule.onNodeWithText("Nothing matches “zzz”").assertExists()
        rule.onNode(hasSetTextAction() and hasText("zzz")).performTextClearance()
        // Swipe to delete, then Undo brings it back.
        rule.waitUntil(3000) { rule.onAllNodesWithText("Coffee").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Coffee").performTouchInput { swipeLeft() }
        rule.waitUntil(3000) { store.size == 1 }
        rule.waitUntil(3000) { rule.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(3000) { store.size == 2 }
        // Delete from the edit sheet.
        rule.onNodeWithText("Team lunch").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(3000) { store.size == 1 }
        assertEquals(listOf("Coffee"), store.items.map { it.note })
    }
}
