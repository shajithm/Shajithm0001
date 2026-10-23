package com.shajith.expensetracker.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shajith.expensetracker.data.CategoryRow
import com.shajith.expensetracker.data.SyncStatus
import com.shajith.expensetracker.data.money
import com.shajith.expensetracker.ui.theme.positiveColor
import kotlin.math.abs

@Composable
fun PeriodSelector(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous") }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next") }
    }
}

@Composable
fun SyncBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val (color, text) = when (status) {
        SyncStatus.SYNCED -> positiveColor to "All changes synced"
        SyncStatus.PENDING -> MaterialTheme.colorScheme.tertiary to "Saved on phone — will sync when online"
        SyncStatus.OFFLINE -> MaterialTheme.colorScheme.outline to "Offline — showing saved data"
    }
    Row(modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun diffColor(diff: Double): Color = if (diff < 0) MaterialTheme.colorScheme.error else positiveColor

fun diffLabel(diff: Double, currency: String): String =
    if (diff < 0) "${money(abs(diff), currency)} over" else "${money(diff, currency)} left"

@Composable
fun SummaryCard(title: String, budget: Double, actual: Double, currency: String) {
    val diff = budget - actual
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("Forecast", money(budget, currency))
                Stat("Actual", money(actual, currency))
                Stat(if (diff < 0) "Over" else "Remaining", money(abs(diff), currency), diffColor(diff))
            }
            Spacer(Modifier.height(12.dp))
            val ratio = if (budget > 0) (actual / budget).toFloat() else if (actual > 0) 1f else 0f
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                color = if (diff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (budget > 0) "${"%.0f".format(ratio * 100)}% of forecast used" else "No forecast set yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryCard(row: CategoryRow, currency: String, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.category, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (row.budget > 0 || row.actual > 0) {
                    Text(diffLabel(row.diff, currency), style = MaterialTheme.typography.labelMedium, color = diffColor(row.diff))
                } else {
                    Text("Set forecast", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { row.ratio.coerceIn(0f, 1f) },
                color = if (row.diff < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "Forecast ${money(row.budget, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("Actual ${money(row.actual, currency)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    else Card(modifier = Modifier.fillMaxWidth()) { content() }
}
