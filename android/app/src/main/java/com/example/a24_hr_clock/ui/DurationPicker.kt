package com.example.a24_hr_clock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.a24_hr_clock.logic.PlanTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Duration picker: a 12-hour dial (1–12 on the rim, 0 inside at the top) then a minute dial,
 * or a typed "H:MM" up to 24:00. Durations over 12:59 open in typed mode.
 */
@Composable
internal fun DurationPickerDialog(initial: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    var typed by rememberSaveable { mutableStateOf(initial > PlanTime.DIAL_MAX + 59) }
    var hours by rememberSaveable { mutableIntStateOf((initial / 60).coerceAtMost(12)) }
    var minutes by rememberSaveable { mutableIntStateOf(initial % 60) }
    var pickingMinutes by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(PlanTime.formatDuration(initial)) }
    val typedValue = PlanTime.parseDuration(text)
    val selected = if (typed) typedValue else (hours * 60 + minutes).takeIf { it > 0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Duration") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (typed) {
                    OutlinedTextField(text, { text = it }, singleLine = true,
                        label = { Text("Duration (H:MM)") },
                        isError = typedValue == null,
                        supportingText = { Text(if (typedValue == null) "Enter 0:01 to 24:00." else "Up to 24 hours.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.testTag("duration-typed"))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DialReadout(hours.toString(), !pickingMinutes, "Select hours") { pickingMinutes = false }
                        Text(":", style = MaterialTheme.typography.displayMedium)
                        DialReadout("%02d".format(minutes), pickingMinutes, "Select minutes") { pickingMinutes = true }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (pickingMinutes) MinuteDial(minutes) { minutes = it }
                    else HourDial(hours) { value, released -> hours = value; if (released) pickingMinutes = true }
                    if (selected == null) Text("Choose at least one minute.", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    if (typed) typedValue?.let { v ->
                        if (v <= PlanTime.DIAL_MAX + 59) { hours = v / 60; minutes = v % 60 }
                    } else selected?.let { text = PlanTime.formatDuration(it) }
                    typed = !typed
                }, modifier = Modifier.testTag("duration-mode")) {
                    Text(if (typed) "Use the 12-hour dial" else "Type a duration (up to 24 h)")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onSet) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun DialReadout(value: String, active: Boolean, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (active) scheme.primaryContainer else scheme.surfaceContainerHighest)
        .clickable(onClick = onClick).semantics { contentDescription = description; selected = active }
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.displayMedium,
            color = if (active) scheme.onPrimaryContainer else scheme.onSurface)
    }
}

private val DIAL = 256.dp
private val LABEL = 40.dp

/** One clickable number on the dial, at [angle] degrees clockwise from 12 o'clock. */
private data class DialLabel(val value: Int, val text: String, val angle: Double, val inner: Boolean, val description: String)

/** Hours follow the finger; [onSelect]'s flag is true on release, which moves on to minutes. */
@Composable
private fun HourDial(hours: Int, onSelect: (Int, Boolean) -> Unit) {
    val labels = (1..12).map { DialLabel(it, it.toString(), it * 30.0, false, "$it hours") } +
        DialLabel(0, "0", 0.0, true, "0 hours")
    Dial(labels, hours, handAngle = hours * 30.0, handInner = hours == 0, valueAt = { angle, inner ->
        if (inner && (angle < 30 || angle > 330)) 0
        else Math.floorMod((angle / 30).roundToInt(), 12).let { if (it == 0) 12 else it }
    }, onPick = onSelect)
}

@Composable
private fun MinuteDial(minutes: Int, onSelect: (Int) -> Unit) {
    val labels = (0 until 60 step 5).map { DialLabel(it, "%02d".format(it), it * 6.0, false, "$it minutes") }
    Dial(labels, minutes, handAngle = minutes * 6.0, handInner = false,
        valueAt = { angle, _ -> Math.floorMod((angle / 6).roundToInt(), 60) },
        onPick = { value, _ -> onSelect(value) })
}

@Composable
private fun Dial(labels: List<DialLabel>, selected: Int, handAngle: Double, handInner: Boolean,
                 valueAt: (angle: Double, inner: Boolean) -> Int, onPick: (value: Int, released: Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val dialPx = with(density) { DIAL.toPx() }
    val labelPx = with(density) { LABEL.toPx() }
    val outerR = dialPx / 2 - labelPx / 2 - with(density) { 4.dp.toPx() }
    val innerR = outerR - labelPx * 1.15f
    val latestValueAt by rememberUpdatedState(valueAt)
    val latestPick by rememberUpdatedState(onPick)
    Box(Modifier.size(DIAL).clip(CircleShape).background(scheme.surfaceContainerHighest)
        .testTag("duration-dial")
        .pointerInput(Unit) {
            fun valueAt(p: Offset): Int {
                val dx = p.x - size.width / 2f
                val dy = p.y - size.height / 2f
                val angle = (Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())) + 360) % 360
                return latestValueAt(angle, hypot(dx, dy) < (outerR + innerR) / 2)
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var last = valueAt(down.position)
                latestPick(last, false)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    last = valueAt(change.position)
                    latestPick(last, false)
                }
                latestPick(last, true)
            }
        }) {
        Canvas(Modifier.matchParentSize()) {
            val r = if (handInner) innerR else outerR
            val rad = Math.toRadians(handAngle)
            val end = Offset(center.x + (r * sin(rad)).toFloat(), center.y - (r * cos(rad)).toFloat())
            drawLine(scheme.primary, center, end, strokeWidth = 2.dp.toPx())
            drawCircle(scheme.primary, 4.dp.toPx(), center)
            drawCircle(scheme.primary, labelPx / 2, end)
        }
        labels.forEach { label ->
            val r = if (label.inner) innerR else outerR
            val rad = Math.toRadians(label.angle)
            val x = dialPx / 2 + r * sin(rad) - labelPx / 2
            val y = dialPx / 2 - r * cos(rad) - labelPx / 2
            val isSelected = label.value == selected
            Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(LABEL)
                .clip(CircleShape)
                .clickable { onPick(label.value, true) }
                .semantics { contentDescription = label.description; this.selected = isSelected },
                contentAlignment = Alignment.Center) {
                Text(label.text, style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) scheme.onPrimary else scheme.onSurface)
            }
        }
    }
}
