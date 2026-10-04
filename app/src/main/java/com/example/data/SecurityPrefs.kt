package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.GCMParameterSpec
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import android.security.keystore.KeyPermanentlyInvalidatedException

data class IntruderLog(
    val id: String,
    val eventId: String? = null,
    val photoCaptured: Boolean = false,
    val locationCaptured: Boolean = false,
    val timestamp: Long,
    val photoPath: String?,
    val latitude: Double?,
    val longitude: Double?,
    val address: String?,
    val emailSent: Boolean,
    val statusMessage: String,
    val photoState: SecurityEventComponentState = if (photoCaptured) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
    val locationState: SecurityEventComponentState = if (locationCaptured) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
    val emailState: SecurityEventComponentState = if (emailSent) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
)

enum class SecurityEventStatus {
    PENDING, DEFERRED, IN_PROGRESS, CAPTURED, SEND_PENDING, SENT,
    FAILED, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED
}

enum class SecurityEventComponentState {
    NOT_REQUESTED, PENDING, SUCCEEDED, FAILED, DEFERRED
}

data class SecurityEvent(
    val id: String,
    val timestamp: Long,
    val failedAttempt: Int,
    val status: SecurityEventStatus,
    val updatedAt: Long = timestamp,
    val recoveryAttempts: Int = 0,
    val sendAttempts: Int = 0,
    val photoPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationTimestamp: Long? = null,
    val photoState: SecurityEventComponentState = SecurityEventComponentState.NOT_REQUESTED,
    val locationState: SecurityEventComponentState = SecurityEventComponentState.NOT_REQUESTED,
    val emailState: SecurityEventComponentState = SecurityEventComponentState.NOT_REQUESTED
)

