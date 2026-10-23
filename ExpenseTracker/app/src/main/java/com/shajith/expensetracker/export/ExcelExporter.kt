package com.shajith.expensetracker.export

import com.shajith.expensetracker.data.Budget
import com.shajith.expensetracker.data.Expense
import com.shajith.expensetracker.data.Prefs
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.OutputStream
import java.time.LocalDate

/**
 * Writes a workbook with four sheets:
 *  1. Budget vs Actual – per month & category, with difference and % used
 *  2. Monthly Totals   – one row per month
 *  3. Expenses         – every recorded expense
 *  4. Budgets          – every planned budget line
 * Values are written directly (not as formulas) so they display correctly in
 * phone viewers that don't recalculate.
 */
object ExcelExporter {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private const val MONEY = "#,##0.00"
    private const val PCT = "0.0%"
    private const val HEADER_FILL = "1F4E78"
    private const val TOTAL_FILL = "DDEBF7"
    private const val RED = "C00000"
    private const val GREEN = "2E7D32"

    fun write(out: OutputStream, budgets: List<Budget>, expenses: List<Expense>, prefs: Prefs) {
        val wb = Workbook(out, "ExpenseTracker", "1.0")
        val cur = prefs.currency
        val months = (budgets.map { it.month } + expenses.map { it.month }).toSortedSet()

        fun order(used: Set<String>) = prefs.categories.filter { it in used } + (used - prefs.categories.toSet()).sorted()

        // 1. Budget vs Actual
        val cmp = wb.newWorksheet("Budget vs Actual")
        header(cmp, listOf("Month", "Category", "Budget ($cur)", "Actual ($cur)", "Difference ($cur)", "% Used"))
        var r = 1
        val monthTotals = mutableListOf<Triple<String, Double, Double>>()
        for (m in months) {
            val b = budgets.filter { it.month == m }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
            val a = expenses.filter { it.month == m }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amount } }
            for (c in order(b.keys + a.keys)) {
                row(cmp, r++, m, c, b[c] ?: 0.0, a[c] ?: 0.0, total = false)
            }
            val tb = b.values.sum()
            val ta = a.values.sum()
            row(cmp, r++, m, "TOTAL", tb, ta, total = true)
            monthTotals += Triple(m, tb, ta)
        }
        widths(cmp, 10.0, 18.0, 16.0, 16.0, 18.0, 10.0)

        // 2. Monthly Totals
        val tot = wb.newWorksheet("Monthly Totals")
        header(tot, listOf("Month", "Budget ($cur)", "Actual ($cur)", "Difference ($cur)", "% Used"))
        r = 1
        for ((m, b, a) in monthTotals) {
            tot.value(r, 0, m)
            money(tot, r, 1, b); money(tot, r, 2, a); diff(tot, r, 3, b - a)
            if (b > 0) { tot.value(r, 4, a / b); tot.style(r, 4).format(PCT).set() }
            r++
        }
        if (monthTotals.isNotEmpty()) {
            val tb = monthTotals.sumOf { it.second }
            val ta = monthTotals.sumOf { it.third }
            tot.value(r, 0, "ALL MONTHS")
            money(tot, r, 1, tb); money(tot, r, 2, ta); diff(tot, r, 3, tb - ta)
            if (tb > 0) tot.value(r, 4, ta / tb)
            tot.range(r, 0, r, 4).style().bold().fillColor(TOTAL_FILL).set()
            tot.style(r, 4).format(PCT).bold().fillColor(TOTAL_FILL).set()
        }
        widths(tot, 12.0, 16.0, 16.0, 18.0, 10.0)

        // 3. Expenses
        val ex = wb.newWorksheet("Expenses")
        header(ex, listOf("Date", "Month", "Category", "Amount ($cur)", "Note"))
        r = 1
        for (e in expenses.sortedWith(compareBy<Expense> { it.date }.thenBy { it.createdAtMillis })) {
            val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
            if (d != null) { ex.value(r, 0, d); ex.style(r, 0).format("yyyy-mm-dd").set() } else ex.value(r, 0, e.date)
            ex.value(r, 1, e.month)
            ex.value(r, 2, e.category)
            money(ex, r, 3, e.amount)
            ex.value(r, 4, e.note)
            r++
        }
        widths(ex, 12.0, 10.0, 18.0, 16.0, 40.0)

        // 4. Budgets
        val bs = wb.newWorksheet("Budgets")
        header(bs, listOf("Month", "Category", "Budget ($cur)"))
        r = 1
        for (b in budgets.sortedWith(compareBy<Budget> { it.month }.thenBy { it.category })) {
            bs.value(r, 0, b.month)
            bs.value(r, 1, b.category)
            money(bs, r, 2, b.amount)
            r++
        }
        widths(bs, 10.0, 18.0, 16.0)

        wb.finish()
    }

    private fun row(ws: Worksheet, r: Int, month: String, cat: String, b: Double, a: Double, total: Boolean) {
        ws.value(r, 0, month)
        ws.value(r, 1, cat)
        money(ws, r, 2, b)
        money(ws, r, 3, a)
        diff(ws, r, 4, b - a)
        if (b > 0) {
            ws.value(r, 5, a / b)
            ws.style(r, 5).format(PCT).set()
        }
        if (total) {
            ws.range(r, 0, r, 1).style().bold().fillColor(TOTAL_FILL).set()
            ws.range(r, 2, r, 3).style().format(MONEY).bold().fillColor(TOTAL_FILL).set()
            ws.style(r, 4).format(MONEY).bold().fillColor(TOTAL_FILL).fontColor(if (b - a < 0) RED else GREEN).set()
            ws.style(r, 5).format(PCT).bold().fillColor(TOTAL_FILL).set()
        }
    }

    private fun header(ws: Worksheet, titles: List<String>) {
        titles.forEachIndexed { c, t -> ws.value(0, c, t) }
        ws.range(0, 0, 0, titles.size - 1).style()
            .bold().fontColor("FFFFFF").fillColor(HEADER_FILL).set()
    }

    private fun money(ws: Worksheet, r: Int, c: Int, v: Double) {
        ws.value(r, c, v)
        ws.style(r, c).format(MONEY).set()
    }

    private fun diff(ws: Worksheet, r: Int, c: Int, v: Double) {
        ws.value(r, c, v)
        ws.style(r, c).format(MONEY).fontColor(if (v < 0) RED else GREEN).set()
    }

    private fun widths(ws: Worksheet, vararg w: Double) = w.forEachIndexed { c, v -> ws.width(c, v) }
}
