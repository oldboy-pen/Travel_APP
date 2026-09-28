package com.example.myfirstapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.AccountRules
import com.example.myfirstapp.data.user.CloudUserViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云端账号验证页（临时）
 *
 * 用途：确认「App → 本机 FastAPI 服务器 → SQLite」这条链路能注册并保存用户。
 * 与 App 内的本地账号是两套独立体系，互不影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAuthScreen(
    onBack: () -> Unit,
    vm: CloudUserViewModel = viewModel()
) {
    val url by vm.serverUrl.collectAsState()
    val busy by vm.busy.collectAsState()
    val users by vm.users.collectAsState()
    val message by vm.message.collectAsState()

    var username by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshUsers() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云端账号（验证）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(12.dp))

            // ---- 服务器地址 ----
            Text("服务器地址", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = url,
                onValueChange = vm::setServerUrl,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            )
            Text(
                "模拟器用 10.0.2.2；真机改成电脑的局域网 IP（如 http://192.168.1.23:8000）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ---- 注册 / 登录表单 ----
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("昵称（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(10.dp))
            PasswordField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "密码",
                imeAction = ImeAction.Done
            )

            val hint = error ?: message
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hint.startsWith("失败") || error != null)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val msg = AccountRules.checkUsername(username.trim())
                            ?: AccountRules.checkPassword(password)
                        if (msg != null) {
                            error = msg
                        } else {
                            error = null
                            vm.register(username.trim(), password, nickname)
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy
                ) { Text("注册") }

                OutlinedButton(
                    onClick = {
                        val msg = AccountRules.checkUsername(username.trim())
                            ?: AccountRules.checkPassword(password)
                        if (msg != null) {
                            error = msg
                        } else {
                            error = null
                            vm.login(username.trim(), password)
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !busy
                ) { Text("登录") }
            }

            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ---- 服务器已存用户 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "服务器已保存 ${users.size} 个用户",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { vm.refreshUsers() }, enabled = !busy) {
                    Text("刷新")
                }
            }

            if (users.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "暂无数据：确认服务器已启动、地址正确，然后点注册",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                users.forEach { u ->
                    ListItem(
                        headlineContent = { Text(u.nickname) },
                        supportingContent = {
                            Text("@${u.username} · ${formatTime(u.createdAt)}")
                        },
                        overlineContent = { Text(u.id) }
                    )
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatTime(time: Long): String =
    if (time > 0) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time)) else "未知"
