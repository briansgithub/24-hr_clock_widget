package com.example.a24_hr_clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.a24_hr_clock.logic.ClockDial
import com.example.a24_hr_clock.logic.PlanOrder
import com.example.a24_hr_clock.logic.PlanTime
import com.example.a24_hr_clock.logic.NewTaskTimes
import com.example.a24_hr_clock.logic.PlanField
import com.example.a24_hr_clock.logic.PlannedTask
import com.example.a24_hr_clock.logic.endMinute
import com.example.a24_hr_clock.logic.taskNameCapitalization
import com.example.a24_hr_clock.logic.withDuration
import com.example.a24_hr_clock.logic.withEnd
import com.example.a24_hr_clock.logic.withStart
import java.time.LocalTime

@Composable
fun PlanScreen(
    tasks: List<PlannedTask>,
    onSave: (PlannedTask) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (id: String, toIndex: Int) -> Unit = { _, _ -> },
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    dailyClearEnabled: Boolean = false,
    onDailyClearChange: (Boolean) -> Unit = {},
    clock: @Composable (Modifier) -> Unit
) {
    // The table is always ordered by start time; dragging a row asks onMove to rewrite its times.
    val table = remember(tasks) { PlanOrder.sorted(tasks) }
    val listState = rememberLazyListState()
    val reorder = remember { RowReorder() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }
    val latestTable by rememberUpdatedState(table)
    val latestTasks by rememberUpdatedState(tasks)
    LaunchedEffect(tasks) { if (reorder.releasedFor != null && reorder.releasedFor !== tasks) reorder.clear() }
    // Scroll the page while a row is held near the top or bottom edge, and keep the row under the finger.
    LaunchedEffect(reorder.id) {
        if (reorder.id == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (reorder.finishing) continue
            val from = reorder.from(latestTable).takeIf { it >= 0 } ?: continue
            val height = reorder.heights[latestTable[from].id] ?: continue
            val top = reorder.rowTop + reorder.offset
            val speed = when {
                top < reorder.viewTop + edgePx -> -(reorder.viewTop + edgePx - top)
                top + height > reorder.viewBottom - edgePx -> top + height - (reorder.viewBottom - edgePx)
                else -> 0f
            }.coerceIn(-edgePx, edgePx) / edgePx * 24f
            if (speed != 0f) {
                val used = listState.scrollBy(speed)
                reorder.offset = reorder.clamp(latestTable, reorder.offset + used)
            }
        }
    }
    fun finishDrag() {
        val id = reorder.id ?: return
        if (reorder.finishing) return
        reorder.finishing = true
        scope.launch {
            val to = reorder.target(latestTable)
            val settle = Animatable(reorder.offset)
            settle.animateTo(reorder.snapOffset(latestTable, to), tween(120)) { reorder.offset = value }
            val before = latestTasks
            if (PlanOrder.move(before, id, to) == before) reorder.clear()
            else {
                reorder.releasedFor = before
                onMove(id, to)
                delay(1500)
                if (reorder.releasedFor === before) reorder.clear()
            }
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().onGloballyPositioned {
        reorder.viewTop = it.positionInWindow().y
        reorder.viewBottom = reorder.viewTop + it.size.height
    }, contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.testTag("plan-undo")) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.testTag("plan-redo")) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                }
            }
        }
        item {
            clock(Modifier.fillMaxWidth().height(280.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Clear all tasks daily at 6:00 am", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = dailyClearEnabled, onCheckedChange = onDailyClearChange,
                    modifier = Modifier.testTag("plan-daily-clear"))
            }
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth().testTag("task-table")) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = HANDLE_WIDTH, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Task", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("Start", Modifier.width(TIME_CELL), style = MaterialTheme.typography.labelMedium)
                    Text("End", Modifier.width(TIME_CELL), style = MaterialTheme.typography.labelMedium)
                    Text("Dur.", Modifier.width(DURATION_CELL), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(48.dp))
                }
                table.forEachIndexed { index, task ->
                    key(task.id) {
                        ReorderableSlot(task, index, table, reorder, haptic, ::finishDrag, onMove) {
                            TaskTableRow(task, onSave, onDelete)
                        }
                    }
                }
                NewTaskRow(NewTaskTimes.defaultStartAfter(table), onSave)
            }
        }
    }
}

