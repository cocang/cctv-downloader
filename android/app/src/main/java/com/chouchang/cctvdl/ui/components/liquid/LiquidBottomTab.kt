/*
 * Adapted from Kyant0/AndroidLiquidGlass catalog (Apache License 2.0).
 * Source revision: 896a94a3ade1cc1a940b92365f942a34971fecda (Android 1.0.6).
 * https://github.com/Kyant0/AndroidLiquidGlass
 * Changes: selected semantics and a non-interactive hidden sampling layer.
 * Full license: app/src/main/assets/licenses/backdrop-demo-LICENSE.txt
 * Further adapted from woliubang256/kudo-android (Apache License 2.0) for CCTV 下载器.
 */
package com.chouchang.cctvdl.ui.components.liquid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }
internal val LocalLiquidBottomTabSampling = staticCompositionLocalOf { false }

@Composable
internal fun RowScope.LiquidBottomTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalLiquidBottomTabScale.current
    val sampling = LocalLiquidBottomTabSampling.current
    Column(
        modifier
            .clip(Capsule())
            .then(if (sampling) Modifier else Modifier
                .semantics { this.selected = selected }
                .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick))
            .fillMaxHeight().weight(1f)
            .graphicsLayer {
                val value = scale()
                scaleX = value
                scaleY = value
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
