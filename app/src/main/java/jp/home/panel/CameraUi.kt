package jp.home.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** ホーム画面のカメラ：静止画を10秒ごとに更新。タップで動画 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraTile(e: Entity, vm: MainViewModel) {
    var img by remember(e.entityId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(e.entityId) { mutableStateOf(false) }
    val viewing by vm.cameraFor.collectAsState()
    val saver by vm.screensaver.collectAsState()
    // 映像を開いている間とスクリーンセーバー中は、静止画の読み込みを休む
    val paused = viewing != null || saver || e.unavailable
    LaunchedEffect(e.entityId, paused) {
        while (!paused && isActive) {
            val next = vm.cameraSnapshot(e.entityId, 640)
            if (next != null) img = next
            failed = next == null && img == null
            delay(10_000)
        }
    }
    Card(
        onClick = { vm.openCamera(e.entityId) },
        enabled = !e.unavailable,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EntityHeader(e, vm, large = false)
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val i = img
                when {
                    e.unavailable -> Text("カメラにつながりません", color = Color.White)
                    i != null -> {
                        Image(i, contentDescription = e.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Text(
                            "▶ タップで動画",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    failed -> Text("映像を読めませんでした", color = Color.White)
                    else -> CircularProgressIndicator()
                }
            }
        }
    }
}

/** カメラの動画（全画面）。高画質（HLS）で再生し、だめなら簡易表示（MJPEG） */
@Composable
fun CameraViewer(vm: MainViewModel) {
    val id by vm.cameraFor.collectAsState()
    val entityId = id ?: return
    val states by vm.allStates.collectAsState()
    val name = states[entityId]?.name ?: entityId
    // null = 確認中、"" = 簡易表示、それ以外 = 高画質の再生アドレス
    var hls by remember(entityId) { mutableStateOf<String?>(null) }
    var note by remember(entityId) { mutableStateOf<String?>(null) }
    LaunchedEffect(entityId) {
        hls = try {
            vm.cameraHlsUrl(entityId) ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note = "高画質の動画を使えないため、簡易表示にしています（${e.message}）"
            ""
        }
    }
    Dialog(onDismissRequest = { vm.closeCamera() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val u = hls) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                "" -> MjpegView(vm, entityId, name) { if (note == null) note = it }
                else -> HlsView(vm, u, onError = {
                    note = "高画質の動画を再生できないため、簡易表示に切り替えました（$it）"
                    hls = ""
                })
            }
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📷 $name", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (hls != null) {
                    Text(
                        if (hls == "") "簡易表示" else "高画質",
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                TextButton(onClick = { vm.closeCamera() }) { Text("✕ 閉じる", color = Color.White) }
            }
            note?.let {
                Text(
                    it, color = Color.White, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(8.dp),
                )
            }
        }
    }
}

/** 高画質（HLS）。Home Assistant がカメラの映像をそのまま配信するので、なめらかで音声も出る */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun HlsView(vm: MainViewModel, url: String, onError: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(vm.playerHttp)))
            .build().apply {
                setMediaItem(
                    MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8)
                        // 遅れを小さく（ライブの先頭近くを再生）
                        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2_000).build())
                        .build(),
                )
                playWhenReady = true
                prepare()
            }
    }
    var buffering by remember(url) { mutableStateOf(true) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName)
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    // 見ている間はスクリーンセーバーを出さない
    LaunchedEffect(url) {
        while (isActive) {
            vm.onUserInteraction()
            delay(20_000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

/** 簡易表示（MJPEG）。どのカメラでも見られるが、コマ数が少なく音声は出ない */
@Composable
private fun MjpegView(vm: MainViewModel, entityId: String, name: String, onNote: (String) -> Unit) {
    var frame by remember(entityId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entityId) {
        // 静止画ですぐに何か出してから、動画につなぐ
        vm.cameraSnapshot(entityId, 1920)?.let { if (frame == null) frame = it }
        while (isActive) {
            try {
                vm.cameraStream(entityId).collect {
                    frame = it
                    vm.onUserInteraction()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 動画がだめなら、しばらく静止画を1秒ごとに更新してから、もう一度つなぐ
                onNote("動画につなげないため、静止画を1秒ごとに更新しています（${e.message}）")
                repeat(20) {
                    vm.cameraSnapshot(entityId, 1920)?.let { frame = it }
                    vm.onUserInteraction()
                    delay(1000)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        frame?.let { Image(it, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
