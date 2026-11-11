package jp.home.panel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 長い説明文：はじめは2行だけ見せ、「続きを読む」で全部を表示する（表示だけ。文章は変えない）。
 * 2行に収まる短い文は、そのまま表示する。
 */
@Composable
fun HelpText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var over by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) over = it.hasVisualOverflow },
        )
        if (over || open) {
            Text(
                if (open) "たたむ ▴" else "続きを読む ▾",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clickable(role = Role.Button) { open = !open }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
