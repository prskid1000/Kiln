@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.kiln.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

/**
 * One screen: top app bar + content, insets handled. [content] receives the
 * padding to apply (system bars + app bar already included).
 *
 * @param onBack shows a back arrow when set (pushed screens).
 * @param large  collapsing large title (good for list screens).
 */
@Composable
fun KilnScreen(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbar: SnackbarHostState? = null,
    centered: Boolean = false,
    large: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        else TopAppBarDefaults.pinnedScrollBehavior()
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = if (Nocturne.surfaceStyle == KStyle.Glass) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
        scrolledContainerColor = if (Nocturne.surfaceStyle == KStyle.Glass) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer,
    )
    val nav: @Composable () -> Unit = {
        if (onBack != null) IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize().kBackdrop().nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            when {
                large -> LargeTopAppBar(title = { Text(title) }, navigationIcon = nav, actions = actions,
                    colors = colors, scrollBehavior = scroll)
                centered -> CenterAlignedTopAppBar(title = { Text(title) }, navigationIcon = nav,
                    actions = actions, colors = colors, scrollBehavior = scroll)
                else -> TopAppBar(title = { Text(title) }, navigationIcon = nav, actions = actions,
                    colors = colors, scrollBehavior = scroll)
            }
        },
        floatingActionButton = floatingAction,
        bottomBar = bottomBar,
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { padding -> content(padding) }
}

/** A top-level destination for [KilnTabs]. */
data class KTab(val label: String, val icon: ImageVector, val badge: String? = null)

/**
 * Top-level navigation that adapts to the window: bottom bar on phones,
 * navigation rail on wider windows (NavigationSuiteScaffold). [content] draws
 * the selected tab, usually a [KilnScreen].
 */
@Composable
fun KilnTabs(
    tabs: List<KTab>,
    initial: Int = 0,
    content: @Composable (index: Int) -> Unit,
) {
    var selected by rememberSaveable { mutableIntStateOf(initial) }
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEachIndexed { i, tab ->
                item(
                    selected = i == selected,
                    onClick = { selected = i },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    badge = tab.badge?.let { b -> { Text(b) } },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) { content(selected) }
    }
}

/** Apply a [KilnScreen]'s padding plus the standard 16dp gutter. */
fun Modifier.screenPadding(padding: PaddingValues, horizontal: Int = 16): Modifier =
    this.padding(padding).padding(horizontal = horizontal.dp)
