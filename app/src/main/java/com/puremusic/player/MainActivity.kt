package com.puremusic.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessionToken = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFFBB86FC),
                    secondary = Color(0xFF03DAC6)
                )
            ) {
                MainScreen(
                    getController = { mediaController },
                    onControllerReady = { ctrl -> mediaController = ctrl }
                )
            }
        }

        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
        }, MoreExecutors.directExecutor())
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    getController: () -> MediaController?,
    onControllerReady: (MediaController) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { MusicRepository(context) }
    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var currentSongId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var isShuffle by remember { mutableStateOf(false) }

    // 意图搜索与抽屉
    var searchQuery by remember { mutableStateOf("") }
    var showDrawer by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf("全部") }

    // 权限检查
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map[Manifest.permission.READ_MEDIA_AUDIO] == true
        hasPermission = granted
        if (granted) {
            allSongs = repository.loadAllSongs()
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            launcher.launch(
                arrayOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else {
            allSongs = repository.loadAllSongs()
        }
    }

    // 定时轮询播放进度
    LaunchedEffect(Unit) {
        while (true) {
            getController()?.let { ctrl ->
                isPlaying = ctrl.isPlaying
                currentPosition = ctrl.currentPosition.coerceAtLeast(0L)
                duration = ctrl.duration.coerceAtLeast(1L)
                val idStr = ctrl.currentMediaItem?.mediaId
                currentSongId = idStr?.toLongOrNull()
                isShuffle = ctrl.shuffleModeEnabled
            }
            delay(500)
        }
    }

    val currentSong = remember(currentSongId, allSongs) {
        allSongs.find { it.id == currentSongId } ?: allSongs.firstOrNull()
    }

    // 播放指定歌曲
    fun playSong(song: Song) {
        val ctrl = getController() ?: return
        val index = allSongs.indexOfFirst { it.id == song.id }
        if (index == -1) return

        if (ctrl.mediaItemCount == 0) {
            val mediaItems = allSongs.map {
                MediaItem.Builder()
                    .setMediaId(it.id.toString())
                    .setUri(it.contentUri)
                    .build()
            }
            ctrl.setMediaItems(mediaItems, index, 0L)
            ctrl.prepare()
        } else {
            ctrl.seekToDefaultPosition(index)
        }
        ctrl.play()
        song.playCount++
        BackupManager.saveBackup(context, allSongs)
    }

    // 快捷切歌
    fun playNext() {
        val ctrl = getController() ?: return
        if (ctrl.hasNextMediaItem()) ctrl.seekToNextMediaItem()
        else if (allSongs.isNotEmpty()) playSong(allSongs.first())
    }

    fun playPrev() {
        val ctrl = getController() ?: return
        if (ctrl.hasPreviousMediaItem()) ctrl.seekToPreviousMediaItem()
        else if (allSongs.isNotEmpty()) playSong(allSongs.last())
    }

    // 自动聚类标签：提取所有文件夹作为意图标签
    val folders = remember(allSongs) {
        allSongs.map { it.folder }.distinct().filter { it.isNotBlank() }
    }
    val autoTags = remember(folders) {
        listOf("全部", "喜欢", "常听") + folders
    }

    val filteredSongs = remember(searchQuery, selectedTag, allSongs) {
        var result = allSongs
        if (selectedTag == "喜欢") {
            result = result.filter { it.isFavorite }
        } else if (selectedTag == "常听") {
            result = result.filter { it.playCount > 0 }.sortedByDescending { it.playCount }
        } else if (selectedTag != "全部") {
            result = result.filter { it.folder == selectedTag }
        }
        repository.filterByIntent(result, searchQuery)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {

                // [上半屏]：纯粹的听歌视觉 + 全局单手手势
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp)
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0xFF1A1A1A))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    currentSong?.let {
                                        it.isFavorite = !it.isFavorite
                                        BackupManager.saveBackup(context, allSongs)
                                        Toast.makeText(
                                            context,
                                            if (it.isFavorite) "已收藏 ❤️" else "已取消收藏",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (totalDrag > 80) playPrev()
                                    else if (totalDrag < -80) playNext()
                                    totalDrag = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // 经典大黑胶质感纯色圆盘（不耗 GPU，极省电）
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF262626)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(if (currentSong?.isFavorite == true) Color(0xFFE91E63) else Color(0xFF333333)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (currentSong?.isFavorite == true) Icons.Default.Favorite else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = if (allSongs.isEmpty()) "本地暂无歌曲" else "左右滑切歌 · 双击收藏",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                // [下半屏]：大拇指黄金触达区（无 Tab，开屏即播，一键搜歌）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    // 曲目信息
                    Text(
                        text = currentSong?.title ?: "纯音播放器",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${currentSong?.artist ?: "本地音频"} · ${currentSong?.folder ?: "默认"}",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.LightGray),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 进度条（下沉至拇指自然滑动区）
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { newPos ->
                            currentPosition = newPos.toLong()
                            getController()?.seekTo(newPos.toLong())
                        },
                        valueRange = 0f..duration.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFBB86FC),
                            activeTrackColor = Color(0xFFBB86FC),
                            inactiveTrackColor = Color(0xFF333333)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(currentPosition), color = Color.Gray, fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 主播放控制器
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            val ctrl = getController() ?: return@IconButton
                            ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
                            isShuffle = ctrl.shuffleModeEnabled
                        }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "随机",
                                tint = if (isShuffle) Color(0xFFBB86FC) else Color.Gray
                            )
                        }

                        IconButton(onClick = { playPrev() }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // 大播放/暂停圆钮
                        FilledIconButton(
                            onClick = {
                                val ctrl = getController() ?: return@FilledIconButton
                                if (ctrl.mediaItemCount == 0 && allSongs.isNotEmpty()) {
                                    playSong(allSongs.first())
                                } else {
                                    if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
                                }
                            },
                            modifier = Modifier.size(68.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFBB86FC)
                            )
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "播放暂停",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = { playNext() }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = {
                            currentSong?.let {
                                it.isFavorite = !it.isFavorite
                                BackupManager.saveBackup(context, allSongs)
                            }
                        }) {
                            Icon(
                                imageVector = if (currentSong?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (currentSong?.isFavorite == true) Color(0xFFE91E63) else Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 替代传统 Tab 的“意图搜歌胶囊”
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .clickable { showDrawer = true },
                        color = Color(0xFF242424)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.LightGray
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "按意图找歌 (共 ${allSongs.size} 首本地音乐)...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // [单手意图抽屉]：只用搜索和自动分组，不要任何标签页
            if (showDrawer) {
                ModalBottomSheet(
                    onDismissRequest = { showDrawer = false },
                    containerColor = Color(0xFF1E1E1E),
                    dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        // 搜索输入框
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索歌曲、歌手或意图关键词...", color = Color.Gray) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFFBB86FC),
                                unfocusedBorderColor = Color(0xFF333333),
                                focusedContainerColor = Color(0xFF262626),
                                unfocusedContainerColor = Color(0xFF262626)
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = null, tint = Color.Gray)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 自动聚类标签流（文件夹即歌单，整理零费力）
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(autoTags) { tag ->
                                val isSelected = selectedTag == tag
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTag = tag },
                                    label = { Text(tag) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFFBB86FC),
                                        selectedLabelColor = Color.Black,
                                        containerColor = Color(0xFF262626),
                                        labelColor = Color.LightGray
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 歌曲列表：一触即播
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            items(filteredSongs) { song ->
                                val isCurrent = song.id == currentSongId
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            playSong(song)
                                            showDrawer = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isCurrent) Icons.Default.Equalizer else Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = if (isCurrent) Color(0xFFBB86FC) else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = if (isCurrent) Color(0xFFBB86FC) else Color.White,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 15.sp
                                        )
                                        Text(
                                            text = "${song.artist} · ${song.folder}",
                                            color = Color.Gray,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                    if (song.isFavorite) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = Color(0xFFE91E63),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
