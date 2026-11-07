package com.wangzan101.mika.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.focus.onFocusChanged
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
    LaunchedEffect(release.apkUrl, held, resumed) {
        if (resumed && !held) {
            delay(3000)
            dismiss()
        }
    }

    Row(
        modifier.testTag("update-ready-capsule")
            .onFocusChanged { focused = it.hasFocus }.focusGroup()
            .clip(CircleShape).background(colors.bg).border(1.dp, colors.hair, CircleShape)
            .hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.about_auto_update_ready, release.version),
            modifier = Modifier.widthIn(max = 250.dp)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onInstall)
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            style = MikaType.Caption,
            color = colors.ink,
        )
        Box(
            Modifier.size(44.dp).clickable(role = Role.Button, onClick = onDisable),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MikaIcons.Close, stringResource(R.string.about_auto_update_disable), Modifier.size(14.dp), tint = colors.ink3)
        }
    }
}
