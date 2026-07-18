package com.example.a24_hr_clock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.a24_hr_clock.logic.MetricHelpContent
import com.example.a24_hr_clock.logic.MetricHelpId
import com.example.a24_hr_clock.logic.MetricHelpLevel

@Composable
fun ExerciseMetricHelpDialog(
    metricId: MetricHelpId,
    onDismiss: () -> Unit
) {
    val entry = MetricHelpContent.entry(metricId)
    var level by remember(metricId) { mutableStateOf(MetricHelpLevel.ELEMENTARY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(entry.shortName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    entry.fullName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (level != MetricHelpLevel.ELEMENTARY) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = entry.elementary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricHelpLevel.entries.forEach { option ->
                        FilterChip(
                            selected = level == option,
                            onClick = { level = option },
                            label = {
                                Text(
                                    when (option) {
                                        MetricHelpLevel.ELEMENTARY -> "Elementary"
                                        MetricHelpLevel.INTERMEDIATE -> "Intermediate"
                                        MetricHelpLevel.ADVANCED -> "Advanced"
                                    }
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = entry.textFor(level),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun ExerciseMetricsGlossary(
    onOpenHelp: (MetricHelpId) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Metrics glossary",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricHelpContent.entries.forEach { entry ->
                MetricHelpChip(
                    label = entry.shortName,
                    onClick = { onOpenHelp(entry.id) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ExerciseChartLegend(
    onOpenHelp: (MetricHelpId) -> Unit,
    modifier: Modifier = Modifier
) {
    val hrssColor = Color(0xFF00E5FF)
    val hrvColor = Color(0xFFFFB300)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendHelpItem(
                swatchColor = hrssColor,
                label = "HRSS",
                onClick = { onOpenHelp(MetricHelpId.HRSS) }
            )
            LegendHelpItem(
                swatchColor = hrvColor,
                label = "HRV",
                onClick = { onOpenHelp(MetricHelpId.HRV) }
            )
            LegendHelpItem(
                swatchColor = null,
                label = "TRIMP (scrub)",
                onClick = { onOpenHelp(MetricHelpId.TRIMP) }
            )
        }
    }
}

@Composable
private fun MetricHelpChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "About $label",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LegendHelpItem(
    swatchColor: Color?,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (swatchColor != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(swatchColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "About $label",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
