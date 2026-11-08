package com.nkj97721.chmee

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val BLACK = 0xFF000000
private const val PANEL = 0xFF171717
private const val WHITE = 0xFFFFFFFF

data class MediaItem(val id: Long, val uri: Uri, val mime: String, val name: String, val date: Long, val size: Long, val trashed: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.systemUiVisibility = 0
        setContent { MediaManager() }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MediaManager() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("media_tags", 0) }
    var media by remember { mutableStateOf(emptyList<MediaItem>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var activeTab by remember { mutableStateOf("全部") }
    var tags by remember { mutableStateOf(prefs.getStringSet("custom_tags", setOf())?.toList()?.sorted() ?: emptyList()) }
    var tagDialog by remember { mutableStateOf(false) }
    var tagName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var columns by remember { mutableFloatStateOf(4f) }
    var newestFirst by remember { mutableStateOf(true) }
    var largeFirst by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                if (Build.VERSION.SDK_INT >= 34) add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
    fun hasPermission(): Boolean = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshKey++ }

    LaunchedEffect(refreshKey) {
        if (!hasPermission()) return@LaunchedEffect
        loading = true
        media = withContext(Dispatchers.IO) { loadMedia(context) }
        loading = false
    }

    val visible = remember(media, activeTab, tags, newestFirst, largeFirst, prefs) {
        val filtered = when (activeTab) {
            "图片" -> media.filter { it.mime.startsWith("image/") && !it.trashed }
            "视频" -> media.filter { it.mime.startsWith("video/") && !it.trashed }
            "最近删除" -> media.filter { it.trashed }
            "全部" -> media.filter { !it.trashed }
            else -> {
                val ids = prefs.getStringSet("tag_$activeTab", emptySet()) ?: emptySet()
                media.filter { !it.trashed && it.id.toString() in ids }
            }
        }
        filtered.sortedWith(if (largeFirst) compareByDescending<MediaItem> { it.size } else compareBy<MediaItem> { it.size }
            .let { sizeComparator -> if (activeTab == "大小") sizeComparator else sizeComparator }
        ).let { sorted ->
            if (activeTab == "全部" || activeTab == "图片" || activeTab == "视频" || activeTab == "最近删除" || tags.contains(activeTab)) {
                if (largeFirst) sorted else sorted
            } else sorted
        }.sortedBy { if (newestFirst) -it.date else it.date }
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Color.Black, surface = Color(0xFF111111), primary = Color.White, onBackground = Color.White, onSurface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Spacer(Modifier.fillMaxWidth().height(42.dp))
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderButton("刷新", Modifier.weight(1f), onClick = { refreshKey++ }) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)) }
                HeaderButton(if (newestFirst) "新→旧" else "旧→新", Modifier.weight(1f), onClick = { newestFirst = !newestFirst })
                HeaderButton(if (largeFirst) "大→小" else "小→大", Modifier.weight(1f), onClick = { largeFirst = !largeFirst })
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    items(listOf("全部", "图片", "视频", "最近删除") + tags) { tag ->
                        FilterChip(selected = activeTab == tag, onClick = { activeTab = tag; selected = emptySet() }, label = { Text(tag, fontSize = 13.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF333333), containerColor = Color(0xFF171717), labelColor = Color.White, selectedLabelColor = Color.White))
                    }
                }
                IconButton(onClick = { tagDialog = true }) { Icon(Icons.Default.Add, "新增标签", tint = Color.White) }
            }
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 项", color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { selected = emptySet() }) { Text("取消选择", color = Color.LightGray) }
                    TextButton(onClick = {
                        if (activeTab !in listOf("全部", "图片", "视频", "最近删除")) {
                            val old = prefs.getStringSet("tag_$activeTab", emptySet())?.toMutableSet() ?: mutableSetOf()
                            old.addAll(selected.map { it.toString() }); prefs.edit().putStringSet("tag_$activeTab", old).apply()
                        }
                        selected = emptySet()
                    }) { Text("加入当前标签", color = Color.White) }
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (!hasPermission()) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("需要媒体读取权限", color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { permissionLauncher.launch(permissions) }) { Text("授权访问媒体") }
                    }
                } else if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                } else if (visible.isEmpty()) {
                    Text("暂无媒体", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                } else {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns.toInt().coerceIn(4, 10)),
                        state = gridState,
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(visible, selected) {
                                detectDragGesturesAfterLongPress(
                                    onDrag = { change, _ ->
                                        val pos = change.position
                                        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                            pos.x >= info.offset.x && pos.x <= info.offset.x + info.size.width &&
                                            pos.y >= info.offset.y && pos.y <= info.offset.y + info.size.height
                                        }
                                        hit?.let { info -> visible.getOrNull(info.index)?.let { item -> selected = selected + item.id } }
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {}
                                )
                            }
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    columns = (columns / zoom).coerceIn(4f, 10f)
                                }
                            },
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(visible, key = { it.id }) { item ->
                            Box(
                                Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp)).background(Color(0xFF151515))
                                    .pointerInput(item.id, selected) {
                                        detectTapGestures(
                                            onTap = {
                                                if (selected.isNotEmpty()) selected = if (item.id in selected) selected - item.id else selected + item.id
                                                else openMedia(context, item)
                                            },
                                            onLongPress = { selected = selected + item.id }
                                        )
                                    }
                            ) {
                                AsyncImage(model = item.uri, contentDescription = item.name, modifier = Modifier.fillMaxSize())
                                if (item.id in selected) {
                                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp))
                                }
                            }
                        }
                    }
                    Text("${columns.toInt()} 列", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
            }
        }
    }

    if (tagDialog) {
        AlertDialog(
            onDismissRequest = { tagDialog = false; tagName = "" },
            title = { Text("新增标签") },
            text = { OutlinedTextField(value = tagName, onValueChange = { tagName = it }, singleLine = true, label = { Text("标签名称") }) },
            confirmButton = {
                TextButton(onClick = {
                    val name = tagName.trim()
                    if (name.isNotEmpty() && name !in listOf("全部", "图片", "视频", "最近删除")) {
                        tags = (tags + name).distinct()
                        prefs.edit().putStringSet("custom_tags", tags.toSet()).apply()
                        activeTab = name
                    }
                    tagDialog = false; tagName = ""
                }) { Text("添加") }
            },
            dismissButton = {
                if (activeTab in tags) TextButton(onClick = {
                    tags = tags - activeTab
                    prefs.edit().putStringSet("custom_tags", tags.toSet()).remove("tag_$activeTab").apply()
                    activeTab = "全部"; tagDialog = false
                }) { Text("删除当前标签") }
            }
        )
    }
}

@Composable
private fun HeaderButton(label: String, modifier: Modifier, onClick: () -> Unit, icon: @Composable (() -> Unit)? = null) {
    Surface(modifier = modifier.fillMaxHeight(), color = Color(0xFF171717), shape = RoundedCornerShape(5.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            Text(label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun loadMedia(context: android.content.Context): List<MediaItem> {
    val resolver = context.contentResolver
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.IS_TRASHED
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val args = arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
    val result = mutableListOf<MediaItem>()
    resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val trashCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val type = cursor.getInt(typeCol)
            val base = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            result += MediaItem(id, ContentUris.withAppendedId(base, id), cursor.getString(mimeCol) ?: "application/octet-stream",
                cursor.getString(nameCol) ?: "", cursor.getLong(dateCol), cursor.getLong(sizeCol), trashCol >= 0 && cursor.getInt(trashCol) == 1)
        }
    }
    return result
}

private fun openMedia(context: android.content.Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "打开媒体")) }
}
