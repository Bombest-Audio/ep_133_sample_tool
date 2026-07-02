package com.ep133.sampletool.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ep133.sampletool.ui.theme.Ep133Sku
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ep133.sampletool.ui.device.DeviceScreen
import com.ep133.sampletool.ui.device.DeviceViewModel
import com.ep133.sampletool.ui.pads.PadsScreen
import com.ep133.sampletool.ui.pads.PadsViewModel
import com.ep133.sampletool.ui.projects.ProjectsScreen
import com.ep133.sampletool.ui.projects.ProjectsViewModel
import com.ep133.sampletool.ui.theme.EP133Theme
import com.ep133.sampletool.ui.theme.LocalEP133Tokens
import com.ep133.sampletool.ui.kit.KitScreen
import com.ep133.sampletool.ui.kit.KitViewModel
import com.ep133.sampletool.ui.kitbuilder.KitBuilderViewModel

private val MonoFont = FontFamily.Monospace
private val BadgeShape = RoundedCornerShape(3.dp)

private enum class NavRoute(val route: String, val label: String) {
    PADS("pads", "PADS"),
    KIT("kit", "KIT"),
    PROJECTS("projects", "PROJ"),
    DEVICE("device", "DEVICE"),
}

/**
 * App shell for the redesign: the EP-133 faceplate chrome (header with badge + current screen
 * title + connection dot, and a mono bottom tab nav) wrapping the NavHost. Colors come from
 * [LocalEP133Tokens]; the screens render their own bodies.
 */
@Composable
fun EP133App(
    padsViewModel: PadsViewModel,
    projectsViewModel: ProjectsViewModel,
    deviceViewModel: DeviceViewModel,
    kitViewModel: KitViewModel,
    kitBuilderViewModel: KitBuilderViewModel,
    isConnected: Boolean = false,
) {
    // Theme state lives above EP133Theme so the header controls can re-theme the whole app.
    var themeMode by remember { mutableIntStateOf(0) } // 0 = follow system, 1 = light, 2 = dark
    var sku by remember { mutableStateOf(Ep133Sku.EP133) }
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    EP133Theme(darkTheme = dark, sku = sku) {
        val t = LocalEP133Tokens.current
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val currentRoute = NavRoute.entries.firstOrNull { r ->
            currentDestination?.hierarchy?.any { it.route == r.route } == true
        } ?: NavRoute.PADS

        Column(
            Modifier
                .fillMaxSize()
                .background(t.bg)
                .statusBarsPadding(),
        ) {
            // ── Faceplate header ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(t.panel2)
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tap the badge to switch SKU theme (EP-133 orange ↔ EP-1320 rust).
                Text(
                    if (sku == Ep133Sku.EP133) "EP·133" else "EP·1320",
                    Modifier
                        .clip(BadgeShape)
                        .background(t.accent, BadgeShape)
                        .clickable(
                            onClickLabel = if (sku == Ep133Sku.EP133) {
                                "Switch to the EP-1320 color theme"
                            } else {
                                "Switch to the EP-133 color theme"
                            },
                            role = Role.Button,
                        ) { sku = if (sku == Ep133Sku.EP133) Ep133Sku.EP1320 else Ep133Sku.EP133 }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    color = t.onAccent,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    currentRoute.label,
                    Modifier.padding(start = 9.dp).weight(1f),
                    color = t.text2,
                    fontFamily = MonoFont,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.1.sp,
                )
                // Tap to cycle theme: follow system → light → dark.
                Text(
                    when (themeMode) { 1 -> "☀"; 2 -> "☾"; else -> "◐" },
                    Modifier
                        .clip(BadgeShape)
                        .clickable(
                            onClickLabel = when (themeMode) {
                                1 -> "Light theme active — switch to dark theme"
                                2 -> "Dark theme active — follow the system theme"
                                else -> "Following the system theme — switch to light theme"
                            },
                            role = Role.Button,
                        ) { themeMode = (themeMode + 1) % 3 }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    color = t.text2,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                val dot = if (isConnected) t.live else t.text3
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                    Text(
                        if (isConnected) "CONNECTED" else "NO DEVICE",
                        color = dot,
                        fontFamily = MonoFont,
                        fontSize = 10.sp,
                        letterSpacing = 0.7.sp,
                    )
                }
            }

            // ── Screen body ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                NavHost(
                    navController = navController,
                    startDestination = NavRoute.PADS.route,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable(NavRoute.PADS.route) { PadsScreen(padsViewModel) }
                    composable(NavRoute.PROJECTS.route) { ProjectsScreen(projectsViewModel) }
                    composable(NavRoute.DEVICE.route) {
                        DeviceScreen(viewModel = deviceViewModel)
                    }
                    composable(NavRoute.KIT.route) {
                        KitScreen(kitViewModel, kitBuilderViewModel)
                    }
                }
            }

            // ── Faceplate bottom tab nav ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(t.panel2)
                    .navigationBarsPadding(),
            ) {
                NavRoute.entries.forEach { item ->
                    val selected = currentRoute == item
                    Box(
                        Modifier
                            .weight(1f)
                            .clickable {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            item.label,
                            color = if (selected) t.accent else t.text3,
                            fontFamily = MonoFont,
                            fontSize = 8.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 0.3.sp,
                        )
                    }
                }
            }
        }
    }
}
