package com.example.myfirstapp.mapsources

import android.graphics.Bitmap
import com.amap.api.maps.model.Tile
import com.amap.api.maps.model.TileProvider
import java.io.ByteArrayOutputStream

/**
 * 高德引擎的瓦片 Provider：把 XYZ URL 模板图源接入高德地图。
 *
 * 像素级的下载/重投影/合成逻辑已抽到引擎无关的 [TileRasterizer]，
 * 本类只负责：目标网格固定 GCJ-02（高德坐标系）+ 把像素编码为高德 [Tile]。
 *
 * ★ WGS84 图源（天地图/OpenTopoMap 等）不再经过本类 —— 它们由 **osmdroid 引擎原生渲染**
 *   （见 MapSource.engineKind 路由），无需逐像素重投影。本类现在只服务于高德引擎上的
 *   GCJ02 / BD09 瓦片图源：GCJ02 同网格直接下载，BD09 仍需逐像素重投影。
 *
 * 高德 SDK 在子线程调用 getTile（其自带 UrlTileProvider 即阻塞网络下载），允许阻塞。
 */
class CustomTileProvider(
    private val source: MapSource,
    private val tkProvider: () -> String
) : TileProvider {

    override fun getTileWidth() = TileRasterizer.TILE
    override fun getTileHeight() = TileRasterizer.TILE

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        if (zoom < source.minZoom || zoom > source.maxZoom) return TileProvider.NO_TILE
        // 复合图层（source.layers）或多模板；单模板退化为 [urlTemplate]
        val templates = if (source.layers.isNotEmpty()) source.layers
        else if (source.urlTemplate.isNotBlank()) listOf(source.urlTemplate)
        else return TileProvider.NO_TILE
        val pixels = TileRasterizer.rasterize(
            templates, x, y, zoom,
            sourceCrs = source.crs,
            targetCrs = TileCrs.GCJ02,            // 高德目标网格固定 GCJ-02
            subdomains = source.subdomains,
            headers = source.headers,
            cacheKeyPrefix = source.id,
            tkProvider = tkProvider
        ) ?: return TileProvider.NO_TILE
        return encode(pixels)
    }

    /** 把 ARGB 像素数组编码为 PNG 瓦片 */
    private fun encode(pixels: IntArray): Tile {
        val bmp = TileRasterizer.toBitmap(pixels)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        return Tile.obtain(TileRasterizer.TILE, TileRasterizer.TILE, bos.toByteArray())
    }
}
