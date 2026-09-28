package com.example.myfirstapp.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 降噪过滤 GeoUtils.isNoise 的分模式行为测试：
 * - 徒步/登山（SENSITIVE）：1 米级小位移应被记录
 * - 其余模式（FAST）：保持历史过滤行为，防 GPS 噪声虚增里程
 */
class GeoUtilsNoiseFilterTest {

    private val last = TrackPoint(30.0, 120.0, 0L, 0.0, 0f)

    /** 与 last 相距约 d 米的正北方向纬度 */
    private fun latOffset(d: Double): Double {
        // 1 纬度 ≈ 111,194 米
        return 30.0 + d / 111_194.0
    }

    // ---------- 徒步/登山（SENSITIVE） ----------

    @Test
    fun sensitive_slow_walk_small_displacement_should_be_recorded() {
        // 徒步慢行：速度 0.8 m/s，位移 1.2 米，GPS 精度 10 米
        // 旧规则会因 1.2 < accuracy/2(5米) 被丢弃 —— 本次修改的核心目标场景
        val noise = GeoUtils.isNoise(
            last, latOffset(1.2), 120.0, speed = 0.8f, accuracy = 10f,
            profile = ActivityType.WALKING.profile
        )
        assertFalse("徒步时 1.2 米小位移不应被丢弃", noise)
    }

    @Test
    fun sensitive_climbing_below_old_speed_cutoff_should_be_recorded() {
        // 登山缓行：速度 0.6 m/s（旧规则 speed<1.0 一刀切判为静止），位移 2.5 米
        val noise = GeoUtils.isNoise(
            last, latOffset(2.5), 120.0, speed = 0.6f, accuracy = 8f,
            profile = ActivityType.HIKING.profile
        )
        assertFalse("登山缓行（0.6 m/s）的位移不应被当静止漂移丢弃", noise)
    }

    @Test
    fun sensitive_rest_stop_drift_should_be_dropped() {
        // 休息点：速度≈0（GPS 多普勒测速），漂移 2 米 —— 仍要丢弃，防虚增
        val noise = GeoUtils.isNoise(
            last, latOffset(2.0), 120.0, speed = 0.2f, accuracy = 8f,
            profile = ActivityType.HIKING.profile
        )
        assertTrue("休息点漂移（速度 0.2、位移 2 米）应被丢弃", noise)
    }

    @Test
    fun sensitive_sub_meter_jitter_should_be_dropped() {
        // 亚米级抖动：即使速度读数正常，<1 米的位移仍视为噪声
        val noise = GeoUtils.isNoise(
            last, latOffset(0.5), 120.0, speed = 1.0f, accuracy = 20f,
            profile = ActivityType.WALKING.profile
        )
        assertTrue("0.5 米抖动应被丢弃", noise)
    }

    @Test
    fun sensitive_gps_jump_should_be_dropped() {
        val noise = GeoUtils.isNoise(
            last, latOffset(50.0), 120.0, speed = 2f, accuracy = 10f,
            profile = ActivityType.HIKING.profile
        )
        assertTrue("1 秒 50 米的跳点应被丢弃", noise)
    }

    // ---------- 其余模式（FAST，保持历史行为） ----------

    @Test
    fun fast_small_displacement_with_poor_accuracy_should_be_dropped() {
        // 骑行/自驾等模式沿用旧规则：位移 1.5 米 < accuracy/2(5米) → 丢弃
        val noise = GeoUtils.isNoise(
            last, latOffset(1.5), 120.0, speed = 1.2f, accuracy = 10f,
            profile = ActivityType.CYCLING.profile
        )
        assertTrue("快速模式下小于精度一半的位移应被丢弃（历史行为）", noise)
    }

    @Test
    fun fast_normal_displacement_should_be_recorded() {
        // 骑行正常速度：速度 6 m/s，位移 6 米 > accuracy/2(5米) → 记录
        val noise = GeoUtils.isNoise(
            last, latOffset(6.0), 120.0, speed = 6f, accuracy = 10f,
            profile = ActivityType.CYCLING.profile
        )
        assertFalse("快速模式下 6 米位移应被记录", noise)
    }

    @Test
    fun fast_gps_jump_should_be_dropped() {
        val noise = GeoUtils.isNoise(
            last, latOffset(80.0), 120.0, speed = 10f, accuracy = 10f,
            profile = ActivityType.DRIVING.profile
        )
        assertTrue("1 秒 80 米的跳点应被丢弃", noise)
    }

