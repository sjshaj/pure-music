package com.puremusic.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 大厂色调系统（Tonal Hierarchy）
private val BgSurface = Color(0xFF0C0D10)           // 基底黑曜石（含2%冷调灰）
private val BgContainer = Color(0xFF16181D)         // 悬浮容器层
private val BgKey = Color(0xFF1E2128)               // 抬升功能层
private val LineHairline = Color(0xFF262A33)        // 发丝级几何线 (0.8dp)
private val TextPrimary = Color(0xFFF3F4F6)         // 象牙白（主视觉）
private val TextSecondary = Color(0xFF888E9B)       // 工业冷灰（副文本）
private val TextTertiary = Color(0xFF525763)        // 暗色时间刻度
private val AccentActive = Color(0xFFECEFF1)        // 冷白钛光标
private val AccentHeart = Color(0xFFE06C75)         // 哑光琥珀红（收藏）

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
                    background = BgSurface,
                    surface = BgContainer,
                    primary = TextPrimary
                )
            ) {
                MainScreen(getController = { mediaController })
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
fun MainScreen(getController: () -> MediaController?) {
    val context = LocalContext.current
    val repository = remember { MusicRepository(context) }
    var allSongs by remember { mutableStateOf(emptyList<Song>()) }
    var currentSongId by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(1L) }
    var isShuffle by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var showDrawer by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf("全部") }

    // 封面位图状态
    var currentCoverBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    // 定时轮询播放器状态
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
            delay(400)
        }
    }

    val currentSong = remember(currentSongId, allSongs) {
        allSongs.find { it.id == currentSongId } ?: allSongs.firstOrNull()
    }

    // 异步加载原生专辑封面
    LaunchedEffect(currentSong?.id) {
        currentSong?.let { song ->
            withContext(Dispatchers.IO) {
                try {
                    val bmp = context.contentResolver.loadThumbnail(song.contentUri, Size(512, 512), null)
                    currentCoverBitmap = bmp
                } catch (_: Exception) {
                    currentCoverBitmap = null
                }
            }
        } ?: run {
            currentCoverBitmap = null
        }
    }

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
        containerColor = BgSurface
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

                // [顶部状态标识：工业极简标尺]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OFFLINE · DIRECT PCM",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp,
                        color = TextTertiary
                    )
                    Text(
                        text = if (isShuffle) "SHUFFLE" else "SEQUENTIAL",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp,
                        color = if (isShuffle) TextPrimary else TextTertiary
                    )
                }

                // [上中区：画廊级专辑海报与呼吸留白]
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 36.dp, vertical = 8.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    currentSong?.let {
                                        it.isFavorite = !it.isFavorite
                                        BackupManager.saveBackup(context, allSongs)
                                        Toast.makeText(
                                            context,
                                            if (it.isFavorite) "已标记偏爱" else "已取消标记",
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
                    // 正方形画廊卡片 (高阶倒角 20dp + 发丝线边框)
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .fillMaxSize(0.85f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(BgContainer)
                            .border(1.dp, LineHairline, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = currentCoverBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // 瑞士风格极简排版图案
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = (currentSong?.title?.take(1) ?: "P").uppercase(),
                                    fontSize = 64.sp,
                                    fontWeight = FontWeight.Light,
                                    color = TextSecondary.copy(alpha = 0.4f),
                                    fontFamily = FontFamily.SansSerif
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .width(32.dp)
                                        .height(1.dp)
                                        .background(LineHairline)
                                )
                            }
                        }
                    }
                }

                // [下半屏：大厂级排版与单手触达区]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    // 1. 曲目信息（主次对比极致分明）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentSong?.title ?: "纯音",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            letterSpacing = (-0.4).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${currentSong?.artist ?: "本地音轨"} · ${currentSong?.folder ?: "默认合辑"}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary,
                            letterSpacing = 0.2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // 2. 发丝级光纤进度条 (2dp 极细轨道)
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { newPos ->
                            currentPosition = newPos.toLong()
                            getController()?.seekTo(newPos.toLong())
                        },
                        valueRange = 0f..duration.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = AccentActive,
                            activeTrackColor = AccentActive,
                            inactiveTrackColor = LineHairline
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(currentPosition),
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            formatTime(duration),
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 3. 几何对称线框控制器 (Dieter Rams 几何秩序)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 随机播放
                        IconButton(onClick = {
                            val ctrl = getController() ?: return@IconButton
                            ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
                            isShuffle = ctrl.shuffleModeEnabled
                        }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "随机",
                                tint = if (isShuffle) TextPrimary else TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // 上一首
                        IconButton(onClick = { playPrev() }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "上一首",
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 核心播放按钮（微抬升哑光圆盘）
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(BgKey)
                                .border(1.dp, LineHairline, CircleShape)
                                .clickable {
                                    val ctrl = getController() ?: return@clickable
                                    if (ctrl.mediaItemCount == 0 && allSongs.isNotEmpty()) {
                                        playSong(allSongs.first())
                                    } else {
                                        if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "播放暂停",
                                tint = TextPrimary,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        // 下一首
                        IconButton(onClick = { playNext() }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "下一首",
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // 收藏心标
                        IconButton(onClick = {
                            currentSong?.let {
                                it.isFavorite = !it.isFavorite
                                BackupManager.saveBackup(context, allSongs)
                            }
                        }) {
                            Icon(
                                imageVector = if (currentSong?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "收藏",
                                tint = if (currentSong?.isFavorite == true) AccentHeart else TextTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // 4. 底部悬浮药丸（Safari / Arc 风格的纯粹意图入口）
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(23.dp))
                            .border(1.dp, LineHairline, RoundedCornerShape(23.dp))
                            .clickable { showDrawer = true },
                        color = BgContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "按意图找歌 (${allSongs.size} 首已收录)",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.2.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // [意图抽屉：大厂级排版流]
            if (showDrawer) {
                ModalBottomSheet(
                    onDismissRequest = { showDrawer = false },
                    containerColor = Color(0xFF111317),
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 12.dp)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(LineHairline)
                        )
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    ) {
                        // 极简圆角搜索框
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索歌曲、歌手或文件夹意图...", color = TextTertiary, fontSize = 14.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = TextPrimary,
                                unfocusedBorderColor = LineHairline,
                                focusedContainerColor = BgContainer,
                                unfocusedContainerColor = BgContainer
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 自动聚类胶囊流
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(autoTags) { tag ->
                                val isSelected = selectedTag == tag
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { selectedTag = tag }
                                        .border(
                                            1.dp,
                                            if (isSelected) TextPrimary else LineHairline,
                                            RoundedCornerShape(16.dp)
                                        ),
                                    color = if (isSelected) TextPrimary else BgContainer
                                ) {
                                    Text(
                                        text = tag,
                                        color = if (isSelected) BgSurface else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 歌曲列表
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
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
                                        .padding(vertical = 12.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isCurrent) Icons.Default.Equalizer else Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = if (isCurrent) TextPrimary else TextTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = if (isCurrent) TextPrimary else TextSecondary,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 15.sp
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${song.artist} · ${song.folder}",
                                            color = TextTertiary,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    }
                                    if (song.isFavorite) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = AccentHeart,
                                            modifier = Modifier.size(15.dp)
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
