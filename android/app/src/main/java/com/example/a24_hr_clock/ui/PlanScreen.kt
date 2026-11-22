package com.example.a24_hr_clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    dailyClearEnabled: Boolean = false,
    onDailyClearChange: (Boolean) -> Unit = {},
    clock: @Composable (Modifier) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
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
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Task", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Text("Start", Modifier.width(TIME_CELL), style = MaterialTheme.typography.labelMedium)
                    Text("End", Modifier.width(TIME_CELL), style = MaterialTheme.typography.labelMedium)
                    Text("Dur.", Modifier.width(DURATION_CELL), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(48.dp))
                }
                tasks.forEach { task ->
                    key(task.id) {
                        TaskTableRow(task, onSave, onDelete)
                        HorizontalDivider()
                    }
                }
                NewTaskRow(onSave)
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
    Column(Modifier.fillMaxWidth().testTag("task-${task.id}").padding(8.dp)) {
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
private fun NewTaskRow(onSave: (PlannedTask) -> Unit) {
    // The row adds itself once it has a name and any two of Start, End and Duration; see
    // NewTaskTimes. Times are picked in dialogs, so each pick is a whole value and can add the
    // row right away. The name adds on focus loss or Done, never per keystroke.
    var name by rememberSaveable { mutableStateOf("") }
    var times by rememberSaveable { mutableStateOf(NewTaskTimes()) }
    val focus = LocalFocusManager.current
    fun tryAdd(current: NewTaskTimes = times): Boolean {
        val task = NewTaskTimes.taskFor(name, current) ?: return false
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
    val resolved = times.resolve()
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    val shownStart = resolved?.first ?: times.start
    val shownDuration = resolved?.second ?: times.duration
    val shownEnd = resolved?.let { PlanTime.endMinute(it.first, it.second) } ?: times.end
    Column(Modifier.fillMaxWidth().testTag("new-task-row").padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TableCell(name, { name = it }, "New task", "New task name", "new-task-name", Modifier.weight(1f),
                onDone = { tryAdd(); focus.clearFocus() }, onFocusLost = { tryAdd() })
            TimeCell("Start time", shownStart, shownStart ?: now,
                { pick(PlanField.START, it) }, "new-task-start", "New task start time")
            TimeCell("End time", shownEnd, shownEnd ?: PlanTime.endMinute(shownStart ?: now, shownDuration ?: 60),
                { pick(PlanField.END, it) }, "new-task-end", "New task end time")
            DurationCell(shownDuration, shownDuration ?: 60, { pick(PlanField.DURATION, it) },
                "new-task-duration", "New task duration")
            FilledTonalIconButton(onClick = { tryAdd() }, enabled = NewTaskTimes.taskFor(name, times) != null) {
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
