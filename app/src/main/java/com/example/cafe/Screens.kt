@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.cafe

import android.app.DatePickerDialog as AndroidDatePicker
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun money(v: Long): String = String.format(Locale.US, "%,d", v)

private fun timeOf(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(ms))

fun pickDate(context: Context, current: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = current }
    AndroidDatePicker(
        context,
        { _, y, m, d ->
            onPick(Calendar.getInstance().apply {
                set(y, m, d, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis)
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@Composable
fun DateButton(label: String, millis: Long, modifier: Modifier = Modifier, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = { pickDate(context, millis, onPick) }, modifier = modifier) {
        Text("$label: ${dateOf(millis)}")
    }
}

@Composable
fun EntryCard(title: String, subtitle: String, amount: String, canDelete: Boolean, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(amount, fontWeight = FontWeight.Bold)
            if (canDelete) {
                TextButton(onClick = onDelete) { Text("حذف") }
            }
        }
    }
}

@Composable
fun PickerField(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onPick(option)
                        open = false
                    }
                )
            }
        }
    }
}

/** Text field with a list of matching suggestions shown right under it. */
@Composable
fun SuggestField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    onPick: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        suggestions.forEach { s ->
            TextButton(onClick = { onPick(s) }, modifier = Modifier.fillMaxWidth()) {
                Text(s)
            }
        }
    }
}

// ---------- Sales ----------

