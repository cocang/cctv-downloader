package com.chouchang.cctvdl.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 品牌金：取自应用图标 */
val Accent = Color(0xFFF5B324)
val AccentDeep = Color(0xFFE29400)
val OnAccent = Color(0xFF231803)
val Danger = Color(0xFFFF5A5F)
val Success = Color(0xFF34C77B)

/** 玻璃材质 */
enum class GlassMode(val label: String, val minSdk: Int, val detail: String) {
    LIQUID("液态玻璃", 33, "折射、色散与高光，需要 Android 13 或以上"),
    BLUR("背景模糊", 31, "实时背景模糊，需要 Android 12 或以上"),
    TRANSLUCENT("半透明", 26, "轻量半透明材质，兼容所有设备，最省电"),
    ;

    val supported: Boolean get() = Build.VERSION.SDK_INT >= minSdk

    companion object {
        /** 设备能支持的最佳材质 */
        val best: GlassMode get() = entries.first { it.supported }

        /** 用户选择（null 为自动）→ 实际生效的材质（不支持时自动降级） */
        fun resolve(choice: GlassMode?): GlassMode {
            val want = choice ?: best
            return entries.drop(want.ordinal).first { it.supported }
        }
    }
}

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

val LocalGlass = staticCompositionLocalOf { GlassMode.TRANSLUCENT }
val LocalDark = staticCompositionLocalOf { false }

private val Light = lightColorScheme(
    primary = AccentDeep, onPrimary = Color.White,
    secondary = Color(0xFF3D5AFE),
    background = Color(0xFFEFF2F7), onBackground = Color(0xFF0E1116),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFFE6EAF1), onSurfaceVariant = Color(0xFF5B6372),
    outline = Color(0x1F0E1116), error = Danger,
)

private val Dark = darkColorScheme(
    primary = Accent, onPrimary = OnAccent,
    secondary = Color(0xFF8C9EFF),
    background = Color(0xFF05070B), onBackground = Color(0xFFF2F4F8),
    surface = Color(0xFF151922), onSurface = Color(0xFFF2F4F8),
    surfaceVariant = Color(0xFF232836), onSurfaceVariant = Color(0xFF9AA3B5),
    outline = Color(0x24FFFFFF), error = Danger,
)

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    )
}

@Composable
fun CctvTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = Type) {
        // 未指定颜色的文字与图标跟随主题前景色（没有 Surface 包裹时默认是黑色）
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onSurface, content = content)
    }
}