@Composable
private fun TaskTableRow(task: PlannedTask, onSave: (PlannedTask) -> Unit, onDelete: (String) -> Unit) {
    // Times save the moment they are picked. The name saves when the field loses focus or the
    // keyboard sends Done; a blank name is never saved and reverts to the saved one.
    var name by rememberSaveable(task.id, task.name) { mutableStateOf(task.name) }
    val focus = LocalFocusManager.current
    fun commitName() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) name = task.name
        else if (trimmed != task.name) onSave(task.copy(name = trimmed))
        else name = trimmed
    }
    Column(Modifier.fillMaxWidth().testTag("task-${task.id}").padding(top = 8.dp, bottom = 8.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                TableCell(name, { name = it }, "Task name", "Task name for ${task.name}",
                    "task-name-${task.id}", Modifier.fillMaxWidth(), name.isBlank(),
                    color = Color(task.color), onDone = { commitName(); focus.clearFocus() },
                    onFocusLost = { commitName() })
            }
            // Start keeps the duration; End changes the duration; Duration moves the end.
            TimeCell("Start time", task.startMinute, task.startMinute,
                { onSave(task.withStart(it)) }, "task-start-${task.id}", "Start time for ${task.name}")
            TimeCell("End time", task.endMinute, task.endMinute,
                { onSave(task.withEnd(it)) }, "task-end-${task.id}", "End time for ${task.name}")
            DurationCell(task.durationMinutes, task.durationMinutes, { onSave(task.withDuration(it)) },
                "task-duration-${task.id}", "Duration for ${task.name}")
            IconButton(onClick = { onDelete(task.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${task.name}")
            }
        }
        if (name.isBlank()) ValidationHint(true)
    }
}

@Composable
private fun NewTaskRow(defaultStart: Int?, onSave: (PlannedTask) -> Unit) {
    // The row adds itself once it has a name and any two of Start, End and Duration; see
    // NewTaskTimes. Times are picked in dialogs, so each pick is a whole value and can add the
    // row right away. The name adds on focus loss or Done, never per keystroke.
    var name by rememberSaveable { mutableStateOf("") }
    var times by rememberSaveable { mutableStateOf(NewTaskTimes()) }
    val focus = LocalFocusManager.current
    fun tryAdd(current: NewTaskTimes = times): Boolean {
        val task = NewTaskTimes.taskFor(name, current, defaultStart) ?: return false
        onSave(task)
        name = ""
        times = NewTaskTimes()
        return true
    }
    fun pick(field: PlanField, value: Int) {
        val next = times.with(field, value)
        times = next
        if (tryAdd(next)) focus.clearFocus()
    }
    val resolved = times.resolve(defaultStart)
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    val shownStart = resolved?.first ?: times.start ?: defaultStart
    val shownDuration = resolved?.second ?: times.duration
    val shownEnd = resolved?.let { PlanTime.endMinute(it.first, it.second) } ?: times.end
    Column(Modifier.fillMaxWidth().testTag("new-task-row")
        .padding(start = HANDLE_WIDTH, top = 8.dp, bottom = 8.dp, end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TableCell(name, { name = it }, "New task", "New task name", "new-task-name", Modifier.weight(1f),
                onDone = { tryAdd(); focus.clearFocus() }, onFocusLost = { tryAdd() })
            TimeCell("Start time", shownStart, shownStart ?: now,
                { pick(PlanField.START, it) }, "new-task-start", "New task start time")
            TimeCell("End time", shownEnd, shownEnd ?: PlanTime.endMinute(shownStart ?: now, shownDuration ?: 60),
                { pick(PlanField.END, it) }, "new-task-end", "New task end time")
            DurationCell(shownDuration, shownDuration ?: 60, { pick(PlanField.DURATION, it) },
                "new-task-duration", "New task duration")
            FilledTonalIconButton(onClick = { tryAdd() }, enabled = NewTaskTimes.taskFor(name, times, defaultStart) != null) {
                Icon(Icons.Default.Add, contentDescription = "Add task")
            }
        }
    }
}

private fun startLabel(minute: Int): String {
    val hour = minute / 60
    return "%d:%02d %s".format(java.util.Locale.US, if (hour % 12 == 0) 12 else hour % 12,
        minute % 60, if (hour < 12) "AM" else "PM")
}

