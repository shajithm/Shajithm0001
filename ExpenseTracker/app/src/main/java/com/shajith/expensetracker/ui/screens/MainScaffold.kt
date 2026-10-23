package com.shajith.expensetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shajith.expensetracker.data.CategoryRow
import com.shajith.expensetracker.data.Expense
import com.shajith.expensetracker.export.ExcelExporter
import com.shajith.expensetracker.ui.AppViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Budget", Icons.Filled.Home),
    Tab("Expenses", Icons.AutoMirrored.Filled.List),
    Tab("Year", Icons.Filled.DateRange),
)

val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<Expense?>(null) }
    var editingBudget by remember { mutableStateOf<CategoryRow?>(null) }
    var addingCategory by remember { mutableStateOf(false) }

    val month by vm.month.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val state by vm.monthState.collectAsStateWithLifecycle()
    val yearState by vm.yearState.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExcelExporter.MIME)
    ) { uri -> if (uri != null) vm.exportTo(context.contentResolver, uri) }
    val export = { exportLauncher.launch("expenses_${LocalDate.now()}.xlsx") }

    BackHandler(enabled = showSettings) { showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "Settings" else "Expense Tracker") },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = export) { Icon(Icons.Filled.Share, "Export to Excel") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!showSettings && tab < 2) {
                ExtendedFloatingActionButton(
                    onClick = { editingExpense = newExpenseFor(month) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("Add expense") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (showSettings) {
                SettingsScreen(
                    email = user?.email.orEmpty(),
                    prefs = prefs,
                    onCurrency = vm::setCurrency,
                    onAddCategory = vm::addCategory,
                    onRemoveCategory = vm::removeCategory,
                    onExport = export,
                    onSignOut = { showSettings = false; vm.signOut() },
                )
                return@Column
            }
            SyncBadge(state.sync)
            when (tab) {
                0, 1 -> {
                    PeriodSelector(month.format(MONTH_LABEL), vm::prevMonth, vm::nextMonth)
                    if (tab == 0) {
                        BudgetTab(
                            state = state,
                            currency = prefs.currency,
                            onEditBudget = { editingBudget = it },
                            onCopyPrevious = vm::copyPreviousBudget,
                            onAddCategory = { addingCategory = true },
                        )
                    } else {
                        ExpensesTab(state = state, currency = prefs.currency, onEdit = { editingExpense = it })
                    }
                }
                else -> {
                    PeriodSelector(year.toString(), vm::prevYear, vm::nextYear)
                    YearTab(
                        state = yearState,
                        currency = prefs.currency,
                        onOpenMonth = { vm.openMonth(it); tab = 0 },
                    )
                }
            }
        }
    }

    editingExpense?.let { e ->
        ExpenseDialog(
            initial = e,
            categories = prefs.categories,
            currency = prefs.currency,
            onDismiss = { editingExpense = null },
            onSave = { vm.saveExpense(it); editingExpense = null },
            onDelete = if (e.id.isBlank()) null else ({ vm.deleteExpense(e); editingExpense = null }),
        )
    }
    editingBudget?.let { row ->
        BudgetDialog(
            row = row,
            monthLabel = month.format(MONTH_LABEL),
            currency = prefs.currency,
            onDismiss = { editingBudget = null },
            onSave = { vm.setBudget(row.category, it); editingBudget = null },
        )
    }
    if (addingCategory) {
        TextInputDialog(
            title = "New category",
            label = "Category name",
            onDismiss = { addingCategory = false },
            onSave = { vm.addCategory(it); addingCategory = false },
        )
    }
}
