package com.example.a24_hr_clock.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.a24_hr_clock.logic.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ExerciseScreen(
    repository: ExerciseRepository,
    modelSettings: ModelSettings,
    onUpdateModel: (ModelSettings) -> Unit
) {
    val scope = rememberCoroutineScope()
    val dailyMetrics by repository.dailyMetricsFlow.collectAsState(initial = emptyList())
    val sessions by repository.sessionsFlow.collectAsState(initial = emptyList())
    val checkIns by repository.checkInsFlow.collectAsState(initial = emptyList())
    val weeklyGoals by repository.weeklyGoalsFlow.collectAsState(initial = WeeklyGoals())
    
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var isSyncing by remember { mutableStateOf(false) }

    val todayMetrics = dailyMetrics.find { it.date == selectedDate.toString() }
    val todayCheckIn = checkIns.find { it.date == selectedDate.toString() }
    
    // Calculate recommendation using rolling signals
    val recommendation = remember(selectedDate, dailyMetrics, checkIns, weeklyGoals, modelSettings) {
        val last7Days = (1..7).map { selectedDate.minusDays(it.toLong()).toString() }
        val last7Metrics = dailyMetrics.filter { it.date in last7Days }
        
        val hrv7DayAvg = last7Metrics.mapNotNull { it.hrv }.average().takeIf { !it.isNaN() }
        val rhr7DayAvg = last7Metrics.mapNotNull { it.rhr }.average().takeIf { !it.isNaN() }
        
        RecommendationEngine.generateRecommendation(
            date = selectedDate,
            hrv7DayAvg = hrv7DayAvg,
            hrvBaseline = modelSettings.hrvMedicatedBase,
            rhr7DayAvg = rhr7DayAvg,
            rhrBaseline = modelSettings.restingHR,
            sleep7DayAvg = null, // Will need to bridge sleep logs to metrics or repo
            sleepNeed = modelSettings.bedtimeGoal,
            recentLoad = last7Metrics.map { it.hrss }.average().takeIf { !it.isNaN() } ?: 0.0,
            trainingGoal = weeklyGoals,
            checkIn = todayCheckIn,
            dataQuality = mapOf(
                "hrv_stale" to (hrv7DayAvg == null)
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ExerciseHeader(
            selectedDate = selectedDate,
            isSyncing = isSyncing,
            onRefresh = {
                scope.launch {
                    isSyncing = true
                    repository.syncExerciseData(selectedDate.minusDays(6), selectedDate)
                    isSyncing = false
                }
            },
            onDateChange = { selectedDate = it }
        )

        Spacer(modifier = Modifier.height(16.dp))

        RecommendationCard(recommendation)

        Spacer(modifier = Modifier.height(24.dp))

        ReadinessCheckIn(
            todayCheckIn = todayCheckIn,
            onCheckIn = { updated ->
                scope.launch { repository.saveCheckIn(updated) }
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        WeeklyPlanProgress(
            goals = weeklyGoals,
            sessions = sessions.filter { 
                val date = LocalDate.parse(it.date)
                date.isAfter(LocalDate.now().minusDays(LocalDate.now().dayOfWeek.value.toLong())) 
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        WorkoutTimeline(sessions = sessions.filter { it.date == selectedDate.toString() })

        Spacer(modifier = Modifier.height(24.dp))

        ExerciseTrends(
            metrics = dailyMetrics.filter { 
                val date = LocalDate.parse(it.date)
                !date.isAfter(LocalDate.now()) && date.isAfter(LocalDate.now().minusDays(28))
            },
            modelSettings = modelSettings
        )

        Spacer(modifier = Modifier.height(24.dp))

        CalibrationSection(
            modelSettings = modelSettings,
            onUpdateModel = onUpdateModel
        )
    }
}

@Composable
fun ExerciseHeader(
    selectedDate: LocalDate,
    isSyncing: Boolean,
    onRefresh: () -> Unit,
    onDateChange: (LocalDate) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Exercise", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = if (selectedDate == LocalDate.now()) "Today, ${selectedDate.format(DateTimeFormatter.ofPattern("MMM d"))}" 
                       else selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Row {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Day")
            }
            IconButton(
                onClick = onRefresh,
                enabled = !isSyncing
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync")
                }
            }
            IconButton(
                onClick = { if (selectedDate.isBefore(LocalDate.now())) onDateChange(selectedDate.plusDays(1)) },
                enabled = selectedDate.isBefore(LocalDate.now())
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next Day")
            }
        }
    }
}

@Composable
fun RecommendationCard(recommendation: Recommendation) {
    val color = when (recommendation.state) {
        RecommendationState.BUILD -> Color(0xFF4CAF50)
        RecommendationState.MAINTAIN -> Color(0xFF2196F3)
        RecommendationState.RECOVER -> Color(0xFFFF9800)
        RecommendationState.INSUFFICIENT_DATA -> Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color, RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${recommendation.headline} · ${recommendation.confidence} confidence",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = recommendation.suggestions,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            if (recommendation.reasons.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                recommendation.reasons.forEach { reason ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(text = "•", modifier = Modifier.padding(end = 8.dp))
                        Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            
            if (recommendation.alternatives.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Alternatives:", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recommendation.alternatives.forEach { alt ->
                        SuggestionChip(
                            onClick = { },
                            label = { Text(alt) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReadinessCheckIn(
    todayCheckIn: CheckIn?,
    onCheckIn: (CheckIn) -> Unit
) {
    val date = LocalDate.now().toString()
    val checkIn = todayCheckIn ?: CheckIn(date = date)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "How are you feeling?", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CheckInIcon(
                    icon = Icons.Default.Sick,
                    label = "Ill",
                    isSelected = checkIn.symptoms?.contains("ill") == true,
                    onClick = { 
                        onCheckIn(checkIn.copy(symptoms = if (checkIn.symptoms == "ill") null else "ill"))
                    }
                )
                CheckInIcon(
                    icon = Icons.Default.Warning,
                    label = "Pain",
                    isSelected = (checkIn.pain ?: 0) > 0,
                    onClick = { 
                        onCheckIn(checkIn.copy(pain = if ((checkIn.pain ?: 0) > 0) 0 else 5))
                    }
                )
                CheckInIcon(
                    icon = Icons.Default.FitnessCenter,
                    label = "Sore",
                    isSelected = (checkIn.soreness ?: 0) > 0,
                    onClick = { 
                        onCheckIn(checkIn.copy(soreness = if ((checkIn.soreness ?: 0) > 0) 0 else 5))
                    }
                )
                CheckInIcon(
                    icon = Icons.Default.BatteryAlert,
                    label = "Tired",
                    isSelected = (checkIn.fatigue ?: 0) > 0,
                    onClick = { 
                        onCheckIn(checkIn.copy(fatigue = if ((checkIn.fatigue ?: 0) > 0) 0 else 5))
                    }
                )
            }
        }
    }
}

@Composable
fun CheckInIcon(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun WeeklyPlanProgress(
    goals: WeeklyGoals,
    sessions: List<ExerciseSession>
) {
    val moderateMins = sessions.filter { it.type == "moderate" }.sumOf { it.durationMinutes }
    val vigorousMins = sessions.filter { it.type == "vigorous" }.sumOf { it.durationMinutes }
    val strengthDays = sessions.filter { it.type == "strength" }.distinctBy { it.date }.size

    Column {
        Text(text = "Weekly Progress", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        
        GoalProgressBar(
            label = "Aerobic Minutes",
            current = moderateMins + (vigorousMins * 2),
            goal = goals.moderateMinutesGoal,
            unit = "min"
        )
        Spacer(modifier = Modifier.height(12.dp))
        GoalProgressBar(
            label = "Strength Training",
            current = strengthDays,
            goal = goals.strengthDaysGoal,
            unit = "days"
        )
    }
}

@Composable
fun GoalProgressBar(label: String, current: Int, goal: Int, unit: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = "$current / $goal $unit", style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = (current.toFloat() / goal.toFloat()).coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .padding(top = 4.dp),
            strokeCap = StrokeCap.Round
        )
    }
}

@Composable
fun WorkoutTimeline(sessions: List<ExerciseSession>) {
    if (sessions.isEmpty()) return
    
    Column {
        Text(text = "Today's Workouts", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        sessions.forEach { session ->
            WorkoutCard(session)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun WorkoutCard(session: ExerciseSession) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DirectionsRun, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = session.type, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(text = "${session.durationMinutes} min · ${session.load ?: 0.0} load", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun ExerciseTrends(
    metrics: List<DailyExerciseMetrics>,
    modelSettings: ModelSettings
) {
    Column {
        Text(text = "Trends (Last 28 Days)", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        
        TrendPanel(
            label = "Cardio Load",
            data = metrics.map { it.hrss.toFloat() },
            color = Color(0xFF00E5FF),
            unit = "%"
        )
        Spacer(modifier = Modifier.height(16.dp))
        TrendPanel(
            label = "HRV",
            data = metrics.mapNotNull { it.hrv?.toFloat() },
            color = Color(0xFFFFB300),
            unit = "ms",
            baseline = modelSettings.hrvMedicatedBase.toFloat()
        )
    }
}

@Composable
fun TrendPanel(
    label: String,
    data: List<Float>,
    color: Color,
    unit: String,
    baseline: Float? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
                if (data.isNotEmpty()) {
                    Text(text = "${data.last().toInt()} $unit", style = MaterialTheme.typography.labelSmall)
                }
            }
            
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (data.size > 1) {
                        val width = size.width
                        val height = size.height
                        val maxVal = (data.maxOrNull() ?: 100f).coerceAtLeast(1f) * 1.2f
                        val xStep = width / (data.size - 1)
                        
                        val points = data.mapIndexed { i, v ->
                            Offset(i * xStep, height - (v / maxVal) * height)
                        }
                        
                        val path = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                        
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        
                        if (baseline != null) {
                            val baseY = height - (baseline / maxVal) * height
                            drawLine(
                                color = color.copy(alpha = 0.5f),
                                start = Offset(0f, baseY),
                                end = Offset(width, baseY),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalibrationSection(
    modelSettings: ModelSettings,
    onUpdateModel: (ModelSettings) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Data and Calibration", style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
        
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                // Moved from MainActivity.kt
                // Use new sliders/entry for maxHR, etc.
                Text("Age: ${modelSettings.userAge}", style = MaterialTheme.typography.bodyMedium)
                Text("Resting HR: ${modelSettings.restingHR} BPM", style = MaterialTheme.typography.bodyMedium)
                Text("Max HR: ${modelSettings.maxHR} BPM", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
