// ui/navigation/BottomNavigationBar.kt
//
// ТИП: UI Component (Navigation)
//
// ИЗМЕНЕНИЯ:
// 1. Dynamic Tabs: Список вкладок теперь формируется динамически на основе настроек ViewModel.
// 2. Feature Flags: Скрытие "Записи" и добавление "Радио".
// 3. Signature Change: Компонент теперь принимает `viewModel`, а не просто цвет.

package com.trec.music.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.trec.music.ui.theme.TrecBlack
import com.trec.music.viewmodel.MusicViewModel

@Composable
fun BottomNavigationBar(navController: NavController, viewModel: MusicViewModel) {
    val activeColor = MaterialTheme.colorScheme.primary

    // Если доминантный цвет слишком темный, используем белый для активности
    val safeActiveColor = if (activeColor.luminance() < 0.3f) Color.White else activeColor

    // Плавное появление/исчезновение вкладок модулей
    val recorderWeight by animateFloatAsState(
        targetValue = if (viewModel.isRecorderFeatureEnabled) 1f else 0.001f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "recorderWeight"
    )
    val radioWeight by animateFloatAsState(
        targetValue = if (viewModel.isRadioEnabled) 1f else 0.001f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "radioWeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrecBlack.copy(alpha = 0.95f))
    ) {
        Divider(
            color = Color.White.copy(alpha = 0.1f),
            thickness = 0.5.dp,
            modifier = Modifier.fillMaxWidth()
        )

        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets.navigationBars
        ) {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavSlot(
                modifier = Modifier.weight(1f),
                item = BottomNavItem("Главная", "home", Icons.Filled.Home, Icons.Outlined.Home),
                visible = true,
                currentDestination = currentDestination,
                navController = navController,
                safeActiveColor = safeActiveColor
            )

            NavSlot(
                modifier = Modifier.weight(1f),
                item = BottomNavItem("Библиотека", "library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
                visible = true,
                currentDestination = currentDestination,
                navController = navController,
                safeActiveColor = safeActiveColor
            )

            NavSlot(
                modifier = Modifier.weight(recorderWeight),
                item = BottomNavItem("Запись", "recorder", Icons.Filled.Mic, Icons.Outlined.Mic),
                visible = viewModel.isRecorderFeatureEnabled,
                currentDestination = currentDestination,
                navController = navController,
                safeActiveColor = safeActiveColor
            )

            NavSlot(
                modifier = Modifier.weight(radioWeight),
                item = BottomNavItem("Радио", "radio", Icons.Filled.Radio, Icons.Outlined.Radio),
                visible = viewModel.isRadioEnabled,
                currentDestination = currentDestination,
                navController = navController,
                safeActiveColor = safeActiveColor
            )

            NavSlot(
                modifier = Modifier.weight(1f),
                item = BottomNavItem("Меню", "settings", Icons.Filled.Settings, Icons.Outlined.Settings),
                visible = true,
                currentDestination = currentDestination,
                navController = navController,
                safeActiveColor = safeActiveColor
            )
        }
    }
}

@Composable
private fun RowScope.NavSlot(
    modifier: Modifier,
    item: BottomNavItem,
    visible: Boolean,
    currentDestination: androidx.navigation.NavDestination?,
    navController: NavController,
    safeActiveColor: Color
) {
    val isSelected = currentDestination?.hierarchy?.any { it.route == item.route } == true

    NavigationBarItem(
        modifier = modifier,
        enabled = visible,
        selected = isSelected,
        onClick = {
            if (isSelected) {
                navController.navigate(item.route) {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            } else {
                navController.navigate(item.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    restoreState = true
                    launchSingleTop = true
                }
            }
        },
        icon = {
            Icon(
                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                contentDescription = item.name
            )
        },
        label = {
            Text(
                text = item.name,
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = safeActiveColor,
            selectedTextColor = safeActiveColor,
            indicatorColor = safeActiveColor.copy(alpha = 0.15f),
            unselectedIconColor = Color.Gray,
            unselectedTextColor = Color.Gray
        )
    )
}

data class BottomNavItem(
    val name: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)
