@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.cafe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.cafe.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// White background with a gold accent.
private val cafeColors = lightColorScheme(
    primary = Color(0xFF9C7A1E),
    onPrimary = Color.White,
    secondary = Color(0xFFB8963A),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1F1F1F),
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF5F1E6),
    error = Color(0xFFB3261E)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = cafeColors) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) {
                        AppRoot()
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var user by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser) }

    DisposableEffect(Unit) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val current = user
    if (current == null) {
        LoginScreen()
    } else {
        key(current.uid) { MainScreen() }
    }
}

@Composable
fun LoginScreen() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = "شعار محمصة معاذ الخطيب",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("دخول الموظفين", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim() },
            label = { Text("الإيميل") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("كلمة السر") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
                    } catch (e: Exception) {
                        error = "ما قدرنا ندخل: ${e.localizedMessage}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "جاري الدخول..." else "دخول")
        }
    }
}

@Composable
fun MainScreen() {
    val repo = remember { Repo() }
    val profile by remember { repo.profileFlow() }.collectAsState(initial = null)

    LaunchedEffect(Unit) {
        runCatching { repo.ensureProfile() }
    }

    val p = profile
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val tabs = if (p.canManage) {
        listOf("المبيعات", "المشتريات", "المصروفات", "الزبائن", "المخزون", "التقارير")
    } else {
        listOf("المبيعات", "المشتريات", "المصروفات", "الزبائن")
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    val selected = tab.coerceAtMost(tabs.lastIndex)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(p.email, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { FirebaseAuth.getInstance().signOut() }) {
                Text("خروج")
            }
        }

        ScrollableTabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, title ->
                Tab(
                    selected = selected == i,
                    onClick = { tab = i },
                    text = { Text(title) }
                )
            }
        }

        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            when (tabs[selected]) {
                "المبيعات" -> SalesScreen(repo, p)
                "المشتريات" -> PurchasesScreen(repo, p)
                "المصروفات" -> ExpensesScreen(repo, p)
                "الزبائن" -> CustomersScreen(repo, p)
                "المخزون" -> InventoryScreen(repo)
                "التقارير" -> ReportsScreen(repo)
            }
        }
    }
}
