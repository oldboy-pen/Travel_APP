package com.example.myfirstapp.offline

import android.content.Context
import org.osmdroid.tileprovider.IRegisterReceiver
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.IArchiveFile
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileFileArchiveProvider
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.modules.MapTileSqlCacheProvider
import org.osmdroid.tileprovider.modules.MBTilesFileArchive
import org.osmdroid.tileprovider.modules.NetworkAvailabliltyCheck
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/**
 * osmdroid 的「离线优先」瓦片提供器。
 *
 * ### 为什么必须自己组装 provider 链
 * osmdroid 自带的两个现成方案都不合用：
 * - `MapTileProviderBasic`：在线为主，离线存档（osmdroid/ 目录下的 zip/mbtiles）排在缓存之后、
 *   网络之前，但它找存档是靠**扫描固定目录**，而我们的存档按区域分散存放且随时增删；
 * - `OfflineTileProvider`：只读存档、完全不联网 —— 没覆盖到的区域全白，且不支持"有网时补全"。
 *
 * 所以这里用 `MapTileProviderArray` 手工排一条链：
 *
 * ```
 * 内存/磁盘缓存 → MBTiles 离线存档 → 近似瓦片（用低级别放大）→ 在线下载
 * ```
 *
 * 请求按链顺序找，第一个给得出瓦片的就返回：
 * - 有网且瓦片在离线区 → **存档命中，不耗流量**；
 * - 有网且瓦片不在离线区 → 走到在线下载，照常出图；
 * - 无网 → 走到存档/近似，最多糊一点，但不会白屏。
 *
 * ★ 近似层（[MapTileApproximater]）放在在线下载**之前**，是"离线优先"的关键：
 *   它负责在没有本级瓦片时，用上一级瓦片放大顶上（比如离线只下了 z14，用户放大到 z16，
 *   画面是糊的但不是空白）。反过来放就变成"先去联网拿，拿不到才用糊的"。
 *
 * ### 离线模式
 * [offlineOnly] 为真时调 `setUseDataConnection(false)`，`MapTileProviderArray` 会自动跳过
 * 所有 `getUsesDataConnection() == true` 的 provider（即在线下载），实现彻底的"零流量"。
 */
object OfflineTileProviders {

    /**
     * @param archives 该图源已就绪的 MBTiles 存档（可为空 → 退化为普通的在线 provider）
     * @param offlineOnly true=完全不联网（飞行模式 / 省流量 / 验证离线覆盖率）
     */
    fun create(
        ctx: Context,
        tileSource: ITileSource,
        archives: Array<IArchiveFile>,
        offlineOnly: Boolean
    ): MapTileProviderArray {
        val receiver: IRegisterReceiver = SimpleRegisterReceiver(ctx)
        val writer: IFilesystemCache = SqlTileWriter()
        val cacheProvider = MapTileSqlCacheProvider(receiver, tileSource)
        val archiveProvider = MapTileFileArchiveProvider(receiver, tileSource, archives)
        val approximater = MapTileApproximater().apply {
            addProvider(cacheProvider)
            addProvider(archiveProvider)
        }
        val downloader = MapTileDownloader(tileSource, writer, NetworkAvailabliltyCheck(ctx))

        val provider = OfflineAwareProvider(
            tileSource, receiver,
            arrayOf(cacheProvider, archiveProvider, approximater, downloader),
            writer
        )
        provider.setUseDataConnection(!offlineOnly)
        return provider
    }

    /** 把一组区域存档文件打开成 osmdroid 的 IArchiveFile（只读，osmdroid 官方实现） */
    fun openArchives(files: List<File>): Array<IArchiveFile> {
        val out = ArrayList<IArchiveFile>(files.size)
        for (f in files) {
            if (!f.exists()) continue
            val a = runCatching { MBTilesFileArchive.getDatabaseFileArchive(f) }.getOrNull()
            if (a != null) out.add(a)
        }
        return out.toTypedArray()
    }
}

/**
 * [MapTileProviderArray] 的薄子类，只补三件事：
 * 1. `getTileWriter()` 基类返回 null，这里返回真正的 writer（在线下载后要写缓存）；
 * 2. detach 时关掉 writer（基类不管，会泄漏 SQLite 连接）；
 * 3. 关掉数据连接时进入"降级模式"：允许直接用缓存里的过期瓦片，避免无网时连旧图都没有。
 */
private class OfflineAwareProvider(
    tileSource: ITileSource,
    receiver: IRegisterReceiver,
    providers: Array<MapTileModuleProviderBase>,
    private val writer: IFilesystemCache
) : MapTileProviderArray(tileSource, receiver, providers) {

    override fun getTileWriter(): IFilesystemCache = writer

    override fun detach() {
        runCatching { writer.onDetach() }
        super.detach()
    }

    override fun isDowngradedMode(pMapTileIndex: Long): Boolean = !useDataConnection()
}
