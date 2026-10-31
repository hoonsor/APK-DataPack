package tw.hoonsor.datapack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.hoonsor.datapack.ui.theme.C

val R24 = RoundedCornerShape(24.dp)
val R20 = RoundedCornerShape(20.dp)

/** 主要按鈕：實心 */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(if (enabled) C.accent else C.line)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) C.onAccent else C.muted, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

/** 次要按鈕：有邊框，文字依用途變色 */
@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = C.text, border: Color = C.lineHi, bg: Color = C.panel, min: Dp = 48.dp, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = min).clip(R24).background(bg).border(1.dp, border, R24).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 15.sp, textAlign = TextAlign.Center) }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier.heightIn(min = 40.dp).clip(shape).background(if (selected) C.accentDeep else C.panel)
            .border(1.dp, if (selected) C.accent else C.line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) C.accentSoft else C.textSoft, fontSize = 14.sp) }
}

@Composable
fun Card(modifier: Modifier = Modifier, border: Color = C.line, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(R20).background(C.surface).border(1.dp, border, R20)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    single: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    BasicTextField(
        value, onChange, singleLine = single,
        textStyle = TextStyle(color = C.text, fontSize = 16.sp),
        cursorBrush = SolidColor(C.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.heightIn(min = 52.dp).clip(R20).background(C.panel).border(1.dp, C.lineHi, R20).padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = C.muted, fontSize = 15.sp)
                inner()
            }
        },
    )
}

/** 頁首：標題＋一行摘要 */
@Composable
fun Header(title: String, sub: String = "", back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (back != null) OutlineButton("返回", min = 44.dp, onClick = back)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) Text(sub, color = C.muted, fontSize = 14.sp)
        }
    }
}

/** 底部膠囊導覽 */
@Composable
fun BottomNav(tab: Tab, reviewCount: Int, onSelect: (Tab) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(C.panel).border(1.dp, C.line, shape).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tab.entries.forEach { t ->
            val on = t == tab
            val label = if (t == Tab.Review && reviewCount > 0) "${t.label} $reviewCount" else t.label
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (on) C.accentDeep else Color.Transparent).clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) C.accentSoft else if (t == Tab.Review && reviewCount > 0) C.warn else C.muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

enum class Tab(val label: String) { Packs("資料包"), Search("查詢"), Review("待確認"), Settings("設定") }
