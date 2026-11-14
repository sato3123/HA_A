package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 設定の保存の状態 */
data class SaveStatus(val state: Int, val at: Long = System.currentTimeMillis(), val where: String = "", val msg: String = "") {
    companion object {
        const val IDLE = 0
        const val SAVING = 1
        const val SAVED = 2
        const val CONFIRMED = 3
        const val ERROR = 4
    }
}

/**
 * 設定の画面の上に、いつも出しておく「保存」の帯。
 * この画面の設定は、選ぶ・切り替える・数字を入れると、その場で保存される（帯に「✓ 保存しました」と出る）。
 * 「保存」ボタンは、入力中の欄を確定して、家のサーバーに設定が残っているかを確かめる（違っていれば送り直す）。
 */
@Composable
fun SettingsSaveBar(vm: MainViewModel) {
    val st by vm.saveStatus.collectAsState()
    val focus = LocalFocusManager.current
    val t = SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(st.at))
    val (icon, text, color) = when (st.state) {
        SaveStatus.SAVING -> Triple("…", "保存しています…", MaterialTheme.colorScheme.onSurfaceVariant)
        SaveStatus.SAVED -> Triple("✓", "保存しました（$t・${st.where}）", MaterialTheme.colorScheme.primary)
        SaveStatus.CONFIRMED -> Triple("✓", "保存されています（$t に${st.where}で確かめました）" + if (st.msg.isNotBlank()) "・${st.msg}" else "", MaterialTheme.colorScheme.primary)
        SaveStatus.ERROR -> Triple("⚠", "保存できませんでした：${st.msg}", MaterialTheme.colorScheme.error)
        else -> Triple("ⓘ", "この画面の設定は、選ぶとその場で保存されます", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text("$icon $text", style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = if (st.state == SaveStatus.ERROR) FontWeight.Bold else null)
            }
            Button(
                onClick = { focus.clearFocus(); vm.confirmSaved() },
                enabled = st.state != SaveStatus.SAVING,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("保存") }
        }
    }
}
