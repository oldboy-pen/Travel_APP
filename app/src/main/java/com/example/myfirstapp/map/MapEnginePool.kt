package com.example.myfirstapp.map

import android.content.Context

/**
 * 地图实例池：按「页面 + 厂商」复用原生地图 View。
 *
 * 背景（沿用原 AMapViewPool 的踩坑结论）：
 * - 三家 SDK 都不扛会话内频繁 销毁-重建：高德在 MapView.onDestroy 后重建时
 *   GLMapEngine.nativeDestroy 直接 SIGABRT 崩溃；腾讯/百度同样建议长期持有。
 * - 因此这里的策略是：**只创建、不销毁**（进程存活期间一直留着），
 *   切 Tab 时只 onPause()，只有 Activity 真正 ON_DESTROY 才 destroy 并清出池。
 *
 * 池的规模上界 = 页面数(3) × 厂商数(3) = 9 个 View，只有用户真的在某一屏
 * 切到某一厂商时才会创建（懒加载），常驻一般就 3 个。
 */
object MapEnginePool {

    private class Entry(val engine: MapEngine) {
        var created = false
        var destroyed = false
    }

    private val pool = mutableMapOf<String, Entry>()

    private fun keyOf(pageKey: String, kind: MapEngineKind) = "$pageKey|${kind.name}"

    /** 惰性创建 + onCreate（幂等） */
    fun get(pageKey: String, kind: MapEngineKind, context: Context): MapEngine {
        val k = keyOf(pageKey, kind)
        // 已经销毁过的实例绝不再交出去（销毁后内部资源已被置空，复用会直接崩）
        val old = pool[k]
        val e = if (old != null && !old.destroyed) old else {
            Entry(createEngine(kind, context.applicationContext)).also { pool[k] = it }
        }
        if (!e.created && !e.destroyed) {
            e.engine.onCreate()
            e.created = true
        }
        return e.engine
    }

    private fun createEngine(kind: MapEngineKind, ctx: Context): MapEngine = when (kind) {
        MapEngineKind.AMAP -> AMapEngine(ctx)
        MapEngineKind.TENCENT -> TencentMapEngine(ctx)
        MapEngineKind.BAIDU -> BaiduMapEngine(ctx)
        MapEngineKind.OSMDROID -> OsmdroidEngine(ctx)
    }

    /** Activity 销毁：释放某个页面持有的全部地图实例 */
    fun destroyPage(pageKey: String) {
        val keys = pool.keys.filter { it.substringBefore('|') == pageKey }
        keys.forEach { key ->
            pool.remove(key)?.let { e ->
                if (!e.destroyed) {
                    e.destroyed = true
                    runCatching { e.engine.onDestroy() }
                }
            }
        }
    }

    /** 全量释放（一般不用，进程结束才需要） */
    fun destroyAll() {
        pool.values.forEach { e ->
            if (!e.destroyed) {
                e.destroyed = true
                runCatching { e.engine.onDestroy() }
            }
        }
        pool.clear()
    }
}
