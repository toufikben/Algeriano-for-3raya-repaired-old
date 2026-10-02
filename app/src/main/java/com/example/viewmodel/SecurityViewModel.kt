package com.example.viewmodel

import com.example.R

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Patterns
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.IntruderLog
import com.example.data.SecurityEventStateMachine
import com.example.data.SecurityPrefs
import com.example.billing.BillingManager
import java.io.File
import com.example.receiver.CountdownScheduler
import com.example.receiver.MyDeviceAdminReceiver
import com.example.service.CameraForegroundService
import com.example.worker.SecurityEventDistributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SecurityUiState(
    val email: String = "",
    // User-input buffer only. Never populated with the stored plaintext
    // password; hasPassword signals that a password is saved.
    val password: String = "",
    val hasPassword: Boolean = false,
    val isTrackingEnabled: Boolean = false,
    val isAdminActive: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isBatteryOptimizationIgnored: Boolean = false,
    val logs: List<IntruderLog> = emptyList(),
    val totalAttempts: Int = 0,
    val isTesting: Boolean = false,
    val saveFeedback: Boolean = false,
    val bannerMessage: String? = null,
    val countdownEnabled: Boolean = false,
    val countdownEndTime: Long = 0L,
    val countdownDurationMillis: Long = 60 * 60 * 1000L,
    val countdownRemainingMillis: Long = 0L,
    val countdownAutoRestart: Boolean = false,
    // Premium / Monetization fields
    val isPremium: Boolean = false,
    val evidenceUsage: Pair<Int, Int> = Pair(0, 3), // (current, limit)
    val historyLimit: Int = 5,
    val localizedPrice: String? = null,
    val showPremiumScreen: Boolean = false
)

class SecurityViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    
    // Billing manager for premium entitlement
    private val billingManager = BillingManager(context)

    private val prefs = SecurityPrefs.getInstance(context)

    // Set by the explicit "clear password" action; the next successful save
    // then writes an empty password instead of keeping the stored one.
    private var credentialsClearRequested = false

    private val _uiState = MutableStateFlow(
        SecurityUiState(
            email = prefs.email,
            hasPassword = prefs.password.isNotEmpty(),
            isTrackingEnabled = prefs.isTrackingEnabled,
            logs = prefs.getVisibleLogs(),
            totalAttempts = prefs.totalAttempts,
            countdownEnabled = prefs.countdownEnabled,
            countdownEndTime = prefs.countdownEndTime,
            countdownDurationMillis = prefs.countdownDurationMillis,
            countdownRemainingMillis = remainingCountdownMillis(),
            countdownAutoRestart = prefs.countdownAutoRestart,
            isPremium = prefs.isPremium,
            evidenceUsage = prefs.getEvidenceUsage(),
            historyLimit = prefs.getHistoryLimit()
        )
    )
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    init {
        // Initialize billing and listen for premium entitlement changes
        viewModelScope.launch {
            billingManager.initialize()
            
            // Collect premium status updates from BillingManager
            launch {
                billingManager.isPremium.collect { isPremium ->
                    // UI mirror only: BillingManager owns the cached entitlement, so
                    // writing prefs here would overwrite it with an unverified value.
                    // The tier also re-bounds the visible history and the quota row.
                    _uiState.update {
                        it.copy(
                            isPremium = isPremium,
                            historyLimit = prefs.getHistoryLimit(),
                            evidenceUsage = prefs.getEvidenceUsage(),
                            logs = prefs.getVisibleLogs()
                        )
                    }
                }
            }
            
            // Play answers later than the screen opens, so the price arrives here.
            launch {
                billingManager.localizedPrice.collect { price ->
                    _uiState.update { it.copy(localizedPrice = price) }
                }
            }
        }
    }

    /** Live-remaining ticker; only alive while a countdown is enabled. */
    private var countdownTickerJob: Job? = null

    init {
        val recoveredEvents = prefs.recoverStaleSecurityEvents()
        CountdownScheduler.rescheduleFromPrefs(context)
        if (recoveredEvents > 0) {
            _uiState.update {
                it.copy(bannerMessage = context.getString(com.example.R.string.ui_recovered_events, recoveredEvents))
            }
        }
        SecurityEventDistributor.dispatchPendingFromVisibleContext(context)
        viewModelScope.launch {
            prefs.trackingEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isTrackingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            prefs.logsFlow.collect { logList ->
                // addLog/clearLogs mutate totalAttempts and publish logsFlow
                // together, so refreshing both here keeps the hero counter
                // exact instead of frozen at the 50-log display cap.
                _uiState.update {
                    it.copy(
                        logs = logList.take(prefs.getHistoryLimit()),
                        totalAttempts = prefs.totalAttempts
                    )
                }
            }
        }
        syncCountdownTicker()
        refreshStatuses()
    }

    /**
     * Binds the 1-second countdown ticker to [SecurityPrefs.countdownEnabled]:
     * no coroutine keeps spinning once the countdown is off, and the loop also
     * self-terminates if the countdown is cleared by a background component.
     */
    private fun syncCountdownTicker() {
        val shouldRun = prefs.countdownEnabled
        if (shouldRun && countdownTickerJob?.isActive != true) {
            countdownTickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    val enabled = prefs.countdownEnabled
                    _uiState.update {
                        it.copy(
                            countdownEnabled = enabled,
                            countdownEndTime = if (enabled) prefs.countdownEndTime else 0L,
                            countdownRemainingMillis = remainingCountdownMillis(),
                            countdownAutoRestart = prefs.countdownAutoRestart
                        )
                    }
                    if (!enabled) return@launch
                }
            }
        } else if (!shouldRun) {
            countdownTickerJob?.cancel()
            countdownTickerJob = null
        }
    }

    private fun remainingCountdownMillis(): Long {
        if (!prefs.countdownEnabled) return 0L
        // FIX: use the tamper-resistant min(epoch, elapsedRealtime) remaining
        // instead of a raw wall-clock subtraction the user could inflate.
        return prefs.countdownRemainingMillis()
    }

    fun resumeProtectionFromVisibleActivity() {
        refreshCredentials()
        if (!prefs.isTrackingEnabled) return
        runCatching {
            val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
        CountdownScheduler.rescheduleFromPrefs(context)
        SecurityEventDistributor.dispatchPendingFromVisibleContext(context)
    }

    fun refreshCredentials() {
        val savedEmail = prefs.email
        val savedPassword = prefs.password
        // FIX: do not copy the decrypted app password into UI state on every
        // resume (recents screenshot / shoulder-surfing exposure). The field
        // stays empty; hasPassword reports that a password is stored.
        _uiState.update {
            it.copy(
                email = savedEmail,
                password = "",
                hasPassword = savedPassword.isNotEmpty()
            )
        }
    }

    fun onEmailChange(newEmail: String) {
        _uiState.update { it.copy(email = newEmail) }
    }

    fun onPasswordChange(newPassword: String) {
        // Typing a new password supersedes a pending explicit clear request.
        if (newPassword.isNotEmpty()) credentialsClearRequested = false
        _uiState.update { it.copy(password = newPassword) }
    }

    /**
     * Explicit "clear password" action from the credentials card. Saving with
     * this flag set writes an empty password; without it, an empty password
     * field keeps the stored value unchanged.
     */
    fun requestClearSavedPassword() {
        credentialsClearRequested = true
        _uiState.update { it.copy(password = "") }
    }

    fun saveCredentials(): Boolean {
        val email = _uiState.value.email.trim()
        val enteredPassword = _uiState.value.password.trim()
        val clearRequested = credentialsClearRequested
        val password = when {
            clearRequested -> ""
            enteredPassword.isEmpty() -> prefs.password
            else -> enteredPassword
        }
        if (email.isNotEmpty() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_2def4fef47d3)) }
            return false
        }
        if (email.isNotEmpty() && password.isEmpty()) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_f509a4a96bac)) }
            return false
        }
        // FIX: an empty email AND empty password used to silently erase both
        // stored credentials even while protection was active. Wiping stored
        // credentials now requires the explicit clear-password action.
        if (email.isEmpty() && password.isEmpty() && !clearRequested &&
            (prefs.email.isNotBlank() || prefs.password.isNotBlank())
        ) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_16b9f058a24f)) }
            return false
        }
        prefs.email = email
        prefs.password = password
        // FIX: verify both email and password round-trip. Previously only
        // password was checked, so a KeyStore encrypt failure on email was
        // silent and the UI reported success.
        if (email.isNotEmpty() && prefs.email != email) {
            _uiState.update {
                it.copy(
                    bannerMessage = context.getString(com.example.R.string.ui_credentials_save_failed),
                    hasPassword = prefs.password.isNotEmpty()
                )
            }
            return false
        }
        if (password.isNotEmpty() && prefs.password != password) {
            _uiState.update {
                it.copy(
                    bannerMessage = context.getString(com.example.R.string.ui_credentials_save_failed),
                    hasPassword = false
                )
            }
            return false
        }
        credentialsClearRequested = false
        // Never echo the plaintext back into UI state; the field stays empty
        // with the "saved" placeholder shown by the credentials card.
        _uiState.update {
            it.copy(
                password = "",
                hasPassword = prefs.password.isNotEmpty()
            )
        }

        viewModelScope.launch {
            _uiState.update { it.copy(saveFeedback = true) }
            delay(2000)
            _uiState.update { it.copy(saveFeedback = false) }
        }
        return true
    }

    fun toggleTracking(enabled: Boolean) {
        if (enabled && !_uiState.value.isAdminActive) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_9a86733913f4)) }
            return
        }

        if (enabled && !saveCredentials()) return
        prefs.isTrackingEnabled = enabled
        if (!enabled) {
            prefs.resetFailedUnlockAttempts()
            CountdownScheduler.cancel(context)
        }
        _uiState.update { it.copy(isTrackingEnabled = enabled) }

        if (enabled) {
            // Start the foreground service while the activity is visible. This
            // lets DeviceAdminReceiver deliver a failed-unlock event to an
            // already-running service instead of attempting a camera FGS start
            // from the lock screen on Android 14+.
            runCatching {
                val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
                    action = CameraForegroundService.ACTION_START_MONITORING
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            SecurityEventDistributor.dispatchPendingFromVisibleContext(context)
        } else {
            // Letting the service stop itself also clears the whole alert
            // notification range, which a bare stopService() left behind. A
            // receiver-initiated stop runs in the background, where startService
            // is not allowed, so fall back to stopping it directly.
            val stopIntent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_STOP_MONITORING
            }
            runCatching { context.startService(stopIntent) }.onFailure {
                context.stopService(Intent(context, CameraForegroundService::class.java))
            }
        }
    }

    fun testAlert() {
        // Persist the values currently visible in the form before the service
        // reads credentials. This prevents the first test after editing from
        // using stale saved credentials.
        if (!saveCredentials()) {
            _uiState.update { it.copy(isTesting = false) }
            return
        }
        _uiState.update { it.copy(isTesting = true) }

        val serviceIntent = Intent(context, CameraForegroundService::class.java).apply {
            action = CameraForegroundService.ACTION_TEST_CAPTURE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            val message = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException ->
                    context.getString(com.example.R.string.ui_d32a71996ff2)
                e is SecurityException ->
                    context.getString(com.example.R.string.ui_f6f4b6b1f861)
                else -> context.getString(com.example.R.string.ui_634e11ba84ef)
            }
            _uiState.update { it.copy(bannerMessage = message, isTesting = false) }
        }

        val requestedAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            // Wait for the service to report the test as finished rather than
            // trusting a fixed duration: a slow SMTP handshake used to make the
            // spinner stop while the alert was still being sent.
            while (CameraForegroundService.lastTestFinishedAt < requestedAt &&
                SystemClock.elapsedRealtime() - requestedAt < 60_000L
            ) {
                delay(400)
            }
            _uiState.update { it.copy(isTesting = false) }
        }
    }

    fun startCountdown(durationMillis: Long) {
        if (!_uiState.value.isTrackingEnabled) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_353209d98cda)) }
            return
        }
        CountdownScheduler.start(context, durationMillis)
        // Start the pre-armed foreground service while this activity is still
        // visible. Only then offer Exact Alarm settings; launching Settings
        // first can make Android classify the FGS start as background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            }
        }
        syncCountdownTicker()
        _uiState.update {
            it.copy(
                countdownEnabled = true,
                countdownEndTime = prefs.countdownEndTime,
                countdownDurationMillis = prefs.countdownDurationMillis,
                countdownRemainingMillis = remainingCountdownMillis()
            )
        }
    }

    fun cancelCountdown() {
        CountdownScheduler.cancel(context)
        syncCountdownTicker()
        _uiState.update {
            it.copy(countdownEnabled = false, countdownEndTime = 0L, countdownRemainingMillis = 0L)
        }
    }

    fun resetCountdown() {
        if (!_uiState.value.isTrackingEnabled) {
            _uiState.update { it.copy(bannerMessage = context.getString(com.example.R.string.ui_3a57361fa5e4)) }
            return
        }
        CountdownScheduler.start(context, prefs.countdownDurationMillis)
        syncCountdownTicker()
        _uiState.update {
            it.copy(
                countdownEnabled = true,
                countdownEndTime = prefs.countdownEndTime,
                countdownDurationMillis = prefs.countdownDurationMillis,
                countdownRemainingMillis = remainingCountdownMillis(),
                bannerMessage = context.getString(com.example.R.string.ui_3651049d0311)
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            // FIX (audit preservation): this previously deleted every file in
            // intruder_photos, which burned the evidence photos still
            // referenced by non-terminal events awaiting send/resend. Build
            // the set of photo paths referenced by pending-work events first
            // (their files and photoPath references must survive), then let
            // prefs.clearLogs() clear the logs and drop photo references for
            // terminal events only, and finally delete just the orphaned
            // files that no pending event references.
            val pendingPhotoPaths = prefs.getSecurityEvents()
                .filter { SecurityEventStateMachine.isPendingWork(it.status) }
                .mapNotNull { event ->
                    event.photoPath?.let { path ->
                        runCatching { File(path).canonicalPath }.getOrNull() ?: path
                    }
                }
                .toSet()
            prefs.clearLogs()
            context.getExternalFilesDir(null)?.resolve("intruder_photos")?.listFiles()
                ?.forEach { file ->
                    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: file.absolutePath
                    if (canonical !in pendingPhotoPaths && file.absolutePath !in pendingPhotoPaths) {
                        runCatching { file.delete() }
                    }
                }
        }
    }

    fun setCountdownAutoRestart(enabled: Boolean) {
        // Enabling is the paid continuous-protection switch; disabling is always
        // allowed so an expired entitlement can never trap the user in it.
        if (enabled && !prefs.isPremium) return
        prefs.countdownAutoRestart = enabled
        _uiState.update { it.copy(countdownAutoRestart = enabled) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(bannerMessage = null) }
    }
    
    // ==================== PREMIUM / MONETIZATION METHODS ====================
    
    /** Show premium screen */
    fun showPremiumScreen() {
        _uiState.update { 
            it.copy(
                showPremiumScreen = true,
                evidenceUsage = prefs.getEvidenceUsage(),
                historyLimit = prefs.getHistoryLimit()
            )
        }
        // The unlock button stays disabled without a price from Play, so the screen that
        // shows it asks again rather than trusting a response from app start.
        refreshEntitlement()
    }
    
    /** Dismiss premium screen */
    fun dismissPremiumScreen() {
        _uiState.update { it.copy(showPremiumScreen = false) }
    }
    
    /** Launch purchase flow - must be called from Activity with context */
    fun launchPurchase(activity: androidx.activity.ComponentActivity) {
        viewModelScope.launch {
            billingManager.launchPurchase(activity)
        }
    }
    
    /** Restore previous purchases */
    fun restorePurchases() {
        viewModelScope.launch {
            billingManager.restorePurchases()
        }
    }
    
    /**
     * Re-read the entitlement from Google Play. Kept out of [refreshStatuses], which
     * is a documented passive read path; Play asks for a purchase query on every
     * resume so a purchase completed outside the app is picked up without a restart.
     */
    fun refreshEntitlement() {
        viewModelScope.launch {
            billingManager.refreshFromPlay()
        }
    }

    fun refreshStatuses() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, MyDeviceAdminReceiver::class.java)
        val isAdmin = dpm?.isAdminActive(adminComponent) == true

        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasLocation = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }

        // FIX (audit): refreshStatuses is a passive read path (called on every
        // ON_RESUME and after permission dialogs). It previously disabled
        // protection, reset the failed-attempt counters and cancelled the
        // countdown whenever isAdminActive read false, even transiently. The
        // explicit "admin removed -> disable protection" behavior belongs to
        // MyDeviceAdminReceiver.onDisabled (the real state-change callback);
        // this path now only refreshes the displayed statuses.
        _uiState.update {
            it.copy(
                isAdminActive = isAdmin,
                hasCameraPermission = hasCamera,
                hasLocationPermission = hasLocation,
                hasNotificationPermission = hasNotifications,
                isBatteryOptimizationIgnored = isBatteryIgnored,
                totalAttempts = prefs.totalAttempts
            )
        }
        syncCountdownTicker()
    }
    
    override fun onCleared() {
        super.onCleared()
        billingManager.destroy()
    }
}
