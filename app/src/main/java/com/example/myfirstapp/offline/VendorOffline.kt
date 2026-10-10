package com.example.myfirstapp.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.amap.api.maps.offlinemap.OfflineMapCity
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.baidu.mapapi.map.offline.MKOfflineMap
import com.baidu.mapapi.map.offline.MKOfflineMapListener
import com.baidu.mapapi.map.offline.MKOLSearchRecord
import com.baidu.mapapi.map.offline.MKOLUpdateElement

/**
 * 厂商官方离线城市包（高德 / 百度）。
 *
 * ### 与自建 MBTiles 的关系
 * 这是**第二条**离线通道，和自建瓦片下载器互补而不是替代：
 * - 高德/百度底图由它们自己的 SDK 渲染，瓦片格式与 URL 都不公开，我们的下载器抓不到；
 * - 但两家都提供"按城市下载离线包"的官方能力，且**下载后 SDK 自动优先用离线数据**，
 *   渲染侧完全不用我们操心 —— 这里只做"下载管理"（列表/开始/暂停/删除）。
 *
 * 所以自建通道服务的是**瓦片图源**（天地图/OpenTopoMap/自定义 XYZ），
 * 官方通道服务的是**厂商原生底图**（高德矢量卫星 / 百度街道卫星）。
 *
 * ### 使用注意
 * 高德的 [OfflineMapManager] 构造会抛异常（SDK 未初始化 / 鉴权失败），
 * 且首次调用 `getOfflineMapCityList()` 常常是空的（要等 `onVerifyComplete` 回调），
 * 所以这里统一在 [ensureLoaded] 里做兜底，失败时对应厂商整块不可用，不影响另一家。
 */
object VendorOffline {

    /** 厂商标识 */
    const val VENDOR_AMAP = "amap"
    const val VENDOR_BAIDU = "baidu"

    /** 高德：全部城市（含已下载/下载中），按拼音排序 */
    var amapCities by mutableStateOf<List<VendorCity>>(emptyList())
        private set

    /** 百度：热门城市 + 已下载城市 */
    var baiduCities by mutableStateOf<List<VendorCity>>(emptyList())
        private set

    /** 某个厂商是否可用（SDK 初始化/鉴权失败时为 false） */
    var amapReady by mutableStateOf(false)
        private set
    var baiduReady by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var inited = false
    private var amapManager: OfflineMapManager? = null
    private var baiduManager: MKOfflineMap? = null

    fun ensureLoaded(context: Context) {
        OfflineStorage.init(context)
        if (inited) return
        synchronized(this) {
            if (inited) return
            initAmap(context.applicationContext)
            initBaidu(context.applicationContext)
            inited = true
        }
    }

    // ==================== 高德 ====================

    private fun initAmap(ctx: Context) {
        val manager = runCatching {
            OfflineMapManager(ctx, object : OfflineMapManager.OfflineMapDownloadListener {
                override fun onDownload(status: Int, completeCode: Int, cityName: String) {
                    refreshAmap()
                }

                override fun onCheckUpdate(hasNew: Boolean, cityName: String) {
                    refreshAmap()
                }

                override fun onRemove(success: Boolean, cityName: String, msg: String) {
                    refreshAmap()
                }
            })
        }.getOrElse {
            error = "高德离线地图不可用：${it.message}"
            amapReady = false
            return
        }
        amapManager = manager
        manager.setOnOfflineLoadedListener {
            // 首次校验完成，这时城市列表才真正可用
            amapReady = true
            refreshAmap()
        }
        amapReady = true
        refreshAmap()
    }

