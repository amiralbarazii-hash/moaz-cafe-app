package com.example.cafe

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val PAY_CASH = "كاش"
const val PAY_CREDIT = "على الحساب"
const val PAY_COLLECT = "تحصيل دين"
val PAYMENT_TYPES = listOf(PAY_CASH, PAY_CREDIT, PAY_COLLECT)

val PURCHASE_CATEGORIES = listOf("مواد للبيع", "مشروبات", "فواكه وخضار", "حليب وألبان", "مستلزمات", "ثلج", "أخرى")
val EXPENSE_CATEGORIES = listOf("تكسي ومواصلات", "رواتب", "صيانة", "كهرباء وماء وغاز", "أخرى")

// Copied from the workbook's القوائم sheet.
val DEFAULT_PRODUCTS = listOf(
    "مي صغيرة" to 80L, "مي كبيرة" to 150L, "قهوة تركي" to 250L, "اسبريسو" to 300L,
    "اسبريسو دبل" to 450L, "أمريكانو" to 350L, "عصير برتقال" to 400L, "عصير أناناس" to 450L,
    "ميلك شيك" to 450L, "زهورات" to 200L, "شاي أخضر" to 200L, "شوت زنجبيل" to 350L,
    "كاسة حليب" to 100L, "أكوا سبين" to 300L, "بسكوتة Fitness" to 200L, "Peanut bar" to 200L,
    "علكة مينتوس" to 150L, "كيندر" to 250L, "سنيكرز" to 250L, "M&M" to 250L,
    "موزة" to 50L, "تفاحة" to 50L
)

val DEFAULT_CUSTOMERS = listOf(
    "الدكتور", "كوتش حلفاوي", "كريم كابيتال", "كريم كاشير", "دانيا دعدع",
    "كوتش عمرو", "كوتش عبدالله", "كوتش خالد", "منير سليمان", "لمى", "قصي"
)

data class Profile(val uid: String, val email: String, val role: String) {
    val isOwner: Boolean get() = role == "owner"
    /** Owner and manager: full access. Employees only record sales, purchases and expenses. */
    val canManage: Boolean get() = role == "owner" || role == "manager"
}

data class Product(val id: String, val name: String, val price: Long)
data class Customer(val id: String, val name: String)

data class Sale(
    val id: String, val date: Long, val product: String, val qty: Long, val unitPrice: Long,
    val discount: Long, val total: Long, val payment: String, val customer: String, val note: String,
    val employeeId: String, val employeeEmail: String
)

data class Purchase(
    val id: String, val date: Long, val item: String, val qty: Long, val amount: Long,
    val category: String, val note: String, val employeeId: String, val employeeEmail: String
)

data class Expense(
    val id: String, val date: Long, val category: String, val amount: Long, val note: String,
    val employeeId: String, val employeeEmail: String
)

fun dayKey(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))
fun monthKey(ms: Long): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(ms))
fun dateOf(ms: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(ms))

fun startOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun endOfDay(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 23)
    set(Calendar.MINUTE, 59)
    set(Calendar.SECOND, 59)
    set(Calendar.MILLISECOND, 999)
}.timeInMillis

class Repo {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    val uid: String get() = auth.currentUser?.uid ?: ""
    val email: String get() = auth.currentUser?.email ?: ""

    private fun baseFields(date: Long): Map<String, Any> = mapOf(
        "date" to date,
        "employeeId" to uid,
        "employeeEmail" to email
    )

