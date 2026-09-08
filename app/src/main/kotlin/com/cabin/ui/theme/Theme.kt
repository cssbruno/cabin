package com.cabin.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** App palettes, not a claim of automotive display certification or hardware glare tuning. */
internal fun cabinColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFB7CCFF),
            onPrimary = Color(0xFF13244D),
            primaryContainer = Color(0xFF233453),
            onPrimaryContainer = Color(0xFFDAE4FF),
            secondary = Color(0xFFC6CDD9),
            onSecondary = Color(0xFF252C39),
            secondaryContainer = Color(0xFF252B36),
            onSecondaryContainer = Color(0xFFDCE2EC),
            tertiary = Color(0xFFF4BF70),
            onTertiary = Color(0xFF422C08),
            tertiaryContainer = Color(0xFF5C411C),
            onTertiaryContainer = Color(0xFFFFDEB1),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(0xFF0B0D11),
            onBackground = Color(0xFFEEF1F6),
            surface = Color(0xFF0B0D11),
            onSurface = Color(0xFFEEF1F6),
            surfaceContainerLowest = Color(0xFF080A0E),
            surfaceContainerLow = Color(0xFF15191F),
            surfaceContainer = Color(0xFF1A1F27),
            surfaceContainerHigh = Color(0xFF20262F),
            surfaceContainerHighest = Color(0xFF2A323D),
            surfaceVariant = Color(0xFF363F4C),
            onSurfaceVariant = Color(0xFFAAB4C3),
            outline = Color(0xFF7F8A9B),
            outlineVariant = Color(0xFF343C48),
            inverseSurface = Color(0xFFEEF1F6),
            inverseOnSurface = Color(0xFF223138),
            inversePrimary = Color(0xFF385B9B),
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF385B9B),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE6FF),
            onPrimaryContainer = Color(0xFF142F60),
            secondary = Color(0xFF45616B),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCE2EC),
            onSecondaryContainer = Color(0xFF203E48),
            tertiary = Color(0xFF78530C),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFDEB1),
            onTertiaryContainer = Color(0xFF49300A),
            error = Color(0xFFBA1A1A),
            onError = Color.White,
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            background = Color(0xFFF3F8FA),
            onBackground = Color(0xFF16262D),
            surface = Color(0xFFF3F8FA),
            onSurface = Color(0xFF16262D),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFEDF3F5),
            surfaceContainer = Color(0xFFE6EFF2),
            surfaceContainerHigh = Color(0xFFDFEAEE),
            surfaceContainerHighest = Color(0xFFD8E5E9),
            surfaceVariant = Color(0xFFDBE7EB),
            onSurfaceVariant = Color(0xFF435C66),
            outline = Color(0xFF687F88),
            outlineVariant = Color(0xFFBCCFD6),
            inverseSurface = Color(0xFF23353D),
            inverseOnSurface = Color(0xFFEAF2F5),
            inversePrimary = Color(0xFFB7CCFF),
            scrim = Color.Black,
        )
    }

private val DarkColorScheme = cabinColorScheme(true)
private val LightColorScheme = cabinColorScheme(false)

/** Consistent app branding by default; dynamic color is optional and API guarded. */
@Composable
fun CabinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(view.context) else dynamicLightColorScheme(view.context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    // A wrapped/dialog/preview host must not crash just to set system-bar appearance.
    if (!view.isInEditMode) {
        LaunchedEffect(view, darkTheme, colorScheme) {
            view.context.findActivity()?.window?.let { window ->
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CabinTypography,
        shapes = CabinShapes,
        content = content,
    )
}

internal fun Context.findActivity(): Activity? {
    var candidate = this
    val seen = mutableSetOf<Context>()
    while (seen.add(candidate)) {
        if (candidate is Activity) return candidate
        candidate = (candidate as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/** Scalable sp text: full labels can wrap instead of disabling system font scaling. */
val CabinTypography =
    Typography(
        displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 64.sp),
        displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp),
        headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 27.sp),
        bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    )

private val CabinShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** Shared dimensions; compact controls remain generous without exhausting short displays. */
object AutomotiveDimens {
    val ButtonMinHeight = 72.dp
    val CompactButtonMinHeight = 56.dp
    val ButtonPaddingHorizontal = 24.dp
    val ButtonPaddingVertical = 20.dp
    val IconSize = 28.dp
}
