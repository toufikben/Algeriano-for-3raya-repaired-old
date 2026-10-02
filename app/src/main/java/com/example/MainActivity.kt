package com.example

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.content.res.Configuration
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.receiver.MyDeviceAdminReceiver
import com.example.ui.components.AppPasswordGuideDialog
import com.example.ui.components.AppPinGate
import com.example.ui.components.CredentialsCard
import com.example.ui.components.CountdownCard
import com.example.ui.components.DeveloperInfoDialog
import com.example.ui.components.IntruderLogsCard
import com.example.ui.components.PermissionsStatusCard
import com.example.ui.components.PinManagementDialog
import com.example.ui.components.PremiumScreen
import com.example.ui.components.ProtectionToggleCard
import com.example.ui.components.SecurityStatusCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyberNavyBg
import com.example.ui.theme.CyberNavySurface
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SecurityViewModel
import com.example.data.SecurityPrefs
import com.example.i18n.AppLanguage
import com.example.i18n.LanguageStore
import com.example.i18n.isRtl

class MainActivity : ComponentActivity() {
    private var deviceAdminRequestStarted = false

    private val viewModel: SecurityViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val languageStore = LanguageStore(this)
        applyLanguage(languageStore.get())

        setContent {
            MyApplicationTheme {
                var selectedLanguage by remember { mutableStateOf(languageStore.get()) }
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (selectedLanguage.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    AppPinGate(SecurityPrefs.getInstance(this@MainActivity)) {
                        // Recomposing with the language as key re-reads every string
                        // from the reconfigured resources. recreate() did the same job
                        // but also stopped the activity, which re-locked the PIN gate.
                        key(selectedLanguage) {
                            SecurityMainScreen(
                                viewModel = viewModel,
                                onRequestAdmin = { requestDeviceAdmin() },
                                onRequestBatteryExemption = { requestBatteryExemption() },
                                language = selectedLanguage,
                                onLanguageChanged = { language ->
                                    languageStore.set(language)
                                    applyLanguage(language)
                                    SecurityApp.refreshNotificationChannels(this@MainActivity)
                                    selectedLanguage = language
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (deviceAdminRequestStarted) {
            deviceAdminRequestStarted = false
            val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isAdminActive(adminComponent)) {
                Toast.makeText(this, getString(com.example.R.string.ui_device_admin_restricted), Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
        viewModel.resumeProtectionFromVisibleActivity()
    }

    private fun applyLanguage(language: AppLanguage) {
        val locale = Locale.forLanguageTag(language.code)
        Locale.setDefault(locale)
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(com.example.R.string.ui_81f1f7a15d28)
                )
            }
            deviceAdminRequestStarted = true
            try {
                startActivity(intent)
            } catch (_: Exception) {
                deviceAdminRequestStarted = false
                Toast.makeText(this, getString(com.example.R.string.ui_device_admin_unavailable), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(com.example.R.string.ui_98f5fc225503), Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, getString(com.example.R.string.ui_09e9d58c3f8a), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityMainScreen(
    viewModel: SecurityViewModel,
    onRequestAdmin: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeveloperInfo by remember { mutableStateOf(false) }
    var showAppPasswordGuide by remember { mutableStateOf(false) }
    var showPinManagement by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    val securityPrefs = remember { SecurityPrefs.getInstance(context) }
    var showPrivacyDisclosure by remember { mutableStateOf(!securityPrefs.privacyDisclosureAccepted) }

    // Lifecycle observer to refresh statuses when returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStatuses()
                viewModel.refreshEntitlement()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Permissions launchers
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshStatuses()
    }

    fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        if (!securityPrefs.privacyDisclosureAccepted) {
            showPrivacyDisclosure = true
        }
    }

    LaunchedEffect(uiState.bannerMessage) {
        uiState.bannerMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissBanner()
        }
    }

    if (showPrivacyDisclosure) {
        AlertDialog(
            onDismissRequest = { showPrivacyDisclosure = false },
            title = { Text(tr(com.example.R.string.ui_privacy_disclosure_title)) },
            text = { Text(tr(com.example.R.string.ui_privacy_disclosure_body)) },
            confirmButton = {
                TextButton(onClick = {
                    securityPrefs.privacyDisclosureAccepted = true
                    showPrivacyDisclosure = false
                    requestRequiredPermissions()
                }) {
                    Text(tr(com.example.R.string.ui_privacy_disclosure_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDisclosure = false }) {
                    Text(tr(com.example.R.string.ui_privacy_disclosure_not_now))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("main_scaffold"),
        containerColor = CyberNavyBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(listOf(CyanAccent, EmeraldActive))
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Phone Fortress",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.showPremiumScreen() },
                        modifier = Modifier.testTag("premium_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Work,
                            contentDescription = tr(com.example.R.string.premium_title),
                            tint = if (uiState.isPremium) MaterialTheme.colorScheme.primary else CyanAccent
                        )
                    }
                    IconButton(
                        onClick = { showLanguagePicker = true },
                        modifier = Modifier.testTag("language_picker_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = tr(com.example.R.string.ui_9a6e57604b4d),
                            tint = CyanAccent
                        )
                    }
                    IconButton(
                        onClick = { showPinManagement = true },
                        modifier = Modifier.testTag("pin_management_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = tr(com.example.R.string.ui_29cc79599c5c),
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = { showAppPasswordGuide = true },
                        modifier = Modifier.testTag("guide_icon_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HelpOutline,
                            contentDescription = tr(com.example.R.string.ui_c2d7f7559f21),
                            tint = CyanAccent
                        )
                    }
                    IconButton(
                        onClick = { showDeveloperInfo = true },
                        modifier = Modifier.testTag("developer_info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = tr(com.example.R.string.ui_cd24acb10110),
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = CyberNavySurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_content_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Warning banner if Device Admin is not enabled
            if (!uiState.isAdminActive) {
                item(key = "admin_warning_banner") {
                    AdminWarningBanner(
                        onActivateAdmin = {
                            if (!securityPrefs.privacyDisclosureAccepted) {
                                showPrivacyDisclosure = true
                            } else {
                                onRequestAdmin()
                            }
                        }
                    )
                }
            }

            // Top Hero Illustration
            item(key = "security_hero") {
                SecurityHeroHeader(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    attemptsCount = uiState.totalAttempts
                )
            }

            item(key = "security_status_card") {
                SecurityStatusCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    isAdminActive = uiState.isAdminActive,
                    hasCameraPermission = uiState.hasCameraPermission,
                    hasLocationPermission = uiState.hasLocationPermission,
                    hasNotificationPermission = uiState.hasNotificationPermission,
                    emailConfigured = uiState.email.isNotBlank() && uiState.hasPassword
                )
            }

            // 1. Protection Switch & Live Toggle
            item(key = "protection_toggle_card") {
                ProtectionToggleCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    isTesting = uiState.isTesting,
                    onToggleTracking = { enabled ->
                        if (enabled && !securityPrefs.privacyDisclosureAccepted) {
                            showPrivacyDisclosure = true
                        } else if (enabled && !uiState.isAdminActive) {
                            onRequestAdmin()
                        } else {
                            viewModel.toggleTracking(enabled)
                        }
                    },
                    onTestAlert = {
                        viewModel.testAlert()
                    }
                )
            }

            // 2. Countdown fallback protection
            item(key = "countdown_card") {
                CountdownCard(
                    isTrackingEnabled = uiState.isTrackingEnabled,
                    countdownEnabled = uiState.countdownEnabled,
                    remainingMillis = uiState.countdownRemainingMillis,
                    selectedDurationMillis = uiState.countdownDurationMillis,
                    autoRestart = uiState.countdownAutoRestart,
                    onStart = viewModel::startCountdown,
                    onReset = viewModel::resetCountdown,
                    onCancel = viewModel::cancelCountdown,
                    onAutoRestartChange = viewModel::setCountdownAutoRestart
                )
            }

            // 3. Email & App Password Configuration
            item(key = "credentials_card") {
                CredentialsCard(
                    email = uiState.email,
                    password = uiState.password,
                    hasSavedPassword = uiState.hasPassword,
                    onEmailChange = viewModel::onEmailChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onClearPassword = viewModel::requestClearSavedPassword,
                    onSave = viewModel::saveCredentials,
                    onOpenGuide = { showAppPasswordGuide = true },
                    saveFeedback = uiState.saveFeedback
                )
            }

            // 3. System Permissions Status Card
            item(key = "permissions_status_card") {
                PermissionsStatusCard(
                    isAdminActive = uiState.isAdminActive,
                    hasCameraPermission = uiState.hasCameraPermission,
                    hasLocationPermission = uiState.hasLocationPermission,
                    hasNotificationPermission = uiState.hasNotificationPermission,
                    isBatteryOptimizationIgnored = uiState.isBatteryOptimizationIgnored,
                    onRequestAdmin = {
                        if (!securityPrefs.privacyDisclosureAccepted) {
                            showPrivacyDisclosure = true
                        } else {
                            onRequestAdmin()
                        }
                    },
                    onRequestCamera = {
                        if (!securityPrefs.privacyDisclosureAccepted) {
                            showPrivacyDisclosure = true
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        }
                    },
                    onRequestLocation = {
                        if (!securityPrefs.privacyDisclosureAccepted) {
                            showPrivacyDisclosure = true
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    onRequestNotifications = {
                        if (!securityPrefs.privacyDisclosureAccepted) {
                            showPrivacyDisclosure = true
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    },
                    onRequestBatteryExemption = onRequestBatteryExemption
                )
            }

            // 4. Intruder Logs & History Card
            item(key = "intruder_logs_card") {
                IntruderLogsCard(
                    logs = uiState.logs,
                    onClearLogs = viewModel::clearLogs
                )
            }

            // Bottom Developer Info trigger button
            item(key = "footer_developer_button_item") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    TextButton(
                        onClick = { showDeveloperInfo = true },
                        modifier = Modifier.testTag("footer_developer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tr(com.example.R.string.ui_9eb289360bce),
                            color = CyanAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Developer Info Modal Dialog
    if (showDeveloperInfo) {
        DeveloperInfoDialog(onDismiss = { showDeveloperInfo = false })
    }

    // App Password Guide Dialog
    if (showAppPasswordGuide) {
        AppPasswordGuideDialog(onDismiss = { showAppPasswordGuide = false })
    }

    if (showPinManagement) {
        PinManagementDialog(
            context = context,
            prefs = SecurityPrefs.getInstance(context),
            onDismiss = { showPinManagement = false }
        )
    }
    if (showLanguagePicker) {
        LanguagePickerDialog(
            language = language,
            onLanguageChanged = {
                onLanguageChanged(it)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false }
        )
    }
    if (uiState.showPremiumScreen) {
        Dialog(
            onDismissRequest = { viewModel.dismissPremiumScreen() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PremiumScreen(
                isPremium = uiState.isPremium,
                localizedPrice = uiState.localizedPrice,
                evidenceUsage = uiState.evidenceUsage,
                historyLimit = uiState.historyLimit,
                onUnlockPremium = {
                    (context as? ComponentActivity)?.let { viewModel.launchPurchase(it) }
                },
                onRestorePurchase = { viewModel.restorePurchases() },
                onDismiss = { viewModel.dismissPremiumScreen() }
            )
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    language: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(com.example.R.string.ui_de636443d0c9)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppLanguage.entries.forEach { option ->
                    TextButton(
                        onClick = { onLanguageChanged(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = context.getString(option.displayNameRes),
                            modifier = Modifier.fillMaxWidth(),
                            color = if (option == language) CyanAccent else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (option == language) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(com.example.R.string.ui_5bf826c5e57c))
            }
        }
    )
}

@Composable
fun SecurityHeroHeader(
    isTrackingEnabled: Boolean,
    attemptsCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = CyberNavySurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            AsyncImage(
                model = R.drawable.security_hero_banner_1787338876086,
                contentDescription = tr(com.example.R.string.ui_51ae4e30735b),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF090D1A).copy(alpha = 0.9f),
                                Color(0xFF090D1A).copy(alpha = 0.4f)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isTrackingEnabled) EmeraldActive else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isTrackingEnabled) tr(com.example.R.string.ui_6239de7ab740) else tr(com.example.R.string.ui_8ed3ffdd1ed5),
                        color = if (isTrackingEnabled) EmeraldActive else Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = tr(com.example.R.string.ui_12d1334739f6),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = tr(com.example.R.string.ui_b557180d4541),
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun AdminWarningBanner(
    onActivateAdmin: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_warning_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = Color(0xFF451A03)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = tr(com.example.R.string.ui_2dd35163a536),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Text(
                        text = tr(com.example.R.string.ui_24caf782e9fc),
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            androidx.compose.material3.Button(
                onClick = onActivateAdmin,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF59E0B),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(tr(com.example.R.string.ui_c98273f24670), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@androidx.compose.runtime.Composable
private fun tr(@StringRes id: Int): String = stringResource(id)