@Composable
fun SalesScreen(repo: Repo, profile: Profile) {
    val products by remember { repo.products() }.collectAsState(initial = emptyList())
    val customers by remember { repo.customers() }.collectAsState(initial = emptyList())
    val sales by remember(profile.isOwner) { repo.sales(profile.isOwner) }.collectAsState(initial = emptyList())
    val pending by remember { repo.pendingCountFlow() }.collectAsState(initial = 0)
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    val today = dayKey(System.currentTimeMillis())
    val todays = sales.filter { dayKey(it.date) == today }
    val cash = todays.filter { it.payment == PAY_CASH }.sumOf { it.total }
    val credit = todays.filter { it.payment == PAY_CREDIT }.sumOf { it.total }
    val collect = todays.filter { it.payment == PAY_COLLECT }.sumOf { it.total }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (pending > 0) {
            Text(
                "تنبيه: $pending عملية ما انرفعت للسحابة بعد. لا تحذف التطبيق قبل ما ترفع.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            "اليوم: كاش ${money(cash)} | على الحساب ${money(credit)} | تحصيل ${money(collect)}",
            fontWeight = FontWeight.Bold
        )
        Button(
            onClick = { showForm = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) { Text("تسجيل بيع أو تحصيل") }

        LazyColumn(Modifier.weight(1f)) {
            items(sales, key = { it.id }) { s ->
                EntryCard(
                    title = if (s.payment == PAY_COLLECT) "تحصيل من ${s.customer}" else "${s.product} × ${s.qty}",
                    subtitle = dateOf(s.date) + " - " + s.payment +
                        (if (s.customer.isNotBlank() && s.payment != PAY_CASH) " - ${s.customer}" else "") +
                        (if (s.discount > 0) " - خصم ${money(s.discount)}" else "") +
                        (if (s.note.isNotBlank()) " - ${s.note}" else ""),
                    amount = money(s.total),
                    canDelete = profile.isOwner || s.employeeId == repo.uid,
                    onDelete = { scope.launch { repo.delete("sales", s.id) } }
                )
            }
        }
    }

    if (showForm) {
        SaleDialog(
            products = products,
            customers = customers,
            onDismiss = { showForm = false },
            onSave = { date, product, qty, unitPrice, discount, total, payment, customer, note ->
                scope.launch {
                    repo.addSale(date, product, qty, unitPrice, discount, total, payment, customer, note)
                    if (customer.isNotBlank() && customers.none { it.name == customer }) {
                        repo.addCustomer(customer)
                    }
                }
                showForm = false
            }
        )
    }
}

@Composable
fun SaleDialog(
    products: List<Product>,
    customers: List<Customer>,
    onDismiss: () -> Unit,
    onSave: (date: Long, product: String, qty: Long, unitPrice: Long, discount: Long, total: Long, payment: String, customer: String, note: String) -> Unit
) {
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var payment by remember { mutableStateOf(PAY_CASH) }
    var productQuery by remember { mutableStateOf("") }
    var productName by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("1") }
    var discountText by remember { mutableStateOf("") }
    var customerText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val isCollect = payment == PAY_COLLECT
    val qty = qtyText.toLongOrNull() ?: 0L
    val price = priceText.toLongOrNull() ?: 0L
    val discount = discountText.toLongOrNull() ?: 0L
    val amount = amountText.toLongOrNull() ?: 0L
    val lineTotal = (qty * price - discount).coerceAtLeast(0L)
    val total = if (isCollect) amount else lineTotal
    val customerName = customerText.trim()

    val productSuggestions = if (productQuery.isBlank() || productQuery == productName) {
        emptyList()
    } else {
        products.filter { it.name.contains(productQuery.trim(), ignoreCase = true) }.take(5).map { it.name }
    }
    val customerSuggestions = if (customerName.isBlank()) {
        emptyList()
    } else {
        customers.filter { it.name.contains(customerName, ignoreCase = true) && it.name != customerName }
            .take(5).map { it.name }
    }

    val valid = if (isCollect) {
        customerName.isNotBlank() && amount > 0
    } else {
        productName.isNotBlank() && qty > 0 && price > 0 && (payment != PAY_CREDIT || customerName.isNotBlank())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل عملية") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DateButton("التاريخ", date, Modifier.fillMaxWidth()) { date = it }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PAYMENT_TYPES.forEach { p ->
                        FilterChip(
                            selected = payment == p,
                            onClick = { payment = p },
                            label = { Text(p) }
                        )
                    }
                }

                if (isCollect) {
                    SuggestField(
                        label = "الزبون",
                        value = customerText,
                        onValueChange = { customerText = it },
                        suggestions = customerSuggestions,
                        onPick = { customerText = it }
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit) },
                        label = { Text("المبلغ المقبوض") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    SuggestField(
                        label = "ابحث عن المادة",
                        value = productQuery,
                        onValueChange = {
                            productQuery = it
                            if (it != productName) productName = ""
                        },
                        suggestions = productSuggestions,
                        onPick = { name ->
                            productName = name
                            productQuery = name
                            priceText = products.firstOrNull { it.name == name }?.price?.toString() ?: ""
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it.filter(Char::isDigit) },
                            label = { Text("الكمية") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it.filter(Char::isDigit) },
                            label = { Text("سعر الوحدة") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it.filter(Char::isDigit) },
                        label = { Text("الخصم (اختياري)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (payment == PAY_CREDIT) {
                        SuggestField(
                            label = "الزبون",
                            value = customerText,
                            onValueChange = { customerText = it },
                            suggestions = customerSuggestions,
                            onPick = { customerText = it }
                        )
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("المجموع: ${money(total)} ل.س", fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (isCollect) {
                        onSave(date, PAY_COLLECT, 1, amount, 0, amount, PAY_COLLECT, customerName, note.trim())
                    } else {
                        onSave(
                            date, productName, qty, price, discount, lineTotal, payment,
                            if (payment == PAY_CREDIT) customerName else "",
                            note.trim()
                        )
                    }
                }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ---------- Purchases ----------

@Composable
fun PurchasesScreen(repo: Repo, profile: Profile) {
    val purchases by remember(profile.isOwner) { repo.purchases(profile.isOwner) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    val today = dayKey(System.currentTimeMillis())
    val todayTotal = purchases.filter { dayKey(it.date) == today }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("مشتريات اليوم: ${money(todayTotal)} ل.س", fontWeight = FontWeight.Bold)
        Button(
            onClick = { showForm = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) { Text("تسجيل مشترى") }

        LazyColumn(Modifier.weight(1f)) {
            items(purchases, key = { it.id }) { pu ->
                EntryCard(
                    title = "${pu.item} × ${pu.qty}",
                    subtitle = dateOf(pu.date) + " - ${pu.category}" +
                        (if (pu.note.isNotBlank()) " - ${pu.note}" else ""),
                    amount = money(pu.amount),
                    canDelete = profile.isOwner || pu.employeeId == repo.uid,
                    onDelete = { scope.launch { repo.delete("purchases", pu.id) } }
                )
            }
        }
    }

    if (showForm) {
        PurchaseDialog(
            onDismiss = { showForm = false },
            onSave = { date, item, qty, amount, category, note ->
                scope.launch { repo.addPurchase(date, item, qty, amount, category, note) }
                showForm = false
            }
        )
    }
}

@Composable
fun PurchaseDialog(
    onDismiss: () -> Unit,
    onSave: (date: Long, item: String, qty: Long, amount: Long, category: String, note: String) -> Unit
) {
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var item by remember { mutableStateOf("") }
    var qtyText by remember { mutableStateOf("1") }
    var amountText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PURCHASE_CATEGORIES.first()) }
    var note by remember { mutableStateOf("") }

    val qty = qtyText.toLongOrNull() ?: 0L
    val amount = amountText.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل مشترى") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DateButton("التاريخ", date, Modifier.fillMaxWidth()) { date = it }
                OutlinedTextField(
                    value = item,
                    onValueChange = { item = it },
                    label = { Text("المادة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it.filter(Char::isDigit) },
                        label = { Text("الكمية") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter(Char::isDigit) },
                        label = { Text("المبلغ المدفوع") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                PickerField("التصنيف", category, PURCHASE_CATEGORIES) { category = it }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("ملاحظة (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = item.isNotBlank() && amount > 0,
                onClick = { onSave(date, item.trim(), qty.coerceAtLeast(1), amount, category, note.trim()) }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ---------- Expenses ----------

@Composable
fun ExpensesScreen(repo: Repo, profile: Profile) {
    val expenses by remember(profile.isOwner) { repo.expenses(profile.isOwner) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    val today = dayKey(System.currentTimeMillis())
    val todayTotal = expenses.filter { dayKey(it.date) == today }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("مصروفات اليوم: ${money(todayTotal)} ل.س", fontWeight = FontWeight.Bold)
        Button(
            onClick = { showForm = true },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) { Text("تسجيل مصروف") }

        LazyColumn(Modifier.weight(1f)) {
            items(expenses, key = { it.id }) { ex ->
                EntryCard(
                    title = ex.category,
                    subtitle = dateOf(ex.date) + (if (ex.note.isNotBlank()) " - ${ex.note}" else ""),
                    amount = money(ex.amount),
                    canDelete = profile.isOwner || ex.employeeId == repo.uid,
                    onDelete = { scope.launch { repo.delete("expenses", ex.id) } }
                )
            }
        }
    }

    if (showForm) {
        ExpenseDialog(
            onDismiss = { showForm = false },
            onSave = { date, category, amount, note ->
                scope.launch { repo.addExpense(date, category, amount, note) }
                showForm = false
            }
        )
    }
}

@Composable
fun ExpenseDialog(
    onDismiss: () -> Unit,
    onSave: (date: Long, category: String, amount: Long, note: String) -> Unit
) {
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var category by remember { mutableStateOf(EXPENSE_CATEGORIES.first()) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val amount = amountText.toLongOrNull() ?: 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل مصروف") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DateButton("التاريخ", date, Modifier.fillMaxWidth()) { date = it }
                PickerField("البند", category, EXPENSE_CATEGORIES) { category = it }
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("المبلغ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("التفاصيل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount > 0,
                onClick = { onSave(date, category, amount, note.trim()) }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

// ---------- Customers ----------

@Composable
fun CustomersScreen(repo: Repo, profile: Profile) {
    val customers by remember { repo.customers() }.collectAsState(initial = emptyList())
    val sales by remember(profile.isOwner) { repo.sales(profile.isOwner) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("زبون جديد") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = newName.isNotBlank(),
                onClick = {
                    val name = newName.trim()
                    newName = ""
                    scope.launch { repo.addCustomer(name) }
                }
            ) { Text("إضافة") }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(customers, key = { it.id }) { c ->
                val credit = sales.filter { it.customer == c.name && it.payment == PAY_CREDIT }.sumOf { it.total }
                val paid = sales.filter { it.customer == c.name && it.payment == PAY_COLLECT }.sumOf { it.total }
                EntryCard(
                    title = c.name,
                    subtitle = "على الحساب ${money(credit)} - مدفوع ${money(paid)}",
                    amount = "الرصيد ${money(credit - paid)}",
                    canDelete = false,
                    onDelete = {}
                )
            }
        }
    }
}

// ---------- Reports (owner only) ----------

@Composable
fun ReportsScreen(repo: Repo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val products by remember { repo.products() }.collectAsState(initial = emptyList())
    val customers by remember { repo.customers() }.collectAsState(initial = emptyList())
    val sales by remember { repo.sales(true) }.collectAsState(initial = emptyList())
    val purchases by remember { repo.purchases(true) }.collectAsState(initial = emptyList())
    val expenses by remember { repo.expenses(true) }.collectAsState(initial = emptyList())

    var fromMs by remember { mutableStateOf(startOfMonth()) }
    var toMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var statementCustomer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var newProduct by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("") }

    // Builds the file in the background, then opens the share sheet.
    fun exportAndShare(baseName: String, pdf: Boolean, build: () -> List<Section>) {
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    exportSections(context, baseName, build(), pdf)
                }
                shareFile(context, file, if (pdf) PDF_MIME else XLSX_MIME)
            } catch (e: Exception) {
                Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (products.isEmpty() && customers.isEmpty()) {
            Button(
                onClick = { scope.launch { repo.seedDefaults() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تحميل قائمة الأسعار والزبائن من الإكسل") }
        }

        Text("تقرير لفترة محددة", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateButton("من", fromMs, Modifier.weight(1f)) { fromMs = it }
            DateButton("إلى", toMs, Modifier.weight(1f)) { toMs = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    exportAndShare("report_${dayKey(fromMs)}_${dayKey(toMs)}", false) {
                        buildSections(fromMs, endOfDay(toMs), sales, purchases, expenses, customers)
                    }
                }
            ) { Text("إكسل") }
            Button(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    exportAndShare("report_${dayKey(fromMs)}_${dayKey(toMs)}", true) {
                        buildSections(fromMs, endOfDay(toMs), sales, purchases, expenses, customers)
                    }
                }
            ) { Text("PDF") }
        }

        Text("كشف حساب زبون", fontWeight = FontWeight.Bold)
        PickerField("الزبون", statementCustomer, customers.map { it.name }) { statementCustomer = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && statementCustomer.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val who = statementCustomer
                    exportAndShare("statement_${who}_${dayKey(System.currentTimeMillis())}", false) {
                        listOf(statementSection(who, sales, fromMs, endOfDay(toMs)))
                    }
                }
            ) { Text("كشف إكسل") }
            Button(
                enabled = !busy && statementCustomer.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val who = statementCustomer
                    exportAndShare("statement_${who}_${dayKey(System.currentTimeMillis())}", true) {
                        listOf(statementSection(who, sales, fromMs, endOfDay(toMs)))
                    }
                }
            ) { Text("كشف PDF") }
        }

        Text("نسخة احتياطية", fontWeight = FontWeight.Bold)
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            val file = exportSections(
                                context, "backup_all",
                                buildSections(0L, Long.MAX_VALUE, sales, purchases, expenses, customers),
                                false
                            )
                            saveToDownloads(context, file)
                        }
                        Toast.makeText(context, "تم الحفظ في Downloads: $saved", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "ما قدرنا نحفظ النسخة: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("نسخة احتياطية كاملة (Downloads)") }

        Text("إضافة صنف للقائمة", fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = newProduct,
            onValueChange = { newProduct = it },
            label = { Text("اسم الصنف") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newPrice,
            onValueChange = { newPrice = it.filter(Char::isDigit) },
            label = { Text("السعر") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = newProduct.isNotBlank() && newPrice.isNotBlank(),
            onClick = {
                val name = newProduct.trim()
                val price = newPrice.toLong()
                newProduct = ""
                newPrice = ""
                scope.launch { repo.addProduct(name, price) }
            }
        ) { Text("إضافة") }
    }
}
