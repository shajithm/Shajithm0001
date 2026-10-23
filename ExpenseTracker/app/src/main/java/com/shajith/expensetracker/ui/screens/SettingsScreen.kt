package com.shajith.expensetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shajith.expensetracker.data.Prefs

@Composable
fun SettingsScreen(
    email: String,
    prefs: Prefs,
    onCurrency: (String) -> Unit,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onExport: () -> Unit,
    onSignOut: () -> Unit,
) {
    var currency by remember { mutableStateOf(prefs.currency) }
    LaunchedEffect(prefs.currency) { currency = prefs.currency }
    var newCategory by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Section("Account") }
        item { Text("Signed in as $email", style = MaterialTheme.typography.bodyMedium) }

        item { Section("Currency") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.take(5) },
                    label = { Text("Currency code, e.g. AED") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = currency.isNotBlank() && currency.trim().uppercase() != prefs.currency,
                    onClick = { onCurrency(currency) },
                ) { Text("Save") }
            }
        }

        item { Section("Categories") }
        items(prefs.categories, key = { "c_$it" }) { c ->
            ListItem(
                headlineContent = { Text(c) },
                trailingContent = {
                    IconButton(onClick = { onRemoveCategory(c) }) { Icon(Icons.Filled.Delete, "Remove $c") }
                },
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text("New category") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = newCategory.isNotBlank(),
                    onClick = { onAddCategory(newCategory); newCategory = "" },
                ) { Text("Add") }
            }
        }
        item {
            Text(
                "Removing a category keeps its past forecasts and expenses — they still appear in reports.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { Section("Data") }
        item {
            Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Export all data to Excel (.xlsx)")
            }
        }
        item {
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
