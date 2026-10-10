package com.example.myfirstapp.map

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.myfirstapp.mapsources.MapSource
import com.example.myfirstapp.mapsources.MapSourceStore
import com.example.myfirstapp.offline.OfflineRegionStore

/**
 * 一屏地图的会话状态（由 [rememberMapSurfaceState] 创建，[MapSurface] 填充 engine）。
 *
 * 地图页所有"画/移动"操作都要通过这里拿到的 [engine]，
 * 相当于老代码里那个全局唯一的 AMap 实例。
 */
class MapSurfaceState internal constructor() {
    /** 当前生效的地图引擎；引擎随底图厂商切换，可能为 null（首帧还没绑上时） */
    var engine: MapEngine? by mutableStateOf(null)
        internal set
}

/** 创建一个跟随某个页面的地图状态 */
@Composable
fun rememberMapSurfaceState(pageKey: String): MapSurfaceState =
    remember(pageKey) { MapSurfaceState() }

/**
 * 地图容器：根据 MapSourceStore 里当前选的底图，自动选用对应厂商的原生地图。
 *
 * 内部负责四件事：
 * 1. 决定用哪家引擎（含"Key 没配置就回落高德"的保护）；
 * 2. 从 [MapEnginePool] 取（或首次创建）引擎实例并处理生命周期；
 * 3. 图源/叠加层变化时通知引擎刷新底图；
 * 4. 把长按、手势、定位回调以 [GeoPoint] 的形式抛给调用方。
 *
 * @param pageKey 页面键（"map"/"record"/"trackDetail"），决定实例池里复用的哪一个地图
 * @param overlay 叠在地图之上的 Compose 内容（图层按钮、自定义定位 FAB 等）
 */
