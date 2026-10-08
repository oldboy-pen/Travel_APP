package com.example.myfirstapp.ui.navigation

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myfirstapp.track.RecorderState
import com.example.myfirstapp.track.TrackRecorder
import com.example.myfirstapp.ui.screens.CloudAuthScreen
import com.example.myfirstapp.ui.screens.HomeScreen
import com.example.myfirstapp.ui.screens.LoginScreen
import com.example.myfirstapp.ui.screens.CoordinateConverterScreen
import com.example.myfirstapp.ui.screens.FootprintActivitiesScreen
import com.example.myfirstapp.ui.screens.FootprintPhotosScreen
import com.example.myfirstapp.ui.screens.FootprintTracksScreen
import com.example.myfirstapp.ui.screens.MapScreen
import com.example.myfirstapp.ui.screens.PaceConverterScreen
import com.example.myfirstapp.ui.screens.RecordScreen
import com.example.myfirstapp.ui.screens.TrackNavigationScreen
import com.example.myfirstapp.ui.screens.RegisterScreen
import com.example.myfirstapp.ui.screens.TodoScreen
import com.example.myfirstapp.ui.screens.TrackSourceViewScreen
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
    // 记录中隐藏底部导航栏（「运动」页进入记录态时让出整屏）
    val isRecording = TrackRecorder.data.collectAsStateWithLifecycle().value.state != RecorderState.IDLE
    val showBottomBar = currentRoute in tabs.map { it.route } && !(currentRoute == "record" && isRecording)

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
                RecordScreen(
                    onTrackSaved = { id -> navController.navigateToTrackDetail(id) },
                    onNavigate = { id -> navController.navigate("nav/$id") }
                )
            }
            composable("history") {
                TrackHistoryScreen(
                    onOpenAuth = { navController.navigate("login") },
                    onOpenCloudAuth = { navController.navigate("cloudAuth") },
                    onOpenTracks = { navController.navigate("footprintTracks") },
                    onOpenPhotos = { navController.navigate("footprintPhotos") },
                    onOpenActivities = { navController.navigate("footprintActivities") },
                    onOpenTodo = { navController.navigate("todo") },
                    onOpenPace = { navController.navigate("pace") },
                    onOpenCoord = { navController.navigate("coord") }
                )
            }
            // ---- 「我的 → 足迹」三个子页（各自独立页，带返回键） ----
            composable("footprintTracks") {
                FootprintTracksScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTrackSource = { id -> navController.navigate("trackView/$id") }
                )
            }
            composable("footprintPhotos") {
                FootprintPhotosScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTrackSource = { id -> navController.navigate("trackView/$id") }
                )
            }
            composable("footprintActivities") {
                FootprintActivitiesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTrackSource = { id -> navController.navigate("trackView/$id") }
                )
            }
            // ---- 「我的 → 工具」三个小工具（全屏页） ----
            composable("todo") {
                TodoScreen(onBack = { navController.popBackStack() })
            }
            composable("pace") {
                PaceConverterScreen(onBack = { navController.popBackStack() })
            }
            composable("coord") {
                CoordinateConverterScreen(onBack = { navController.popBackStack() })
            }
            composable("cloudAuth") {
                CloudAuthScreen(onBack = { navController.popBackStack() })
            }
            composable("login") {
                LoginScreen(
                    onBack = { navController.popBackStack() },
                    onSuccess = { navController.popBackStack() },   // 登录成功退回「我的」
                    onGoRegister = { navController.navigate("register") }
                )
            }
            composable("register") {
                RegisterScreen(
                    onBack = { navController.popBackStack() },
                    // 注册入口固定是 我的 → 登录 → 注册，成功就把这两页一起弹掉
                    onSuccess = { navController.popBackStack("login", inclusive = true) },
                    onGoLogin = { navController.popBackStack() }
                )
            }
            // 轨迹查看：从「我的 → 足迹」点轨迹弹出，先看来源与摘要，自底向上滑入
            composable(
                route = "trackView/{id}",
                enterTransition = { slideInVertically(initialOffsetY = { it }) },
                exitTransition = { slideOutVertically(targetOffsetY = { it }) },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }) },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) }
            ) { entry ->
                TrackSourceViewScreen(
                    trackId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { id -> navController.navigate("track/$id") }
                )
            }
            composable("track/{id}") { entry ->
                TrackDetailScreen(
                    trackId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onNavigate = { id -> navController.navigate("nav/$id") }
                )
            }
            // 轨迹导航：沿已保存的轨迹行进，偏离超阈值语音预警（全屏页）
            composable("nav/{id}") { entry ->
                TrackNavigationScreen(
                    trackId = entry.arguments?.getString("id").orEmpty(),
                    onExit = { navController.popBackStack() }
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