class SecurityPrefs private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val store = SecurityStore(context)

    init {
        migrateLegacyStorageIfNeeded()
    }

    private val _trackingEnabledFlow = MutableStateFlow(isTrackingEnabled)
    val trackingEnabledFlow: StateFlow<Boolean> = _trackingEnabledFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow(getLogs())
    val logsFlow: StateFlow<List<IntruderLog>> = _logsFlow.asStateFlow()

    companion object {
        private const val PREF_NAME = "intruder_security_prefs"
        private const val KEY_EMAIL = "key_email"
        private const val KEY_PASSWORD = "key_password"
        private const val KEY_TRACKING_ENABLED = "key_tracking_enabled"
        private const val KEY_PRIVACY_DISCLOSURE_ACCEPTED = "key_privacy_disclosure_accepted"
        private const val KEY_TOTAL_ATTEMPTS = "key_total_attempts"
        private const val KEY_LAST_ATTEMPT_TIME = "key_last_attempt_time"
        private const val KEY_LAST_ATTEMPT_LOCATION = "key_last_attempt_location"
        private const val KEY_LOGS_JSON = "key_logs_json"
        private const val KEY_THRESHOLD = "key_failed_threshold"
        private const val KEY_FAILED_UNLOCK_ATTEMPTS = "key_failed_unlock_attempts"
        private const val KEY_COUNTDOWN_ENABLED = "key_countdown_enabled"
        private const val KEY_COUNTDOWN_END_TIME = "key_countdown_end_time"
        private const val KEY_COUNTDOWN_END_REALTIME = "key_countdown_end_realtime"
        private const val KEY_COUNTDOWN_DURATION = "key_countdown_duration"
        private const val KEY_COUNTDOWN_CAPTURE_PENDING = "key_countdown_capture_pending"
        private const val KEY_COUNTDOWN_RETRY_COUNT = "key_countdown_retry_count"
        private const val KEY_COUNTDOWN_EVENT_ID = "key_countdown_event_id"
        private const val KEY_COUNTDOWN_AUTO_RESTART = "key_countdown_auto_restart"
        private const val KEY_APP_PIN_SALT = "key_app_pin_salt"
        private const val KEY_APP_PIN_HASH = "key_app_pin_hash"
        private const val KEY_PIN_FAILED_ATTEMPTS = "key_pin_failed_attempts"
        private const val KEY_PIN_BLOCKED_UNTIL = "key_pin_blocked_until"
        private const val KEY_PIN_BLOCKED_UNTIL_REALTIME = "key_pin_blocked_until_realtime"
        private const val KEY_SECURITY_EVENTS_JSON = "key_security_events_json"
        private const val KEY_PREMIUM_ENTITLEMENT = "key_premium_entitlement"
        private const val KEY_EVIDENCE_MONTH_COUNTER = "key_evidence_month_counter"
        private const val KEY_EVIDENCE_MONTH_INDEX = "key_evidence_month_index"
        private const val KEYSTORE_ALIAS = "intruder_security_credentials"
        private const val ENCRYPTED_PREFIX = "v1:"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val MAX_SECURITY_EVENTS = 100

        // The published tier numbers. Public because the paywall has to show the same
        // figures the enforcement paths apply, and two copies drift apart.
        const val FREE_HISTORY_LIMIT = 5
        const val PREMIUM_HISTORY_LIMIT = 50
        const val FREE_EVIDENCE_MONTHLY_LIMIT = 3
        const val PREMIUM_EVIDENCE_MONTHLY_LIMIT = 50
        const val FREE_PHOTO_RETENTION_DAYS = 7L
        const val PREMIUM_PHOTO_RETENTION_DAYS = 30L
        private const val EVENT_LEASE_TIMEOUT_MILLIS = 2 * 60 * 1000L
        private const val MAX_EVENT_RECOVERY_ATTEMPTS = 2
        private const val KEY_OFFLINE_SWEEP_REPAIRED = "key_offline_sweep_repaired_v30"
        private const val OFFLINE_REPAIR_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_SEND_ATTEMPTS = 3
        private const val PIN_ITERATIONS = 120_000
        private const val PIN_KEY_LENGTH = 256
        private const val PIN_MIN_LENGTH = 6
        private const val PIN_MAX_LENGTH = 8
        private const val PIN_LOCKOUT_THRESHOLD = 5
        private const val PIN_LOCKOUT_MILLIS = 30_000L
        private const val DEFAULT_COUNTDOWN_DURATION_MILLIS = 60 * 60 * 1000L

        @Volatile
        private var INSTANCE: SecurityPrefs? = null

        fun getInstance(context: Context): SecurityPrefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityPrefs(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    var email: String
        get() = readSecret(KEY_EMAIL)
        set(value) = writeSecret(KEY_EMAIL, value.trim())

    /** Records that the in-app data disclosure was shown and accepted. */
    var privacyDisclosureAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_DISCLOSURE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVACY_DISCLOSURE_ACCEPTED, value).apply()

    var password: String
        get() = readSecret(KEY_PASSWORD)
        set(value) = writeSecret(KEY_PASSWORD, value.trim())

    private fun readSecret(key: String): String {
        val stored = prefs.getString(key, "") ?: return ""
        if (stored.isBlank()) return ""
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // One-time migration for credentials saved by older app versions.
            writeSecret(key, stored)
            return stored
        }

        return try {
            decrypt(stored.removePrefix(ENCRYPTED_PREFIX))
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to decrypt stored credential: ${e.javaClass.simpleName}")
            ""
        }
    }

    private fun writeSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }

        try {
            val encrypted = ENCRYPTED_PREFIX + encrypt(value)
            prefs.edit().putString(key, encrypted).apply()
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to encrypt credential: ${e.javaClass.simpleName}")
        }
    }

    private fun getOrCreateCredentialKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A restored or changed device lock can invalidate the old key.
            // Delete only the unusable key; callers will receive an empty secret
            // rather than crashing or falling back to plaintext storage.
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        }

        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCredentialKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            ByteBuffer.allocate(4 + iv.size + ciphertext.size)
                .putInt(iv.size)
                .put(iv)
                .put(ciphertext)
                .array(),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(encoded: String): String {
        val payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val ivLength = payload.int
        require(ivLength == GCM_IV_LENGTH_BYTES) { "Invalid credential IV" }
        val iv = ByteArray(ivLength)
        payload.get(iv)
        val ciphertext = ByteArray(payload.remaining())
        payload.get(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateCredentialKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    var isTrackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()
            _trackingEnabledFlow.value = value
        }

    var totalAttempts: Int
        get() = prefs.getInt(KEY_TOTAL_ATTEMPTS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_ATTEMPTS, value).apply()

    var lastAttemptTime: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ATTEMPT_TIME, value).apply()

    var lastAttemptLocation: String
        get() = prefs.getString(KEY_LAST_ATTEMPT_LOCATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ATTEMPT_LOCATION, value).apply()

    var failedThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 1).coerceAtLeast(1)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value.coerceAtLeast(1)).apply()


    /**
     * Records one failed unlock attempt and returns the consecutive total
     * since the last successful unlock.
     */
    @Synchronized
    fun registerFailedUnlockAttempt(): Int {
        val attempts = prefs.getInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_FAILED_UNLOCK_ATTEMPTS, attempts).apply()
        return attempts
    }

    /** Resets the consecutive failed-unlock counter after a successful unlock. */
    @Synchronized
    fun resetFailedUnlockAttempts(cancelPendingEvents: Boolean = true) {
        prefs.edit().putInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0).apply()
        if (!cancelPendingEvents) return
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                // FIX: route cancellation through the state machine instead of a
                // hardcoded status list, keeping the machine the single source
                // of truth (CAPTURED events legitimately stay in flight).
                if (event.status != SecurityEventStatus.CANCELLED &&
                    SecurityEventStateMachine.canTransition(
                        event.status,
                        SecurityEventStatus.CANCELLED
                    )
                ) event.copy(status = SecurityEventStatus.CANCELLED) else event
            }
        }
    }

    @Synchronized
    fun enqueueSecurityEvent(timestamp: Long = System.currentTimeMillis()): SecurityEvent {
        val event = SecurityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            failedAttempt = prefs.getInt(KEY_FAILED_UNLOCK_ATTEMPTS, 0),
            status = SecurityEventStatus.PENDING,
            updatedAt = timestamp
        )
        updateSecurityEventsInRoom { it + event }
        return event
    }

    @Synchronized
    fun claimNextSecurityEvent(): SecurityEvent? {
        val now = System.currentTimeMillis()
        val next = getSecurityEventsFromRoom().firstOrNull {
            it.status == SecurityEventStatus.PENDING || it.status == SecurityEventStatus.DEFERRED
        } ?: return null
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == next.id) it.copy(
                    status = SecurityEventStatus.IN_PROGRESS,
                    updatedAt = now
                ) else it
            }
        }
        return next.copy(status = SecurityEventStatus.IN_PROGRESS, updatedAt = now)
    }

    @Synchronized
    fun claimSecurityEvent(id: String): Boolean {
        val event = getSecurityEventsFromRoom().firstOrNull { it.id == id } ?: return false
        if (event.status != SecurityEventStatus.PENDING && event.status != SecurityEventStatus.DEFERRED) return false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id) it.copy(
                    status = SecurityEventStatus.IN_PROGRESS,
                    updatedAt = System.currentTimeMillis()
                ) else it
            }
        }
        return true
    }

    @Synchronized
    fun completeSecurityEvent(id: String, status: SecurityEventStatus) {
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(it.status, status)) {
                    it.copy(status = status, updatedAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }

    @Synchronized
    fun deferSecurityEvent(id: String): Boolean {
        var changed = false
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (event.id == id && SecurityEventStateMachine.canTransition(event.status, SecurityEventStatus.DEFERRED)) {
                    changed = true
                    event.copy(
                        status = SecurityEventStatus.DEFERRED,
                        updatedAt = System.currentTimeMillis(),
                        photoState = if (event.photoState == SecurityEventComponentState.SUCCEEDED) event.photoState else SecurityEventComponentState.DEFERRED,
                        locationState = if (event.locationState == SecurityEventComponentState.SUCCEEDED) event.locationState else SecurityEventComponentState.DEFERRED
                    )
                } else event
            }
        }
        return changed
    }

    @Synchronized
    fun getSecurityEvent(id: String): SecurityEvent? = getSecurityEventsFromRoom().firstOrNull { it.id == id }

    @Synchronized
    fun markCapturePending(id: String) {
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (event.id == id) event.copy(
                    photoState = if (event.photoState == SecurityEventComponentState.SUCCEEDED) event.photoState else SecurityEventComponentState.PENDING,
                    locationState = if (event.locationState == SecurityEventComponentState.SUCCEEDED) event.locationState else SecurityEventComponentState.PENDING,
                    updatedAt = System.currentTimeMillis()
                ) else event
            }
        }
    }

    @Synchronized
    fun recordCaptureResult(
        id: String,
        photoPath: String?,
        latitude: Double?,
        longitude: Double?,
        locationTimestamp: Long?
    ) {
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(
                        it.status,
                        SecurityEventStatus.CAPTURED
                    )) {
                    it.copy(
                        status = SecurityEventStatus.CAPTURED,
                        updatedAt = System.currentTimeMillis(),
                        photoPath = photoPath,
                        latitude = latitude,
                        longitude = longitude,
                        locationTimestamp = locationTimestamp,
                        photoState = if (photoPath.isNullOrBlank()) SecurityEventComponentState.FAILED else SecurityEventComponentState.SUCCEEDED,
                        locationState = if (latitude != null && longitude != null) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED
                    )
                } else it
            }
        }
    }

    @Synchronized
    fun moveCapturedEventToSendPending(id: String): Boolean {
        var changed = false
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (event.id == id && SecurityEventStateMachine.canTransition(event.status, SecurityEventStatus.SEND_PENDING)) {
                    changed = true
                    event.copy(status = SecurityEventStatus.SEND_PENDING, updatedAt = System.currentTimeMillis())
                } else event
            }
        }
        return changed
    }

    @Synchronized
    fun recordLocationResult(id: String, latitude: Double?, longitude: Double?, locationTimestamp: Long?) {
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (event.id == id && event.photoState == SecurityEventComponentState.SUCCEEDED) event.copy(
                    latitude = latitude,
                    longitude = longitude,
                    locationTimestamp = locationTimestamp,
                    locationState = if (latitude != null && longitude != null) SecurityEventComponentState.SUCCEEDED else SecurityEventComponentState.FAILED,
                    updatedAt = System.currentTimeMillis()
                ) else event
            }
        }
    }

    @Synchronized
    fun updateEmailState(id: String, state: SecurityEventComponentState) {
        updateSecurityEventsInRoom { events ->
            events.map { event -> if (event.id == id) event.copy(emailState = state, updatedAt = System.currentTimeMillis()) else event }
        }
    }

    @Synchronized
    fun recordSendRetry(id: String): Boolean {
        var retry = false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && it.status in setOf(
                        SecurityEventStatus.SEND_PENDING,
                        SecurityEventStatus.FAILED_RETRYABLE
                    )) {
                    val attempts = it.sendAttempts + 1
                    retry = attempts < MAX_SEND_ATTEMPTS
                    it.copy(
                        status = if (retry) SecurityEventStatus.FAILED_RETRYABLE else SecurityEventStatus.FAILED_FINAL,
                        sendAttempts = attempts,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        }
        return retry
    }

    @Synchronized
    fun markSendPendingForRetry(id: String): Boolean {
        var changed = false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id && SecurityEventStateMachine.canTransition(
                        it.status,
                        SecurityEventStatus.SEND_PENDING
                    )) {
                    changed = true
                    it.copy(
                        status = SecurityEventStatus.SEND_PENDING,
                        emailState = SecurityEventComponentState.PENDING,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
        }
        return changed
    }

    /** Returns true only for the call that actually moved the event to a failed state. */
    @Synchronized
    fun failPendingSecurityEvent(id: String): Boolean {
        var changed = false
        updateSecurityEventsInRoom { events ->
            events.map {
                if (it.id == id) {
                    // FIX: SEND_PENDING / FAILED_RETRYABLE cannot go to FAILED
                    // per state machine, so fall back to FAILED_FINAL.
                    val target = if (SecurityEventStateMachine.canTransition(
                            it.status,
                            SecurityEventStatus.FAILED
                        )
                    ) SecurityEventStatus.FAILED
                    else if (SecurityEventStateMachine.canTransition(
                            it.status,
                            SecurityEventStatus.FAILED_FINAL
                        )
                    ) SecurityEventStatus.FAILED_FINAL
                    else return@map it
                    if (target == it.status) return@map it
                    changed = true
                    it.copy(status = target, updatedAt = System.currentTimeMillis())
                } else it
            }
        }
        return changed
    }

    // FIX: both predicates now derive from SecurityEventStateMachine.isPendingWork
    // so has/get answer the same question. Safety for existing callers: the worker
    // gate (getPendingSecurityEvent below) and claimSecurityEvent still refuse
    // IN_PROGRESS events, and CAPTURED events resume only through the send-only
    // path, so including IN_PROGRESS/CAPTURED here cannot double-process a claim.
    fun hasPendingSecurityEvents(): Boolean = synchronized(this) {
        getSecurityEventsFromRoom().any { SecurityEventStateMachine.isPendingWork(it.status) }
    }

    fun getPendingSecurityEvents(): List<SecurityEvent> = synchronized(this) {
        getSecurityEventsFromRoom().filter { SecurityEventStateMachine.isPendingWork(it.status) }
    }

    /** Worker-side gate: intentionally excludes IN_PROGRESS/CAPTURED so an
     * enqueued worker never re-processes an event the service already claims. */
    @Synchronized
    fun getPendingSecurityEvent(id: String): SecurityEvent? {
        return store.event(id)?.takeIf {
            it.status == SecurityEventStatus.PENDING ||
                it.status == SecurityEventStatus.DEFERRED ||
                it.status == SecurityEventStatus.SEND_PENDING ||
                it.status == SecurityEventStatus.FAILED_RETRYABLE
        }
    }

    /**
     * One-time repair for installs that ran the old sweep: events finalized while they
     * were only waiting for a network (photo on disk, mail never refused by the server,
     * younger than a week) go back to SEND_PENDING. A refused send leaves emailState
     * FAILED and is not touched, so a wrong App Password is never retried this way.
     */
    @Synchronized
    fun reviveEventsFinalizedWhileOffline(
        now: Long = System.currentTimeMillis(),
        evenIfDone: Boolean = false
    ): Int {
        if (!evenIfDone && prefs.getBoolean(KEY_OFFLINE_SWEEP_REPAIRED, false)) return 0
        var revived = 0
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                val waitingForNetwork = event.emailState == SecurityEventComponentState.PENDING ||
                    event.emailState == SecurityEventComponentState.DEFERRED
                val photoOnDisk = event.photoPath?.let { File(it).exists() } == true
                if (event.status == SecurityEventStatus.FAILED_FINAL && waitingForNetwork &&
                    photoOnDisk && now - event.timestamp < OFFLINE_REPAIR_MAX_AGE_MILLIS
                ) {
                    revived += 1
                    // Deliberately outside the state machine: FAILED_FINAL is terminal,
                    // and this undoes a finalization that should not have happened.
                    event.copy(status = SecurityEventStatus.SEND_PENDING, updatedAt = now)
                } else event
            }
        }
        prefs.edit().putBoolean(KEY_OFFLINE_SWEEP_REPAIRED, true).apply()
        return revived
    }

    /** Re-queues abandoned work after a process/device restart, with a bounded retry count. */
    @Synchronized
    fun recoverStaleSecurityEvents(now: Long = System.currentTimeMillis()): Int {
        var recovered = 0
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                val stale = (event.status == SecurityEventStatus.IN_PROGRESS ||
                    event.status == SecurityEventStatus.CAPTURED ||
                    event.status == SecurityEventStatus.SEND_PENDING) &&
                    now - event.updatedAt >= EVENT_LEASE_TIMEOUT_MILLIS
                if (!stale) return@map event

                // An event that already holds its evidence and is only waiting for a
                // network to send it is not an abandoned lease: an overnight outage
                // used to sweep it into FAILED_FINAL after two passes, so the photo was
                // never mailed. Re-arm it for sending without spending a recovery attempt.
                val holdsEvidence = event.photoPath != null || event.latitude != null
                if (holdsEvidence && event.status != SecurityEventStatus.IN_PROGRESS) {
                    if (!SecurityEventStateMachine.canTransition(event.status, SecurityEventStatus.SEND_PENDING)) {
                        return@map event
                    }
                    recovered += 1
                    return@map event.copy(status = SecurityEventStatus.SEND_PENDING, updatedAt = now)
                }

                // FIX: lease-expiry recoveries (IN_PROGRESS/CAPTURED -> PENDING)
                // are declared legal in the state machine, and every recovery
                // write is validated through canTransition like all other paths.
                val target = if (event.recoveryAttempts < MAX_EVENT_RECOVERY_ATTEMPTS) {
                    if (event.photoPath != null) SecurityEventStatus.SEND_PENDING
                    else SecurityEventStatus.PENDING
                } else {
                    SecurityEventStatus.FAILED_FINAL
                }
                if (!SecurityEventStateMachine.canTransition(event.status, target)) return@map event
                if (event.recoveryAttempts < MAX_EVENT_RECOVERY_ATTEMPTS) recovered += 1
                event.copy(
                    status = target,
                    updatedAt = now,
                    recoveryAttempts = if (target == SecurityEventStatus.FAILED_FINAL)
                        event.recoveryAttempts else event.recoveryAttempts + 1
                )
            }
        }
        return recovered
    }

    private fun readLegacySecurityEvents(): List<SecurityEvent> {
        val json = prefs.getString(KEY_SECURITY_EVENTS_JSON, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SecurityEvent(
                    id = item.getString("id"),
                    timestamp = item.getLong("timestamp"),
                    failedAttempt = item.getInt("failedAttempt"),
                    status = runCatching {
                        SecurityEventStatus.valueOf(item.getString("status"))
                    }.getOrDefault(SecurityEventStatus.FAILED),
                    updatedAt = item.optLong("updatedAt", item.optLong("timestamp")),
                    recoveryAttempts = item.optInt("recoveryAttempts", 0).coerceAtLeast(0),
                    sendAttempts = item.optInt("sendAttempts", 0).coerceAtLeast(0),
                    photoPath = if (item.has("photoPath")) item.getString("photoPath") else null,
                    latitude = if (item.has("latitude")) item.optDouble("latitude") else null,
                    longitude = if (item.has("longitude")) item.optDouble("longitude") else null,
                    locationTimestamp = if (item.has("locationTimestamp")) item.optLong("locationTimestamp") else null
                )
            }
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to read security events", e)
            emptyList()
        }
    }

    private fun getSecurityEventsFromRoom(): List<SecurityEvent> = store.events()

    fun getSecurityEvents(): List<SecurityEvent> = getSecurityEventsFromRoom()

    private fun updateSecurityEventsInRoom(transform: (List<SecurityEvent>) -> List<SecurityEvent>) {
        val events = transform(store.events())
        val active = events.filterNot { SecurityEventStateMachine.isTerminal(it.status) }
        val terminal = events.filter { SecurityEventStateMachine.isTerminal(it.status) }
        // Never evict retryable or in-flight work; bound only terminal history.
        // The bound is the storage window, NOT the tier history limit: trimming by
        // entitlement here would destroy already-captured evidence the moment a
        // premium user refunded or a billing query flipped the cached flag.
        val boundedTerminal = terminal.sortedBy { it.timestamp }
            .takeLast(MAX_SECURITY_EVENTS)
        val merged = (active + boundedTerminal).sortedBy { it.timestamp }
        store.replaceEvents(merged)
    }

    private fun migrateLegacyStorageIfNeeded() {
        val legacyEvents = readLegacySecurityEvents()
        val legacyLogs = readLegacyLogs()
        if (store.events().isEmpty() && legacyEvents.isNotEmpty()) store.replaceEvents(legacyEvents)
        if (store.logs().isEmpty() && legacyLogs.isNotEmpty()) store.replaceLogs(legacyLogs)
        if (legacyEvents.isNotEmpty() || legacyLogs.isNotEmpty()) {
            prefs.edit().remove(KEY_SECURITY_EVENTS_JSON).remove(KEY_LOGS_JSON).apply()
        }
    }

    val hasAppPin: Boolean
        get() = prefs.getString(KEY_APP_PIN_SALT, null).isNullOrBlank().not() &&
            prefs.getString(KEY_APP_PIN_HASH, null).isNullOrBlank().not()

    fun setAppPin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{$PIN_MIN_LENGTH,$PIN_MAX_LENGTH}"))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePinHash(pin, salt)
        prefs.edit()
            .putString(KEY_APP_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_APP_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verifyAppPin(pin: String): Boolean {
        if (!hasAppPin || !pin.matches(Regex("\\d{$PIN_MIN_LENGTH,$PIN_MAX_LENGTH}"))) return false
        return try {
            val salt = Base64.decode(prefs.getString(KEY_APP_PIN_SALT, ""), Base64.NO_WRAP)
            val expected = Base64.decode(prefs.getString(KEY_APP_PIN_HASH, ""), Base64.NO_WRAP)
            java.security.MessageDigest.isEqual(derivePinHash(pin, salt), expected)
        } catch (_: Exception) {
            false
        }
    }

    fun changeAppPin(currentPin: String, newPin: String): Boolean {
        if (!verifyAppPin(currentPin)) return false
        return setAppPin(newPin)
    }

    fun isPinBlocked(now: Long = System.currentTimeMillis()): Boolean {
        val persistedUntil = prefs.getLong(KEY_PIN_BLOCKED_UNTIL, 0L)
        if (persistedUntil <= 0L) return false
        // FIX: enforce the lockout with elapsedRealtime so advancing the device
        // clock cannot shorten the 30-second block. When no realtime deadline was
        // persisted (older installs), fall back to the legacy wall-clock check.
        val realtimeDeadline = prefs.getLong(KEY_PIN_BLOCKED_UNTIL_REALTIME, 0L)
        return if (realtimeDeadline > 0L) {
            SystemClock.elapsedRealtime() < realtimeDeadline
        } else {
            persistedUntil > now
        }
    }

    fun getPinBlockedUntil(): Long = pinBlockedUntilForUi(System.currentTimeMillis())

    /** Wall-clock mirror of the lockout end, kept in sync with the elapsedRealtime deadline. */
    private fun pinBlockedUntilForUi(now: Long): Long {
        val persistedUntil = prefs.getLong(KEY_PIN_BLOCKED_UNTIL, 0L)
        val realtimeDeadline = prefs.getLong(KEY_PIN_BLOCKED_UNTIL_REALTIME, 0L)
        if (realtimeDeadline <= 0L) return persistedUntil
        val realtimeRemaining = realtimeDeadline - SystemClock.elapsedRealtime()
        return if (realtimeRemaining > 0L) {
            persistedUntil.coerceAtLeast(now + realtimeRemaining)
        } else {
            persistedUntil
        }
    }

    @Synchronized
    fun registerPinFailure(now: Long = System.currentTimeMillis()): Long {
        if (isPinBlocked(now)) return pinBlockedUntilForUi(now)
        val attempts = prefs.getInt(KEY_PIN_FAILED_ATTEMPTS, 0) + 1
        return if (attempts >= PIN_LOCKOUT_THRESHOLD) {
            val blockedUntil = now + PIN_LOCKOUT_MILLIS
            // FIX: persist the authoritative deadline on the monotonic
            // elapsedRealtime clock; the epoch value stays as a UI mirror.
            val realtimeDeadline = SystemClock.elapsedRealtime() + PIN_LOCKOUT_MILLIS
            prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
                .putLong(KEY_PIN_BLOCKED_UNTIL, blockedUntil)
                .putLong(KEY_PIN_BLOCKED_UNTIL_REALTIME, realtimeDeadline).apply()
            blockedUntil
        } else {
            prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, attempts).apply()
            0L
        }
    }

    @Synchronized
    fun resetPinFailures() {
        prefs.edit().putInt(KEY_PIN_FAILED_ATTEMPTS, 0)
            .putLong(KEY_PIN_BLOCKED_UNTIL, 0L)
            .putLong(KEY_PIN_BLOCKED_UNTIL_REALTIME, 0L).apply()
    }

    private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_LENGTH)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: java.security.NoSuchAlgorithmException) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        return factory.generateSecret(spec).encoded
    }

    var countdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_ENABLED, value).apply()

    var countdownEndTime: Long
        get() = prefs.getLong(KEY_COUNTDOWN_END_TIME, 0L)
        set(value) {
            // FIX: keep the epoch target for AlarmManager scheduling, but also
            // capture an elapsedRealtime-based deadline at scheduling time so
            // watchdog/expiry checks can detect wall-clock tampering. The setter
            // is invoked exactly when the countdown is (re)armed or cleared.
            val remaining = value - System.currentTimeMillis()
            prefs.edit()
                .putLong(KEY_COUNTDOWN_END_TIME, value)
                .putLong(KEY_COUNTDOWN_END_REALTIME, SystemClock.elapsedRealtime() + remaining)
                .apply()
        }

    /**
     * Countdown expiry evaluated against the earlier (min) of the epoch and the
     * elapsedRealtime deadlines, so advancing the device clock cannot freeze the
     * countdown. Missing realtime deadlines (persisted by older versions) fall
     * back to the legacy wall-clock comparison.
     */
    fun isCountdownExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (countdownEndTime <= now) return true
        val realtimeDeadline = prefs.getLong(KEY_COUNTDOWN_END_REALTIME, 0L)
        return realtimeDeadline > 0L && SystemClock.elapsedRealtime() >= realtimeDeadline
    }

    /** Remaining countdown time measured against the min of both deadlines. */
    fun countdownRemainingMillis(now: Long = System.currentTimeMillis()): Long {
        val epochRemaining = (countdownEndTime - now).coerceAtLeast(0L)
        val realtimeDeadline = prefs.getLong(KEY_COUNTDOWN_END_REALTIME, 0L)
        if (realtimeDeadline <= 0L) return epochRemaining // legacy data: old behavior
        val realtimeRemaining =
            (realtimeDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return minOf(epochRemaining, realtimeRemaining)
    }

    var countdownDurationMillis: Long
        get() = prefs.getLong(KEY_COUNTDOWN_DURATION, DEFAULT_COUNTDOWN_DURATION_MILLIS)
        set(value) = prefs.edit().putLong(KEY_COUNTDOWN_DURATION, value).apply()

    var countdownCapturePending: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, value).apply()

    var countdownRetryCount: Int
        get() = prefs.getInt(KEY_COUNTDOWN_RETRY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_COUNTDOWN_RETRY_COUNT, value.coerceAtLeast(0)).apply()

    var countdownEventId: String
        get() = prefs.getString(KEY_COUNTDOWN_EVENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_COUNTDOWN_EVENT_ID, value).apply()

    var countdownAutoRestart: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_AUTO_RESTART, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_AUTO_RESTART, value).apply()

    fun clearCountdown() {
        prefs.edit()
            .putBoolean(KEY_COUNTDOWN_ENABLED, false)
            .putLong(KEY_COUNTDOWN_END_TIME, 0L)
            .putLong(KEY_COUNTDOWN_END_REALTIME, 0L)
            .putBoolean(KEY_COUNTDOWN_CAPTURE_PENDING, false)
            .putInt(KEY_COUNTDOWN_RETRY_COUNT, 0)
            .putString(KEY_COUNTDOWN_EVENT_ID, "")
            .apply()
    }

    @Synchronized
    fun addLog(log: IntruderLog) {
        val currentLogs = getLogs().toMutableList()
        val existingIndex = log.eventId?.let { eventId ->
            currentLogs.indexOfFirst { it.eventId == eventId }
        } ?: -1
        val isNewLog = existingIndex < 0
        if (isNewLog) {
            currentLogs.add(0, log)
        } else {
            currentLogs[existingIndex] = log.copy(id = currentLogs[existingIndex].id)
        }
        // Storage always keeps the premium-sized window so a refund cannot wipe history
        // that was never the paying tier's to delete; the tier only bounds what is shown.
        val trimmed = if (currentLogs.size > PREMIUM_HISTORY_LIMIT) {
            currentLogs.take(PREMIUM_HISTORY_LIMIT)
        } else {
            currentLogs
        }
        saveLogs(trimmed)
        if (isNewLog) {
            totalAttempts += 1
            lastAttemptTime = log.timestamp
            if (log.latitude != null && log.longitude != null) {
                lastAttemptLocation = "${log.latitude}, ${log.longitude}"
            }
        }
        _logsFlow.value = trimmed
    }

    private fun readLegacyLogs(): List<IntruderLog> {
        val jsonString = prefs.getString(KEY_LOGS_JSON, "[]") ?: "[]"
        val list = mutableListOf<IntruderLog>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    IntruderLog(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        eventId = obj.optString("eventId").takeIf { it.isNotEmpty() },
                        photoCaptured = if (obj.has("photoCaptured")) {
                            obj.optBoolean("photoCaptured")
                        } else {
                            !obj.optString("photoPath").isNullOrEmpty()
                        },
                        locationCaptured = if (obj.has("locationCaptured")) {
                            obj.optBoolean("locationCaptured")
                        } else {
                            obj.has("latitude") && obj.has("longitude")
                        },
                        timestamp = obj.optLong("timestamp", 0L),
                        photoPath = obj.optString("photoPath").takeIf { it.isNotEmpty() },
                        latitude = if (obj.has("latitude")) obj.optDouble("latitude") else null,
                        longitude = if (obj.has("longitude")) obj.optDouble("longitude") else null,
                        address = obj.optString("address").takeIf { it.isNotEmpty() },
                        emailSent = obj.optBoolean("emailSent", false),
                        statusMessage = obj.optString("statusMessage", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SecurityPrefs", "Unable to read intruder logs", e)
        }
        return list
    }

    private fun saveLogs(logs: List<IntruderLog>) = store.replaceLogs(logs)

    fun getLogs(): List<IntruderLog> = store.logs()

    /**
     * History window shown to the user: 5 entries on Free, 50 on Premium.
     * Applied at the display boundary so storage keeps the full window either way.
     */
    fun getVisibleLogs(): List<IntruderLog> = getLogs().take(getHistoryLimit())

    @Synchronized
    fun clearPhotoReference(file: File) {
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                val eventCanonical = event.photoPath?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                if (eventCanonical == canonical) event.copy(
                    photoPath = null,
                    photoState = SecurityEventComponentState.FAILED,
                    updatedAt = System.currentTimeMillis()
                ) else event
            }
        }
    }

    fun clearLogs() {
        // FIX: evidence for events still in flight (pending resend/capture) must
        // survive a log clear: their photo files are kept and their photoPath
        // references stay intact. Only terminal events lose the reference.
        val protectedPhotoPaths = getSecurityEvents()
            .filter { SecurityEventStateMachine.isPendingWork(it.status) }
            .mapNotNull { event ->
                event.photoPath?.let { path ->
                    runCatching { File(path).canonicalPath }.getOrNull() ?: path
                }
            }
            .toSet()
        val photoPaths = buildSet {
            getLogs().mapNotNullTo(this) { it.photoPath }
            getSecurityEvents().mapNotNullTo(this) { it.photoPath }
        }
        for (path in photoPaths) {
            val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: path
            if (path in protectedPhotoPaths || canonical in protectedPhotoPaths) continue
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                // Ignore
            }
        }
        saveLogs(emptyList())
        updateSecurityEventsInRoom { events ->
            events.map { event ->
                if (SecurityEventStateMachine.isPendingWork(event.status)) event
                else event.copy(
                    photoPath = null,
                    photoState = SecurityEventComponentState.NOT_REQUESTED,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        totalAttempts = 0
        _logsFlow.value = emptyList()
    }

    // ==================== PREMIUM ENTITLEMENT ====================

    /**
     * Premium entitlement flag - source of truth for premium status.
     * Set by BillingManager after purchase verification.
     */
    var isPremium: Boolean
        get() = prefs.getBoolean(KEY_PREMIUM_ENTITLEMENT, false)
        set(value) = prefs.edit().putBoolean(KEY_PREMIUM_ENTITLEMENT, value).apply()

    /**
     * Evidence captured in the current calendar month. A slot is reserved before
     * the camera opens and refunded when the capture produced nothing, so the
     * published allowance counts evidence the user actually received.
     */
    private var evidenceMonthCounter: Int
        get() = prefs.getInt(KEY_EVIDENCE_MONTH_COUNTER, 0)
        set(value) = prefs.edit().putInt(KEY_EVIDENCE_MONTH_COUNTER, value.coerceAtLeast(0)).apply()

    /** Calendar month the counter belongs to, as year * 12 + month (0 = not set yet). */
    private var evidenceMonthIndex: Int
        get() = prefs.getInt(KEY_EVIDENCE_MONTH_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_EVIDENCE_MONTH_INDEX, value).apply()

    /**
     * Get evidence monthly limit based on premium status.
     * FREE: 3 events/month
     * PREMIUM: 50 events/month
     */
    fun getEvidenceMonthlyLimit(): Int {
        return if (isPremium) PREMIUM_EVIDENCE_MONTHLY_LIMIT else FREE_EVIDENCE_MONTHLY_LIMIT
    }

    /**
     * Get history limit based on premium status.
     * FREE: 5 events
     * PREMIUM: 50 events
     */
    fun getHistoryLimit(): Int {
        return if (isPremium) PREMIUM_HISTORY_LIMIT else FREE_HISTORY_LIMIT
    }

    /** Days an evidence photo stays on the device before the weekly cleanup removes it. */
    fun getPhotoRetentionDays(): Long {
        return if (isPremium) PREMIUM_PHOTO_RETENTION_DAYS else FREE_PHOTO_RETENTION_DAYS
    }

    /**
     * Read-only check for callers that do not start a capture themselves (the
     * durable queue). The capture itself goes through [tryReserveEvidenceSlot].
     */
    @Synchronized
    fun canCreateEvidenceEvent(): Boolean {
        resetMonthlyCounterIfNeeded()
        return evidenceMonthCounter < getEvidenceMonthlyLimit()
    }

    /**
     * Checks the allowance and takes one slot in a single step: two failed unlocks
     * a second apart must not both pass a separate check-then-increment.
     */
    @Synchronized
    fun tryReserveEvidenceSlot(): Boolean {
        resetMonthlyCounterIfNeeded()
        if (evidenceMonthCounter >= getEvidenceMonthlyLimit()) return false
        evidenceMonthCounter = evidenceMonthCounter + 1
        return true
    }

    /** Gives a reserved slot back when the capture produced neither photo nor location. */
    @Synchronized
    fun releaseEvidenceSlot() {
        resetMonthlyCounterIfNeeded()
        evidenceMonthCounter = evidenceMonthCounter - 1
    }

    /**
     * Starts a new allowance when the calendar month changes. A clock moved back
     * into an earlier month keeps the current count instead of handing out a
     * fresh allowance.
     */
    private fun resetMonthlyCounterIfNeeded() {
        val calendar = java.util.Calendar.getInstance()
        val currentIndex = calendar.get(java.util.Calendar.YEAR) * 12 +
            calendar.get(java.util.Calendar.MONTH)
        val storedIndex = evidenceMonthIndex
        if (storedIndex == 0) {
            // First run, or an upgrade from the old 30-day window: keep what was
            // already spent instead of granting a fresh allowance mid-month.
            evidenceMonthIndex = currentIndex
            return
        }
        if (currentIndex > storedIndex) {
            evidenceMonthCounter = 0
            evidenceMonthIndex = currentIndex
        }
    }

    /**
     * Get current evidence usage for display.
     * Returns Pair(current_count, monthly_limit)
     */
    @Synchronized
    fun getEvidenceUsage(): Pair<Int, Int> {
        resetMonthlyCounterIfNeeded()
        return Pair(evidenceMonthCounter, getEvidenceMonthlyLimit())
    }

}
