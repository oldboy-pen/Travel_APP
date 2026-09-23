package com.example.myfirstapp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.myfirstapp.ui.screens.HomeScreen
import com.example.myfirstapp.ui.screens.MapScreen
import com.example.myfirstapp.ui.screens.RecordScreen
import com.example.myfirstapp.ui.screens.TrackDetailScreen
import com.example.myfirstapp.ui.screens.TrackHistoryScreen

/** 底部导航 Tab 定义 */
private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("home", "首页", Icons.Default.List),
    TabItem("map", "地图", Icons.Default.LocationOn),
    TabItem("record", "运动", Icons.Default.Hiking),
    TabItem("history", "我的", Icons.Default.Route)
)

/**
 * 应用根骨架：底部导航（4 Tab）+ Navigation Compose
 * 详情页（轨迹详情）为全屏页，不显示底部导航栏
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen(onOpenTrack = { id -> navController.navigateToTrackDetail(id) }) }
            composable("map") { MapScreen() }
            composable("record") {
                RecordScreen(onTrackSaved = { id -> navController.navigateToTrackDetail(id) })
            }
            composable("history") {
                TrackHistoryScreen(onOpenTrack = { id -> navController.navigateToTrackDetail(id) })
            }
            composable("track/{id}") { entry ->
                TrackDetailScreen(
                    trackId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/** 跳转轨迹详情并清掉返回栈中的记录页（避免返回时又回到记录页） */
private fun NavHostController.navigateToTrackDetail(id: String) {
    navigate("track/$id") {
        popUpTo("record") { inclusive = true }
        launchSingleTop = true
    }
}
