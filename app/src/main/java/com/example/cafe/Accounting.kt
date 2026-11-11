package com.example.cafe

/** Default chart of accounts. Codes are fixed; names can be changed later with the owner's data. */
const val ACC_CASH = "1000"
const val ACC_BANK = "1010"
const val ACC_RECEIVABLE = "1100"
const val ACC_INVENTORY = "1200"
const val ACC_PAYABLE = "2000"
const val ACC_CAPITAL = "3000"
const val ACC_REVENUE = "4000"
const val ACC_PURCHASES = "5000"
const val ACC_EXPENSES = "6000"

val ACCOUNT_NAMES: LinkedHashMap<String, String> = linkedMapOf(
    ACC_CASH to "الصندوق (كاش)",
    ACC_BANK to "البنك",
    ACC_RECEIVABLE to "زبائن مؤجلة (ذمم مدينة)",
    ACC_INVENTORY to "المخزون",
    ACC_PAYABLE to "الموردين (ذمم دائنة)",
    ACC_CAPITAL to "رأس المال",
    ACC_REVENUE to "إيرادات المبيعات",
    ACC_PURCHASES to "المشتريات",
    ACC_EXPENSES to "المصاريف"
)

data class JEntry(
    val date: Long,
    val ref: String,
    val account: String,
    val debit: Long,
    val credit: Long,
    val note: String
)

data class TrialRow(val account: String, val name: String, val debit: Long, val credit: Long) {
    val balance: Long get() = debit - credit
}

/**
 * Journal entries are generated from the records, so they always match the sales,
 * purchases and expenses. Every record produces a balanced pair of debit and credit.
 */
fun buildJournal(sales: List<Sale>, purchases: List<Purchase>, expenses: List<Expense>): List<JEntry> {
    val out = mutableListOf<JEntry>()
    for (s in sales) {
        when (s.payment) {
            PAY_COLLECT -> {
                out += JEntry(s.date, "تحصيل", ACC_CASH, s.total, 0, s.customer)
                out += JEntry(s.date, "تحصيل", ACC_RECEIVABLE, 0, s.total, s.customer)
            }
            PAY_CREDIT -> {
                out += JEntry(s.date, "بيع مؤجل", ACC_RECEIVABLE, s.total, 0, s.customer)
                out += JEntry(s.date, "بيع مؤجل", ACC_REVENUE, 0, s.total, s.product)
            }
            else -> {
                out += JEntry(s.date, "بيع", ACC_CASH, s.total, 0, s.product)
                out += JEntry(s.date, "بيع", ACC_REVENUE, 0, s.total, s.product)
            }
        }
    }
    for (p in purchases) {
        out += JEntry(p.date, "مشتريات", ACC_INVENTORY, p.amount, 0, p.item)
        out += JEntry(p.date, "مشتريات", ACC_CASH, 0, p.amount, p.item)
    }
    for (e in expenses) {
        out += JEntry(e.date, "مصروف", ACC_EXPENSES, e.amount, 0, e.category)
        out += JEntry(e.date, "مصروف", ACC_CASH, 0, e.amount, e.category)
    }
    return out.sortedBy { it.date }
}

fun trialBalance(journal: List<JEntry>): List<TrialRow> =
    ACCOUNT_NAMES.map { (code, name) ->
        val rows = journal.filter { it.account == code }
        TrialRow(code, name, rows.sumOf { it.debit }, rows.sumOf { it.credit })
    }
