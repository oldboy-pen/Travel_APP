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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.AccountRules
import com.example.myfirstapp.data.user.AuthResult
import com.example.myfirstapp.data.user.UserViewModel

/**
 * 注册页（全屏，不带底部导航）
 *
 * 用户名唯一（忽略大小写）；昵称选填，不填就用用户名；
 * 注册成功即视为登录，直接退回上一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    onGoLogin: () -> Unit,
    vm: UserViewModel = viewModel()
) {
    var username by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val busy by vm.busy.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))

            Text("创建账号", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "账号保存在本机，密码加密存储",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text("用户名") },
                placeholder = { Text("${AccountRules.USERNAME_MIN}-${AccountRules.USERNAME_MAX} 位，中英文/数字/下划线") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("昵称（选填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )

            Spacer(Modifier.height(12.dp))

            PasswordField(
                value = password,
                onValueChange = { password = it; error = null },
                label = "密码"
            )

            Spacer(Modifier.height(12.dp))

            PasswordField(
                value = confirm,
                onValueChange = { confirm = it; error = null },
                label = "确认密码",
                imeAction = ImeAction.Done
            )

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val name = username.trim()
                    val msg = AccountRules.checkUsername(name)
                        ?: AccountRules.checkPassword(password)
                        ?: if (password != confirm) "两次输入的密码不一致" else null
                    if (msg != null) {
                        error = msg
                    } else {
                        vm.register(name, password, nickname.trim()) { result ->
                            when (result) {
                                is AuthResult.Success -> onSuccess()
                                is AuthResult.Failure -> error = result.message
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("注册并登录")
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onGoLogin, enabled = !busy) {
                Text("已有账号？去登录")
            }
        }
    }
}
