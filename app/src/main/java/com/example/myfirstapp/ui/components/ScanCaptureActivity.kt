package com.example.myfirstapp.ui.components

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 自定义扫码页：在 zxing 默认界面上加一个「返回」按钮。
 * zxing 的 CaptureActivity 默认没有返回键，全面屏手势/横屏下容易卡住出不来。
 */
class ScanCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val back = Button(this).apply {
            text = "← 返回"
            setOnClickListener { finish() }
        }
        val density = resources.displayMetrics.density
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val m = (16 * density).toInt()
            setMargins(m, m, m, m)
        }
        findViewById<FrameLayout>(android.R.id.content).addView(back, lp)
    }
}