    // ---------- 通用 ----------

    @Test
    fun first_point_should_always_be_recorded() {
        assertFalse(
            "首个点不过滤",
            GeoUtils.isNoise(null, 30.0, 120.0, 0f, 30f, ActivityType.WALKING.profile)
        )
    }

    @Test
    fun walking_and_hiking_use_sensitive_profile_others_use_fast() {
        assertEquals(RecordingProfile.SENSITIVE, ActivityType.WALKING.profile)
        assertEquals(RecordingProfile.SENSITIVE, ActivityType.HIKING.profile)
        assertEquals(RecordingProfile.FAST, ActivityType.TRAIL_RUNNING.profile)
        assertEquals(RecordingProfile.FAST, ActivityType.CYCLING.profile)
        assertEquals(RecordingProfile.FAST, ActivityType.MOTORCYCLE.profile)
        assertEquals(RecordingProfile.FAST, ActivityType.DRIVING.profile)
    }

    // ---------- 计步器确认移动（motionConfirmed） ----------

    @Test
    fun motion_confirmed_slow_shuffle_should_be_recorded() {
        // 计步器确认在走：速度 0.3 m/s、位移 2 米的碎步挪动（旧规则判静止漂移）
        val noise = GeoUtils.isNoise(
            last, latOffset(2.0), 120.0, speed = 0.3f, accuracy = 10f,
            profile = ActivityType.HIKING.profile, motionConfirmed = true
        )
        assertFalse("计步器确认在走时，慢速碎步位移不应被当静止漂移丢弃", noise)
    }

    @Test
    fun motion_confirmed_should_not_bypass_jump_filter() {
        // 计步器只豁免"静止漂移"规则，1 秒 40 米的跳点仍要丢弃
        val noise = GeoUtils.isNoise(
            last, latOffset(40.0), 120.0, speed = 0.5f, accuracy = 10f,
            profile = ActivityType.HIKING.profile, motionConfirmed = true
        )
        assertTrue("计步器确认在走也不能放过跳点", noise)
    }

    @Test
    fun motion_confirmed_should_not_bypass_jitter_filter() {
        // 计步器不豁免抖动规则：0.1 米的微小抖动仍丢弃（防锯齿噪声虚增里程）
        val noise = GeoUtils.isNoise(
            last, latOffset(0.1), 120.0, speed = 0.5f, accuracy = 20f,
            profile = ActivityType.WALKING.profile, motionConfirmed = true
        )
        assertTrue("确认在走时亚米级抖动仍应被丢弃", noise)
    }

    // ---------- 时间感知跳点阈值（GPS 丢锁恢复） ----------

    @Test
    fun gps_gap_recovery_displacement_should_be_recorded() {
        // GPS 丢锁 60 秒后恢复：徒步 3 m/s 上限 × 60s = 180 米内都是可信位移。
        // 旧规则固定 30/60 米阈值会把恢复后的点永久丢弃（后续位移越拉越大）
        val noise = GeoUtils.isNoise(
            last, latOffset(120.0), 120.0, speed = 1.5f, accuracy = 10f,
            profile = ActivityType.HIKING.profile, elapsedMs = 60_000L
        )
        assertFalse("GPS 间隙 60 秒后的 120 米真实位移应被记录", noise)
    }

    @Test
    fun impossible_speed_even_with_gap_should_be_dropped() {
        // 间隔 10 秒却位移 200 米 = 20 m/s（72 km/h），超过徒步可信上限 3 m/s，丢弃
        val noise = GeoUtils.isNoise(
            last, latOffset(200.0), 120.0, speed = 2f, accuracy = 10f,
            profile = ActivityType.HIKING.profile, elapsedMs = 10_000L
        )
        assertTrue("超过可信速度的位移仍应被丢弃", noise)
    }

    @Test
    fun fast_mode_long_gap_should_allow_highway_recovery() {
        // 自驾模式：丢锁 30 秒后恢复，位移 500 米（16.7 m/s ≈ 60 km/h）可信
        val noise = GeoUtils.isNoise(
            last, latOffset(500.0), 120.0, speed = 20f, accuracy = 10f,
            profile = ActivityType.DRIVING.profile, elapsedMs = 30_000L
        )
        assertFalse("自驾模式 GPS 间隙后的真实位移应被记录", noise)
    }
}
