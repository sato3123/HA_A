package jp.home.panel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var afterMicGrant: (() -> Unit)? = null

    // 設定の書き出し（保存先を選ぶ画面）と読み込み（ファイルを選ぶ画面）
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(vm.exportSettings().toByteArray()) }
                vm.onExported(true)
            } catch (e: Exception) {
                vm.onExported(false, e.message)
            }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            } catch (e: Exception) {
                ""
            }
            if (vm.importSettings(text)) {
                // 新しい設定で起動し直す
                window.decorView.postDelayed({
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }, 1200)
            }
        }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) afterMicGrant?.invoke()
            afterMicGrant = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 壁掛けパネルとして使うので画面を消さない
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val themeId by vm.themeId.collectAsState()
            MaterialTheme(colorScheme = paletteOf(themeId).colorScheme()) {
                App(
                    vm,
                    onMic = { withMic { vm.onMicPressed() } },
                    onEnableWake = { withMic { vm.setWakeEnabled(true) } },
                )
                CrashReportDialog()
            }
        }
    }

    init {
        // インストール画面・許可設定画面を開く
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.fileRequest.collect { kind ->
                    if (kind == "export") {
                        val date = java.time.LocalDate.now().toString()
                        exportLauncher.launch("homepanel-settings-$date.json")
                    } else {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.launchRequest.collect { intent ->
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "画面を開けませんでした: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 画面に触れたらスクリーンセーバーを消し、操作していない時間を数え直す */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        vm.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
        vm.onResume()
    }

    override fun onPause() {
        // 画面を離れたらマイクの待ち受けを止める
        vm.setForeground(false)
        super.onPause()
    }

    private fun withMic(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterMicGrant = action
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** 前回アプリが止まっていたら、その原因を表示する（コピーして送れるように） */
@androidx.compose.runtime.Composable
private fun CrashReportDialog() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashLog.read(ctx)) }
    val t = text ?: return
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { CrashLog.clear(ctx); text = null },
        title = { androidx.compose.material3.Text("前回アプリが止まりました") },
        text = {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                androidx.compose.material3.Text("原因の調査に使います。「コピー」を押して、内容を送ってください。")
                androidx.compose.material3.Text(t, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                clip.setText(androidx.compose.ui.text.AnnotatedString(t))
            }) { androidx.compose.material3.Text("コピー") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { CrashLog.clear(ctx); text = null }) {
                androidx.compose.material3.Text("閉じる")
            }
        },
    )
}

