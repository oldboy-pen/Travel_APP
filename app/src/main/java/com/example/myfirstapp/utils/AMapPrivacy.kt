package com.example.myfirstapp.utils

import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings

/**
 * 高德 SDK 隐私合规初始化。
 *
 * ★ 重要：2021年10月后高德要求 App 必须先弹窗获得用户同意，
 *   再调用 updatePrivacyShow/updatePrivacyAgree，否则 SDK 会拒绝工作。
 *   本项目在 MainActivity 的隐私弹窗"同意"回调中调用本方法。
 */
object AMapPrivacy {

    fun init(context: Context) {
        // 地图 SDK
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
        // 定位 SDK
        AMapLocationClient.updatePrivacyShow(context, true, true)
        AMapLocationClient.updatePrivacyAgree(context, true)
        // 搜索/路径规划 SDK
        ServiceSettings.updatePrivacyShow(context, true, true)
        ServiceSettings.updatePrivacyAgree(context, true)
    }
}
