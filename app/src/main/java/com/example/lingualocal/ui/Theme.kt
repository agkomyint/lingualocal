package com.example.lingualocal.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** LinguaLocal palette (matches the design canvas). Amber always means Thai, blue always means English. */
object LL {
    val Bg = Color(0xFF0D0F13)
    val Side = Color(0xFF111318)
    val Surface = Color(0xFF15181E)
    val Raised = Color(0xFF1C2028)
    val Control = Color(0xFF262B35)
    val Line = Color(0xFF22262F)
    val LineStrong = Color(0xFF2A2F3A)
    val Text = Color(0xFFECEEF2)
    val Muted = Color(0xFFA3AAB8)
    val Faint = Color(0xFF7D8595)
    val Idle = Color(0xFF3A404C)
    val Thai = Color(0xFFF4B860)
    val English = Color(0xFF7AB4FF)
    val EnglishSoft = Color(0xFFA9CEFF)
    val Ok = Color(0xFF5FD39A)
    val Live = Color(0xFFFF7A7A)
    val Danger = Color(0xFFFF6B6B)
    val MineBubble = Color(0xFF18233A)
    val MineLine = Color(0xFF27395A)
    val Selected = Color(0xFF2A3B5C)

    val Burmese = Color(0xFF7FD4C1)

    fun lang(code: String) = when (code) { "th" -> Thai; "my" -> Burmese; else -> English }
}

private val scheme: ColorScheme = darkColorScheme(
    primary = LL.English,
    onPrimary = LL.Bg,
    secondary = LL.Thai,
    onSecondary = LL.Bg,
    background = LL.Bg,
    onBackground = LL.Text,
    surface = LL.Surface,
    onSurface = LL.Text,
    surfaceVariant = LL.Raised,
    onSurfaceVariant = LL.Muted,
    surfaceContainer = LL.Surface,
    surfaceContainerHigh = LL.Raised,
    surfaceContainerHighest = LL.Control,
    outline = LL.LineStrong,
    outlineVariant = LL.Line,
    error = LL.Danger,
)

@Composable
fun LinguaTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