@Composable
private fun SelectorCell(value: String, tag: String, description: String, width: androidx.compose.ui.unit.Dp,
                         onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.width(width).heightIn(min = 48.dp).testTag(tag)
        .semantics { contentDescription = description }
        .background(scheme.surface, RoundedCornerShape(6.dp))
        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
        .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(value, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private val TIME_CELL = 76.dp
private val HANDLE_WIDTH = 20.dp
private val DURATION_CELL = 56.dp

/** [minute] is null while the new-task row has not been given this time; [initial] is where the dial opens. */
@Composable
private fun TimeCell(title: String, minute: Int?, initial: Int, onChange: (Int) -> Unit,
                     tag: String, description: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectorCell(minute?.let(::startLabel) ?: "--:--", tag, description, TIME_CELL) { open = true }
    if (open) DialPickerDialog(title, initial, false, { open = false }) {
        onChange(it)
        open = false
    }
}

@Composable
private fun DurationCell(minutes: Int?, initial: Int, onChange: (Int) -> Unit, tag: String, description: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectorCell(minutes?.let(PlanTime::formatDuration) ?: "-:--", tag, description, DURATION_CELL) { open = true }
    if (open) DurationPickerDialog(initial, { open = false }) {
        onChange(it)
        open = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialPickerDialog(title: String, initial: Int, is24Hour: Boolean,
                             onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val time = rememberTimePickerState(initialHour = initial / 60 % 24,
        initialMinute = initial % 60, is24Hour = is24Hour)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(time, modifier = Modifier.testTag("selector-clock"))
            }
        },
        confirmButton = { TextButton(onClick = { onSet(time.hour * 60 + time.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun TableCell(
    value: String, onChange: (String) -> Unit, placeholder: String, description: String,
    tag: String, modifier: Modifier, error: Boolean = false, numeric: Boolean = false,
    color: Color? = null, onDone: (() -> Unit)? = null, onFocusLost: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val latestFocusLost by rememberUpdatedState(onFocusLost)
    val latestDone by rememberUpdatedState(onDone)
    val shape = RoundedCornerShape(6.dp)
    val scheme = MaterialTheme.colorScheme
    BasicTextField(value, onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = taskNameCapitalization(value),
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { latestDone?.invoke() }),
        modifier = modifier.testTag(tag).semantics { contentDescription = description }
            .onFocusChanged {
                if (focused && !it.isFocused) latestFocusLost?.invoke()
                focused = it.isFocused
            },
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(scheme.surface, shape)
                .border(1.dp, when { error -> scheme.error; focused -> scheme.primary; else -> scheme.outlineVariant }, shape)
                .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    input()
                }
            }
        })
}

/**
 * One table row with its drag handle at the left edge. The handle is a quiet grip of dots; drag it and the row
 * lifts (shadow, a touch larger, a tick of haptic feedback) while the rows it passes slide aside. TalkBack gets
 * "Move up" and "Move down" actions on the same handle.
 */
@Composable
private fun ReorderableSlot(
    task: PlannedTask, index: Int, table: List<PlannedTask>, reorder: RowReorder,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback, onFinish: () -> Unit,
    onMove: (String, Int) -> Unit, content: @Composable () -> Unit
) {
    val latestTable by rememberUpdatedState(table)
    val latestFinish by rememberUpdatedState(onFinish)
    val active = reorder.active(table)
    val dragged = active && reorder.id == task.id
    val shiftTarget = if (active && !dragged) reorder.shift(table, index) else 0f
    val animatedShift by animateFloatAsState(shiftTarget, tween(160), label = "row-shift")
    val lift by animateFloatAsState(if (dragged) 1f else 0f, tween(120), label = "row-lift")
    val shape = RoundedCornerShape(8.dp)
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    val canReorder = table.size > 1
    Column(Modifier.fillMaxWidth()
        .onSizeChanged { reorder.heights[task.id] = it.height }
        .onGloballyPositioned { if (reorder.id == task.id) reorder.rowTop = it.positionInWindow().y }
        .zIndex(if (dragged) 1f else 0f)
        .graphicsLayer {
            translationY = if (dragged) reorder.offset else if (active) animatedShift else 0f
            scaleX = 1f + 0.015f * lift; scaleY = 1f + 0.015f * lift
            shadowElevation = 12.dp.toPx() * lift
            this.shape = shape
        }
        .background(if (dragged) surface else Color.Transparent, shape)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(HANDLE_WIDTH).height(48.dp)
                .testTag("task-handle-${task.id}")
                .semantics {
                    if (canReorder) {
                        contentDescription = "Reorder ${task.name}"
                        customActions = listOf(
                            CustomAccessibilityAction("Move up") { if (index > 0) onMove(task.id, index - 1); index > 0 },
                            CustomAccessibilityAction("Move down") { if (index < table.lastIndex) onMove(task.id, index + 1); index < table.lastIndex })
                    }
                }
                .then(if (!canReorder) Modifier else Modifier.pointerInput(task.id) {
                    detectDragGestures(
                        onDragStart = {
                            if (reorder.id != null) return@detectDragGestures
                            reorder.clear()
                            reorder.id = task.id
                            reorder.lastTarget = latestTable.indexOfFirst { it.id == task.id }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (reorder.id != task.id || reorder.finishing) return@detectDragGestures
                            reorder.offset = reorder.clamp(latestTable, reorder.offset + amount.y)
                            val target = reorder.target(latestTable)
                            if (target != reorder.lastTarget) {
                                reorder.lastTarget = target
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = { if (reorder.id == task.id) latestFinish() },
                        onDragCancel = { if (reorder.id == task.id) latestFinish() })
                }),
                contentAlignment = Alignment.Center) {
                if (canReorder) GripDots(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dragged) 0.9f else 0.45f))
            }
            Box(Modifier.weight(1f)) { content() }
        }
        HorizontalDivider()
    }
}

/** Two columns of three small dots: the usual quiet "grab here" mark. */
@Composable
private fun GripDots(color: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val r = 1.5.dp.toPx()
        for (col in 0..1) for (row in 0..2) {
            drawCircle(color, r, Offset(r + col * 7.dp.toPx(), r + row * 6.5.dp.toPx()))
        }
    }
}

