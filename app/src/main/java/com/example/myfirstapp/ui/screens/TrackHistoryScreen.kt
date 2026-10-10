package com.example.myfirstapp.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myfirstapp.data.user.SocialDirectory
import com.example.myfirstapp.data.user.UserAccount
import com.example.myfirstapp.data.user.UserProfileStore
import com.example.myfirstapp.data.user.UserViewModel
import com.example.myfirstapp.track.TrackRepository
import com.example.myfirstapp.ui.components.collectPhotos
import com.example.myfirstapp.ui.components.decodeSampledBitmap
import com.example.myfirstapp.ui.components.formatDate
import com.example.myfirstapp.ui.components.summarizeActivities
import kotlinx.coroutines.launch

/**
 * 「我的」页
 *
 * 结构（自上而下）：
 * 1. 个人信息：头像（可换）、昵称、简介、关注 / 粉丝 / 足迹 三个统计入口（点足迹进轨迹页）；
 * 2. 足迹：轨迹 / 照片 / 活动 三个入口，各自进独立页面（带返回键），页内只显示数量概览；
 * 3. 工具：日常清单、配速换算、经纬度转换。
 *
 * 关注/粉丝目前是本机关系图（SharedPreferences，双向记账），可关注的人是本机注册的
 * 其他账号与服务器 /api/users；等服务端补了关注接口，把 [UserProfileStore] 换成网络实现即可，UI 不用动。
 */
