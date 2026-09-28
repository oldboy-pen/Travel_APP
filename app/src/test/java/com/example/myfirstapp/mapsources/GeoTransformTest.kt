package com.example.myfirstapp.mapsources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * GeoTransform 单元测试。
 *
 * 基准数据来源（2026-09-26 实测）：
 * - 腾讯瓦片 z7 x=105 y=49（标准 XYZ）→ 下载到北京一带影像，验证 Web 墨卡托换算；
 * - 百度瓦片 z7 x=24 y=9（百度网格）→ 下载到含北京市区的路网图，
 *   验证 GCJ→BD09→BD09MC→百度网格整条链路；
 * - wgs→gcj 基准值取自 eviltransform 等开源库的经典测试用例。
 */
class GeoTransformTest {

    @Test
    fun `wgs84 to gcj02 canonical values`() {
        val gcj = GeoTransform.wgs84ToGcj02(116.404, 39.915)
        assertEquals(116.41024449983831, gcj[0], 1e-6)
        assertEquals(39.91640428150764, gcj[1], 1e-6)
    }

    @Test
    fun `gcj02 to wgs84 roundtrip`() {
        val wgs = doubleArrayOf(116.404, 39.915)
        val gcj = GeoTransform.wgs84ToGcj02(wgs[0], wgs[1])
        val back = GeoTransform.gcj02ToWgs84(gcj[0], gcj[1])
        assertTrue(abs(back[0] - wgs[0]) < 1e-6)
        assertTrue(abs(back[1] - wgs[1]) < 1e-6)
    }

    @Test
    fun `out of china passes through`() {
        val r = GeoTransform.wgs84ToGcj02(-122.4194, 37.7749)
        assertEquals(-122.4194, r[0], 1e-12)
        assertEquals(37.7749, r[1], 1e-12)
    }

    @Test
    fun `gcj02 to bd09`() {
        // 116.404,39.915 → 约 116.41038, 39.92133（手算基准，容差放宽）
        val bd = GeoTransform.gcj02ToBd09(116.404, 39.915)
        assertEquals(116.41038, bd[0], 5e-5)
        assertEquals(39.92133, bd[1], 5e-5)
    }

    @Test
    fun `baidu tile of beijing z7 is 24_9`() {
        // 实测：百度瓦片 z7 x=24 y=9 覆盖北京市区（含★北京标记）
        val bd = GeoTransform.gcj02ToBd09(116.404, 39.915)
        val tile = GeoTransform.baiduTileOf(bd[0], bd[1], 7)
        assertEquals(24, tile[0])
        assertEquals(9, tile[1])
    }

    @Test
    fun `standard xyz tile of beijing z7 is 105_48`() {
        // 实测：腾讯瓦片 z7 x=105 标准 y=48 → TMS 79 覆盖北京一带；
        // （此前下载验证的 y=49/TMS78 是保定-德州一带，与该行换算一致）
        val px = GeoTransform.latLngToGlobalPixel(39.915, 116.404, 7)
        assertEquals(105, kotlin.math.floor(px[0] / 256).toInt())
        assertEquals(48, kotlin.math.floor(px[1] / 256).toInt())
    }

    @Test
    fun `tms y inversion`() {
        // 实测：腾讯 z7 标准 y=49 → TMS y=78 请求成功（保定-德州一带影像）
        assertEquals(78, GeoTransform.tmsY(49, 7))
        // 北京所在标准 y=48 → TMS 79
        assertEquals(79, GeoTransform.tmsY(48, 7))
        // 反演：再次反转应还原
        assertEquals(49, GeoTransform.tmsY(GeoTransform.tmsY(49, 7), 7))
    }

    @Test
    fun `tile pixel to latlng inverts global pixel`() {
        val px = GeoTransform.latLngToGlobalPixel(30.5, 110.25, 12)
        val tileX = kotlin.math.floor(px[0] / 256).toInt()
        val tileY = kotlin.math.floor(px[1] / 256).toInt()
        val inX = (px[0] - tileX * 256).toInt()
        val inY = (px[1] - tileY * 256).toInt()
        val ll = GeoTransform.tilePixelToLatLng(tileX, tileY, 12, inX, inY)
        // 像素坐标取整丢失小数部分，1 像素 @z12 ≈ 3.4e-4 度，容差放宽到 1e-3
        assertEquals(110.25, ll[0], 1e-3)
        assertEquals(30.5, ll[1], 1e-3)
    }

    @Test
    fun `baidu negative tile coord uses M prefix`() {
        assertEquals("M3", GeoTransform.baiduTileCoord(-3))
        assertEquals("3", GeoTransform.baiduTileCoord(3))
    }
}
