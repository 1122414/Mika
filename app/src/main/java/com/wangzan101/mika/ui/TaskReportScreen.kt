package com.wangzan101.mika.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.record.PlaybackMark
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun TaskReportEntries(onReport: () -> Unit, onReplay: () -> Unit) {
    val c = MikaTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .clickable(onClick = onReport).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(MikaIcons.Doc, null, Modifier.size(26.dp), tint = c.accent)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.report_title), style = MikaType.BodyStrong, color = c.ink)
                Text(stringResource(R.string.report_entry_hint), style = MikaType.Hint, color = c.ink2)
            }
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink3)
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onReplay)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(MikaIcons.Route, null, Modifier.size(18.dp), tint = c.accent)
            Text(stringResource(R.string.playback_title), Modifier.weight(1f), style = MikaType.Caption, color = c.accent)
            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink3)
        }
    }
}

@Composable
fun TaskReportScreen(vm: TaskReportViewModel, onBack: () -> Unit, onReplay: (Int) -> Unit, onTimeline: () -> Unit) {
    BackHandler(onBack = onBack)
    var loaded by remember(vm) { mutableStateOf(false) }
    val report by produceState<TaskReport?>(null, vm) {
        value = withContext(Dispatchers.IO) { vm.load() }
        loaded = true
    }
    val c = MikaTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.report_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            val r = report
            if (r == null) {
                if (loaded) EmptyNote(TimelineUi.EXPIRED_TEXT, Modifier.padding(top = 70.dp))
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, bottom + 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item("goal") { Text(r.header.goal, style = MikaType.LargeTitle, color = c.ink) }
                item("status") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.answer).padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.header.outcome, Modifier.weight(1f), style = MikaType.BodyStrong, color = c.ink)
                        r.header.duration?.let { Text(it, style = MikaType.Caption, color = c.ink2) }
                    }
                }
                if (r.header.criteria.isNotEmpty()) item("criteria") {
                    Text(r.header.criteria.joinToString("\n"), style = MikaType.Caption, color = c.ink2)
                }
                item("answer") {
                    if (r.answer.isBlank()) Text(stringResource(R.string.report_no_answer), style = MikaType.Body, color = c.ink2)
                    else MarkdownText(r.answer, MikaType.Body.copy(color = c.ink), Modifier.fillMaxWidth(), reportStyle = true)
                }
                if (r.playback.isNotEmpty()) {
                    item("screens-heading") { Text(stringResource(R.string.report_screens), style = MikaType.BodyStrong, color = c.ink) }
                    // 抽取至多四个不同步骤作入口；完整序列在回放中。
                    val evidence = r.playback.indices.let { indices ->
                        if (indices.count() <= 4) indices.toList() else listOf(0, indices.last / 3, indices.last * 2 / 3, indices.last)
                    }
                    itemsIndexed(evidence, key = { _, index -> "evidence-$index" }) { _, index ->
                        val step = r.playback[index]
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
                            .clickable { onReplay(index) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PlaybackImage(vm, step.afterId ?: step.beforeId, null, Modifier.width(46.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stepLabel(step), style = MikaType.BodyStrong, color = c.ink)
                                Text(stringResource(R.string.report_view_step, step.step), style = MikaType.Hint, color = c.ink2)
                            }
                            Icon(MikaIcons.Chevron, null, Modifier.size(16.dp), tint = c.ink3)
                        }
                    }
                }
                item("replay") { MikaButton(stringResource(R.string.playback_title), { onReplay(0) }, Modifier.fillMaxWidth(), kind = ButtonKind.Primary) }
                item("timeline") { MikaButton(stringResource(R.string.chat_view_process), onTimeline, Modifier.fillMaxWidth(), kind = ButtonKind.Tonal) }
                item("privacy") { Text(stringResource(R.string.report_local_note), style = MikaType.Hint, color = c.ink2) }
            }
        }
    }
}

@Composable
private fun stepLabel(step: PlaybackStep): String {
    val labels = remember { ToolLabels(knownTools()) }
    return step.tool?.let(labels::label) ?: stringResource(R.string.step_number, step.step)
}

