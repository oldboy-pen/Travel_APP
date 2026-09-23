package com.example.myfirstapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.myfirstapp.ui.navigation.AppRoot
import com.example.myfirstapp.ui.theme.MyFirstAppTheme
import com.example.myfirstapp.utils.AMapPrivacy

/**
 * App 入口 Activity
 *
 * 启动流程：隐私弹窗（首次）→ 同意后初始化高德SDK → 进入主界面
 * （高德合规要求：必须先获用户同意，SDK 才能初始化）
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val agreed = prefs.getBoolean(KEY_PRIVACY, false)
        if (agreed) AMapPrivacy.init(this)  // 之前已同意过，直接初始化

        setContent {
            MyFirstAppTheme {
                var isAgreed by remember { mutableStateOf(agreed) }
                if (isAgreed) {
                    AppRoot()
                } else {
                    PrivacyDialog(onAgree = {
                        prefs.edit().putBoolean(KEY_PRIVACY, true).apply()
                        AMapPrivacy.init(this)
                        isAgreed = true
                    })
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_PRIVACY = "privacy_agreed"
    }
}

/** 首次启动的隐私政策弹窗（合规必需，拒绝则退出） */
@Composable
private fun PrivacyDialog(onAgree: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.privacy_title)) },
        text = {
            Text(stringResource(R.string.privacy_body))
        },
        confirmButton = { TextButton(onClick = onAgree) { Text("同意") } },
        dismissButton = {
            TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                Text("退出")
            }
        }
    )
}
