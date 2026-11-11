package com.example.cafe

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal .xlsx writer (no external library, works on Android). Arabic is written as UTF-8. */
object XlsxWriter {
    fun write(file: File, sheets: List<Pair<String, List<List<Any?>>>>) {
        file.parentFile?.mkdirs()
        val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
        val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rels = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            fun put(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put("[Content_Types].xml", buildString {
                append(header)
                append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
                append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
                sheets.indices.forEach { i ->
                    append("""<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
                }
                append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                append("</Types>")
            })

            put("_rels/.rels", header +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$rels/officeDocument" Target="xl/workbook.xml"/>""" +
                "</Relationships>")

            put("xl/workbook.xml", buildString {
                append(header)
                append("""<workbook xmlns="$ns" xmlns:r="$rels"><sheets>""")
                sheets.forEachIndexed { i, (name, _) ->
                    append("""<sheet name="${esc(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                append("</sheets></workbook>")
            })

            put("xl/_rels/workbook.xml.rels", buildString {
                append(header)
                append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheets.indices.forEach { i ->
                    append("""<Relationship Id="rId${i + 1}" Type="$rels/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
                }
                append("""<Relationship Id="rId${sheets.size + 1}" Type="$rels/styles" Target="styles.xml"/>""")
                append("</Relationships>")
            })

            put("xl/styles.xml", header +
                """<styleSheet xmlns="$ns">""" +
                """<fonts count="1"><font><sz val="11"/><name val="Arial"/></font></fonts>""" +
                """<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
                """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
                """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
                """<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>""" +
                "</styleSheet>")

            sheets.forEachIndexed { i, (_, rows) ->
                put("xl/worksheets/sheet${i + 1}.xml", buildString {
                    append(header)
                    append("""<worksheet xmlns="$ns"><sheetViews><sheetView rightToLeft="1" workbookViewId="0"/></sheetViews><sheetData>""")
                    rows.forEachIndexed { r, row ->
                        append("""<row r="${r + 1}">""")
                        row.forEachIndexed { c, v ->
                            val ref = "${colName(c)}${r + 1}"
                            when (v) {
                                null -> Unit
                                is Number -> append("""<c r="$ref"><v>$v</v></c>""")
                                else -> append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v.toString())}</t></is></c>""")
                            }
                        }
                        append("</row>")
                    }
                    append("</sheetData></worksheet>")
                })
            }
        }
    }

    private fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + (n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/** Last [count] months as "yyyy-MM", newest first. */
fun lastMonths(count: Int): List<String> =
    (0 until count).map { i ->
        monthKey(Calendar.getInstance().apply { add(Calendar.MONTH, -i) }.timeInMillis)
    }

/**
 * Builds one workbook with the same sheets as the owner's Excel file.
 * [month] is "yyyy-MM" to limit the period, or null for all records.
 * Customer balances always use all-time sales.
 */
fun buildMonthlyExport(
    context: Context,
    month: String?,
    sales: List<Sale>,
    purchases: List<Purchase>,
    expenses: List<Expense>,
    customers: List<Customer>
): File {
    fun inPeriod(ms: Long) = month == null || monthKey(ms) == month

    val s = sales.filter { inPeriod(it.date) }.sortedBy { it.date }
    val p = purchases.filter { inPeriod(it.date) }.sortedBy { it.date }
    val e = expenses.filter { inPeriod(it.date) }.sortedBy { it.date }
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    // Daily summary (same logic as the workbook's الملخص sheet)
    val days = (s.map { dayKey(it.date) } + p.map { dayKey(it.date) } + e.map { dayKey(it.date) }).toSortedSet()
    val summary = mutableListOf<List<Any?>>(
        listOf("التاريخ", "مبيعات كاش", "على الحساب", "تحصيل دين", "إجمالي المبيعات", "المشتريات", "المصروفات", "صافي الكاش")
    )
    var tCash = 0L
    var tCredit = 0L
    var tCollect = 0L
    var tBuy = 0L
    var tExp = 0L
    for (d in days) {
        val cash = s.filter { dayKey(it.date) == d && it.payment == PAY_CASH }.sumOf { it.total }
        val credit = s.filter { dayKey(it.date) == d && it.payment == PAY_CREDIT }.sumOf { it.total }
        val collect = s.filter { dayKey(it.date) == d && it.payment == PAY_COLLECT }.sumOf { it.total }
        val buy = p.filter { dayKey(it.date) == d }.sumOf { it.amount }
        val exp = e.filter { dayKey(it.date) == d }.sumOf { it.amount }
        summary.add(listOf(d, cash, credit, collect, cash + credit, buy, exp, cash + collect - buy - exp))
        tCash += cash
        tCredit += credit
        tCollect += collect
        tBuy += buy
        tExp += exp
    }
    summary.add(
        listOf("الإجمالي", tCash, tCredit, tCollect, tCash + tCredit, tBuy, tExp, tCash + tCollect - tBuy - tExp)
    )

    val salesRows = mutableListOf<List<Any?>>(
        listOf("التاريخ", "المادة", "الكمية", "سعر الوحدة", "المجموع", "الدفع", "الزبون", "ملاحظة", "الموظف")
    )
    s.forEach {
        salesRows.add(
            listOf(stamp.format(Date(it.date)), it.product, it.qty, it.unitPrice, it.total,
                it.payment, it.customer, it.note, it.employeeEmail)
        )
    }

    val purchaseRows = mutableListOf<List<Any?>>(
        listOf("التاريخ", "المادة", "الكمية", "المبلغ", "التصنيف", "ملاحظة", "الموظف")
    )
    p.forEach {
        purchaseRows.add(
            listOf(stamp.format(Date(it.date)), it.item, it.qty, it.amount, it.category, it.note, it.employeeEmail)
        )
    }

    val expenseRows = mutableListOf<List<Any?>>(
        listOf("التاريخ", "البند", "المبلغ", "ملاحظة", "الموظف")
    )
    e.forEach {
        expenseRows.add(listOf(stamp.format(Date(it.date)), it.category, it.amount, it.note, it.employeeEmail))
    }

    val customerRows = mutableListOf<List<Any?>>(
        listOf("الزبون", "على الحساب", "تحصيل", "الرصيد المستحق حتى الآن")
    )
    customers.sortedBy { it.name }.forEach { c ->
        val credit = sales.filter { it.customer == c.name && it.payment == PAY_CREDIT }.sumOf { it.total }
        val paid = sales.filter { it.customer == c.name && it.payment == PAY_COLLECT }.sumOf { it.total }
        customerRows.add(listOf(c.name, credit, paid, credit - paid))
    }

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "cafe_report_${month ?: "all"}.xlsx")
    XlsxWriter.write(
        file,
        listOf(
            "الملخص" to summary,
            "المبيعات" to salesRows,
            "المشتريات" to purchaseRows,
            "المصروفات" to expenseRows,
            "أرصدة الزبائن" to customerRows
        )
    )
    return file
}

/** Copies the file into the public Downloads folder so it survives uninstalling the app. Returns the saved name. */
fun saveToDownloads(context: Context, source: File): String {
    val name = "moaz_backup_${System.currentTimeMillis()}.xlsx"
    val mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("ما قدرنا ننشئ الملف")
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: error("ما قدرنا نكتب الملف")
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        name
    } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        source.copyTo(File(dir, name), overwrite = true)
        name
    }
}

fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "شارك التقرير"))
}
