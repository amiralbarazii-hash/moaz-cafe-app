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
import kotlinx.coroutines.flow.flowOf
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
fun EntryCard(
    title: String,
    subtitle: String,
    amount: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(amount, fontWeight = FontWeight.Bold)
            if (onEdit != null) {
                TextButton(onClick = onEdit) { Text("تعديل") }
            }
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
    val customers by remember(profile.canManage) { if (profile.canManage) repo.customers() else flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val sales by remember(profile.canManage) { if (profile.canManage) repo.sales(true) else flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val pending by remember { repo.pendingCountFlow() }.collectAsState(initial = 0)
    var editing by remember { mutableStateOf<Sale?>(null) }
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
        if (profile.canManage) Text(
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
                    canDelete = profile.canManage,
                    onDelete = { scope.launch { repo.delete("sales", s.id) } },
                    onEdit = if (profile.canManage) ({ editing = s }) else null
                )
            }
        }
    }

    if (showForm || editing != null) {
        val current = editing
        SaleDialog(
            products = products,
            customers = customers,
            initial = current,
            onDismiss = { showForm = false; editing = null },
            onSave = { date, product, qty, unitPrice, discount, total, payment, customer, note ->
                scope.launch {
                    if (current != null) {
                        repo.updateSale(current.id, date, product, qty, unitPrice, discount, total, payment, customer, note)
                    } else {
                        repo.addSale(date, product, qty, unitPrice, discount, total, payment, customer, note)
                    }
                    if (customer.isNotBlank() && customers.none { it.name == customer }) {
                        repo.addCustomer(customer)
                    }
                }
                showForm = false
                editing = null
            }
        )
    }
}

