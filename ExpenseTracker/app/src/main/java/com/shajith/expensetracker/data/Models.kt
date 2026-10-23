package com.shajith.expensetracker.data

import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter

val DEFAULT_CATEGORIES = listOf(
    "Housing", "Utilities", "Groceries", "Transport", "Dining Out",
    "Health", "Shopping", "Entertainment", "Education", "Other"
)

private val MONTH_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

/** Months are stored as "yyyy-MM" strings so they sort and range-query correctly. */
fun YearMonth.key(): String = format(MONTH_KEY)
fun String.toYearMonth(): YearMonth = YearMonth.parse(this, MONTH_KEY)

data class Budget(
    val id: String,
    val month: String,
    val category: String,
    val amount: Double,
)

data class Expense(
    val id: String = "",
    val month: String = "",
    val date: String = "",      // ISO yyyy-MM-dd
    val category: String = "",
    val amount: Double = 0.0,
    val note: String = "",
    val createdAtMillis: Long = 0,
)

data class Prefs(
    val currency: String = "AED",
    val categories: List<String> = DEFAULT_CATEGORIES,
)

data class CategoryRow(val category: String, val budget: Double, val actual: Double) {
    val diff: Double get() = budget - actual
    val ratio: Float
        get() = when {
            budget > 0 -> (actual / budget).toFloat()
            actual > 0 -> 1f
            else -> 0f
        }
}

enum class SyncStatus { SYNCED, PENDING, OFFLINE }

fun money(value: Double, currency: String): String = "$currency ${"%,.2f".format(value)}"

fun parseAmount(text: String): Double? = text.replace(",", "").trim().toDoubleOrNull()

fun plainAmount(value: Double): String =
    if (value == 0.0) "" else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
