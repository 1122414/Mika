package com.wangzan101.mika.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.wangzan101.mika.R
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.delay

@Composable
internal fun UpdateReadyCapsule(
    release: ReleaseInfo,
    onInstall: () -> Unit,
    onTimeout: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MikaTheme.colors
    val reduceMotion = MikaTheme.reduceMotion
    val reveal = remember(release.apkUrl) { Animatable(if (reduceMotion) 1f else 0f) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val dismiss by rememberUpdatedState(onTimeout)
    val held = hovered || pressed || focused
    LaunchedEffect(reveal, held, resumed, reduceMotion) {
        if (!resumed) return@LaunchedEffect
        if (reduceMotion) reveal.snapTo(1f)
        else reveal.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
        if (!held) {
            delay(3000)
            if (reduceMotion) reveal.snapTo(0f)
            else reveal.animateTo(0f, tween(230, easing = FastOutLinearInEasing))
            dismiss()
        }
    }

    Row(
        modifier.testTag("update-ready-capsule")
            .graphicsLayer {
                alpha = reveal.value
                translationY = if (reduceMotion) 0f else -52.dp.toPx() * (1f - reveal.value)
            }
            .onFocusChanged { focused = it.hasFocus }.focusGroup()
            .shadow(12.dp, CircleShape, clip = false,
                ambientColor = colors.floatShadow.copy(alpha = 0.22f),
                spotColor = colors.floatShadow.copy(alpha = 0.32f))
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(colors.surface, colors.bg)))
            .border(1.dp, Brush.verticalGradient(listOf(colors.glassEdge, colors.edge)), CircleShape)
            .hoverable(interaction)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(CircleShape)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onInstall)
                .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(29.dp).background(colors.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MikaIcons.Check, null, Modifier.size(15.dp), tint = colors.accent)
            }
            Text(
                stringResource(R.string.about_auto_update_ready, release.version),
                modifier = Modifier.widthIn(max = 224.dp),
                style = MikaType.Caption,
                fontWeight = FontWeight.Medium,
                color = colors.ink,
            )
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDisable),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, stringResource(R.string.about_auto_update_disable), Modifier.size(14.dp), tint = colors.ink3)
        }
    }
}
