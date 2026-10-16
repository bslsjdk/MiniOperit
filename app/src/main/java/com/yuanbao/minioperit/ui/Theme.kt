package com.yuanbao.minioperit.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- 品牌色 ----
val BrandIndigo = Color(0xFF6366F1)
val BrandViolet = Color(0xFF8B5CF6)
val BrandCyan = Color(0xFF22D3EE)
val BrandPink = Color(0xFFF472B6)

// ---- 深色（默认，观感优先） ----
val DarkBg = Color(0xFF0B0D17)
val DarkSurface = Color(0xFF151827)
val DarkSurfaceAlt = Color(0xFF1D2133)
val DarkText = Color(0xFFE8EAF2)
val DarkTextDim = Color(0xFF9AA1B9)

val DarkColors = darkColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2F52),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF332A52),
    onSecondaryContainer = Color(0xFFE7DEFF),
    tertiary = BrandCyan,
    onTertiary = Color(0xFF04222B),
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkTextDim,
    outline = Color(0xFF2C3149),
    error = Color(0xFFFF6B6B)
)

// ---- 浅色 ----
val LightBg = Color(0xFFF7F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceAlt = Color(0xFFEEF1F8)
val LightText = Color(0xFF14182B)
val LightTextDim = Color(0xFF646B85)

val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E5FF),
    onPrimaryContainer = Color(0xFF1B2060),
    secondary = BrandViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBE2FF),
    onSecondaryContainer = Color(0xFF2E1F63),
    tertiary = Color(0xFF0E7490),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightTextDim,
    outline = Color(0xFFD8DCE8),
    error = Color(0xFFD64545)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.sp
    )
)

/** 顶部渐变（品牌色 → 背景），用于 AppBar / Hero 区域 */
fun brandBrush(dark: Boolean): Brush = Brush.horizontalGradient(
    listOf(
        BrandIndigo.copy(alpha = if (dark) 0.28f else 0.18f),
        BrandViolet.copy(alpha = if (dark) 0.20f else 0.12f),
        Color.Transparent
    )
)

/** 用户气泡渐变 */
fun userBubbleBrush(dark: Boolean): Brush = Brush.linearGradient(
    listOf(
        BrandIndigo,
        BrandViolet
    )
)