    private fun refreshAmap() {
        val m = amapManager ?: return
        val done = runCatching { m.downloadOfflineMapCityList }.getOrDefault(ArrayList())
        val doing = runCatching { m.downloadingCityList }.getOrDefault(ArrayList())
        val all = runCatching { m.offlineMapCityList }.getOrDefault(ArrayList())

        val byName = HashMap<String, OfflineMapCity>()
        all.forEach { byName[it.city] = it }
        done.forEach { byName[it.city] = it }
        doing.forEach { byName[it.city] = it }

        amapCities = byName.values.map { c ->
            VendorCity(
                vendor = VENDOR_AMAP,
                id = c.code ?: c.city,
                name = c.city ?: "",
                sizeBytes = runCatching { c.size }.getOrDefault(0L),
                status = mapAmapStatus(runCatching { c.state }.getOrDefault(OfflineMapStatus.SUCCESS)),
                // ★ 高德的 getter 叫 getcompleteCode()（小写 c），Kotlin 不会把它合成属性，
                //   必须按方法调用；写成 c.completeCode 是编译不过的
                progress = runCatching { c.getcompleteCode() }.getOrDefault(0)
            )
        }.sortedBy { it.name }
    }

    /**
     * 高德状态码 → 统一状态。
     * ★ 用 if/else 而不是 when：OfflineMapStatus 的常量是我们无法控制的 SDK 常量，
     *   万一有两个取值相同，`when` 会直接编译失败（Duplicate label），if/else 没这个风险。
     */
    private fun mapAmapStatus(state: Int): VendorStatus {
        if (state == OfflineMapStatus.SUCCESS || state == OfflineMapStatus.NEW_VERSION) {
            return VendorStatus.DONE
        }
        if (state == OfflineMapStatus.LOADING || state == OfflineMapStatus.UNZIP) {
            return VendorStatus.DOWNLOADING
        }
        if (state == OfflineMapStatus.WAITING || state == OfflineMapStatus.CHECKUPDATES) {
            return VendorStatus.WAITING
        }
        if (state == OfflineMapStatus.STOP || state == OfflineMapStatus.PAUSE) {
            return VendorStatus.PAUSED
        }
        if (state == OfflineMapStatus.ERROR ||
            state == OfflineMapStatus.EXCEPTION_AMAP ||
            state == OfflineMapStatus.EXCEPTION_NETWORK_LOADING ||
            state == OfflineMapStatus.EXCEPTION_SDCARD ||
            state == OfflineMapStatus.START_DOWNLOAD_FAILD
        ) {
            return VendorStatus.ERROR
        }
        return VendorStatus.AVAILABLE
    }

    // ==================== 百度 ====================

    private fun initBaidu(ctx: Context) {
        val m = MKOfflineMap()
        val ok = runCatching {
            m.init(object : MKOfflineMapListener {
                override fun onGetOfflineMapState(type: Int, state: Int) {
                    refreshBaidu()
                }
            })
        }.getOrDefault(false)
        if (!ok) {
            // 百度 SDK 未初始化（没配 Key / 没调 SDKInitializer）时整块不可用，不影响高德
            baiduReady = false
            return
        }
        baiduManager = m
        baiduReady = true
        refreshBaidu()
    }

    private fun refreshBaidu() {
        val m = baiduManager ?: return
        val updated = runCatching { m.allUpdateInfo }.getOrNull().orEmpty()
        val hot = runCatching { m.hotCityList }.getOrNull().orEmpty()
        val offline = runCatching { m.offlineCityList }.getOrNull().orEmpty()

        val map = LinkedHashMap<Int, VendorCity>()
        fun putSearch(r: MKOLSearchRecord) {
            map[r.cityID] = VendorCity(
                vendor = VENDOR_BAIDU,
                id = r.cityID.toString(),
                name = r.cityName ?: "",
                sizeBytes = r.dataSize,
                status = VendorStatus.AVAILABLE,
                progress = 0,
                cityIdInt = r.cityID
            )
        }
        hot.forEach { putSearch(it) }
        offline.forEach { putSearch(it) }
        for (e: MKOLUpdateElement in updated) {
            map[e.cityID] = VendorCity(
                vendor = VENDOR_BAIDU,
                id = e.cityID.toString(),
                name = e.cityName ?: "",
                sizeBytes = e.serversize.toLong() * 1024L,
                status = mapBaiduStatus(e.status),
                progress = e.ratio,
                cityIdInt = e.cityID
            )
        }
        baiduCities = map.values.sortedBy { it.name }
    }

