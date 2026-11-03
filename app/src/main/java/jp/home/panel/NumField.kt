package jp.home.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun numStr(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)

/**
 * 数値（温度・湿度など）の入力：−／＋ のボタンと、文字（数字）での入力の両方ができる。
 * 入力中も、範囲内の数字になったところで onChange する。入力を終えたら step の刻みにそろえる。
 */
@Composable
fun NumField(
    value: Double,
    onChange: (Double) -> Unit,
    unit: String = "℃",
    step: Double = 0.5,
    min: Double = 10.0,
    max: Double = 35.0,
    big: Boolean = false,
) {
    var text by remember { mutableStateOf(numStr(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(value) {
        if (!focused || text.replace(',', '.').toDoubleOrNull() != value) text = numStr(value)
    }
    fun snap(v: Double) = (Math.round(v / step) * step).coerceIn(min, max).let { Math.round(it * 10) / 10.0 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilledTonalButton(onClick = { onChange(snap(value - step)) }, enabled = value > min) { Text("−", fontSize = if (big) 22.sp else 16.sp) }
        OutlinedTextField(
            value = text,
            onValueChange = { s ->
                val t = s.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
                text = t
                t.replace(',', '.').toDoubleOrNull()?.let { v -> if (v in min..max) onChange(Math.round(v * 10) / 10.0) }
            },
            singleLine = true,
            suffix = { Text(unit) },
            textStyle = TextStyle(fontSize = if (big) 24.sp else 18.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.width(if (big) 120.dp else 104.dp).onFocusChanged { f ->
                if (focused && !f.isFocused) {
                    // 入力を終えたら、刻みにそろえて範囲に入れる
                    val v = text.replace(',', '.').toDoubleOrNull()?.let(::snap) ?: value
                    if (v != value) onChange(v)
                    text = numStr(v)
                }
                focused = f.isFocused
            },
        )
        FilledTonalButton(onClick = { onChange(snap(value + step)) }, enabled = value < max) { Text("＋", fontSize = if (big) 22.sp else 16.sp) }
    }
}
