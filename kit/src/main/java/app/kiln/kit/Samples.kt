@file:OptIn(androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi::class)

package app.kiln.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

/*
 * Compiled examples for API.md. If a library renames something, this file stops
 * compiling and the reference gets fixed with it — the agent never reads an
 * example that doesn't build.
 */

@Serializable internal data object SampleHome : NavKey
@Serializable internal data class SampleDetail(val id: Long) : NavKey

@Composable
internal fun SampleNavigation() {
    val backStack = rememberNavBackStack(SampleHome)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<SampleHome> {
                KilnScreen(title = "Home") { p -> Column(androidx.compose.ui.Modifier.screenPadding(p)) {
                    KListRow("Open 1", onClick = { backStack.add(SampleDetail(1)) })
                } }
            }
            entry<SampleDetail> { key ->
                KilnScreen(title = "Item ${key.id}", onBack = { backStack.removeLastOrNull() }) { Text("Detail") }
            }
        },
    )
}

@Composable
internal fun SampleListDetail() {
    val backStack = rememberNavBackStack(SampleHome)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(rememberListDetailSceneStrategy<NavKey>()),
        entryProvider = entryProvider {
            entry<SampleHome>(metadata = ListDetailSceneStrategy.listPane()) { Text("List") }
            entry<SampleDetail>(metadata = ListDetailSceneStrategy.detailPane()) { Text("Detail") }
        },
    )
}

@Composable
internal fun SampleTabs() {
    KilnTabs(listOf(KTab("Home", Icons.Filled.Home), KTab("Settings", Icons.Filled.Settings))) { i ->
        KilnScreen(title = if (i == 0) "Home" else "Settings") { Text("Tab $i") }
    }
}
