# Kiln app kit — reference

Every app Kiln builds is **Kotlin + Jetpack Compose + Material 3**, links the
Kiln kit, and uses Kiln's **Nocturne** theme unless the user asks for another look. Nothing else is available: there is no
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

Never call `setContent` or `enableEdgeToEdge`. To change the look, override `Theme` (see Theming).

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
| Search | `KSearchBar` |
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

## Components and services (`app.kiln.kit`)

The **Kit index** (in your instructions) lists every component and service by category; it is generated
from the kit's code, so if a name is there, it exists. `kit_search <name>` returns its exact signature
and an example. Use them before writing your own: they match Nocturne, handle accessibility, and are tested.

Shared knobs, all with defaults: `modifier`, `width`/`height` (Dp; omitted = natural or full width),
`tone: KTone` (Accent, Neutral, Ok, Warn, Danger), `size: KSize` (Small, Medium, Large),
`variant: KVariant` (Filled, Tonal, Outline, Ghost), `corner` (Dp), `enabled`.

- Overlays are driven by state: `KDialog(open, …)`, `KBottomSheet(open, …)`, `KConfirm(open, …)`.
- Toasts: `val toast = rememberKToast()`, `KToastHost(toast)` last inside a `Box`, then `toast.show("Saved", action = "Undo") { … }`.
- Data: one value → `KStore` / `rememberStored`; a growing list (notes, expenses) → `KCollection`; a small setting → `rememberPref`.
- Network: `KHttp.get<T>(url)` / `KHttp.post<B, T>(url, body)` throw `KHttpException`; show `KError(…) { retry }`.
- Plain Material 3 is available too. Icons: `Icons.Filled.*`, `Icons.Outlined.*`, `Icons.Rounded.*`, `Icons.AutoMirrored.*` (extended set).

## Theming

**Default: Nocturne** (dark, soft violet). Keep it unless the user asks for a different look, colour,
mood or brand. Then change it in one place — every K component, Material component and `Nocturne.*`
read follows:

```kotlin
class MainActivity : KilnActivity() {
    @Composable override fun Theme(content: @Composable () -> Unit) =
        KilnTheme(theme = KThemes.OceanDepths, content = content)   // or any of the options below
    @Composable override fun Content() { App() }
}
```

- **Presets** (`KThemes.*`, also `KThemes.named("ocean depths")`, `KThemes.all`): `Nocturne` (default),
  `ArcticFrost` (light, ice blue/steel), `BotanicalGarden` (light, fern green/marigold, serif),
  `DesertRose` (light, dusty rose/clay, serif), `ForestCanopy` (light, forest green/sage),
  `GoldenHour` (light, terracotta/mustard), `MidnightGalaxy` (dark, purple/lavender),
  `ModernMinimalist` (light, charcoal/grey, sharp), `OceanDepths` (dark, navy/teal),
  `SunsetBoulevard` (light, burnt orange/coral), `TechInnovation` (dark, electric blue/cyan, sharp).
  Map the user's words to the closest ("calm", "techy", "warm", "natural", "minimal").
- **Brand colour**: `KilnTheme(accent = Color(0xFFE91E63), dark = false)` — tints, selection and Material
  roles are derived. Also `accent2`, `background`, or a full `palette = KPalette.from(…).copy(…)`.
- **Look and feel**: `font = KFonts.Inter | System | Serif | Mono` (or a FontFamily from res/font),
  `corners = KCorners.Sharp | Default | Soft | Round`, `density = KDensity.Compact | Default |
  Comfortable | Large` (heights, padding, icons of every component), `textScale = 1.15f`,
  `surfaceAlpha = 0.8f` (translucent cards/fields over a background image or gradient).
- **Surface style** (`style = KStyle.…`, combines with any theme): `Flat` (default), `Glass` (glassmorphism:
  frosted translucent panes over a gradient backdrop), `Neumorphic` (soft extruded, best on light themes),
  `Outlined` (borders only), `Elevated` (floating shadows), `Brutalist` (thick borders, hard offset shadows,
  forces sharp corners), `Clay` (claymorphism: puffy pastel 3D, round), `Neon` (cyberpunk glowing edges,
  dark themes), `Skeuomorphic` (gradients and bevels). Custom surfaces match with `Modifier.kSurface(shape, fill, border)`; full-screen
  custom layouts get the style's background with `Modifier.kBackdrop()`.
- **Let the app's users choose**: `KThemePicker(selected, onSelect)` + `rememberStored("theme", …)` (see the theming skill).
- **One component only** (rare; only when the user asks): `colors = KColors(container, content, border)`
  on KButton, KIconButton, KCardBox, KChip, KTextField, KSearchBar, KSelect; `color =` on KAlert,
  KProgressBar, KProgressRing, KBadge, KBarChart, KLineChart; `textStyle`, `border`, `contentPadding`,
  `elevation` where they apply; `Modifier.alpha(…)` for transparency.

Tokens, always of the active theme: `Nocturne.bg, surface, surfaceHi, text, textMuted, textLabel, divider,
accent, accent2, accent100, accent300, accent800, accent900, neutral100/300/600/700/800, ok, warn, danger,
isDark`; fonts `Nocturne.sans`, `Nocturne.mono`; sizes `kr(12)` (corner), `ks(48)` (size), `kt(14)` (text)
scaled by the theme. Never hard-code colours in screens: read tokens, so a theme change restyles everything.

## App icon

Every app draws its own launcher icon in `res/drawable/ic_launcher.xml` (the
manifest already points at it). It is a 108×108 vector drawable:

- First path: the full-bleed background square `M0,0h108v108h-108z`, filled
  with the theme's bg or surface (Nocturne: `#161826` / `#232532`).
- Then one bold, simple glyph for what the app *does* (a drop for water, a
  check for todos, a flame for streaks…) drawn inside the centre safe zone
  x,y 30–78 — launchers crop to a circle or squircle, so keep it centred.
  Make it big: the glyph should span most of that zone (about 40–48 units
  wide), not a small mark in the middle.
- Colours from the app's theme palette (Nocturne: `#9184D9` accent, `#A7A1DB`
  accent2, `#F5F4FF` accent100, `#423A6A` accent800, `#7FB69A` ok,
  `#D9C48A` warn, `#D98A8A` danger; a preset's are in `KThemes`). Use 1–3 of them; no text.
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
| `KHttp.get<T>(url)`, `KHttp.post<B, T>(url, body)`, `put`, `patch`, `delete` | HTTP + JSON (suspend; throws KHttpException) |
| `AsyncImage(model = url, contentDescription)` | images (Coil 3) |
| `KNotify.post(context, title, text)` | notification (needs POST_NOTIFICATIONS granted) |
| `rememberPermission(Manifest.permission.X)` → `.granted`, `.request()` | runtime permissions |
| `KOpen.url / dial / sms / email / map / shareText / calendarEvent / appSettings / playStore` | hand off to other apps |
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
