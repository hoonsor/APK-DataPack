package tw.hoonsor.datapack.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 唯一色票（docs/DESIGN.md）。全部 HSL，不用原色 */
object C {
    val bg = Color.hsl(220f, 0.26f, 0.11f)
    val surface = Color.hsl(220f, 0.24f, 0.19f)
    val surfaceHi = Color.hsl(220f, 0.22f, 0.22f)
    val panel = Color.hsl(220f, 0.24f, 0.16f)
    val line = Color.hsl(220f, 0.20f, 0.28f)
    val lineHi = Color.hsl(220f, 0.20f, 0.34f)
    val text = Color.hsl(220f, 0.25f, 0.95f)
    val textSoft = Color.hsl(220f, 0.20f, 0.86f)
    val muted = Color.hsl(220f, 0.15f, 0.74f)
    val accent = Color.hsl(172f, 0.55f, 0.52f)
    val onAccent = Color.hsl(200f, 0.40f, 0.10f)
    val accentDeep = Color.hsl(172f, 0.40f, 0.26f)
    val accentSoft = Color.hsl(172f, 0.70f, 0.82f)
    val warn = Color.hsl(38f, 0.90f, 0.72f)
    val warnLine = Color.hsl(38f, 0.70f, 0.50f)
    val warnBg = Color.hsl(38f, 0.45f, 0.17f)
    val danger = Color.hsl(0f, 0.80f, 0.84f)
    val dangerLine = Color.hsl(0f, 0.45f, 0.45f)
    val dangerBg = Color.hsl(0f, 0.30f, 0.20f)
    val ok = Color.hsl(150f, 0.48f, 0.62f)
    val scrim = Color.hsl(220f, 0.30f, 0.04f).copy(alpha = 0.72f)
    val paper = Color.hsl(40f, 0.25f, 0.93f)

    // 從待辦 APP 複製來的 App 內更新畫面用到的名稱
    val border = line
    val dim = Color.hsl(220f, 0.10f, 0.60f)
    val warnText = warn

    /** 資料包色：依代號雜湊挑一個，新增資料包不用改程式 */
    private val hues = listOf(172f, 205f, 38f, 330f, 265f, 100f, 12f, 190f)
    fun pack(key: String) = Color.hsl(hues[key.hashCode().mod(hues.size)], 0.70f, 0.72f)
}

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    labelMedium = TextStyle(fontSize = 13.sp),
)

@Composable
fun DataPackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.onAccent, secondary = C.warn,
            background = C.bg, onBackground = C.text, surface = C.surface, onSurface = C.text,
            surfaceVariant = C.surfaceHi, onSurfaceVariant = C.muted, surfaceContainer = C.panel,
            surfaceContainerHigh = C.surfaceHi, surfaceContainerHighest = C.surfaceHi,
            outline = C.lineHi, outlineVariant = C.line, error = C.danger,
        ),
        typography = typography,
        content = content,
    )
}