@Composable
fun SaleDialog(
    products: List<Product>,
    customers: List<Customer>,
    initial: Sale? = null,
    onDismiss: () -> Unit,
    onSave: (date: Long, product: String, qty: Long, unitPrice: Long, discount: Long, total: Long, payment: String, customer: String, note: String) -> Unit
) {
    var date by remember { mutableStateOf(initial?.date ?: System.currentTimeMillis()) }
    var payment by remember { mutableStateOf(initial?.payment ?: PAY_CASH) }
    var productQuery by remember { mutableStateOf(initial?.product ?: "") }
    var productName by remember { mutableStateOf(initial?.product ?: "") }
    var priceText by remember { mutableStateOf(initial?.unitPrice?.toString() ?: "") }
    var qtyText by remember { mutableStateOf(initial?.qty?.toString() ?: "1") }
    var discountText by remember { mutableStateOf(initial?.discount?.takeIf { it > 0 }?.toString() ?: "") }
    var customerText by remember { mutableStateOf(initial?.customer ?: "") }
    var amountText by remember {
        mutableStateOf(if (initial?.payment == PAY_COLLECT) initial.total.toString() else "")
    }
    var note by remember { mutableStateOf(initial?.note ?: "") }

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
        val q = productQuery.trim()
        products.filter { it.name.contains(q, ignoreCase = true) }
            .sortedBy { !it.name.startsWith(q, ignoreCase = true) }
            .take(8)
            .map { it.name }
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
    val purchases by remember(profile.canManage) { if (profile.canManage) repo.purchases(true) else flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    val today = dayKey(System.currentTimeMillis())
    val todayTotal = purchases.filter { dayKey(it.date) == today }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (profile.canManage) Text("مشتريات اليوم: ${money(todayTotal)} ل.س", fontWeight = FontWeight.Bold)
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
                    canDelete = profile.canManage,
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
    val expenses by remember(profile.canManage) { if (profile.canManage) repo.expenses(true) else flowOf(emptyList()) }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }

    val today = dayKey(System.currentTimeMillis())
    val todayTotal = expenses.filter { dayKey(it.date) == today }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (profile.canManage) Text("مصروفات اليوم: ${money(todayTotal)} ل.س", fontWeight = FontWeight.Bold)
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
                    canDelete = profile.canManage,
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
    val sales by remember(profile.canManage) { repo.sales(profile.canManage) }.collectAsState(initial = emptyList())
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
    val items by remember { repo.items() }.collectAsState(initial = emptyList())
    val consumptions by remember { repo.consumptions() }.collectAsState(initial = emptyList())

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
                        buildSections(fromMs, endOfDay(toMs), sales, purchases, expenses, customers, items, consumptions)
                    }
                }
            ) { Text("إكسل") }
            Button(
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    exportAndShare("report_${dayKey(fromMs)}_${dayKey(toMs)}", true) {
                        buildSections(fromMs, endOfDay(toMs), sales, purchases, expenses, customers, items, consumptions)
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
                                buildSections(0L, Long.MAX_VALUE, sales, purchases, expenses, customers, items, consumptions),
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

// ---------- Inventory (owner and manager) ----------

@Composable
fun InventoryScreen(repo: Repo) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stock by remember { repo.items() }.collectAsState(initial = emptyList())
    val sales by remember { repo.sales(true) }.collectAsState(initial = emptyList())
    val purchases by remember { repo.purchases(true) }.collectAsState(initial = emptyList())
    val consumptions by remember { repo.consumptions() }.collectAsState(initial = emptyList())

    val counted = remember { mutableStateMapOf<String, String>() }
    var newName by remember { mutableStateOf("") }
    var newUnit by remember { mutableStateOf("") }
    var newOpening by remember { mutableStateOf("") }
    var consItem by remember { mutableStateOf("") }
    var consQty by remember { mutableStateOf("") }
    var consNote by remember { mutableStateOf("") }

    fun systemQty(item: Item): Long =
        item.opening +
            purchases.filter { it.item == item.name }.sumOf { it.qty } -
            sales.filter { it.product == item.name }.sumOf { it.qty } -
            consumptions.filter { it.itemName == item.name }.sumOf { it.qty }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text("إضافة صنف للمخزون", fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = newName, onValueChange = { newName = it },
                label = { Text("اسم الصنف") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newUnit, onValueChange = { newUnit = it },
                    label = { Text("الوحدة") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newOpening, onValueChange = { newOpening = it.filter(Char::isDigit) },
                    label = { Text("الرصيد الافتتاحي") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                enabled = newName.isNotBlank(),
                onClick = {
                    val name = newName.trim()
                    val unit = newUnit.trim()
                    val opening = newOpening.toLongOrNull() ?: 0L
                    newName = ""; newUnit = ""; newOpening = ""
                    scope.launch { repo.addItem(name, unit, opening) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("إضافة") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("تسجيل استهلاك يدوي", fontWeight = FontWeight.Bold)
            PickerField("الصنف", consItem, stock.map { it.name }) { consItem = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = consQty, onValueChange = { consQty = it.filter(Char::isDigit) },
                    label = { Text("الكمية") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = consNote, onValueChange = { consNote = it },
                    label = { Text("ملاحظة") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Button(
                enabled = consItem.isNotBlank() && (consQty.toLongOrNull() ?: 0L) > 0,
                onClick = {
                    val qty = consQty.toLong()
                    val name = consItem
                    val note = consNote.trim()
                    consQty = ""; consNote = ""
                    scope.launch {
                        repo.addConsumption(System.currentTimeMillis(), name, qty, note)
                        Toast.makeText(context, "تم تسجيل الاستهلاك", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تسجيل") }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الجرد الأسبوعي", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(
                    enabled = counted.isNotEmpty(),
                    onClick = {
                        val values = counted.mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }.toMap()
                        counted.clear()
                        scope.launch { repo.saveCounts(System.currentTimeMillis(), values) }
                    }
                ) { Text("حفظ الجرد") }
            }
            Text(
                "اكتب الكمية المعدودة فعلياً لكل صنف، والفرق مع النظام يتسجل.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        items(stock, key = { it.id }) { it ->
            val sys = systemQty(it)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${it.name} ${if (it.unit.isNotBlank()) "(${it.unit})" else ""}", fontWeight = FontWeight.Bold)
                    Text("بالنظام: $sys", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = counted[it.name] ?: "",
                        onValueChange = { v -> counted[it.name] = v.filter(Char::isDigit) },
                        label = { Text("المعدود فعلياً") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