@Composable
fun TaskPlaybackScreen(vm: TaskReportViewModel, initialStep: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var loaded by remember(vm) { mutableStateOf(false) }
    val report by produceState<TaskReport?>(null, vm) {
        value = withContext(Dispatchers.IO) { vm.load() }
        loaded = true
    }
    val c = MikaTheme.colors
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var position by rememberSaveable(vm.runId, initialStep) { mutableIntStateOf(-1) }
    var speed by rememberSaveable(vm.runId) { mutableIntStateOf(1) }
    // 不保存自动播放状态；切换页面、应用进入后台时暂停。
    var playing by remember(vm) { mutableStateOf(false) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) playing = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val steps = report?.playback.orEmpty()
    val frames = remember(steps) { steps.flatMapIndexed { index, step -> step.frames.map { index to it } } }
    LaunchedEffect(frames, initialStep) {
        if (position < 0 && frames.isNotEmpty()) position = frames.indexOfFirst { it.first == initialStep }.coerceAtLeast(0)
    }
    val current = position.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
    LaunchedEffect(playing, speed, current) {
        if (playing) {
            delay(900L / speed)
            if (current < frames.lastIndex) position = current + 1 else playing = false
        }
    }
    fun seek(frame: Int) { playing = false; position = frame.coerceIn(0, (frames.size - 1).coerceAtLeast(0)) }
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.playback_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            if (frames.isEmpty()) {
                if (loaded) EmptyNote(stringResource(if (report == null) R.string.timeline_expired else R.string.playback_empty), Modifier.padding(24.dp))
            } else {
                val (index, frame) = frames[current]
                val step = steps[index]
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, bottom + 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item("image") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            PlaybackImage(vm, frame.screenshotId, step.mark.takeIf { frame.after == false }, Modifier.widthIn(max = 280.dp).fillMaxWidth())
                            Text(stringResource(if (step.legacy) R.string.playback_legacy else if (step.mark?.approximate == true) R.string.playback_approximate else R.string.playback_marks),
                                Modifier.padding(top = 8.dp), style = MikaType.Hint, color = c.ink2)
                        }
                    }
                    item("current") {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.answer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.playback_position, index + 1, steps.size), style = MikaType.Caption, color = c.ink2)
                            Text(stepLabel(step), style = MikaType.BodyStrong, color = c.ink)
                            if (!step.succeeded) Text(stringResource(R.string.result_error), style = MikaType.Caption, color = c.bad)
                            if (!step.legacy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(false, true).forEach { after ->
                                    MikaButton(stringResource(if (after) R.string.playback_after else R.string.playback_before),
                                        { seek(frames.indexOfFirst { it.first == index && it.second.after == after }) }, Modifier.weight(1f),
                                        kind = if (frame.after == after) ButtonKind.Primary else ButtonKind.Tonal, size = ButtonSize.Small)
                                }
                            }
                        }
                    }
                    item("controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val progressLabel = stringResource(R.string.playback_progress)
                            Slider(current.toFloat(), { seek(it.toInt()) }, valueRange = 0f..frames.lastIndex.coerceAtLeast(1).toFloat(),
                                steps = (frames.size - 2).coerceAtLeast(0), enabled = frames.size > 1,
                                modifier = Modifier.semantics { contentDescription = progressLabel })
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MikaButton(stringResource(if (playing) R.string.playback_pause else R.string.playback_play), {
                                    if (playing) playing = false else { if (current == frames.lastIndex) position = 0; playing = true }
                                }, Modifier.weight(1f), kind = ButtonKind.Primary)
                                MikaButton("${speed}×", { speed = if (speed == 1) 2 else 1 }, kind = ButtonKind.Tonal)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MikaButton(stringResource(R.string.playback_previous), { seek(frames.indexOfFirst { it.first == index - 1 }) },
                                    Modifier.weight(1f), enabled = index > 0, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                                MikaButton(stringResource(R.string.playback_next), { seek(frames.indexOfFirst { it.first == index + 1 }) },
                                    Modifier.weight(1f), enabled = index < steps.lastIndex, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                            }
                        }
                    }
                    itemsIndexed(steps, key = { i, _ -> "step-$i" }) { i, s ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (i == index) c.surface else c.bg)
                            .clickable { seek(frames.indexOfFirst { it.first == i }) }.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${i + 1}", style = MikaType.Caption, color = c.ink2)
                            Text(stepLabel(s), style = MikaType.Caption, color = if (i == index) c.accent else c.ink2)
                        }
                    }
                    item("readonly") { Text(stringResource(R.string.playback_readonly), style = MikaType.Hint, color = c.ink2) }
                }
            }
        }
    }
}

@Composable
private fun PlaybackImage(vm: TaskReportViewModel, id: Int?, mark: PlaybackMark?, modifier: Modifier) {
    // 每次只解码当前帧；key 防止异步加载期间把新标记叠在旧画面上。
    key(vm.runId, id) {
        var loaded by remember { mutableStateOf(false) }
        val image by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) { runCatching { id?.let(vm::screen)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }.getOrNull() }
            loaded = true
        }
        val c = MikaTheme.colors
        val bitmap = image
        val description = stringResource(R.string.playback_image)
        if (bitmap == null) Box(modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(c.fill), contentAlignment = Alignment.Center) {
            if (loaded) Text(stringResource(R.string.playback_missing), Modifier.padding(8.dp), style = MikaType.Hint, color = c.ink2)
        } else Box(modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height).clip(RoundedCornerShape(16.dp))) {
            Image(bitmap, description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            mark?.takeIf { it.valid() }?.let { m ->
                Canvas(Modifier.matchParentSize().testTag("playback-mark-${m.kind}")) {
                    fun point(x: Int, y: Int) = Offset(x / 999f * size.width, y / 999f * size.height)
                    val start = point(m.x, m.y)
                    if (m.kind == "tap") {
                        drawCircle(c.bad.copy(alpha = .15f), 16.dp.toPx(), start)
                        drawCircle(c.bad, 13.dp.toPx(), start, style = Stroke(2.5.dp.toPx()))
                    } else {
                        val end = point(m.endX!!, m.endY!!)
                        drawLine(c.bad, start, end, 3.dp.toPx())
                        val angle = atan2(end.y - start.y, end.x - start.x)
                        val head = 12.dp.toPx()
                        drawPath(Path().apply {
                            moveTo(end.x, end.y)
                            lineTo(end.x - head * cos(angle - .5f), end.y - head * sin(angle - .5f))
                            lineTo(end.x - head * cos(angle + .5f), end.y - head * sin(angle + .5f)); close()
                        }, c.bad)
                    }
                }
            }
        }
    }
}