@Composable
fun TrackHistoryScreen(
    onOpenAuth: () -> Unit,
    onOpenCloudAuth: () -> Unit,
    onOpenTracks: () -> Unit = {},
    onOpenPhotos: () -> Unit = {},
    onOpenActivities: () -> Unit = {},
    onOpenTodo: () -> Unit = {},
    onOpenPace: () -> Unit = {},
    onOpenCoord: () -> Unit = {},
    onOpenOffline: () -> Unit = {}
) {
    val context = LocalContext.current
    val repo = remember { TrackRepository.get(context) }
    val userVm: UserViewModel = viewModel()
    val user by userVm.currentUser.collectAsStateWithLifecycle()

    var tracks by remember { mutableStateOf(repo.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingBio by remember { mutableStateOf(false) }

    // 关注 / 粉丝弹层
    var showFollowing by remember { mutableStateOf(false) }
    var showFollowers by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<SocialDirectory.Person>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ---------- 个人资料（本机存储） ----------
    UserProfileStore.ensureLoaded(context)
    val profileVersion by UserProfileStore.revision.collectAsStateWithLifecycle()
    val selfKey = remember(user) { user?.let { UserProfileStore.localKey(it.id) } ?: "" }
    val followingCount = remember(profileVersion, selfKey) { UserProfileStore.followingOf(selfKey).size }
    val followersCount = remember(profileVersion, selfKey) { UserProfileStore.followersOf(selfKey).size }
    val avatarUri = remember(profileVersion, selfKey) { UserProfileStore.avatarOf(selfKey) }
    val bioText = remember(profileVersion, selfKey) { UserProfileStore.bioOf(selfKey) }

    // ---------- 足迹概览（只算数量，明细在各子页） ----------
    val photoCount = remember(tracks) { collectPhotos(tracks).size }
    val activityCount = remember(tracks) { summarizeActivities(tracks).sumOf { it.count } }

    // 从足迹子页返回时重新读一次（那边可能删了轨迹 / 导入了新轨迹）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tracks = repo.list()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 头像：优先系统照片选择器（Android 13+，无需存储权限），老设备回退到系统文件选择器
    fun applyAvatar(uri: Uri?) {
        if (uri == null) return
        val key = currentUserKey(userVm)
        if (key.isBlank()) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        UserProfileStore.setAvatar(context, key, uri.toString())
    }
    val pickAvatar = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { applyAvatar(it) }
    val pickAvatarLegacy = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { applyAvatar(it) }

    fun chooseAvatar() {
        if (currentUserKey(userVm).isBlank()) {
            message = "登录后才能设置头像"
            return
        }
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else {
            pickAvatarLegacy.launch("image/*")
        }
    }

    /** 打开「关注」弹层：拉取可关注的人（本机其他账号 + 服务器用户） */
    fun openFollowing() {
        if (selfKey.isBlank()) {
            message = "登录后才能关注别人"
            return
        }
        showFollowing = true
        discovering = true
        scope.launch {
            val list = SocialDirectory.discover(context, selfKey)
            list.forEach { UserProfileStore.cacheName(context, it.key, it.name) }
            discovered = list
            discovering = false
        }
    }

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            message = null
        }
    }

    if (editingBio) {
        var draft by remember { mutableStateOf(UserProfileStore.bioOf(selfKey)) }
        AlertDialog(
            onDismissRequest = { editingBio = false },
            title = { Text("编辑简介") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 60) draft = it },
                    placeholder = { Text("写点什么介绍自己…") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    UserProfileStore.setBio(context, selfKey, draft)
                    editingBio = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingBio = false }) { Text("取消") }
            }
        )
    }

    if (showFollowing) {
        val myFollowing = remember(profileVersion, selfKey) {
            UserProfileStore.followingOf(selfKey).map {
                SocialDirectory.Person(it, SocialDirectory.nameOf(context, it), SocialDirectory.descOf(it))
            }
        }
        PeopleSheet(
            title = "关注",
            sections = listOf(
                PeopleSection("我的关注（${myFollowing.size}）", myFollowing, "还没有关注任何人，去下面挑几个"),
                PeopleSection(
                    "发现用户",
                    discovered.filter { it.key !in myFollowing.map { p -> p.key } },
                    if (discovering) "正在查找…" else "本机没有其他账号，服务器也没连上"
                )
            ),
            showLoading = discovering,
            actionOf = { key -> if (UserProfileStore.isFollowing(selfKey, key)) "已关注" else "关注" },
            onToggle = { key ->
                val now = UserProfileStore.toggleFollow(context, selfKey, key)
                message = if (now) "已关注" else "已取消关注"
            },
            onDismiss = { showFollowing = false }
        )
    }

    if (showFollowers) {
        val myFollowers = remember(profileVersion, selfKey) {
            UserProfileStore.followersOf(selfKey).map {
                SocialDirectory.Person(it, SocialDirectory.nameOf(context, it), SocialDirectory.descOf(it))
            }
        }
        PeopleSheet(
            title = "粉丝",
            sections = listOf(
                PeopleSection(
                    "我的粉丝（${myFollowers.size}）",
                    myFollowers,
                    "还没有粉丝 —— 用另一个账号登录并关注你，这里就会出现"
                )
            ),
            showLoading = false,
            actionOf = { key -> if (UserProfileStore.isFollowing(selfKey, key)) "已关注" else "回关" },
            onToggle = { key ->
                val now = UserProfileStore.toggleFollow(context, selfKey, key)
                message = if (now) "已回关" else "已取消关注"
            },
            onDismiss = { showFollowers = false }
        )
    }

    // ---------- 页面主体 ----------
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProfileCard(
                user = user,
                avatarUri = avatarUri,
                bio = bioText,
                followingCount = followingCount,
                followersCount = followersCount,
                trackCount = tracks.size,
                onPickAvatar = ::chooseAvatar,
                onEditBio = { editingBio = true },
                onOpenAuth = onOpenAuth,
                onLogout = {
                    userVm.logout()
                    message = "已退出登录"
                },
                onOpenFollowing = ::openFollowing,
                onOpenFollowers = {
                    if (selfKey.isBlank()) message = "登录后才能查看粉丝"
                    else showFollowers = true
                },
                onOpenTracks = onOpenTracks
            )
        }

        item { SectionHeader("足迹") }
        item {
            FootprintEntries(
                trackCount = tracks.size,
                photoCount = photoCount,
                activityCount = activityCount,
                onOpenTracks = onOpenTracks,
                onOpenPhotos = onOpenPhotos,
                onOpenActivities = onOpenActivities
            )
        }

        item { SectionHeader("工具") }
        item {
            ToolGrid(
                onOpenTodo = onOpenTodo,
                onOpenPace = onOpenPace,
                onOpenCoord = onOpenCoord,
                onOpenOffline = onOpenOffline
            )
        }

        // 云端账号验证入口（临时，验证完可连同 cloudAuth 路由一起删掉）
        item {
            TextButton(onClick = onOpenCloudAuth, modifier = Modifier.fillMaxWidth()) {
                Text("云端账号验证（临时）", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * 当前账号 key。ActivityResult 的回调 lambda 会被 remember 住（老是第一次组合时那个），
 * 里面不能引用 selfKey 这种"组合期局部变量"，否则登录后拿到的还是空串；这里从 ViewModel 现取。
 */
private fun currentUserKey(vm: UserViewModel): String =
    vm.currentUser.value?.let { UserProfileStore.localKey(it.id) } ?: ""

// ==================== 个人信息 ====================

@Composable
private fun ProfileCard(
    user: UserAccount?,
    avatarUri: String?,
    bio: String,
    followingCount: Int,
    followersCount: Int,
    trackCount: Int,
    onPickAvatar: () -> Unit,
    onEditBio: () -> Unit,
    onOpenAuth: () -> Unit,
    onLogout: () -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit,
    onOpenTracks: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(avatarUri) {
        if (avatarUri.isNullOrBlank()) null else decodeSampledBitmap(context, avatarUri, 256)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPickAvatar),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (user == null) {
                                Icon(
                                    Icons.Default.PersonOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(
                                    user.nickname.take(1),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "更换头像",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = user?.nickname ?: "未登录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (user == null) "点击登录或注册账号"
                        else "@${user.username} · 注册于 ${formatDate(user.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (user == null) {
                    Button(onClick = onOpenAuth, shape = RoundedCornerShape(12.dp)) {
                        Text("登录/注册")
                    }
                } else {
                    OutlinedButton(onClick = onLogout, shape = RoundedCornerShape(12.dp)) {
                        Text("退出")
                    }
                }
            }

            if (user != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = bio.ifBlank { "这个人很懒，还没写简介" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bio.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onEditBio, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "编辑简介",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(4.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(followingCount.toString(), "关注", Modifier.weight(1f), onOpenFollowing)
                StatCell(followersCount.toString(), "粉丝", Modifier.weight(1f), onOpenFollowers)
                StatCell(trackCount.toString(), "足迹", Modifier.weight(1f), onOpenTracks)
            }
        }
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 足迹入口 ====================

@Composable
private fun SectionHeader(title: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** 足迹三入口：轨迹 / 照片 / 活动，各自一行，点击进入独立页面 */
@Composable
private fun FootprintEntries(
    trackCount: Int,
    photoCount: Int,
    activityCount: Int,
    onOpenTracks: () -> Unit,
    onOpenPhotos: () -> Unit,
    onOpenActivities: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            FootprintEntryRow(
                icon = Icons.Default.Route,
                title = "轨迹",
                subtitle = "导入 · 导出 · 同步云端",
                count = trackCount,
                onClick = onOpenTracks
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            FootprintEntryRow(
                icon = Icons.Default.PhotoLibrary,
                title = "照片",
                subtitle = "轨迹上的图片与视频",
                count = photoCount,
                onClick = onOpenPhotos
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            FootprintEntryRow(
                icon = Icons.Default.EmojiEvents,
                title = "活动",
                subtitle = "按运动方式统计",
                count = activityCount,
                onClick = onOpenActivities
            )
        }
    }
}

@Composable
private fun FootprintEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    count: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "$count",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = "进入",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ==================== 工具 ====================

@Composable
private fun ToolGrid(
    onOpenTodo: () -> Unit,
    onOpenPace: () -> Unit,
    onOpenCoord: () -> Unit,
    onOpenOffline: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolTile("日常清单", Icons.Default.Checklist, Modifier.weight(1f), onOpenTodo)
            ToolTile("配速换算", Icons.Default.Speed, Modifier.weight(1f), onOpenPace)
            ToolTile("经纬度转换", Icons.Default.Public, Modifier.weight(1f), onOpenCoord)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 离线地图：出发前把要去的区域下下来，无网也能看图、定位、查海拔
            ToolTile("离线地图", Icons.Default.DownloadForOffline, Modifier.weight(1f), onOpenOffline)
            Spacer(Modifier.weight(2f))
        }
    }
}

@Composable
private fun ToolTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ==================== 关注 / 粉丝 ====================

private data class PeopleSection(
    val title: String,
    val people: List<SocialDirectory.Person>,
    val emptyText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleSheet(
    title: String,
    sections: List<PeopleSection>,
    showLoading: Boolean,
    actionOf: (String) -> String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 人数很少（本机账号 + 服务器用户），直接纵向滚动即可，
        // 不用 LazyColumn —— 弹层里嵌套懒列表容易踩无限高度的坑
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (showLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                }
            }
            sections.forEach { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                if (section.people.isEmpty()) {
                    Text(
                        section.emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    section.people.forEach { person ->
                        PersonRow(
                            name = person.name,
                            desc = person.desc,
                            actionText = actionOf(person.key),
                            onToggle = { onToggle(person.key) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PersonRow(
    name: String,
    desc: String,
    actionText: String,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(onClick = onToggle, shape = RoundedCornerShape(10.dp)) {
            Text(actionText, style = MaterialTheme.typography.labelMedium)
        }
    }
}