    /** 百度状态码 → 统一状态（同样用 if/else 规避 when 的重复标签风险） */
    private fun mapBaiduStatus(status: Int): VendorStatus {
        if (status == MKOLUpdateElement.FINISHED) return VendorStatus.DONE
        if (status == MKOLUpdateElement.DOWNLOADING ||
            status == MKOLUpdateElement.eOLDSInstalling
        ) return VendorStatus.DOWNLOADING
        if (status == MKOLUpdateElement.WAITING) return VendorStatus.WAITING
        if (status == MKOLUpdateElement.SUSPENDED) return VendorStatus.PAUSED
        if (status == MKOLUpdateElement.eOLDSMd5Error ||
            status == MKOLUpdateElement.eOLDSNetError ||
            status == MKOLUpdateElement.eOLDSIOError ||
            status == MKOLUpdateElement.eOLDSWifiError ||
            status == MKOLUpdateElement.eOLDSFormatError
        ) return VendorStatus.ERROR
        return VendorStatus.AVAILABLE
    }

    // ==================== 操作 ====================

    fun download(city: VendorCity) {
        error = null
        when (city.vendor) {
            VENDOR_AMAP -> runCatching {
                // 有 code 用 code，没有（部分老数据）退回城市名
                amapManager?.downloadByCityCode(city.id)
            }.onFailure { error = "高德下载失败：${it.message}" }
            VENDOR_BAIDU -> runCatching {
                baiduManager?.start(city.cityIdInt)
            }.onFailure { error = "百度下载失败：${it.message}" }
        }
        refreshAll()
    }

    fun pause(city: VendorCity) {
        when (city.vendor) {
            VENDOR_AMAP -> runCatching { amapManager?.pauseByName(city.name) }
            VENDOR_BAIDU -> runCatching { baiduManager?.pause(city.cityIdInt) }
        }
        refreshAll()
    }

    fun remove(city: VendorCity) {
        when (city.vendor) {
            VENDOR_AMAP -> runCatching { amapManager?.remove(city.name) }
            VENDOR_BAIDU -> runCatching { baiduManager?.remove(city.cityIdInt) }
        }
        refreshAll()
    }

    fun refreshAll() {
        refreshAmap()
        refreshBaidu()
    }

    /** 已下载完成的城市（两个厂商合并），用于"离线覆盖概览" */
    fun downloadedCities(): List<VendorCity> =
        (amapCities + baiduCities).filter { it.status == VendorStatus.DONE }

    /** 释放（页面退出时调用；不释放 SDK 也不会崩，但会占着下载线程） */
    fun destroy() {
        runCatching { amapManager?.destroy() }
        runCatching { baiduManager?.destroy() }
        amapManager = null
        baiduManager = null
        inited = false
    }
}

/** 一个厂商离线城市 */
data class VendorCity(
    val vendor: String,
    /** 城市标识：高德用 adcode，百度用 cityID（转字符串） */
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val status: VendorStatus,
    val progress: Int,
    /** 百度需要的整型 cityID（高德为 0） */
    val cityIdInt: Int = 0
) {
    val vendorLabel: String
        get() = if (vendor == VendorOffline.VENDOR_AMAP) "高德" else "百度"
}

/** 离线城市包状态 */
enum class VendorStatus(val label: String) {
    /** 未下载 */
    AVAILABLE("未下载"),

    /** 排队中 */
    WAITING("排队中"),

    /** 下载中 */
    DOWNLOADING("下载中"),

    /** 已暂停 */
    PAUSED("已暂停"),

    /** 已完成 */
    DONE("已完成"),

    /** 出错 */
    ERROR("失败")
}
