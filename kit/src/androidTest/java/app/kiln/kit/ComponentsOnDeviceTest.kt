package app.kiln.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** Every kit component rendered on the device and used the way a person would, with screenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComponentsOnDeviceTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        KilnTheme { Surface(Modifier.fillMaxSize(), color = Nocturne.bg) { Column(Modifier.padding(16.dp)) { content() } } }
    }

    private fun shot(name: String) {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun buttons() {
        var clicks = 0; var seg by mutableIntStateOf(0); var toggles by mutableStateOf(setOf<Int>())
        show {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton("Save", icon = Icons.Filled.Add) { clicks++ }
                    KButton("Outline", variant = KVariant.Outline, tone = KTone.Danger, size = KSize.Small) { clicks += 10 }
                    KButton("Busy", loading = true) { clicks += 100 }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton("Tonal", variant = KVariant.Tonal) {}; KButton("Ghost", variant = KVariant.Ghost) {}
                    KButton("Off", enabled = false) { clicks += 1000 }
                }
                KButton("Wide", fullWidth = true, size = KSize.Large) {}
                KButton("Fixed", width = 200.dp, height = 60.dp, corner = 4.dp) {}
                KIconButton(Icons.Filled.Edit, "Edit it", variant = KVariant.Tonal) { clicks += 1 }
                KSegmented(listOf("Day", "Week", "Month"), seg, { seg = it })
                KToggleGroup(listOf("Bold", "Italic"), toggles, { toggles = it })
                KFab(Icons.Filled.Add, "Add item", text = "New") { clicks += 5 }
            }
        }
        shot("buttons")
        rule.onNodeWithText("Save").performClick(); rule.onNodeWithText("Outline").performClick()
        rule.onNodeWithText("Busy").performClick(); rule.onNodeWithText("Off").performClick()
        rule.onNodeWithContentDescription("Edit it").performClick(); rule.onNodeWithContentDescription("Add item").performClick()
        assertEquals(1 + 10 + 1 + 5, clicks)                       // loading and disabled ignore taps
        rule.onNodeWithText("Month").performClick(); assertEquals(2, seg)
        rule.onNodeWithText("Italic").performClick(); assertEquals(setOf(1), toggles)
        rule.onNodeWithText("Italic").performClick(); assertEquals(emptySet<Int>(), toggles)
        val wide = rule.onNodeWithText("Fixed").fetchSemanticsNode().boundsInRoot
        assertTrue("Fixed was ${wide.width}", wide.width > 0)
    }

    @Test fun textInputs() {
        var name by mutableStateOf(""); var pin by mutableStateOf(""); var q by mutableStateOf(""); var otp by mutableStateOf("")
        var notes by mutableStateOf("")
        show {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KTextField(name, { name = it }, label = "Name", helper = "As on ID", maxLength = 5, error = if (name == "bad") "Not allowed" else null)
                KTextField(pin, { pin = it }, label = "PIN", password = true)
                KTextArea(notes, { notes = it }, label = "Notes")
                KSearchBar(q, { q = it }, hint = "Search recipes")
                KOtpField(otp, { otp = it }, length = 4)
            }
        }
        rule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Ada Lovelace")
        assertEquals("", name)                                     // longer than maxLength: refused whole
        rule.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Ada")
        assertEquals("Ada", name); rule.onNodeWithText("3/5").assertIsDisplayed(); rule.onNodeWithText("As on ID").assertIsDisplayed()
        name = "bad"; rule.waitForIdle(); rule.onNodeWithText("Not allowed").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("PIN")).performTextInput("1234")
        assertEquals("1234", pin)
        fun pinShown() = rule.onNode(hasSetTextAction() and hasText("PIN")).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("••••", pinShown())                          // masked on screen
        rule.onNodeWithContentDescription("Show").performClick(); assertEquals("1234", pinShown())
        rule.onNode(hasSetTextAction() and hasText("Notes")).performTextInput("line one\nline two")
        assertEquals("line one\nline two", notes)
        rule.onNodeWithText("Search recipes").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction())[3].performTextInput("soup"); assertEquals("soup", q)
        rule.onNodeWithContentDescription("Clear search").performClick(); assertEquals("", q)
        rule.onAllNodes(hasSetTextAction())[4].performTextInput("12a34567"); assertEquals("1234", otp)
        shot("text_inputs")
    }

    @Test fun selection() {
        var qty by mutableIntStateOf(1); var city by mutableStateOf<Int?>(null); var fruit by mutableStateOf<String?>(null)
        var agree by mutableStateOf(false); var plan by mutableStateOf<Int?>(null); var on by mutableStateOf(false)
        var chips by mutableStateOf(setOf<Int>()); var single by mutableStateOf(setOf<Int>()); var stars by mutableIntStateOf(0)
        var vol by mutableFloatStateOf(10f); var range by mutableStateOf(0f..100f); var sw by mutableStateOf(false)
        show {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KStepper(qty, { qty = it }, min = 1, max = 3, label = "Servings")
                KSelect(listOf("Berlin", "Delhi", "Lagos"), city, { city = it }, label = "City")
                KCombobox(listOf("Apple", "Banana", "Cherry"), fruit, { fruit = it }, label = "Fruit")
                KCheckbox(agree, { agree = it }, "I agree")
                KRadioGroup(listOf("Free", "Pro"), plan, { plan = it })
                KSwitchRow("Alerts", on, { on = it })
                Row { KSwitch(sw, { sw = it }, Modifier.testTag("bare-switch")) }
                KChipGroup(listOf("Vegan", "Quick"), chips, { chips = it })
                KChipGroup(listOf("S", "M", "L"), single, { single = it }, multi = false)
                KRating(stars, { stars = it })
                KSlider(vol, { vol = it }, label = "Volume", modifier = Modifier.testTag("slider"))
                KRangeSlider(range, { range = it }, label = "Price")
            }
        }
        shot("selection")
        rule.onNodeWithContentDescription("Increase").performClick(); rule.onNodeWithContentDescription("Increase").performClick()
        assertEquals(3, qty); rule.onNodeWithContentDescription("Increase").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Decrease").performClick(); assertEquals(2, qty)
        rule.onNodeWithText("Choose…").performClick(); rule.onNodeWithText("Delhi").performClick(); assertEquals(1, city)
        rule.onNodeWithText("Delhi").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Fruit")).performTextInput("ban")
        rule.onNodeWithText("Banana").performClick(); assertEquals("Banana", fruit)
        rule.onNodeWithText("I agree").performClick(); assertTrue(agree)
        rule.onNodeWithText("Pro").performClick(); assertEquals(1, plan)
        rule.onNodeWithText("Alerts").performClick(); assertTrue(on)
        rule.onNodeWithTag("bare-switch").performClick(); assertTrue(sw)
        rule.onNodeWithText("Quick").performClick(); rule.onNodeWithText("Vegan").performClick(); assertEquals(setOf(0, 1), chips)
        rule.onNodeWithText("M").performClick(); rule.onNodeWithText("L").performClick(); assertEquals(setOf(2), single)
        rule.onNodeWithContentDescription("4 of 5").performClick(); assertEquals(4, stars)
        rule.onNodeWithContentDescription("4 of 5").performClick(); assertEquals(0, stars)   // tapping the same star clears
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and SemanticsMatcher("vol") {
            it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) { null }?.current == 10f })
            .performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        assertEquals(55f, vol, 1f); rule.onNodeWithText("55").assertIsDisplayed()
    }

    @Test fun dateAndTime() {
        var date by mutableStateOf<LocalDate?>(LocalDate.of(2026, 10, 8)); var time by mutableStateOf<LocalTime?>(null)
        show {
            KDateField(date, { date = it }, label = "Due")
            KTimeField(time, { time = it }, label = "Reminder")
            KField("Custom", error = "Required") { Text("inside") }
        }
        rule.onNodeWithText("8 Oct 2026").assertIsDisplayed(); rule.onNodeWithText("Required").assertIsDisplayed()
        rule.onNodeWithText("8 Oct 2026").performClick(); shot("date_picker")
        rule.onNodeWithText("OK").performClick(); assertEquals(LocalDate.of(2026, 10, 8), date)
        rule.onNodeWithText("Pick a time").performClick(); shot("time_picker")
        rule.onNodeWithText("OK").performClick(); assertEquals(LocalTime.of(9, 0), time)
        rule.onNodeWithText("09:00").assertIsDisplayed()
    }

    @Test fun displayAndData() {
        var card = 0; var page by mutableIntStateOf(1); var closed = false
        show {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KCardBox(variant = KVariant.Tonal, tone = KTone.Ok, height = 70.dp, onClick = { card++ }) { Text("Tonal card") }
                KCardBox(variant = KVariant.Filled, tone = KTone.Accent, width = 160.dp) { Text("Filled") }
                KAccordion("Question", subtitle = "Tap me") { Text("The answer") }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KAvatar("Ada Lovelace", online = true); KAvatarGroup(listOf("A B", "C D", "E F", "G H", "I J"), max = 3)
                    KBadge(150) { Icon(Icons.Filled.Notifications, "Inbox") }; KBadge(null) { Icon(Icons.Filled.Home, "Home") }
                    KKbd("Ctrl")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { KChip("Kotlin", onClose = { closed = true }); KTag("Done", KTone.Ok) }
                KStat("Steps", "8,412", caption = "Goal 10k")
                KKeyValue("Total", "$42.00", bold = true, valueTone = KTone.Ok)
                KTable(listOf("Item", "Qty"), listOf(listOf("Milk", "2"), listOf("Eggs", "12")), weights = listOf(3f, 1f))
                KTimeline(listOf(KTimelineItem("Ordered", time = "9:00"), KTimelineItem("Shipped", "Courier"), KTimelineItem("Delivered", done = false)))
                KSteps(listOf("Cart", "Address", "Pay"), current = 1)
                KDivider(label = "or")
                KPagination(page, 9, { page = it })
                KListRow("A row", subtitle = "with subtitle", icon = Icons.Filled.Settings)
                KEmptyState("Nothing here", "Add something to start.", actionLabel = "Add", onAction = {})
            }
        }
        shot("display")
        rule.onNodeWithText("Tonal card").performClick(); assertEquals(1, card)
        rule.onNodeWithText("The answer").assertDoesNotExist()
        rule.onNodeWithText("Question").performClick(); rule.onNodeWithText("The answer").assertIsDisplayed()
        rule.onNodeWithText("AL").assertIsDisplayed()                 // initials
        rule.onNodeWithText("+2").assertIsDisplayed(); rule.onNodeWithText("99+").assertIsDisplayed()
        rule.onNodeWithContentDescription("Remove Kotlin").performClick(); assertTrue(closed)
        rule.onNodeWithText("Eggs").assertIsDisplayed(); rule.onNodeWithText("Courier").assertIsDisplayed()
        rule.onNodeWithText("Next").performScrollTo().performClick(); assertEquals(2, page)
        rule.onNodeWithText("Prev").performClick(); assertEquals(1, page)
        rule.onNodeWithText("Prev").assertIsNotEnabled()
        shot("display_after")
    }

    @Test fun feedback() {
        var dismissed = false; var acted = false
        show {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KAlert("Trial ends soon.", tone = KTone.Warn, title = "Heads up", actionLabel = "Upgrade", onAction = { acted = true }, onDismiss = { dismissed = true })
                KAlert("Saved.", tone = KTone.Ok); KAlert("Failed.", tone = KTone.Danger)
                KProgressBar(0.62f, label = "Upload"); KProgressBar(null, label = "Working")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { KProgressRing(0.75f, size = 90.dp); KSpinner(); KSkeleton(width = 100.dp) }
                KSkeleton(circle = true, height = 40.dp)
                KError("Couldn't load", onRetry = {})
            }
        }
        shot("feedback")
        rule.onNodeWithText("62%").assertIsDisplayed(); rule.onNodeWithText("75%").assertIsDisplayed()
        rule.onNodeWithText("Upgrade").performClick(); assertTrue(acted)
        rule.onNodeWithContentDescription("Dismiss").performClick(); assertTrue(dismissed)
    }

    @Test fun overlaysAndToast() {
        var dialog by mutableStateOf(false); var sheet by mutableStateOf(false); var confirm by mutableStateOf(false)
        var saved = false; var menuHit = ""; var undone = false; var deleted = false
        lateinit var toast: KToast
        show {
            toast = rememberKToast()
            Box(Modifier.fillMaxSize()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    KButton("Open dialog") { dialog = true }; KButton("Open sheet") { sheet = true }; KButton("Delete all") { confirm = true }
                    KMenu(listOf(KMenuItem("Rename", Icons.Filled.Edit) { menuHit = "rename" }, KMenuItem("Remove", Icons.Filled.Delete, destructive = true) { menuHit = "remove" })) { open ->
                        KIconButton(Icons.Filled.MoreVert, "More", onClick = open)
                    }
                    KTooltip("Opens settings") { KIconButton(Icons.Filled.Settings, "Settings") {} }
                }
                KToastHost(toast)
            }
            KDialog(dialog, "Rename", { dialog = false }, confirmLabel = "Save", onConfirm = { saved = true }) { Text("Dialog body") }
            KBottomSheet(sheet, { sheet = false }, title = "Filters") { Text("Sheet body") }
            KConfirm(confirm, "Delete all?", "Can't be undone.", "Delete", destructive = true, onConfirm = { deleted = true }, onDismiss = { confirm = false })
        }
        rule.onNodeWithText("Open dialog").performClick(); rule.onNodeWithText("Dialog body").assertIsDisplayed(); shot("dialog")
        rule.onNodeWithText("Save").performClick(); assertTrue(saved); rule.onNodeWithText("Dialog body").assertDoesNotExist()
        rule.onNodeWithText("Open sheet").performClick(); rule.waitUntil(5000) { rule.onAllNodesWithText("Sheet body").fetchSemanticsNodes().isNotEmpty() }
        shot("sheet"); sheet = false; rule.waitForIdle()
        rule.onNodeWithText("Delete all").performClick(); rule.onNodeWithText("Delete").performClick(); assertTrue(deleted)
        rule.onNodeWithContentDescription("More").performClick(); shot("menu"); rule.onNodeWithText("Remove").performClick(); assertEquals("remove", menuHit)
        rule.onNodeWithContentDescription("Settings").performTouchInput { longClick() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Opens settings").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { toast.show("Item deleted", KTone.Danger, action = "Undo") { undone = true } }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Item deleted").fetchSemanticsNodes().isNotEmpty() }
        shot("toast"); rule.onNodeWithText("Undo").performClick(); rule.waitForIdle(); assertTrue(undone)
    }

    @Test fun gesturesNavigationGridCarousel() {
        var items by mutableStateOf(listOf("Alpha", "Beta")); var refreshed = 0; var tab by mutableIntStateOf(0)
        show {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.height(200.dp).testTag("list")) {
                    KPullRefresh(false, { refreshed++ }) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(items, key = { it }) { t -> KSwipeRow(onDelete = { items = items - t; true }) { KListRow(t) } }
                        }
                    }
                }
                KCarousel(3, height = 80.dp) { p -> KCardBox(height = 80.dp) { Text("Slide ${p + 1}") } }
                Box(Modifier.height(120.dp)) { KGrid(columns = 3) { items(listOf("G1", "G2", "G3", "G4")) { Text(it) } } }
                KBottomBar(listOf(KNavItem("Home", Icons.Filled.Home), KNavItem("Inbox", Icons.Filled.Notifications, badge = 3)), tab, { tab = it })
            }
        }
        shot("gestures")
        rule.onNodeWithText("Alpha").performTouchInput { swipeLeft() }
        rule.waitUntil(5000) { items == listOf("Beta") }
        rule.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 10f, endY = bottom - 10f, durationMillis = 600) }
        rule.waitUntil(5000) { refreshed > 0 }
        rule.onNodeWithText("Slide 1").assertIsDisplayed()
        rule.onNodeWithText("Slide 1").performTouchInput { swipeLeft() }
        rule.waitUntil(5000) { rule.onAllNodesWithText("Slide 2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("G4").assertIsDisplayed()
        rule.onNodeWithText("Inbox").performClick(); assertEquals(1, tab)
    }

    @Test fun charts() {
        show {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KBarChart(listOf(3f, 5f, 2f, 8f), listOf("M", "T", "W", "T"), highlight = 3, valueFormat = { it.toInt().toString() })
                KLineChart(listOf(1f, 3f, 2f, 5f, 4f), showDots = true)
                KLineChart(listOf(1f))                                      // one point: nothing drawn, no crash
                KDonutChart(listOf(KSlice("Food", 40f, KTone.Accent), KSlice("Rent", 60f, KTone.Ok)), center = "$1k")
                KBarChart(emptyList())
            }
        }
        shot("charts")
        rule.onNodeWithText("Food · 40%").assertIsDisplayed(); rule.onNodeWithText("8").assertIsDisplayed(); rule.onNodeWithText("$1k").assertIsDisplayed()
    }

    @Test fun mapsSvgAndScreen() {
        var tapped = false
        show {
            KilnScreen("Screen title") { p ->
                Column(Modifier.padding(p), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KMap(KLatLng(52.5163, 13.3777), zoom = 14.0, height = 260.dp, markers = listOf(KMarker(KLatLng(52.5163, 13.3777), "Gate")),
                        route = listOf(KLatLng(52.5163, 13.3777), KLatLng(52.52, 13.39)), onTap = { tapped = true }, modifier = Modifier.testTag("map"))
                    KSvg("file:///android_asset/test.svg", "Test art", Modifier.size(96.dp))
                    KImage("https://picsum.photos/seed/kiln/600/300", "Photo", aspectRatio = 2f)
                }
            }
        }
        rule.onNodeWithText("Screen title").assertIsDisplayed(); rule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        rule.onNodeWithTag("map").assertIsDisplayed().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.8f)) }
        Thread.sleep(4000)                                          // let tiles and images load for the screenshot
        rule.waitForIdle(); shot("map_svg")
        rule.waitUntil(3000) { tapped }
    }
}