    private fun <T> listen(query: Query, mapper: (DocumentSnapshot) -> T): Flow<List<T>> = callbackFlow {
        val reg = query.addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.documents?.map(mapper) ?: emptyList())
        }
        awaitClose { reg.remove() }
    }

    // Employees only see their own records; the owner sees everything.
    private fun scoped(col: String, isOwner: Boolean): Query {
        val base: Query = db.collection(col)
        return if (isOwner) base else base.whereEqualTo("employeeId", uid)
    }

    fun profileFlow(): Flow<Profile?> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(snap?.takeIf { it.exists() }?.let {
                Profile(uid, it.getString("email") ?: email, it.getString("role") ?: "employee")
            })
        }
        awaitClose { reg.remove() }
    }

    // Creates the employee profile on first login. The owner's role is changed in the Firebase console.
    suspend fun ensureProfile() {
        val ref = db.collection("users").document(uid)
        if (!ref.get().await().exists()) {
            ref.set(mapOf("email" to email, "role" to "employee")).await()
        }
    }

    /** Number of this user's records that are still waiting to reach the cloud (offline). */
    fun pendingCountFlow(): Flow<Int> = callbackFlow {
        val reg = db.collection("sales").whereEqualTo("employeeId", uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val pending = snap?.documents?.count { it.metadata.hasPendingWrites() } ?: 0
                trySend(pending)
            }
        awaitClose { reg.remove() }
    }

    fun products(): Flow<List<Product>> = listen(db.collection("products")) { d ->
        Product(d.id, d.getString("name") ?: "", d.getLong("price") ?: 0L)
    }.map { list -> list.sortedBy { it.name } }

    fun customers(): Flow<List<Customer>> = listen(db.collection("customers")) { d ->
        Customer(d.id, d.getString("name") ?: "")
    }.map { list -> list.sortedBy { it.name } }

    fun sales(isOwner: Boolean): Flow<List<Sale>> = listen(scoped("sales", isOwner)) { d ->
        Sale(
            id = d.id,
            date = d.getLong("date") ?: 0L,
            product = d.getString("product") ?: "",
            qty = d.getLong("qty") ?: 0L,
            unitPrice = d.getLong("unitPrice") ?: 0L,
            discount = d.getLong("discount") ?: 0L,
            total = d.getLong("total") ?: 0L,
            payment = d.getString("payment") ?: "",
            customer = d.getString("customer") ?: "",
            note = d.getString("note") ?: "",
            employeeId = d.getString("employeeId") ?: "",
            employeeEmail = d.getString("employeeEmail") ?: ""
        )
    }.map { list -> list.sortedByDescending { it.date } }

    fun purchases(isOwner: Boolean): Flow<List<Purchase>> = listen(scoped("purchases", isOwner)) { d ->
        Purchase(
            id = d.id,
            date = d.getLong("date") ?: 0L,
            item = d.getString("item") ?: "",
            qty = d.getLong("qty") ?: 0L,
            amount = d.getLong("amount") ?: 0L,
            category = d.getString("category") ?: "",
            note = d.getString("note") ?: "",
            employeeId = d.getString("employeeId") ?: "",
            employeeEmail = d.getString("employeeEmail") ?: ""
        )
    }.map { list -> list.sortedByDescending { it.date } }

    fun expenses(isOwner: Boolean): Flow<List<Expense>> = listen(scoped("expenses", isOwner)) { d ->
        Expense(
            id = d.id,
            date = d.getLong("date") ?: 0L,
            category = d.getString("category") ?: "",
            amount = d.getLong("amount") ?: 0L,
            note = d.getString("note") ?: "",
            employeeId = d.getString("employeeId") ?: "",
            employeeEmail = d.getString("employeeEmail") ?: ""
        )
    }.map { list -> list.sortedByDescending { it.date } }

    suspend fun addSale(
        date: Long, product: String, qty: Long, unitPrice: Long, discount: Long, total: Long,
        payment: String, customer: String, note: String
    ) {
        db.collection("sales").add(
            baseFields(date) + mapOf(
                "product" to product,
                "qty" to qty,
                "unitPrice" to unitPrice,
                "discount" to discount,
                "total" to total,
                "payment" to payment,
                "customer" to customer,
                "note" to note
            )
        ).await()
    }

    /** Owner only (enforced by Firestore rules). Keeps the original employee fields. */
    suspend fun updateSale(
        id: String, date: Long, product: String, qty: Long, unitPrice: Long, discount: Long,
        total: Long, payment: String, customer: String, note: String
    ) {
        db.collection("sales").document(id).update(
            mapOf(
                "date" to date,
                "product" to product,
                "qty" to qty,
                "unitPrice" to unitPrice,
                "discount" to discount,
                "total" to total,
                "payment" to payment,
                "customer" to customer,
                "note" to note
            )
        ).await()
    }

    suspend fun addPurchase(date: Long, item: String, qty: Long, amount: Long, category: String, note: String) {
        db.collection("purchases").add(
            baseFields(date) + mapOf(
                "item" to item,
                "qty" to qty,
                "amount" to amount,
                "category" to category,
                "note" to note
            )
        ).await()
    }

    suspend fun addExpense(date: Long, category: String, amount: Long, note: String) {
        db.collection("expenses").add(
            baseFields(date) + mapOf(
                "category" to category,
                "amount" to amount,
                "note" to note
            )
        ).await()
    }

    suspend fun addCustomer(name: String) {
        db.collection("customers").add(mapOf("name" to name)).await()
    }

    suspend fun addProduct(name: String, price: Long) {
        db.collection("products").add(mapOf("name" to name, "price" to price)).await()
    }

    suspend fun delete(col: String, id: String) {
        db.collection(col).document(id).delete().await()
    }

    fun items(): Flow<List<Item>> = listen(db.collection("items")) { d ->
        Item(d.id, d.getString("name") ?: "", d.getString("unit") ?: "", d.getLong("opening") ?: 0L)
    }

    fun consumptions(): Flow<List<Consumption>> = listen(db.collection("consumptions")) { d ->
        Consumption(
            d.id, d.getLong("date") ?: 0L, d.getString("itemName") ?: "",
            d.getLong("qty") ?: 0L, d.getString("note") ?: ""
        )
    }

    suspend fun addItem(name: String, unit: String, opening: Long) {
        db.collection("items").add(mapOf("name" to name, "unit" to unit, "opening" to opening)).await()
    }

    suspend fun addConsumption(date: Long, itemName: String, qty: Long, note: String) {
        db.collection("consumptions").add(
            mapOf("date" to date, "itemName" to itemName, "qty" to qty, "note" to note, "employeeId" to uid)
        ).await()
    }

    /** Weekly count: one record per item counted, stored with the count date. */
    suspend fun saveCounts(date: Long, counts: Map<String, Long>) {
        val batch = db.batch()
        counts.forEach { (name, counted) ->
            batch.set(
                db.collection("counts").document(),
                mapOf("date" to date, "itemName" to name, "counted" to counted, "employeeId" to uid)
            )
        }
        batch.commit().await()
    }

    suspend fun seedDefaults() {
        val batch = db.batch()
        DEFAULT_PRODUCTS.forEach { (name, price) ->
            batch.set(db.collection("products").document(), mapOf("name" to name, "price" to price))
        }
        DEFAULT_CUSTOMERS.forEach { name ->
            batch.set(db.collection("customers").document(), mapOf("name" to name))
        }
        batch.commit().await()
    }
}

data class Item(val id: String, val name: String, val unit: String, val opening: Long)
data class Consumption(val id: String, val date: Long, val itemName: String, val qty: Long, val note: String)
