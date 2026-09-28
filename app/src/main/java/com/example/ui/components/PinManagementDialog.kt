package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SecurityPrefs
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PinManagementDialog(
    context: Context,
    prefs: SecurityPrefs,
    onDismiss: () -> Unit
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var blockedUntil by remember { mutableLongStateOf(prefs.getPinBlockedUntil()) }
    var isProcessing by remember { mutableStateOf(false) }
    var blockedRemainingSeconds by remember { mutableLongStateOf(0L) }
    val isBlocked = blockedUntil > System.currentTimeMillis()
    val scope = rememberCoroutineScope()

    // Forgot/reset PIN flow (replaces the old "open system settings" bypass).
    // passwordConfigured: null = checking in background,
    //   true  = Gmail app password exists -> verify it to set a new PIN,
    //   false = no credentials saved -> no in-app bypass; only then the
    //   system-settings data-wipe path is offered.
    var showForgotFlow by remember { mutableStateOf(false) }
    var passwordConfigured by remember { mutableStateOf<Boolean?>(null) }
    var resetPassword by remember { mutableStateOf("") }
    var resetNewPin by remember { mutableStateOf("") }
    var resetConfirmation by remember { mutableStateOf("") }

    LaunchedEffect(blockedUntil) {
        while (blockedUntil > System.currentTimeMillis()) {
            blockedRemainingSeconds = ((blockedUntil - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L)
            kotlinx.coroutines.delay(250L)
        }
        blockedRemainingSeconds = 0L
        if (blockedUntil != 0L) blockedUntil = 0L
    }

    fun closeForgotFlow() {
        showForgotFlow = false
        passwordConfigured = null
        resetPassword = ""
        resetNewPin = ""
        resetConfirmation = ""
        error = null
    }

    if (showForgotFlow) {
        when (passwordConfigured) {
            null -> AlertDialog(
                onDismissRequest = { closeForgotFlow() },
                title = { Text(context.getString(com.example.R.string.ui_pin_reset_title)) },
                text = { Text(context.getString(com.example.R.string.ui_pin_checking), fontSize = 13.sp) },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { closeForgotFlow() }) { Text(context.getString(com.example.R.string.ui_e776b0209b50)) }
                }
            )
            false -> AlertDialog(
                // No Gmail app password configured: give the thief nothing
                // easier than before, and warn the owner this wipes data.
                onDismissRequest = { closeForgotFlow() },
                title = { Text(context.getString(com.example.R.string.ui_d6b354ff91de)) },
                text = {
                    Text(
                        context.getString(com.example.R.string.ui_pin_reset_no_password),
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        closeForgotFlow()
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }) { Text(context.getString(com.example.R.string.ui_6d8288ba5097)) }
                },
                dismissButton = {
                    TextButton(onClick = { closeForgotFlow() }) { Text(context.getString(com.example.R.string.ui_e776b0209b50)) }
                }
            )
            true -> AlertDialog(
                onDismissRequest = { closeForgotFlow() },
                title = { Text(context.getString(com.example.R.string.ui_pin_reset_title)) },
                text = {
                    Column {
                        Text(
                            context.getString(com.example.R.string.ui_pin_reset_desc),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = resetPassword,
                            onValueChange = { resetPassword = it; error = null },
                            label = { Text(context.getString(com.example.R.string.ui_pin_reset_password_label)) },
                            singleLine = true,
                            enabled = !isBlocked && !isProcessing,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        PinField(resetNewPin, { resetNewPin = it; error = null }, context.getString(com.example.R.string.ui_58306554a47d), !isBlocked && !isProcessing)
                        Text(
                            context.getString(com.example.R.string.ui_pin_new_rule),
                            color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        PinField(resetConfirmation, { resetConfirmation = it; error = null }, context.getString(com.example.R.string.ui_bcf33093a0fa), !isBlocked && !isProcessing)
                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = androidx.compose.ui.graphics.Color(0xFFDC2626), fontSize = 12.sp)
                        }
                        if (isBlocked) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                context.getString(com.example.R.string.ui_pin_try_again_seconds, blockedRemainingSeconds),
                                color = androidx.compose.ui.graphics.Color(0xFFFCD34D),
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(enabled = !isBlocked && !isProcessing, onClick = {
                        if (isBlocked || isProcessing) return@Button
                        when {
                            resetPassword.isEmpty() -> error = context.getString(com.example.R.string.ui_pin_reset_password_required)
                            !resetNewPin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_8dd08f154388)
                            resetNewPin != resetConfirmation -> error = context.getString(com.example.R.string.ui_8d10332dd5a1)
                            else -> {
                                isProcessing = true
                                val passwordInput = resetPassword
                                val pinToSet = resetNewPin
                                scope.launch {
                                    // FIX: password read (Keystore decrypt),
                                    // constant-time comparison and the 120k
                                    // iteration PBKDF2 in setAppPin all run off
                                    // the main thread. The entered password is
                                    // never displayed or logged.
                                    val outcome = withContext(Dispatchers.Default) {
                                        val storedPassword = prefs.password
                                        val verified = storedPassword.isNotEmpty() &&
                                            MessageDigest.isEqual(
                                                passwordInput.toByteArray(Charsets.UTF_8),
                                                storedPassword.toByteArray(Charsets.UTF_8)
                                            )
                                        when {
                                            !verified -> 0
                                            prefs.setAppPin(pinToSet) -> 2
                                            else -> 1
                                        }
                                    }
                                    isProcessing = false
                                    // Wipe the secret from UI state either way.
                                    resetPassword = ""
                                    when (outcome) {
                                        2 -> {
                                            prefs.resetPinFailures()
                                            closeForgotFlow()
                                            onDismiss()
                                        }
                                        1 -> error = context.getString(com.example.R.string.ui_pin_reset_failed_generic)
                                        else -> {
                                            error = context.getString(com.example.R.string.ui_pin_reset_password_wrong)
                                            // Same 5-tries/30s lockout counter as
                                            // the PIN paths; no new counter added.
                                            val persistedBlockedUntil = prefs.registerPinFailure()
                                            if (persistedBlockedUntil > 0L) {
                                                blockedUntil = persistedBlockedUntil
                                                error = context.getString(com.example.R.string.ui_9816b280d8ee)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }) { Text(if (isProcessing) context.getString(com.example.R.string.ui_pin_checking) else if (isBlocked) context.getString(com.example.R.string.ui_7429493736f9) else context.getString(com.example.R.string.ui_pin_reset_submit)) }
                },
                dismissButton = {
                    TextButton(onClick = { closeForgotFlow() }) { Text(context.getString(com.example.R.string.ui_pin_reset_back)) }
                }
            )
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(com.example.R.string.ui_17b11cf37f25)) },
        text = {
            Column {
                PinField(currentPin, { currentPin = it; error = null }, context.getString(com.example.R.string.ui_c8522f0305c3), !isBlocked && !isProcessing)
                Text(
                    context.getString(com.example.R.string.ui_pin_current_rule),
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                PinField(newPin, { newPin = it; error = null }, context.getString(com.example.R.string.ui_58306554a47d), !isBlocked && !isProcessing)
                Text(
                    context.getString(com.example.R.string.ui_pin_new_rule),
                    color = androidx.compose.ui.graphics.Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                PinField(confirmation, { confirmation = it; error = null }, context.getString(com.example.R.string.ui_bcf33093a0fa), !isBlocked && !isProcessing)
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = androidx.compose.ui.graphics.Color(0xFFDC2626), fontSize = 12.sp)
                }
                if (isBlocked) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        context.getString(com.example.R.string.ui_pin_try_again_seconds, blockedRemainingSeconds),
                        color = androidx.compose.ui.graphics.Color(0xFFFCD34D),
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = !isBlocked && !isProcessing, onClick = {
                // FIX: double-tap guard; isProcessing spans the background
                // PBKDF2 work now, so the "checking" label actually renders.
                if (isBlocked || isProcessing) return@Button
                when {
                    !currentPin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_e18e557fd5d6)
                    !newPin.matches(Regex("\\d{6,8}")) -> error = context.getString(com.example.R.string.ui_8dd08f154388)
                    newPin != confirmation -> error = context.getString(com.example.R.string.ui_8d10332dd5a1)
                    else -> {
                        isProcessing = true
                        val oldPinInput = currentPin
                        val pinToChange = newPin
                        scope.launch {
                            // FIX: changeAppPin = verifyAppPin + setAppPin,
                            // i.e. two 120k-iteration PBKDF2 derivations; run
                            // them off the main thread.
                            val changed = withContext(Dispatchers.Default) {
                                prefs.changeAppPin(oldPinInput, pinToChange)
                            }
                            isProcessing = false
                            if (changed) {
                                prefs.resetPinFailures()
                                onDismiss()
                            } else {
                                error = context.getString(com.example.R.string.ui_35c16c4d4620)
                                val persistedBlockedUntil = prefs.registerPinFailure()
                                if (persistedBlockedUntil > 0L) {
                                    blockedUntil = persistedBlockedUntil
                                    error = context.getString(com.example.R.string.ui_9816b280d8ee)
                                }
                            }
                        }
                    }
                }
            }) { Text(if (isProcessing) context.getString(com.example.R.string.ui_pin_checking) else if (isBlocked) context.getString(com.example.R.string.ui_7429493736f9) else context.getString(com.example.R.string.ui_f09f791e2ff8)) }
        },
        dismissButton = {
            TextButton(onClick = {
                // Forgot-PIN entry point: no longer jumps straight to the
                // system App-info page. First check (off the main thread, the
                // getter decrypts via Keystore) whether a Gmail app password
                // is saved; only the no-credentials case offers settings.
                error = null
                showForgotFlow = true
                passwordConfigured = null
                scope.launch {
                    val configured = withContext(Dispatchers.Default) { prefs.password.isNotBlank() }
                    passwordConfigured = configured
                }
            }) { Text(context.getString(com.example.R.string.ui_5c2d36070240)) }
        }
    )
}

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth()
    )
}
