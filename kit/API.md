# Kiln app kit — reference

Every app Kiln builds is **Kotlin + Jetpack Compose + Material 3**, links the
Kiln kit, and uses the **Nocturne** theme. Nothing else is available: there is no
Gradle and no Maven — only the libraries listed here (they are prebuilt).

## Project layout

```
kiln.json                 package, label, versionCode/Name, minSdk 30, targetSdk 36, permissions
AndroidManifest.xml       application + activities (no <uses-sdk>, no package attr — Kiln adds them)
src/**.kt                 Kotlin sources (package = kiln.json "package")
res/                      optional Android resources (drawable/, values/, raw/, font/…)
assets/                   optional raw assets
```

`permissions` in kiln.json become `<uses-permission>` entries. INTERNET and
POST_NOTIFICATIONS are always present.

## Entry point

```kotlin
class MainActivity : KilnActivity() {          // edge-to-edge, KilnTheme, crash hook
    @Composable override fun Content() { App() }
}
```

Never call `setContent`, `enableEdgeToEdge` or define a theme yourself.

## Screens and navigation

| Need | Use |
|---|---|
| One screen with a top bar | `KilnScreen(title, onBack?, actions, floatingAction, large, centered) { padding -> … }` — apply `Modifier.screenPadding(padding)` (or `.padding(padding)`) to the content |
| 2–5 top-level sections | `KilnTabs(tabs = listOf(KTab("Home", Icons.Filled.Home), …)) { index -> … }` — bottom bar on phones, rail on wide windows |
| Push/pop between screens | **Navigation 3** (below) |
| List + detail that adapts to width | Navigation 3 + `rememberListDetailSceneStrategy()` (below) |
| Main content + side panel | `SupportingPaneScaffold` / `rememberSupportingPaneSceneStrategy()` |
| Bottom sheet | `ModalBottomSheet(onDismissRequest) { … }` |
| Dialog | `KConfirm(...)` or Material 3 `AlertDialog` |
| Pull to refresh | `PullToRefreshBox(isRefreshing, onRefresh) { … }` |
| Tabs inside a screen | `PrimaryTabRow` + `Tab` |
| Search | `KSearchField` (simple) or Material 3 `SearchBar` |
| Carousel | `HorizontalMultiBrowseCarousel` |
| Date / time | `DatePicker`, `DatePickerDialog`, `TimePicker` |

### Navigation 3

```kotlin
@Serializable data object Home : NavKey
@Serializable data class Detail(val id: Long) : NavKey

@Composable fun App() {
    val backStack = rememberNavBackStack(Home)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                                 rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Home> { HomeScreen(onOpen = { backStack.add(Detail(it)) }) }
            entry<Detail> { key -> DetailScreen(key.id, onBack = { backStack.removeLastOrNull() }) }
        },
    )
}
```

Imports: `androidx.navigation3.runtime.*`, `androidx.navigation3.ui.NavDisplay`,
`androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator`,
`kotlinx.serialization.Serializable`. Adaptive list-detail: pass
`sceneStrategies = listOf(rememberListDetailSceneStrategy<NavKey>())` to `NavDisplay` and give
entries `metadata = ListDetailSceneStrategy.listPane()` / `.detailPane()`
(`androidx.compose.material3.adaptive.navigation3`).

## Components (`app.kiln.kit`)

Every component takes `modifier`; most also take `width` / `height` (Dp, omitted = natural or full
width), `tone: KTone` (Accent, Neutral, Ok, Warn, Danger), `size: KSize` (Small, Medium, Large),
`variant: KVariant` (Filled, Tonal, Outline, Ghost), `corner` (Dp) and `enabled`. Every parameter has
a sensible default, so the minimal call is short. Prefer these over raw Material: they match Nocturne.

**Layout & containers**
| Component | Purpose |
|---|---|
| `KCard(onClick?, padding) { … }` | simple outlined card |
| `KCardBox(variant, tone, width, height, padding, corner, spacing, onClick?) { … }` | card with every knob |
| `KSection(title, action?)` | uppercase accent heading above a group |
| `KAccordion(title, subtitle?, icon?, initiallyOpen) { … }` | expandable / collapsible section |
| `KDivider(label?, thickness)` | divider, optionally "— or —" |
| `KGrid(columns = 2 or null+minCellWidth, spacing) { items(…) { … } }` | lazy grid |
| `KCarousel(count, height, peek, showDots) { page -> … }`, `KPageDots(count, current)` | swipeable pages |
| `VGap(dp)` / `HGap(dp)` | spacing |

**Buttons & actions**
| Component | Purpose |
|---|---|
| `KButton(text, variant, tone, size, icon?, trailingIcon?, loading, fullWidth, width, height, corner) { … }` | the button |
| `KIconButton(icon, description, variant, tone, size) { … }` | icon-only (always pass a description) |
| `KFab(icon, description, text?) { … }` | floating action button (extended with text) |
| `KSegmented(options, selected, onSelect, size, icons?)` | segmented control / single toggle group |
| `KToggleGroup(options, selected: Set<Int>, onChange)` | multi toggle group |
| `KMenu(items = listOf(KMenuItem(label, icon?, destructive) { … })) { open -> KIconButton(…, onClick = open) }` | dropdown / context menu |

