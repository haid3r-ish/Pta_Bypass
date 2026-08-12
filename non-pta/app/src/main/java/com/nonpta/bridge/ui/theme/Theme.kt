package com.nonpta.bridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AmoledDark = darkColorScheme(
    primary            = Cyan400,
    onPrimary          = Black,
    primaryContainer   = Cyan800,
    onPrimaryContainer = TextPrimary,
    secondary          = Cyan600,
    onSecondary        = Black,
    background         = Black,
    onBackground       = TextPrimary,
    surface            = Surface1,
    onSurface          = TextPrimary,
    surfaceVariant     = Surface2,
    onSurfaceVariant   = TextSecondary,
    surfaceContainerLow  = Surface1,
    surfaceContainer     = Surface2,
    surfaceContainerHigh = Surface3,
    error              = Red400,
    onError            = Black,
    outline            = Surface3,
    outlineVariant     = Surface4,
)

@Composable
fun P2PBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmoledDark,
        typography  = BridgeTypography,
        content     = content
    )
}
