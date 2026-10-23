package com.shajith.expensetracker.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.shajith.expensetracker.data.Budget
import com.shajith.expensetracker.data.CategoryRow
import com.shajith.expensetracker.data.Expense
import com.shajith.expensetracker.data.ExpenseRepository
import com.shajith.expensetracker.data.Live
import com.shajith.expensetracker.data.LiveDoc
import com.shajith.expensetracker.data.Prefs
import com.shajith.expensetracker.data.SyncStatus
import com.shajith.expensetracker.data.key
import com.shajith.expensetracker.export.ExcelExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Year
import java.time.YearMonth

data class MonthState(
    val rows: List<CategoryRow> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val totalBudget: Double = 0.0,
    val totalActual: Double = 0.0,
    val sync: SyncStatus = SyncStatus.OFFLINE,
    val loaded: Boolean = false,
) {
    val diff: Double get() = totalBudget - totalActual
}

data class MonthTotal(val month: YearMonth, val budget: Double, val actual: Double)

data class YearState(
    val months: List<MonthTotal> = emptyList(),
    val categories: List<CategoryRow> = emptyList(),
) {
    val totalBudget: Double get() = months.sumOf { it.budget }
    val totalActual: Double get() = months.sumOf { it.actual }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // ---------- auth ----------

    val user: StateFlow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, auth.currentUser)

    val authBusy = MutableStateFlow(false)
    val authError = MutableStateFlow<String?>(null)
    val authInfo = MutableStateFlow<String?>(null)

    private val repo: StateFlow<ExpenseRepository?> = user
        .map { it?.uid }
        .distinctUntilChanged()
        .map { uid -> uid?.let { ExpenseRepository(db, it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, auth.currentUser?.let { ExpenseRepository(db, it.uid) })

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------- selection ----------

    val month = MutableStateFlow(YearMonth.now())
    val year = MutableStateFlow(Year.now().value)

    // ---------- data ----------

    private val prefsLive: Flow<LiveDoc<Prefs>> =
        repo.flatMapLatest { it?.prefs() ?: flowOf(LiveDoc(Prefs())) }

    val prefs: StateFlow<Prefs> = prefsLive.map { it.value }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Prefs())

    val monthState: StateFlow<MonthState> = combine(repo, month) { r, m -> r to m }
        .flatMapLatest { (r, m) ->
            if (r == null) flowOf(MonthState())
            else combine(prefsLive, r.budgets(m.key()), r.expenses(m.key())) { p, b, e -> buildMonth(p, b, e) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthState())

    val yearState: StateFlow<YearState> = combine(repo, year) { r, y -> r to y }
        .flatMapLatest { (r, y) ->
            if (r == null) flowOf(YearState())
            else combine(prefsLive, r.budgetsBetween("$y-01", "$y-12"), r.expensesBetween("$y-01", "$y-12")) { p, b, e ->
                buildYear(y, p.value, b.items, e.items)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YearState())

    private fun buildMonth(p: LiveDoc<Prefs>, b: Live<Budget>, e: Live<Expense>): MonthState {
        val budgetBy = b.items.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val actualBy = e.items.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val rows = ordered(p.value.categories, budgetBy.keys + actualBy.keys)
            .map { CategoryRow(it, budgetBy[it] ?: 0.0, actualBy[it] ?: 0.0) }
        val sync = when {
            p.pending || b.pending || e.pending -> SyncStatus.PENDING
            p.fromCache || b.fromCache || e.fromCache -> SyncStatus.OFFLINE
            else -> SyncStatus.SYNCED
        }
        return MonthState(
            rows = rows,
            expenses = e.items.sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.createdAtMillis }),
            totalBudget = budgetBy.values.sum(),
            totalActual = actualBy.values.sum(),
            sync = sync,
            loaded = true,
        )
    }

    private fun buildYear(y: Int, p: Prefs, b: List<Budget>, e: List<Expense>): YearState {
        val bByMonth = b.groupBy { it.month }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val eByMonth = e.groupBy { it.month }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val months = (1..12).map { YearMonth.of(y, it) }.map {
            MonthTotal(it, bByMonth[it.key()] ?: 0.0, eByMonth[it.key()] ?: 0.0)
        }
        val bByCat = b.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val eByCat = e.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
        val cats = ordered(p.categories, bByCat.keys + eByCat.keys)
            .filter { (bByCat[it] ?: 0.0) > 0 || (eByCat[it] ?: 0.0) > 0 }
            .map { CategoryRow(it, bByCat[it] ?: 0.0, eByCat[it] ?: 0.0) }
        return YearState(months, cats)
    }

    private fun ordered(pref: List<String>, used: Set<String>) = pref + (used - pref.toSet()).sorted()

    // ---------- actions ----------

    fun signIn(email: String, password: String, createAccount: Boolean) = viewModelScope.launch {
        authBusy.value = true
        authError.value = null
        authInfo.value = null
        try {
            if (createAccount) auth.createUserWithEmailAndPassword(email, password).await()
            else auth.signInWithEmailAndPassword(email, password).await()
        } catch (e: Exception) {
            authError.value = e.localizedMessage ?: "Sign-in failed"
        } finally {
            authBusy.value = false
        }
    }

    fun resetPassword(email: String) = viewModelScope.launch {
        authError.value = null
        try {
            auth.sendPasswordResetEmail(email).await()
            authInfo.value = "Password reset email sent to $email"
        } catch (e: Exception) {
            authError.value = e.localizedMessage
        }
    }

    fun signOut() = auth.signOut()

    fun prevMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { it.plusMonths(1) }
    fun openMonth(m: YearMonth) { month.value = m }
    fun prevYear() = year.update { it - 1 }
    fun nextYear() = year.update { it + 1 }

    fun setBudget(category: String, amount: Double) {
        repo.value?.setBudget(month.value.key(), category, amount)
    }

    fun saveExpense(e: Expense) { repo.value?.saveExpense(e) }

    fun deleteExpense(e: Expense) {
        repo.value?.deleteExpense(e.id)
        _messages.tryEmit("Expense deleted")
    }

    fun copyPreviousBudget() = viewModelScope.launch {
        val r = repo.value ?: return@launch
        val prev = month.value.minusMonths(1)
        try {
            val n = r.copyBudgets(prev.key(), month.value.key())
            _messages.emit(if (n == 0) "No budget set for the previous month" else "Copied $n budget lines")
        } catch (e: Exception) {
            _messages.emit("Couldn't copy budget: ${e.localizedMessage}")
        }
    }

    fun setCurrency(code: String) {
        val c = code.trim().uppercase()
        if (c.isNotEmpty()) repo.value?.savePrefs(prefs.value.copy(currency = c))
    }

    fun addCategory(name: String) {
        val n = name.trim()
        if (n.isEmpty() || prefs.value.categories.any { it.equals(n, ignoreCase = true) }) return
        repo.value?.savePrefs(prefs.value.copy(categories = prefs.value.categories + n))
    }

    fun removeCategory(name: String) {
        repo.value?.savePrefs(prefs.value.copy(categories = prefs.value.categories - name))
    }

    fun exportTo(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val r = repo.value ?: return@launch
        try {
            val (budgets, expenses) = r.fetchAll()
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri)?.use { ExcelExporter.write(it, budgets, expenses, prefs.value) }
                    ?: error("Couldn't open the file for writing")
            }
            _messages.emit("Exported ${expenses.size} expenses and ${budgets.size} budget lines")
        } catch (e: Exception) {
            _messages.emit("Export failed: ${e.localizedMessage}")
        }
    }
}
