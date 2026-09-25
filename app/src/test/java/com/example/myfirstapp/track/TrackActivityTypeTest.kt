package com.example.myfirstapp.track

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackActivityTypeTest {
    @Test
    fun track_should_keep_selected_activity_type() {
        val track = Track(
            id = "t1",
            name = "测试轨迹",
            startTime = 0L,
            endTime = 0L,
            points = emptyList(),
            waypoints = emptyList(),
            distanceMeters = 0.0,
            durationMillis = 0L,
            climbMeters = 0.0,
            activityType = ActivityType.MOTORCYCLE
        )

        assertEquals(ActivityType.MOTORCYCLE, track.activityType)
    }
}