@Composable
fun MapSurface(
    state: MapSurfaceState,
    pageKey: String,
    modifier: Modifier = Modifier,
    uiSettings: MapUiSettings = MapUiSettings(),
    onLongClick: ((GeoPoint) -> Unit)? = null,
    onUserGesture: (() -> Unit)? = null,
    onLocationChange: ((GeoPoint) -> Unit)? = null,
    overlay: @Composable (BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Key 有没有配要在 MapEngineKeys.init 之后才算数：首帧 appContext 可能还没绑定，
    // 会把已经配好的厂商误判成"未配置"而一直回落高德。这里用一个状态位在 init 完成后
    // 触发一次重组，保证解析结果与最终 applyBase 用的是同一套判断。
    var keysReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        MapSourceStore.ensureLoaded(context)
        // 离线区域清单：没加载过时引擎会以为"这个图源没有离线数据"，从而一直走在线 provider
        OfflineRegionStore.ensureLoaded(context)
        MapEngineKeys.init(context)
        keysReady = true
    }

    // 读取图源状态（MapSourceStore 内部是 Compose State，会自动触发重组）
    val revision = MapSourceStore.revision
    // 离线区域增删 / 离线模式开关：两者都会改变瓦片 provider 链，必须重刷底图
    val offlineRevision = OfflineRegionStore.revision
    val rawBase = MapSourceStore.activeBase()
    val rawOverlay = MapSourceStore.activeOverlay()

    // Key 没配就把这一屏回落成高德，避免用户看到空白+鉴权水印
    val base: MapSource =
        if (MapEngineKeys.isConfigured(rawBase.engineKind)) rawBase
        else MapSource.find("amap.normal")!!
    val kind = base.engineKind
    // 叠加层只在「它的坐标系引擎 == 当前底图引擎」时才应用：
    // - WGS84 叠加层（天地图标注/等高线/OpenTopoMap）只在 osmdroid 底图上渲染，不再到高德上逐像素重投影；
    // - GCJ02/BD09 叠加层只在高德底图上渲染（腾讯/百度引擎本就不支持第三方瓦片叠加，原为空实现）。
    val resolvedOverlay =
        if (rawOverlay == null || rawOverlay.engineKind == kind) rawOverlay
        else null

    // 引擎创建失败（比如某家 SDK 初始化抛异常）时回落高德，绝不让"切个图源"把整屏搞崩。
    // 高德是唯一必配的厂商，回落它至少还能出图。
    val engine = remember(pageKey, kind) {
        runCatching { MapEnginePool.get(pageKey, kind, context) }
            .getOrElse { MapEnginePool.get(pageKey, MapEngineKind.AMAP, context) }
    }

    LaunchedEffect(engine) { state.engine = engine }

    // ---- 生命周期：切走只 pause（各 SDK 都不扛频繁 destroy/create）----
    DisposableEffect(engine, lifecycleOwner) {
        engine.onStart()
        engine.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> engine.onStart()
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                Lifecycle.Event.ON_PAUSE -> engine.onPause()
                Lifecycle.Event.ON_STOP -> engine.onStop()
                Lifecycle.Event.ON_DESTROY -> MapEnginePool.destroyPage(pageKey)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { engine.onPause() }
        }
    }

    Box(modifier = modifier) {
        // ★ key 至关重要（坑）：
        //   AndroidView 内部是 ComposeNode，factory 只在节点【首次创建】时调用一次，
        //   之后无论重组多少次都不会重建。切图源换了厂商（engine 变了）如果不换 key，
        //   画面会一直贴着上一个厂商的 View，而业务层却在调用新引擎 —— 地图直接失灵。
        //   用 (页面 + 厂商) 做 key：换厂商时销毁旧节点、按新 factory 挂新厂商的 MapView。
        key(pageKey, kind) {
            AndroidView(
                factory = {
                    // ★ 挂载前先解绑：池化复用的 View 可能还挂在上一任父容器上。
                    // Compose 在 key 变化时是先插入新节点、再释放旧节点，旧节点的
                    // onRelease 未必赶在这之前执行；此时直接 addView 会抛
                    // "The specified child already has a parent"（adb logcat 实证），
                    // 整屏崩溃。这里主动摘一次，让挂载永远从干净状态开始。
                    (engine.view.parent as? android.view.ViewGroup)?.removeView(engine.view)
                    engine.view
                },
                modifier = Modifier.matchParentSize(),
                onRelease = { view ->
                    // ★★ 顺序至关重要：必须【先暂停引擎】，再从视图树里摘掉 View。
                    //
                    // 背景（"记录中从高德切到天地图必闪退"的根因）：
                    //   AndroidView 节点因 key 变化被移除时，会走到 View.onDetachedFromWindow。
                    //   高德的 TextureMapView 是 GLSurfaceView/TextureView 渲染， detach 时
                    //   其 GL 渲染线程会被动接触已经失效的窗口资源；如果此刻地图仍处于
                    //   resume（渲染循环在跑），native 层就会访问已释放的上下文 →
                    //   SIGSEGV / SIGABRT，整个进程直接闪退（Java 层 try/catch 拦不住）。
                    //   记录中每 2 秒重绘一次轨迹线，渲染循环几乎时刻忙碌，命中率极高；
                    //   静止时 GL 线程空闲，detach 反而侥幸不崩 —— 表现就是「记录中切换才崩」。
                    //
                    //   反方向（天地图 → 高德）不崩，是因为被摘掉的是 osmdroid 的 MapView：
                    //   它是纯 Canvas 2D 渲染，没有 GL 线程，detach 前不 pause 也不会炸。
                    //   两侧不对称正好印证了这一点。
                    //
                    // 注意：onRelease 是在 Compose 把 View 摘出视图树【之前】回调的，
                    // 因此这里补的这次 onPause 能赶在 detach 之前生效。各家 SDK 的
                    // onPause 都是幂等的，后面 DisposableEffect 再调一次也不会有问题。
                    runCatching { engine.onPause() }
                    // 从视图树摘除时确保与父容器解绑，避免下次 attach 抛
                    // "child already has a parent" / 多次 attach 状态错乱
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                }
            )
        }
        overlay?.invoke(this)
    }

    // ---- 图源 / 叠加层变化 → 重建底图 ----
    // 换图源是用户高频操作，瓦片源构造/叠加层切换一旦抛异常不该带崩整屏，
    // 这里兜住异常，保证"最多这一层没画出来"，进程还活着。
    LaunchedEffect(engine, revision, offlineRevision, keysReady) {
        runCatching { engine.applyBase(base) }
        runCatching { engine.applyOverlay(resolvedOverlay) }
    }

    // ---- 控件开关 ----
    LaunchedEffect(engine, uiSettings) {
        engine.setUiSettings(uiSettings)
    }

    // ---- 三个回调：用 rememberUpdatedState 包一层，避免每次重组都重新注册监听 ----
    val currentLongClick by rememberUpdatedState(onLongClick)
    val currentGesture by rememberUpdatedState(onUserGesture)
    val currentLocation by rememberUpdatedState(onLocationChange)

    LaunchedEffect(engine) {
        engine.setLongClickListener { point -> currentLongClick?.invoke(point) }
        engine.setUserGestureListener { currentGesture?.invoke() }
        engine.setLocationChangeListener { point -> currentLocation?.invoke(point) }
    }
}
