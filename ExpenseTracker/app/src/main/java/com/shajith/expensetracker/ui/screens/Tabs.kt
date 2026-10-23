package com.shajith.expensetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shajith.expensetracker.data.Expense
import com.shajith.expensetracker.data.money
import com.shajith.expensetracker.ui.MonthState
import com.shajith.expensetracker.ui.MonthTotal
import com.shajith.expensetracker.ui.YearState
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp)

// ---------------- Budget vs Actual ----------------

@Composable
fun BudgetTab(
    state: MonthState,
    currency: String,
    onEditBudget: (com.shajith.expensetracker.data.CategoryRow) -> Unit,
    onCopyPrevious: () -> Unit,
    onAddCategory: () -> Unit,
) {
    LazyColumn(contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SummaryCard("This month", state.totalBudget, state.totalActual, currency) }
        if (state.loaded && state.totalBudget == 0.0) {
            item {
                OutlinedButton(onClick = onCopyPrevious, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy forecast from previous month")
                }
            }
        }
        item {
            Text(
                "Tap a category to set its forecast",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(state.rows, key = { it.category }) { row ->
            CategoryCard(row, currency) { onEditBudget(row) }
        }
        item {
            TextButton(onClick = onAddCategory) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Add category")
            }
        }
    }
}

// ---------------- Expenses list ----------------

private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM")

@Composable
fun ExpensesTab(state: MonthState, currency: String, onEdit: (Expense) -> Unit) {
    if (state.loaded && state.expenses.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No expenses recorded this month.\nTap \"Add expense\" to record one.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val byDay = state.expenses.groupBy { it.date }
    LazyColumn(contentPadding = LIST_PADDING) {
        item {
            Text(
                "${state.expenses.size} expenses · ${money(state.totalActual, currency)}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        byDay.forEach { (day, list) ->
            item(key = "h_$day") {
                val label = runCatching { LocalDate.parse(day).format(DAY_LABEL) }.getOrDefault(day)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        money(list.sumOf { it.amount }, currency),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            items(list, key = { it.id }) { e ->
                ListItem(
                    headlineContent = { Text(e.category) },
                    supportingContent = if (e.note.isNotBlank()) ({ Text(e.note) }) else null,
                    trailingContent = { Text(money(e.amount, currency), fontWeight = FontWeight.SemiBold) },
                    modifier = Modifier.clickable { onEdit(e) },
                )
            }
        }
    }
}

// ---------------- Year overview ----------------

private val SHORT_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

@Composable
fun YearTab(state: YearState, currency: String, onOpenMonth: (YearMonth) -> Unit) {
    val maxValue = state.months.maxOfOrNull { maxOf(it.budget, it.actual) }?.takeIf { it > 0 } ?: 1.0
    LazyColumn(contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SummaryCard("Whole year", state.totalBudget, state.totalActual, currency) }
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(MaterialTheme.colorScheme.outlineVariant, "Forecast")
                Spacer(Modifier.width(16.dp))
                Legend(MaterialTheme.colorScheme.primary, "Actual")
                Spacer(Modifier.weight(1f))
                Text("Tap a month to open it", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    state.months.forEach { MonthBar(it, maxValue, currency) { onOpenMonth(it.month) } }
                }
            }
        }
        if (state.categories.isNotEmpty()) {
            item {
                Text("By category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(state.categories, key = { "y_" + it.category }) { CategoryCard(it, currency, null) }
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(12.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MonthBar(m: MonthTotal, max: Double, currency: String, onClick: () -> Unit) {
    val over = m.actual > m.budget && m.budget > 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.month.format(SHORT_MONTH), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Bar((m.budget / max).toFloat(), MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(3.dp))
            Bar((m.actual / max).toFloat(), if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Text(
            if (m.budget == 0.0 && m.actual == 0.0) "—" else diffLabel(m.budget - m.actual, currency),
            style = MaterialTheme.typography.labelSmall,
            color = if (m.budget == 0.0 && m.actual == 0.0) MaterialTheme.colorScheme.onSurfaceVariant
            else diffColor(m.budget - m.actual),
            textAlign = TextAlign.End,
            modifier = Modifier.width(120.dp),
        )
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(7.dp)) {
        if (fraction > 0f) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp)).background(color)
            )
        }
    }
}