**Inputs & forms**
| Component | Purpose |
|---|---|
| `KTextField(value, onChange, label?, placeholder?, leadingIcon?, trailingIcon?, helper?, error?, maxLength?, password, keyboard, singleLine, width, height)` | text input |
| `KTextArea(value, onChange, label?, minLines, maxLength?)` | multi-line input |
| `KSearchBar(query, onQuery, hint, trailing?)` / `KSearchField(query, onQuery)` | search with clear button |
| `KOtpField(code, onChange, length)` | one-time code boxes |
| `KStepper(value, onChange, min, max, step, label?)` | − value + |
| `KSelect(options, selected: Int?, onSelect, label?, placeholder)` | dropdown select |
| `KCombobox(options, selected: String?, onSelect, label?)` | searchable select |
| `KCheckbox(checked, onChange, label, subtitle?)` | checkbox with label |
| `KRadioGroup(options, selected, onSelect)` | radio buttons |
| `KSwitch(checked, onChange)` / `KSwitchRow(title, checked, onChange, subtitle?)` | switch |
| `KChipGroup(options, selected: Set<Int>, onChange, multi)` | filter chips |
| `KSlider(value, onChange, range, steps, label?, format)` / `KRangeSlider(range, onChange, …)` | sliders |
| `KRating(value, onChange?, max, starSize)` | star rating (display-only when onChange is null) |
| `KDateField(date: LocalDate?, onChange, label)` / `KTimeField(time: LocalTime?, onChange, label, is24h)` | date / time pickers |
| `KField(label, error?, helper?) { custom input }` | label + error around your own input |

**Display**
| Component | Purpose |
|---|---|
| `KListRow(title, subtitle?, icon?, onClick?, trailing?)` | list row |
| `KAvatar(name, imageUrl?, icon?, size, tone, online?)` / `KAvatarGroup(names, max)` | avatars |
| `KBadge(count?) { icon }` | count badge (null = dot, 0 = hidden) |
| `KChip(text, icon?, tone, selected, onClick?, onClose?)` / `KTag(text, tone)` | chip / status pill |
| `KStat(label, value, caption?)` | metric tile |
| `KKeyValue(key, value, valueTone?, bold)` | label → value row |
| `KTable(headers, rows, weights, zebra)` | simple table |
| `KTimeline(listOf(KTimelineItem(title, subtitle?, time?, tone, done)))` | vertical timeline |
| `KSteps(steps, current)` | multi-step progress (1—2—3) |
| `KImage(url, description, width, height, aspectRatio, corner)` | image from a URL |
| `KKbd(key)` | keyboard key cap |
| `KPagination(page, pages, onPage)` | page numbers |

**Feedback**
| Component | Purpose |
|---|---|
| `KAlert(message, tone, title?, icon?, actionLabel?, onAction?, onDismiss?)` | banner / notice bar |
| `KProgressBar(progress 0–1 or null, label?, tone, height)` | linear progress |
| `KProgressRing(progress, size, stroke, tone, center)` | circular progress / goal ring |
| `KSpinner(size)` / `KLoading()` | inline / full-screen loading |
| `KSkeleton(width?, height, circle)` | shimmering placeholder while loading |
| `KEmptyState(title, body, icon?, actionLabel?, onAction?)` / `KError(message, onRetry?)` | empty / error |
| `val toast = rememberKToast()` + `KToastHost(toast)` (in a Box) → `toast.show("Saved", tone, action = "Undo") { … }` | toasts / snackbars |

**Overlays & gestures**
| Component | Purpose |
|---|---|
| `KDialog(open, title, onDismiss, confirmLabel?, onConfirm, destructive) { content }` | dialog with your content |
| `KConfirm(open, title, body, confirmLabel, destructive, onConfirm, onDismiss)` | yes/no dialog |
| `KBottomSheet(open, onDismiss, title?, fullHeight) { … }` | bottom sheet / drawer |
| `KTooltip(text) { content }` | long-press tooltip |
| `KSwipeRow(onDelete = { remove(); true }, onArchive?) { row }` | swipe to delete / archive |
| `KPullRefresh(refreshing, onRefresh) { list }` | pull to refresh |

**Navigation**: `KilnScreen`, `KilnTabs` (adaptive, preferred), or `KBottomBar(listOf(KNavItem(label, icon, badge?)), selected, onSelect)`.

**Charts**: `KBarChart(values, labels, height, tone, highlight?, valueFormat?)`, `KLineChart(values, height, tone, fill, showDots)`,
`KDonutChart(listOf(KSlice(label, value, tone)), size, center?)`.