@Composable
private fun ValidationHint(nameMissing: Boolean) {
    if (nameMissing) Text("Enter a task name.", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
}

/** Consume only task drags, allowing ordinary preview taps and list scrolling. */
@Composable
fun Modifier.taskWedgeDrag(
    tasks: List<PlannedTask>,
    enabled: Boolean,
    geometry: (IntSize) -> ClockDial,
    onPreview: (PlannedTask?) -> Unit,
    onCommit: (PlannedTask) -> Unit
): Modifier {
    val latestTasks by rememberUpdatedState(tasks)
    val latestGeometry by rememberUpdatedState(geometry)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestCommit by rememberUpdatedState(onCommit)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val dial = latestGeometry(size)
            val task = dial.taskAt(down.position.x, down.position.y, latestTasks) ?: return@awaitEachGesture
            val initial = dial.minuteAt(down.position.x, down.position.y) ?: return@awaitEachGesture
            val firstDrag = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                ?: return@awaitEachGesture
            var candidate = task
            fun move(x: Float, y: Float) {
                if (dial.minuteAt(x, y) == null) return
                candidate = task.copy(startMinute = dial.rotatedStart(task, initial, x, y))
                latestPreview(candidate)
            }
            try {
                move(firstDrag.position.x, firstDrag.position.y)
                val released = drag(firstDrag.id) { change ->
                    move(change.position.x, change.position.y)
                    change.consume()
                }
                if (released) latestCommit(candidate)
            } finally {
                latestPreview(null)
            }
        }
    }
}

/**
 * Drag state for reordering the table. Rows are only previewed while a handle is dragged: the dragged
 * row follows the finger, the rows it passes slide out of its way, and one [PlanOrder.move] is committed on release.
 */
@Stable
private class RowReorder {
    var id by mutableStateOf<String?>(null)
    var offset by mutableFloatStateOf(0f)
    var finishing by mutableStateOf(false)
    /** The plan as it was on release; the drag stays drawn until a different plan arrives from storage. */
    var releasedFor by mutableStateOf<List<PlannedTask>?>(null)
    val heights = mutableStateMapOf<String, Int>()
    var rowTop = 0f
    var viewTop = 0f
    var viewBottom = 0f
    var lastTarget = -1

    fun active(tasks: List<PlannedTask>) = id != null && !(releasedFor != null && releasedFor !== tasks)
    fun clear() { id = null; offset = 0f; finishing = false; releasedFor = null; lastTarget = -1 }

    private fun height(task: PlannedTask) = (heights[task.id] ?: 0).toFloat()
    private fun top(table: List<PlannedTask>, index: Int) = table.take(index).sumOf { heights[it.id] ?: 0 }.toFloat()
    fun from(table: List<PlannedTask>) = table.indexOfFirst { it.id == id }

    fun clamp(table: List<PlannedTask>, value: Float): Float {
        val from = from(table).takeIf { it >= 0 } ?: return 0f
        val total = table.sumOf { heights[it.id] ?: 0 }.toFloat()
        return value.coerceIn(-top(table, from), total - top(table, from) - height(table[from]))
    }

    /** The table index the dragged row would take if released now. */
    fun target(table: List<PlannedTask>): Int {
        val from = from(table).takeIf { it >= 0 } ?: return 0
        val center = top(table, from) + height(table[from]) / 2 + offset
        return table.indices.count { it != from && top(table, it) + height(table[it]) / 2 < center }
    }

    /** How far row [index] slides to open a gap for the dragged row. */
    fun shift(table: List<PlannedTask>, index: Int): Float {
        val from = from(table).takeIf { it >= 0 } ?: return 0f
        val to = target(table)
        val h = height(table[from])
        return when {
            from < to && index in (from + 1)..to -> -h
            from > to && index in to until from -> h
            else -> 0f
        }
    }

    /** Where the dragged row's top ends up relative to where it started, once dropped at [to]. */
    fun snapOffset(table: List<PlannedTask>, to: Int): Float {
        val from = from(table)
        val arranged = table.toMutableList().apply { add(to, removeAt(from)) }
        return top(arranged, to) - top(table, from)
    }
}
