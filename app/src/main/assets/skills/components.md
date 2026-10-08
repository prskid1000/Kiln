# components — the kit's UI components, all of them in use

- Reach for a `K…` component before raw Material: they match Nocturne and take the same knobs —
  `modifier`, `width`/`height`, `tone` (KTone), `size` (KSize), `variant` (KVariant), `corner`, `enabled`.
- Forms: `KTextField` (label, helper, error, maxLength, password, keyboard), `KSelect`, `KDateField`,
  `KStepper`, `KChipGroup`, `KSlider`. Validate on change and pass `error = "…"`.
- Feedback: `KAlert` for inline messages, toasts via `rememberKToast()` + `KToastHost`, `KSkeleton` while loading.
- Lists: `KListRow` inside `KSwipeRow` for swipe-to-delete; wrap the list in `KPullRefresh`.

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.kiln.kit.*
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun ComponentGallery() {
    val toast = rememberKToast()
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var qty by remember { mutableIntStateOf(1) }
    var city by remember { mutableStateOf<Int?>(null) }
    var fruit by remember { mutableStateOf<String?>(null) }
    var agree by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Int?>(0) }
    var on by remember { mutableStateOf(true) }
    var tags by remember { mutableStateOf(setOf(0)) }
    var volume by remember { mutableFloatStateOf(40f) }
    var price by remember { mutableStateOf(10f..60f) }
    var stars by remember { mutableIntStateOf(3) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var toggles by remember { mutableStateOf(setOf(1)) }
    var dialog by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    var refreshing by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(listOf("Milk", "Eggs", "Bread")) }

    Box(Modifier.fillMaxSize()) {
        KPullRefresh(refreshing, { refreshing = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KSection("Buttons")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton("Save", icon = Icons.Filled.Add) { toast.show("Saved", KTone.Ok, action = "Undo") {} }
                    KButton("Delete", variant = KVariant.Outline, tone = KTone.Danger, size = KSize.Small) { confirm = true }
                    KIconButton(Icons.Filled.Edit, "Edit", variant = KVariant.Tonal) {}
                }
                KButton("Continue", fullWidth = true, size = KSize.Large, loading = false, height = 56.dp) { sheet = true }
                KSegmented(listOf("Day", "Week", "Month"), tab, { tab = it })
                KToggleGroup(listOf("Bold", "Italic", "Underline"), toggles, { toggles = it })
                KMenu(listOf(KMenuItem("Edit", Icons.Filled.Edit) {}, KMenuItem("Delete", Icons.Filled.Delete, destructive = true) {})) { open ->
                    KIconButton(Icons.Filled.MoreVert, "More", onClick = open)
                }

                KSection("Inputs")
                KTextField(name, { name = it }, label = "Name", placeholder = "Ada Lovelace", helper = "As on your ID",
                    error = if (name.length == 1) "Too short" else null, maxLength = 40)
                KTextField(pin, { pin = it }, label = "PIN", password = true, keyboard = KeyboardType.NumberPassword, maxLength = 6, width = 200.dp)
                KTextArea(notes, { notes = it }, label = "Notes", minLines = 3)
                KSearchBar(query, { query = it }, placeholder = "Search recipes")
                KOtpField(otp, { otp = it }, length = 4)
                KStepper(qty, { qty = it }, min = 1, max = 10, label = "Servings")
                KSelect(listOf("Berlin", "Delhi", "Lagos"), city, { city = it }, label = "City")
                KCombobox(listOf("Apple", "Banana", "Cherry", "Mango"), fruit, { fruit = it }, label = "Fruit")
                KCheckbox(agree, { agree = it }, "I agree", subtitle = "Terms and privacy")
                KRadioGroup(listOf("Free", "Pro"), plan, { plan = it })
                KSwitchRow("Notifications", on, { on = it })
                KChipGroup(listOf("Vegan", "Quick", "Spicy"), tags, { tags = it })
                KSlider(volume, { volume = it }, label = "Volume")
                KRangeSlider(price, { price = it }, range = 0f..100f, label = "Price", format = { "$" + it.toInt() })
                KRating(stars, { stars = it })
                KDateField(date, { date = it }, label = "Due date")
                KTimeField(time, { time = it }, label = "Reminder")
                KLabeled("Custom", helper = "Any input inside a labelled field") { KSwitch(on, { on = it }) }

                KSection("Display")
                KCardBox(variant = KVariant.Tonal, tone = KTone.Accent, height = 96.dp, onClick = {}) { Text("A tonal card") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KAvatar("Ada Lovelace", online = true)
                    KAvatarGroup(listOf("Ann Lee", "Bo Chen", "Cy Diaz", "Di Eze", "Ed Fox"), max = 3)
                    KBadge(3) { Icon(Icons.Filled.Notifications, "Alerts") }
                    KChip("Kotlin", onClose = {})
                    KTag("Active", KTone.Ok)
                    KKbd("Ctrl")
                }
                KStat("Steps", "8,412", caption = "Goal 10,000")
                KKeyValue("Total", "$42.00", bold = true)
                KTable(listOf("Item", "Qty"), listOf(listOf("Milk", "2"), listOf("Eggs", "12")), weights = listOf(3f, 1f))
                KTimeline(listOf(KTimelineItem("Ordered", time = "9:00"), KTimelineItem("Shipped", "Courier"), KTimelineItem("Delivered", done = false)))
                KSteps(listOf("Cart", "Address", "Pay"), current = 1)
                KImage("https://picsum.photos/600/300", "Sample photo", aspectRatio = 2f)
                KCarousel(3, height = 120.dp) { p -> KCardBox(height = 120.dp) { Text("Slide ${p + 1}") } }
                KAccordion("What is Kiln?", subtitle = "Tap to expand") { Text("An app that builds apps.") }
                KDivider(label = "or")
                KPagination(page, 7, { page = it })

                KSection("Feedback")
                KAlert("Your trial ends in 3 days.", tone = KTone.Warn, title = "Heads up", actionLabel = "Upgrade", onAction = {}, onDismiss = {})
                KProgressBar(0.62f, label = "Uploading")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KProgressRing(0.75f, size = 96.dp)
                    KSpinner()
                    KSkeleton(width = 120.dp, height = 18.dp)
                }

                KSection("Lists")
                items.forEach { item ->
                    KSwipeRow(onDelete = { items = items - item; true }) { KListRow(item, subtitle = "Swipe to delete", icon = Icons.Filled.Person) }
                }

                KSection("Charts")
                KBarChart(listOf(3f, 5f, 2f, 8f, 6f), listOf("M", "T", "W", "T", "F"), highlight = 3)
                KLineChart(listOf(1f, 3f, 2f, 5f, 4f, 7f), showDots = true)
                KDonutChart(listOf(KSlice("Food", 40f, KTone.Accent), KSlice("Rent", 50f, KTone.Ok), KSlice("Fun", 10f, KTone.Warn)), center = "$1.2k")

                KTooltip("Opens settings") { KIconButton(Icons.Filled.Settings, "Settings") { dialog = true } }
                KBottomBar(listOf(KNavItem("Home", Icons.Filled.Home), KNavItem("Inbox", Icons.Filled.Notifications, badge = 2)), 0, {})
            }
        }
        KFab(Icons.Filled.Add, "Add", text = "New", modifier = Modifier) { dialog = true }
        KToastHost(toast)
    }
    KDialog(dialog, "Rename", { dialog = false }, confirmLabel = "Save", onConfirm = {}) { KTextField(name, { name = it }, label = "Name") }
    KBottomSheet(sheet, { sheet = false }, title = "Filters") { KChipGroup(listOf("A", "B"), tags, { tags = it }) }
    KConfirm(confirm, "Delete item?", "This can't be undone.", "Delete", destructive = true, onConfirm = {}, onDismiss = { confirm = false })
}
```
