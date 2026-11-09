package com.ispeech.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ispeech.settings.store.SettingsStore
import com.ispeech.settings.store.ThemeMode

// 浅色配色方案：品牌蓝紫主色
private val IspeechLightColorScheme = lightColorScheme(
    primary = IspeechPrimary,
    onPrimary = Color.White,
    primaryContainer = IspeechPrimaryContainer,
    onPrimaryContainer = Color(0xFF131B66),
    secondary = IspeechSecondary,
    onSecondary = Color.White,
    secondaryContainer = IspeechSecondaryContainer,
    onSecondaryContainer = Color(0xFF2D0B54),
    background = IspeechLightBackground,
    onBackground = IspeechLightOnBackground,
    surface = IspeechLightSurface,
    onSurface = IspeechLightOnBackground,
    surfaceVariant = IspeechLightSurfaceVariant,
    onSurfaceVariant = IspeechLightOnSurfaceVariant,
    outline = IspeechLightOutline,
)

// 深色配色方案：主色 / 次色使用提亮变体
private val IspeechDarkColorScheme = darkColorScheme(
    primary = IspeechPrimaryDark,
    onPrimary = Color(0xFF1A2270),
    primaryContainer = IspeechPrimaryContainerDark,
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = IspeechSecondaryDark,
    onSecondary = Color(0xFF3B1063),
    secondaryContainer = IspeechSecondaryContainerDark,
    onSecondaryContainer = Color(0xFFF2E4FF),
    background = IspeechDarkBackground,
    onBackground = IspeechDarkOnBackground,
    surface = IspeechDarkSurface,
    onSurface = IspeechDarkOnBackground,
    surfaceVariant = IspeechDarkSurfaceVariant,
    onSurfaceVariant = IspeechDarkOnSurfaceVariant,
    outline = IspeechDarkOutline,
)

/**
 * 智语 iSpeech 全局主题。
 *
 * 外观模式（浅色 / 深色 / 跟随系统）持久化在 DataStore，由「设置 - 主题」页修改；
 * 这里订阅 [SettingsStore.themeModeFlow] 实时生效。
 *
 * @param dynamicColor 是否启用 Android 12+（API 31+）动态取色，默认关闭以保持品牌配色
 */
@Composable
fun IspeechTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 读取持久化的主题模式（默认跟随系统）
    val themeModeFlow = remember(context) { SettingsStore.themeModeFlow(context) }
    val themeMode by themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> IspeechDarkColorScheme
        else -> IspeechLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = IspeechTypography,
        content = content,
    )
}
