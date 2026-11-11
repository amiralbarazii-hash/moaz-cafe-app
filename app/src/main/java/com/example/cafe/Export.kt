package com.example.cafe

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val PDF_MIME = "application/pdf"

/** One table in a report: a sheet in Excel, a titled table in PDF. */
data class Section(val name: String, val headers: List<String>, val rows: List<List<Any?>>)

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

/** Builds the report tables for a date range. [sales] must be all sales, so customer balances are all-time. */
fun buildSections(
    fromMs: Long,
    toMs: Long,
    sales: List<Sale>,
    purchases: List<Purchase>,
    expenses: List<Expense>,
    customers: List<Customer>,
    items: List<Item> = emptyList(),
    consumptions: List<Consumption> = emptyList()
): List<Section> {
    fun inRange(ms: Long) = ms in fromMs..toMs

    val s = sales.filter { inRange(it.date) }.sortedBy { it.date }
    val p = purchases.filter { inRange(it.date) }.sortedBy { it.date }
    val e = expenses.filter { inRange(it.date) }.sortedBy { it.date }
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    val days = (s.map { dayKey(it.date) } + p.map { dayKey(it.date) } + e.map { dayKey(it.date) }).toSortedSet()
    val summaryRows = mutableListOf<List<Any?>>()
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
        summaryRows.add(listOf(d, cash, credit, collect, cash + credit, buy, exp, cash + collect - buy - exp))
        tCash += cash
        tCredit += credit
        tCollect += collect
        tBuy += buy
        tExp += exp
    }
    summaryRows.add(listOf("الإجمالي", tCash, tCredit, tCollect, tCash + tCredit, tBuy, tExp, tCash + tCollect - tBuy - tExp))

    val customerRows = customers.sortedBy { it.name }.map { c ->
        val credit = sales.filter { it.customer == c.name && it.payment == PAY_CREDIT }.sumOf { it.total }
        val paid = sales.filter { it.customer == c.name && it.payment == PAY_COLLECT }.sumOf { it.total }
        listOf<Any?>(c.name, credit, paid, credit - paid)
    }

    // Stock is all-time: opening balance + purchases − sales − manual consumption.
    val stockRows = items.sortedBy { it.name }.map { it2 ->
        val bought = purchases.filter { p -> p.item == it2.name }.sumOf { p -> p.qty }
        val sold = sales.filter { sa -> sa.product == it2.name }.sumOf { sa -> sa.qty }
        val used = consumptions.filter { c -> c.itemName == it2.name }.sumOf { c -> c.qty }
        listOf<Any?>(it2.name, it2.unit, it2.opening, bought, sold, used, it2.opening + bought - sold - used)
    }

    return listOf(
        Section(
            "الملخص اليومي",
            listOf("التاريخ", "مبيعات كاش", "على الحساب", "تحصيل دين", "إجمالي المبيعات", "المشتريات", "المصروفات", "صافي الكاش"),
            summaryRows
        ),
        Section(
            "المبيعات",
            listOf("التاريخ", "المادة", "الكمية", "سعر الوحدة", "الخصم", "المجموع", "الدفع", "الزبون", "ملاحظة", "الموظف"),
            s.map {
                listOf<Any?>(
                    stamp.format(Date(it.date)), it.product, it.qty, it.unitPrice, it.discount,
                    it.total, it.payment, it.customer, it.note, it.employeeEmail
                )
            }
        ),
        Section(
            "المشتريات",
            listOf("التاريخ", "المادة", "الكمية", "المبلغ", "التصنيف", "ملاحظة", "الموظف"),
            p.map {
                listOf<Any?>(stamp.format(Date(it.date)), it.item, it.qty, it.amount, it.category, it.note, it.employeeEmail)
            }
        ),
        Section(
            "المصروفات",
            listOf("التاريخ", "البند", "المبلغ", "ملاحظة", "الموظف"),
            e.map {
                listOf<Any?>(stamp.format(Date(it.date)), it.category, it.amount, it.note, it.employeeEmail)
            }
        ),
        Section(
            "أرصدة الزبائن حتى الآن",
            listOf("الزبون", "على الحساب", "تحصيل", "الرصيد المستحق"),
            customerRows
        ),
        Section(
            "المخزون",
            listOf("الصنف", "الوحدة", "الرصيد الافتتاحي", "مشتريات", "مبيعات", "استهلاك", "الرصيد الحالي"),
            stockRows
        )
    )
}

/** Account statement for one customer. Opening balance carries everything before [fromMs]. */
fun statementSection(customer: String, sales: List<Sale>, fromMs: Long, toMs: Long): Section {
    val mine = sales
        .filter { it.customer == customer && (it.payment == PAY_CREDIT || it.payment == PAY_COLLECT) }
        .sortedBy { it.date }
    var running = mine.filter { it.date < fromMs }
        .sumOf { if (it.payment == PAY_CREDIT) it.total else -it.total }

    val rows = mutableListOf<List<Any?>>()
    rows.add(listOf<Any?>("", "رصيد سابق", "", "", running))
    for (sale in mine.filter { it.date in fromMs..toMs }) {
        if (sale.payment == PAY_CREDIT) {
            running += sale.total
            rows.add(listOf<Any?>(dateOf(sale.date), "${sale.product} × ${sale.qty}", sale.total, "", running))
        } else {
            running -= sale.total
            rows.add(listOf<Any?>(dateOf(sale.date), "تحصيل", "", sale.total, running))
        }
    }
    return Section(
        "كشف حساب $customer",
        listOf("التاريخ", "البيان", "على الحساب", "تحصيل", "الرصيد"),
        rows
    )
}

fun exportSections(context: Context, baseName: String, sections: List<Section>, pdf: Boolean): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    if (pdf) {
        val file = File(dir, "$baseName.pdf")
        writePdf(file, sections)
        return file
    }
    val file = File(dir, "$baseName.xlsx")
    XlsxWriter.write(file, sections.map { s -> s.name to (listOf<List<Any?>>(s.headers) + s.rows) })
    return file
}

/** Simple paginated PDF with one titled table per section. Uses Android's built-in PdfDocument. */
fun writePdf(file: File, sections: List<Section>) {
    val pageW = 595
    val pageH = 842
    val margin = 36f
    val lineH = 15f

    val doc = PdfDocument()
    val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; isFakeBoldText = true }
    val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; isFakeBoldText = true }
    val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }

    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f

    fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        y = margin + 10f
    }

    fun ensure(height: Float) {
        if (page == null || y + height > pageH - margin) newPage()
    }

    newPage()
    for (section in sections) {
        ensure(40f)
        page!!.canvas.drawText(section.name, margin, y, titleP)
        y += 24f

        val cols = section.headers.size.coerceAtLeast(1)
        val colW = (pageW - 2 * margin) / cols

        fun drawRow(cells: List<String>, paint: Paint) {
            cells.forEachIndexed { i, cell ->
                page!!.canvas.drawText(cell.take(24), margin + i * colW, y, paint)
            }
            y += lineH
        }

        ensure(lineH)
        drawRow(section.headers, headP)
        for (row in section.rows) {
            ensure(lineH)
            drawRow(row.map { it?.toString() ?: "" }, bodyP)
        }
        y += 16f
    }
    page?.let { doc.finishPage(it) }
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
}

/** Copies the file into the public Downloads folder so it survives uninstalling the app. Returns the saved name. */
fun saveToDownloads(context: Context, source: File): String {
    val name = "moaz_backup_${System.currentTimeMillis()}.xlsx"
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME)
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

fun shareFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "شارك التقرير"))
}
