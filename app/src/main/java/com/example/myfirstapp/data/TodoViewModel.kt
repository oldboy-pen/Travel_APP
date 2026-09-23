package com.example.myfirstapp.data

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一条待办事项（数据模型） */
data class TodoItem(
    val id: Int,
    val text: String,
    val isDone: Boolean = false
)

/** UI 状态：一次快照，交给 Compose 渲染 */
data class TodoUiState(
    val items: List<TodoItem> = emptyList(),
    val inputText: String = ""
)

/**
 * ViewModel：持有业务逻辑和状态，屏幕旋转后数据不丢失。
 * 这就是 MVVM 中的 "VM"——View (Compose) 只管渲染，逻辑都在这里。
 */
class TodoViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TodoUiState())
    val uiState: StateFlow<TodoUiState> = _uiState.asStateFlow()

    /** 输入框内容变化 */
    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** 添加一条待办 */
    fun addTodo() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        _uiState.update { state ->
            state.copy(
                items = state.items + TodoItem(id = state.items.size + 1, text = text),
                inputText = ""
            )
        }
    }

    /** 切换完成状态 */
    fun toggleTodo(id: Int) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == id) it.copy(isDone = !it.isDone) else it
                }
            )
        }
    }

    /** 删除一条待办 */
    fun removeTodo(id: Int) {
        _uiState.update { state ->
            state.copy(items = state.items.filterNot { it.id == id })
        }
    }
}
