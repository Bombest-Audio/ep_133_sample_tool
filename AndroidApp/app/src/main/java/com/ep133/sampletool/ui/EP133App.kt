package com.ep133.sampletool.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalContext
import com.ep133.sampletool.SampleManagerActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ep133.sampletool.ui.beats.BeatsScreen
import com.ep133.sampletool.ui.beats.BeatsViewModel
import com.ep133.sampletool.ui.chords.ChordsScreen
import com.ep133.sampletool.ui.chords.ChordsViewModel
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsScreen
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.sounds.SoundsScreen
import com.ep133.sampletool.ui.sounds.SoundsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme

private enum class NavRoute(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    PADS("pads", "PADS", Icons.Default.GridView),
    BEATS("beats", "BEATS", Icons.Default.ViewWeek),
    SOUNDS("sounds", "SOUNDS", Icons.Default.LibraryMusic),
    CHORDS("chords", "CHORDS", Icons.Default.MusicNote),
    DEVICE("device", "DEVICE", Icons.Default.Usb),
}

@Composable
fun EP133App(
    padsViewModel: PadsViewModel,
    beatsViewModel: BeatsViewModel,
    soundsViewModel: SoundsViewModel,
    chordsViewModel: ChordsViewModel,
    deviceViewModel: DeviceViewModel,
) {
    EP133Theme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    NavRoute.entries.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label,
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        letterSpacing = 1.5.sp,
                                    ),
                                    textAlign = TextAlign.Center,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = NavRoute.PADS.route,
                modifier = Modifier
                    .padding(innerPadding)
                    .statusBarsPadding(),
            ) {
                composable(NavRoute.PADS.route) { PadsScreen(padsViewModel) }
                composable(NavRoute.BEATS.route) { BeatsScreen(beatsViewModel) }
                composable(NavRoute.SOUNDS.route) { SoundsScreen(soundsViewModel) }
                composable(NavRoute.CHORDS.route) { ChordsScreen(chordsViewModel) }
                composable(NavRoute.DEVICE.route) {
                    val context = LocalContext.current
                    DeviceScreen(
                        viewModel = deviceViewModel,
                        onNavigateToWebView = { SampleManagerActivity.launch(context) },
                    )
                }
            }
        }
    }
}
