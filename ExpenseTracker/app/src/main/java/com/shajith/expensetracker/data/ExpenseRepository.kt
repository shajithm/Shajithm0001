package com.shajith.expensetracker.data

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder

/** A live result plus its sync metadata. */
data class Live<T>(val items: List<T> = emptyList(), val pending: Boolean = false, val fromCache: Boolean = true)
data class LiveDoc<T>(val value: T, val pending: Boolean = false, val fromCache: Boolean = true)

/**
 * Firestore layout (all private to the signed-in user):
 *   users/{uid}/budgets/{month_category}  { month, category, amount }
 *   users/{uid}/expenses/{autoId}         { month, date, category, amount, note }
 *   users/{uid}/settings/prefs            { currency, categories[] }
 *
 * Writes are never awaited: Firestore applies them to the local cache instantly
 * and uploads them when online, which is what makes offline entry work.
 */
class ExpenseRepository(private val db: FirebaseFirestore, uid: String) {
    private val userDoc = db.collection("users").document(uid)
    private val budgets = userDoc.collection("budgets")
    private val expenses = userDoc.collection("expenses")
    private val prefsDoc = userDoc.collection("settings").document("prefs")

    // ---------- live reads ----------

    fun prefs(): Flow<LiveDoc<Prefs>> = callbackFlow {
        val reg = prefsDoc.addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { Log.w(TAG, "prefs listener", err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener
            val cats = (snap.get("categories") as? List<*>)?.filterIsInstance<String>()
            val prefs = Prefs(
                currency = snap.getString("currency") ?: Prefs().currency,
                categories = cats ?: DEFAULT_CATEGORIES,
            )
            trySend(LiveDoc(prefs, snap.metadata.hasPendingWrites(), snap.metadata.isFromCache))
        }
        awaitClose { reg.remove() }
    }

    fun budgets(month: String) = budgets.whereEqualTo("month", month).live(::toBudget)
    fun expenses(month: String) = expenses.whereEqualTo("month", month).live(::toExpense)

    fun budgetsBetween(from: String, to: String) =
        budgets.whereGreaterThanOrEqualTo("month", from).whereLessThanOrEqualTo("month", to).live(::toBudget)

    fun expensesBetween(from: String, to: String) =
        expenses.whereGreaterThanOrEqualTo("month", from).whereLessThanOrEqualTo("month", to).live(::toExpense)

    private fun <T> Query.live(map: (DocumentSnapshot) -> T?): Flow<Live<T>> = callbackFlow {
        val reg = addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            if (err != null) { Log.w(TAG, "query listener", err); return@addSnapshotListener }
            if (snap == null) return@addSnapshotListener
            trySend(Live(snap.documents.mapNotNull(map), snap.metadata.hasPendingWrites(), snap.metadata.isFromCache))
        }
        awaitClose { reg.remove() }
    }

    // ---------- writes ----------

    fun setBudget(month: String, category: String, amount: Double) {
        val ref = budgets.document(budgetId(month, category))
        if (amount <= 0.0) {
            ref.delete()
        } else {
            ref.set(
                mapOf(
                    "month" to month,
                    "category" to category,
                    "amount" to amount,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
        }
    }

    fun saveExpense(e: Expense) {
        val isNew = e.id.isBlank()
        val ref = if (isNew) expenses.document() else expenses.document(e.id)
        val data = mutableMapOf<String, Any>(
            "month" to e.month,
            "date" to e.date,
            "category" to e.category,
            "amount" to e.amount,
            "note" to e.note,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (isNew) data["createdAt"] = FieldValue.serverTimestamp()
        ref.set(data, SetOptions.merge())
    }

    fun deleteExpense(id: String) {
        expenses.document(id).delete()
    }

    fun savePrefs(p: Prefs) {
        prefsDoc.set(mapOf("currency" to p.currency, "categories" to p.categories), SetOptions.merge())
    }

    /** Copies every budget line from one month to another. Returns the number copied. */
    suspend fun copyBudgets(from: String, to: String): Int {
        val source = fetch(budgets.whereEqualTo("month", from)).mapNotNull(::toBudget)
        if (source.isEmpty()) return 0
        val batch = db.batch()
        source.forEach {
            batch.set(
                budgets.document(budgetId(to, it.category)),
                mapOf(
                    "month" to to,
                    "category" to it.category,
                    "amount" to it.amount,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            )
        }
        batch.commit()
        return source.size
    }

    /** Everything, for export. Tries the server first, falls back to the offline copy. */
    suspend fun fetchAll(): Pair<List<Budget>, List<Expense>> =
        fetch(budgets).mapNotNull(::toBudget) to fetch(expenses).mapNotNull(::toExpense)

    private suspend fun fetch(q: Query): List<DocumentSnapshot> = try {
        withTimeout(8_000) { q.get(Source.SERVER).await() }.documents
    } catch (e: Exception) {
        q.get(Source.CACHE).await().documents
    }

    // ---------- mapping ----------

    private fun toBudget(d: DocumentSnapshot): Budget? {
        val month = d.getString("month") ?: return null
        val category = d.getString("category") ?: return null
        return Budget(id = d.id, month = month, category = category, amount = d.getDouble("amount") ?: 0.0)
    }

    private fun toExpense(d: DocumentSnapshot): Expense? {
        val month = d.getString("month") ?: return null
        val date = d.getString("date") ?: return null
        return Expense(
            id = d.id,
            month = month,
            date = date,
            category = d.getString("category") ?: "Other",
            amount = d.getDouble("amount") ?: 0.0,
            note = d.getString("note") ?: "",
            createdAtMillis = d.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time ?: 0L,
        )
    }

    private fun budgetId(month: String, category: String) =
        month + "_" + URLEncoder.encode(category, "UTF-8")

    private companion object { const val TAG = "ExpenseRepository" }
}
