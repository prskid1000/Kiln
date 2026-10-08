# design-guidelines — making screens look and feel professional

Load this before designing any screen. Check every screen against it before calling the work done.

**Layout**
- Spacing comes from one scale: 4, 8, 12, 16, 24, 32 dp. Screen edges 16 dp (`screenPadding`), between cards 12, inside cards 14–16.
- One primary action per screen (`KButton` Filled or a `KFab`); secondary actions are Tonal/Outline/Ghost.
- Group related things in `KCardBox`/`KSection`; don't put cards inside cards.
- Lists use `LazyColumn` with stable `key`s. Long forms scroll. Nothing overlaps the system bars (KilnScreen handles insets).
- Design for 360 dp wide first, then check that wide screens don't stretch content: cap text columns around 600 dp.

**Hierarchy and type**
- Use `MaterialTheme.typography` roles: headlineSmall for the screen's hero number or title, titleMedium for cards,
  bodyLarge for content, bodySmall/labelSmall in `Nocturne.textMuted` for secondary text. At most 3 sizes per screen.
- Colour means something: accent for the primary action and selection; Ok/Warn/Danger only for status. Never hard-code colours:
  read `Nocturne.*` tokens. The look is the theme's job (Nocturne by default; `load_skill theming` if the user wants another).

**Every state is designed**
- Empty: `KEmptyState` with what to do next. Loading: `KSkeleton` shaped like the content (or `KLoading`).
  Error: `KError` with Retry, or `KAlert` inline. Success: a toast (`rememberKToast`), with Undo for destructive actions.
- Destructive actions ask first (`KConfirm`) or offer Undo, not both.

**Touch and accessibility**
- Touch targets ≥ 48 dp (K components already are). Icon-only buttons always get a content description.
- Text contrast: text on `Nocturne.surface` uses `Nocturne.text` or `textLabel`, never `neutral600` for content.
- Don't rely on colour alone: pair status colours with an icon or a word (`KTag("Overdue", KTone.Danger)`).
- Inputs: a label on every field, the right `keyboard` type, and an error message under the field (not a toast).

**Motion and feel**
- Animate changes in size and visibility (`animateContentSize`, `AnimatedVisibility`); keep it to 150–300 ms.
- Give feedback within 100 ms of a tap: a pressed state, `KHaptics.tick`, or a spinner on the button (`loading = true`).

**Polish checklist**: custom launcher icon (make_graphic) · app label · no placeholder text · numbers formatted
(`KFormat`) · dates relative where recent · empty, loading and error states · works in landscape · no clipped text.

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kiln.kit.*

/** A dashboard following the guidelines: one hero number, grouped stats, one primary action, designed empty state. */
@Composable
fun BudgetScreen(spent: Double, budget: Double, recent: List<Pair<String, Double>>, onAdd: () -> Unit) {
    KilnScreen("Budget") { padding ->
        Column(Modifier.screenPadding(padding).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KCardBox(variant = KVariant.Tonal, tone = KTone.Accent) {
                Text("Spent this month", style = MaterialTheme.typography.bodySmall, color = Nocturne.textMuted)
                Text(KFormat.money(spent, "USD"), style = MaterialTheme.typography.headlineSmall)
                KProgressBar((spent / budget).toFloat(), label = "of ${KFormat.money(budget, "USD")}",
                    tone = if (spent > budget) KTone.Danger else KTone.Accent)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KStat("Left", KFormat.money((budget - spent).coerceAtLeast(0.0), "USD"), Modifier.weight(1f))
                KStat("Per day", KFormat.money(spent / 30, "USD"), Modifier.weight(1f))
            }
            KSection("Recent")
            if (recent.isEmpty()) KEmptyState("No expenses yet", "Add one to start tracking.", actionLabel = "Add expense", onAction = onAdd)
            else recent.forEach { (name, amount) -> KKeyValue(name, KFormat.money(amount, "USD")) }
            KButton("Add expense", fullWidth = true, onClick = onAdd)
        }
    }
}
```
