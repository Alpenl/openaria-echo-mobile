package com.openaria.openaria_echo_mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.staticCompositionLocalOf

object EchoColors {
    val Void = Color(0xFF000000)
    val Deck = Color(0xFF07090A)
    val Ink = Color(0xFFF0F3F4)
    val InkSecondary = Color(0xFFAAB3B8)
    val InkMuted = Color(0xFF7D878C)
    val Record = Color(0xFFFF3B2D)
    val Caution = Color(0xFFE0A020)
    val Permit = Color(0xFF46C98A)
    val Live = Color(0xFF7FE3F5)
    val Peak = Color(0xFFE858FF)
    val Hair = Color.White.copy(alpha = 0.12f)
    val HairStrong = Color.White.copy(alpha = 0.19f)
    val Glass = Color(0xCC080A0B)
    val GlassStrong = Color(0xEE090B0C)
    val Sunken = Color.White.copy(alpha = 0.06f)
}

val LocalEchoTextStyle = staticCompositionLocalOf {
    TextStyle(
        color = EchoColors.Ink,
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        letterSpacing = 0.sp,
    )
}

@Composable
fun EchoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEchoTextStyle provides LocalEchoTextStyle.current) {
        content()
    }
}

@Composable
fun EchoText(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = EchoColors.Ink,
    style: TextStyle = LocalEchoTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    BasicText(
        text = value,
        modifier = modifier,
        style = style.copy(color = color, letterSpacing = 0.sp),
        maxLines = maxLines,
        overflow = overflow,
    )
}
