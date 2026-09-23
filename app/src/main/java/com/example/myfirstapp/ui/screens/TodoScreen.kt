package com.example.myfirstapp.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.TodoViewModel

/**
 * 待办清单主界面
 *
 * 关键概念：
 * - viewModel() 获取/创建 ViewModel
 * - collectAsStateWithLifecycle() 把 StateFlow 转成 Compose 状态，
 *   数据变化时界面自动刷新（"数据驱动 UI"）
 * - UI 是 immutable 的：用户操作全部转发给 ViewModel 处理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(viewModel: TodoViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的待办") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // ---- 输入区：输入框 + 添加按钮 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = viewModel::onInputChange,
                    placeholder = { Text("想做什么？") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = viewModel::addTodo,
                    enabled = state.inputText.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加")
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 统计条 ----
            Text(
                text = "共 ${state.items.size} 项 · 待完成 " +
                        "${state.items.count { !it.isDone }} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // ---- 列表区：LazyColumn 高效滚动列表 ----
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有待办，添加第一条吧 👋",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        TodoRow(
                            text = item.text,
                            isDone = item.isDone,
                            onToggle = { viewModel.toggleTodo(item.id) },
                            onRemove = { viewModel.removeTodo(item.id) }
                        )
                    }
                }
            }
        }
    }
}

/** 单条待办：点击勾选/取消，右侧删除按钮 */
@Composable
private fun TodoRow(
    text: String,
    isDone: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = text,
                style = if (isDone) MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                ) else MaterialTheme.typography.bodyLarge
            )
        },
        leadingContent = {
            Checkbox(checked = isDone, onCheckedChange = { onToggle() })
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    )
}
