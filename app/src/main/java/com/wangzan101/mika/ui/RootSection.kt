package com.wangzan101.mika.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.setup.RootControl
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons

/** 设置 › 手机操作里 root 那一组,接上真实状态:只在找得到 su 时显示。[helperRow] 为假时(安卓 13 以下)不显示。 */
@Composable
fun RootSwitches(helperRow: Boolean) {
    val context = LocalContext.current
    val root = remember { PhoneServices.of(context).shellManager.root }
    // 打开这一页时重新找一次 su(用户可能刚装上或去掉了 root)
    LaunchedEffect(Unit) { root.refresh() }
    val state by root.state.collectAsState()
    if (!state.suPresent || !helperRow) return
    RootSection(state, onStart = root::setStart, onRetryStart = root::retryStart)
}

/** root 一组:「用 root 启动手机助手」,默认关;上次被拒或没回应时,点这一行再问一次 su。 */
@Composable
fun RootSection(state: RootControl.State, onStart: (Boolean) -> Unit, onRetryStart: () -> Unit) {
    GroupLabel(PhoneSetupCopy.ROOT_GROUP)
    GroupCard {
        val retries = PhoneSetupCopy.rootStartRetries(state.start, state.startGrant)
        ListRow(
            MikaIcons.Key, PhoneSetupCopy.ROOT_START, PhoneSetupCopy.rootStartLine(state.startGrant), first = true,
            tone = if (retries) TileTone.Warn else TileTone.Neutral,
            onClick = if (retries) onRetryStart else ({ onStart(!state.start) }),
        ) { MikaSwitch(state.start, onStart, Modifier.testTag("root-start")) }
    }
    FooterNote(PhoneSetupCopy.ROOT_NOTE)
}
