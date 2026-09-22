package com.example.debtmanager

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ============ النماذج ============
@Serializable
data class Customer(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("name") val name: String,
    @SerialName("phone") val phone: String,
    @SerialName("total_debt") val totalDebt: Double = 0.0,
    @SerialName("remaining_debt") val remainingDebt: Double = 0.0,
    @SerialName("assigned_employee_id") val assignedEmployeeId: String? = null,
    @SerialName("assigned_employee_name") val assignedEmployeeName: String? = null,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("contact_status") val contactStatus: String = "none",
    @SerialName("notes") val notes: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class InventoryItem(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("item_name") val itemName: String,
    @SerialName("location") val location: String = "",
    @SerialName("holder") val holder: String = "",
    @SerialName("status") val status: String = "available",
    @SerialName("notes") val notes: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class Payment(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("customer_id") val customerId: String,
    @SerialName("amount") val amount: Double,
    @SerialName("payment_date") val paymentDate: String,
    @SerialName("notes") val notes: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class MessageTemplate(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("title") val title: String,
    @SerialName("body") val body: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class AuditLog(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("action") val action: String,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class UserProfile(
    @SerialName("id") val id: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("role") val role: String,
    @SerialName("permissions") val permissions: Map<String, Boolean> = emptyMap()
)

// ============ إعداد Supabase ============
object SupabaseManager {
    private const val SUPABASE_URL = "https://egxxoflsdlmzzttpgoon.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_wTUnHCGojPXzZuY-FeNf1g_YvtKm0pC"

    val client: SupabaseClient by lazy {
        createSupabaseClient(SUPABASE_URL, SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }
    }
}

// ============ أدوات التاريخ ============
object DateTimeUtils {
    private val zoneId = ZoneId.of("Asia/Aden")
    fun getTodayISO(): String = LocalDate.now(zoneId).format(DateTimeFormatter.ISO_DATE)
    fun getNowISO(): String = OffsetDateTime.now(zoneId).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    fun formatDate(isoDate: String): String = try {
        LocalDate.parse(isoDate).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    } catch (e: Exception) { isoDate }
    fun formatDateTime(isoDateTime: String): String = try {
        OffsetDateTime.parse(isoDateTime).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
    } catch (e: Exception) { isoDateTime }
    fun daysUntil(dateISO: String): Long = try {
        java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(zoneId), LocalDate.parse(dateISO))
    } catch (e: Exception) { 0 }
}

fun Double.format(decimals: Int = 2): String =
    BigDecimal(this).setScale(decimals, RoundingMode.HALF_UP).toPlainString()

// ============ المستودعات ============
object AuthRepository {
    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    suspend fun login(email: String, password: String): Result<UserProfile> {
        return try {
            SupabaseManager.client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val userId = SupabaseManager.client.auth.currentUserOrNull()?.id
                ?: throw Exception("تعذر العثور على المستخدم")
            val profile = SupabaseManager.client.postgrest["profiles"]
                .select { filter { eq("id", userId) } }
                .decodeSingle<UserProfile>()
            _currentUser.value = profile
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        try { SupabaseManager.client.auth.signOut() } catch (_: Exception) {}
        _currentUser.value = null
    }

    fun isAdmin(): Boolean = _currentUser.value?.role == "admin"
}

object CustomerRepository {
    private val table get() = SupabaseManager.client.postgrest["customers"]

    fun getCustomers(): Flow<List<Customer>> = flow {
        emit(table.select().decodeList<Customer>())
    }

    fun searchCustomers(query: String): Flow<List<Customer>> = flow {
        emit(table.select {
            filter {
                or {
                    like("name", "%$query%")
                    like("phone", "%$query%")
                }
            }
        }.decodeList<Customer>())
    }

    suspend fun addCustomer(customer: Customer): Result<Customer> = try {
        val newCust = table.insert(customer).decodeSingle<Customer>()
        AuditLogRepository.addLog("إضافة العميل: ${customer.name}")
        Result.success(newCust)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun updateCustomer(customer: Customer): Result<Customer> = try {
        val updated = table.update(customer) { filter { eq("id", customer.id) } }
            .decodeSingle<Customer>()
        AuditLogRepository.addLog("تحديث بيانات العميل: ${customer.name}")
        Result.success(updated)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun deleteCustomer(id: String): Result<Unit> = try {
        table.delete { filter { eq("id", id) } }
        AuditLogRepository.addLog("حذف العميل: $id")
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun getPayments(customerId: String): List<Payment> = try {
        SupabaseManager.client.postgrest["payments"]
            .select { filter { eq("customer_id", customerId) } }
            .decodeList()
    } catch (e: Exception) { emptyList() }

    suspend fun addPayment(payment: Payment): Result<Payment> = try {
        val newPayment = SupabaseManager.client.postgrest["payments"]
            .insert(payment).decodeSingle<Payment>()
        val customer = getCustomer(payment.customerId)
        if (customer != null && customer.remainingDebt >= payment.amount) {
            val updated = customer.copy(remainingDebt = customer.remainingDebt - payment.amount)
            updateCustomer(updated)
        }
        AuditLogRepository.addLog("إضافة دفعة بقيمة ${payment.amount} للعميل ${customer?.name ?: ""}")
        Result.success(newPayment)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun getCustomer(id: String): Customer? = try {
        table.select { filter { eq("id", id) } }.decodeSingle<Customer>()
    } catch (e: Exception) { null }
}

object InventoryRepository {
    private val table get() = SupabaseManager.client.postgrest["inventory"]

    fun getInventory(): Flow<List<InventoryItem>> = flow {
        emit(table.select().decodeList<InventoryItem>())
    }

    suspend fun addItem(item: InventoryItem): Result<InventoryItem> = try {
        val newItem = table.insert(item).decodeSingle<InventoryItem>()
        AuditLogRepository.addLog("إضافة أداة مخزون: ${item.itemName}")
        Result.success(newItem)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun updateItem(item: InventoryItem): Result<InventoryItem> = try {
        val updated = table.update(item) { filter { eq("id", item.id) } }
            .decodeSingle<InventoryItem>()
        AuditLogRepository.addLog("تحديث أداة مخزون: ${item.itemName}")
        Result.success(updated)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun deleteItem(id: String): Result<Unit> = try {
        table.delete { filter { eq("id", id) } }
        AuditLogRepository.addLog("حذف أداة مخزون: $id")
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
}

object AuditLogRepository {
    private val table get() = SupabaseManager.client.postgrest["audit_logs"]

    fun getLogs(): Flow<List<AuditLog>> = flow {
        emit(table.select { order("created_at", ascending = false) }.decodeList<AuditLog>())
    }

    suspend fun addLog(action: String) {
        val user = AuthRepository.currentUser.value ?: return
        try {
            table.insert(
                AuditLog(
                    userId = user.id,
                    userName = user.fullName,
                    action = action,
                    createdAt = DateTimeUtils.getNowISO()
                )
            )
        } catch (_: Exception) {}
    }
}

object TemplateRepository {
    private val table get() = SupabaseManager.client.postgrest["templates"]

    fun getTemplates(): Flow<List<MessageTemplate>> = flow {
        emit(table.select().decodeList<MessageTemplate>())
    }

    suspend fun addTemplate(template: MessageTemplate): Result<MessageTemplate> = try {
        val result = table.insert(template).decodeSingle<MessageTemplate>()
        AuditLogRepository.addLog("إضافة قالب رسالة: ${template.title}")
        Result.success(result)
    } catch (e: Exception) { Result.failure(e) }

    suspend fun deleteTemplate(id: String): Result<Unit> = try {
        table.delete { filter { eq("id", id) } }
        AuditLogRepository.addLog("حذف قالب رسالة: $id")
        Result.success(Unit)
    } catch (e: Exception) { Result.failure(e) }
}

// ============ ViewModels ============
class AuthViewModel : ViewModel() {
    var loginState by mutableStateOf<LoginState>(LoginState.Idle)
        private set
    var isLoading by mutableStateOf(false)
        private set

    sealed class LoginState {
        object Idle : LoginState()
        data class Success(val user: UserProfile) : LoginState()
        data class Error(val message: String) : LoginState()
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            isLoading = true
            AuthRepository.login(email, password)
                .onSuccess { loginState = LoginState.Success(it) }
                .onFailure { loginState = LoginState.Error(it.localizedMessage ?: "فشل تسجيل الدخول") }
            isLoading = false
        }
    }

    fun logout() {
        viewModelScope.launch { AuthRepository.logout() }
    }
}

class CustomerViewModel : ViewModel() {
    val customers = MutableStateFlow<List<Customer>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val searchQuery = MutableStateFlow("")

    init { loadCustomers() }

    fun loadCustomers() {
        viewModelScope.launch {
            isLoading.value = true
            CustomerRepository.getCustomers()
                .catch { isLoading.value = false }
                .collect { list ->
                    customers.value = list
                    isLoading.value = false
                }
        }
    }

    fun search(query: String) {
        searchQuery.value = query
        viewModelScope.launch {
            if (query.isBlank()) {
                loadCustomers()
            } else {
                isLoading.value = true
                CustomerRepository.searchCustomers(query)
                    .catch { isLoading.value = false }
                    .collect { list ->
                        customers.value = list
                        isLoading.value = false
                    }
            }
        }
    }

    fun addCustomer(customer: Customer, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = CustomerRepository.addCustomer(customer)
            if (result.isSuccess) loadCustomers()
            onComplete(result.isSuccess)
        }
    }

    fun updateCustomer(customer: Customer, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = CustomerRepository.updateCustomer(customer)
            if (result.isSuccess) loadCustomers()
            onComplete(result.isSuccess)
        }
    }

    fun deleteCustomer(id: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = CustomerRepository.deleteCustomer(id)
            if (result.isSuccess) loadCustomers()
            onComplete(result.isSuccess)
        }
    }

    fun addPayment(customerId: String, amount: Double, notes: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val payment = Payment(
                customerId = customerId,
                amount = amount,
                paymentDate = DateTimeUtils.getTodayISO(),
                notes = notes,
                createdAt = DateTimeUtils.getNowISO()
            )
            val result = CustomerRepository.addPayment(payment)
            if (result.isSuccess) loadCustomers()
            onComplete(result.isSuccess)
        }
    }
}

class InventoryViewModel : ViewModel() {
    val items = MutableStateFlow<List<InventoryItem>>(emptyList())
    val isLoading = MutableStateFlow(false)

    init { loadInventory() }

    fun loadInventory() {
        viewModelScope.launch {
            isLoading.value = true
            InventoryRepository.getInventory()
                .catch { isLoading.value = false }
                .collect { list ->
                    items.value = list
                    isLoading.value = false
                }
        }
    }

    fun addItem(item: InventoryItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = InventoryRepository.addItem(item)
            if (result.isSuccess) loadInventory()
            onComplete(result.isSuccess)
        }
    }

    fun updateItem(item: InventoryItem, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = InventoryRepository.updateItem(item)
            if (result.isSuccess) loadInventory()
            onComplete(result.isSuccess)
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            if (InventoryRepository.deleteItem(id).isSuccess) loadInventory()
        }
    }
}

class AuditLogViewModel : ViewModel() {
    val logs = MutableStateFlow<List<AuditLog>>(emptyList())
    init {
        viewModelScope.launch {
            AuditLogRepository.getLogs().collect { logs.value = it }
        }
    }
}

class TemplateViewModel : ViewModel() {
    val templates = MutableStateFlow<List<MessageTemplate>>(emptyList())
    init {
        viewModelScope.launch {
            TemplateRepository.getTemplates().collect { templates.value = it }
        }
    }

    fun addTemplate(template: MessageTemplate, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = TemplateRepository.addTemplate(template)
            if (result.isSuccess) loadTemplates()
            onComplete(result.isSuccess)
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            if (TemplateRepository.deleteTemplate(id).isSuccess) loadTemplates()
        }
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            TemplateRepository.getTemplates().collect { templates.value = it }
        }
    }
}

// ============ الشاشات ============
@Composable
fun MainScaffold(navController: NavHostController, content: @Composable (PaddingValues) -> Unit) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isAdmin = AuthRepository.isAdmin()

    val navigationItems = buildList {
        add(Triple("customers", "العملاء", Icons.Default.Person))
        add(Triple("inventory", "المخزون", Icons.Default.ShoppingCart))
        if (isAdmin) add(Triple("audit", "السجل", Icons.Default.List))
        add(Triple("settings", "الإعدادات", Icons.Default.Settings))
    }

    Scaffold(
        bottomBar = {
            if (currentRoute != "login" && currentRoute?.startsWith("customer/") == false) {
                NavigationBar {
                    navigationItems.forEach { (route, title, icon) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                if (currentRoute != route) {
                                    navController.navigate(route) {
                                        popUpTo("customers") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            label = { Text(title, fontSize = 12.sp) },
                            icon = { Icon(icon, contentDescription = title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { padding -> content(padding) }
}

@Composable
fun LoginScreen(viewModel: AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state = viewModel.loginState
    val isLoading = viewModel.isLoading

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(Icons.Default.AccountBalanceWallet, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                Text("نظام إدارة الديون والمخزون", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text("تسجيل الدخول للمتابعة", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("البريد الإلكتروني") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("كلمة المرور") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, null) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                if (state is AuthViewModel.LoginState.Error) {
                    Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    onClick = { viewModel.login(email.trim(), password) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                    } else {
                        Text("تسجيل الدخول", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerListScreen(
    viewModel: CustomerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onCustomerClick: (String) -> Unit,
    onAddCustomer: () -> Unit
) {
    val customers by viewModel.customers.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("قائمة العملاء", fontWeight = FontWeight.Bold)
                        Text("إجمالي: ${customers.size}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadCustomers() }) {
                        Icon(Icons.Default.Refresh, "تحديث")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCustomer) {
                Icon(Icons.Default.Add, "إضافة عميل")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.search(it) },
                label = { Text("بحث عن عميل...") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp)
            )
            if (isLoading && customers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (customers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PersonOff, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text("لا يوجد عملاء", style = MaterialTheme.typography.bodyLarge)
                        Text("اضغط على + لإضافة عميل", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(customers, key = { it.id }) { customer ->
                        CustomerCard(customer, onClick = { onCustomerClick(customer.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerCard(customer: Customer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(customer.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (customer.remainingDebt > 0) {
                    AssistChip(
                        onClick = {},
                        label = { Text("عليه دين", fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("الهاتف: ${customer.phone}", style = MaterialTheme.typography.bodyMedium)
            Text("الإجمالي: ${customer.totalDebt.format()} ريال", style = MaterialTheme.typography.bodySmall)
            Text(
                "المتبقي: ${customer.remainingDebt.format()} ريال",
                style = MaterialTheme.typography.bodySmall,
                color = if (customer.remainingDebt > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(
    customerId: String,
    viewModel: CustomerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit
) {
    val customers by viewModel.customers.collectAsState()
    val customer = customers.find { it.id == customerId }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var payments by remember { mutableStateOf<List<Payment>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(customerId) {
        payments = CustomerRepository.getPayments(customerId)
    }

    if (customer == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(customer.name, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, "حذف", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        },
        floatingActionButton = {
            if (customer.remainingDebt > 0) {
                FloatingActionButton(onClick = { showPaymentDialog = true }) {
                    Icon(Icons.Default.Payment, "إضافة دفعة")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("الاسم", customer.name)
                    InfoRow("الهاتف", customer.phone)
                    InfoRow("إجمالي الدين", "${customer.totalDebt.format()} ريال")
                    InfoRow("المتبقي", "${customer.remainingDebt.format()} ريال",
                        valueColor = if (customer.remainingDebt > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    customer.dueDate?.let { InfoRow("الاستحقاق", DateTimeUtils.formatDate(it)) }
                    if (customer.notes.isNotBlank()) InfoRow("ملاحظات", customer.notes)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("سجل الدفعات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (payments.isEmpty()) {
                Text("لا توجد دفعات", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn {
                    items(payments) { payment ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${payment.amount.format()} ريال", fontWeight = FontWeight.Bold)
                                Text(DateTimeUtils.formatDate(payment.paymentDate))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPaymentDialog) {
        var amount by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("إضافة دفعة") },
            text = {
                Column {
                    OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0 && amt <= customer.remainingDebt) {
                        viewModel.addPayment(customerId, amt, notes) { success ->
                            if (success) {
                                showPaymentDialog = false
                                viewModel.loadCustomers()
                            }
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showPaymentDialog = false }) { Text("إلغاء") } }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد من حذف هذا العميل؟") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteCustomer(customerId) { success ->
                            if (success) onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerFormScreen(
    customerId: String?,
    viewModel: CustomerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onBack: () -> Unit
) {
    val customers by viewModel.customers.collectAsState()
    val isEdit = customerId != null && customerId != "new"
    val existing = if (isEdit) customers.find { it.id == customerId } else null

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var totalDebt by remember { mutableStateOf(existing?.totalDebt?.toString() ?: "0.0") }
    var remainingDebt by remember { mutableStateOf(existing?.remainingDebt?.toString() ?: "0.0") }
    var dueDate by remember { mutableStateOf(existing?.dueDate ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "تعديل عميل" else "إضافة عميل") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم العميل *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف *") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(totalDebt, { totalDebt = it }, label = { Text("إجمالي الدين") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(remainingDebt, { remainingDebt = it }, label = { Text("المتبقي") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(dueDate, { dueDate = it }, label = { Text("تاريخ الاستحقاق (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات") }, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    val total = totalDebt.toDoubleOrNull() ?: 0.0
                    val remaining = remainingDebt.toDoubleOrNull() ?: 0.0
                    if (remaining > total) {
                        Toast.makeText(context, "المتبقي لا يمكن أن يتجاوز الإجمالي", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val customer = Customer(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = name,
                        phone = phone,
                        totalDebt = total,
                        remainingDebt = remaining,
                        dueDate = dueDate.ifBlank { null },
                        notes = notes,