Plain Material 3 components are all available too (TextField, Slider, NavigationRail, SearchBar, …).
Icons: `Icons.Filled.*`, `Icons.Outlined.*`, `Icons.Rounded.*`, `Icons.AutoMirrored.*` (extended set).

## Theme tokens (`Nocturne`)

`bg, surface, surfaceHi, text, textMuted, textLabel, divider, accent, accent2,
accent100, accent300, accent800, accent900, neutral100/300/600/700/800, ok, warn,
danger`; fonts `Nocturne.sans`, `Nocturne.mono`. Prefer
`MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` in components.
Dark only. Never hard-code other colours.

## App icon

Every app draws its own launcher icon in `res/drawable/ic_launcher.xml` (the
manifest already points at it). It is a 108×108 vector drawable:

- First path: the full-bleed background square `M0,0h108v108h-108z`, filled
  `#161826` (bg) or `#232532` (surface).
- Then one bold, simple glyph for what the app *does* (a drop for water, a
  check for todos, a flame for streaks…) drawn inside the centre safe zone
  x,y 30–78 — launchers crop to a circle or squircle, so keep it centred.
  Make it big: the glyph should span most of that zone (about 40–48 units
  wide), not a small mark in the middle.
- Colours from the Nocturne palette only: `#9184D9` accent, `#A7A1DB`
  accent2, `#F5F4FF` accent100, `#423A6A` accent800, `#7FB69A` ok,
  `#D9C48A` warn, `#D98A8A` danger. Use 1–3 of them; no gradients, no text.
- Use `android:pathData` with simple commands (M, L, H, V, C, A, Z) and
  `android:strokeWidth`/`android:strokeColor`/`android:strokeLineCap="round"`
  for line icons.

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#161826" android:pathData="M0,0h108v108h-108z" />
    <!-- water drop -->
    <path android:fillColor="#9184D9"
        android:pathData="M54,30 C54,30 72,50 72,62 A18,18 0 0 1 36,62 C36,50 54,30 54,30 Z" />
</vector>
```

## State and data

- UI state: `remember { mutableStateOf(…) }`, `rememberSaveable`, or a `ViewModel`
  (`viewModel()` from lifecycle-viewmodel-compose) exposing `StateFlow`; collect
  with `collectAsStateWithLifecycle()`.
- Persisted data: `KStore(context, "name", default)` — a JSON file exposed as
  `state: StateFlow<T>`; `update { … }` / `set(value)`. Types must be
  `@Serializable`. One store per collection is fine.
- Key/value prefs: DataStore Preferences (`androidx.datastore.preferences`).
- JSON: `KJson` (kotlinx.serialization).
- Background / scheduled work: WorkManager (`androidx.work`).

## System

| API | Purpose |
|---|---|
| `KNet.getText(url)`, `KNet.getJson<T>(url)`, `KNet.postJson(url, json)` | HTTP (suspend; IO dispatcher) |
| `AsyncImage(model = url, contentDescription)` | images (Coil 3) |
| `KNotify.post(context, title, text)` | notification (needs POST_NOTIFICATIONS granted) |
| `rememberPermission(Manifest.permission.X)` → `.granted`, `.request()` | runtime permissions |
| `KIntents.openUrl(ctx, url)`, `KIntents.share(ctx, text)` | intents |
| `KLog.i/w/e(msg)` | logging (tag `KILN-APP`, what `logcat` shows first) |

Coroutines: `rememberCoroutineScope()`, `LaunchedEffect`, `viewModelScope`.

## Backend and payments

| API | Purpose |
|---|---|
| `KSupabase(context, url, anonKey)` | Supabase: `signUp/signIn(email, password)`, `signOut()`, `userId: StateFlow<String?>` |
| `db.table("t").select<T>("select=*&order=id.desc")` | rows (PostgREST query); also `insert(row)`, `update("id=eq.1", json)`, `delete("id=eq.1")` |
| `KBilling(context, consumable = setOf(...))` | Play in-app purchases: `load(ids)`, `product(id)` (price), `buy(activity, id)`, `owned: StateFlow<Set<String>>`, `refresh()` |

- Keys come from the app's Secrets (`AppSecrets.SUPABASE_ANON_KEY`), never from source. Only Supabase's anon
  key belongs in an app; protect tables with Row Level Security.
- Billing needs opting in: add the permission `com.android.vending.BILLING` with `set_app_meta`. Products
  are created in Play Console, and purchases work only in builds installed from Play (internal testing).
  Load the skills `supabase` / `billing` before using these.

## Rules

1. Kotlin only, Compose only (no XML layouts, no Fragments, no AppCompat).
2. Every screen is a `KilnScreen` or lives inside `KilnTabs`; apply the padding.
3. Only the libraries above exist. Do not invent dependencies or APIs — use
   `sdk_lookup` when unsure of a signature.
4. Use `KLog` for anything worth seeing in `logcat`.
5. Keep files small and focused (one screen per file is a good default).
