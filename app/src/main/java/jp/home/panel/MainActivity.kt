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
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var afterMicGrant: (() -> Unit)? = null

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFB74D),
                    primaryContainer = Color(0xFF5D4020),
                    onPrimaryContainer = Color(0xFFFFE0B2),
                )
            ) {
                App(
                    vm,
                    onMic = { withMic { vm.onMicPressed() } },
                    onEnableWake = { withMic { vm.setWakeEnabled(true) } },
                )
            }
        }
    }

    init {
        // インストール画面・許可設定画面を開く
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
