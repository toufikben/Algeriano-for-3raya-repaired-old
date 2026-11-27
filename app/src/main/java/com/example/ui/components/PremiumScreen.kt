package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.SecurityPrefs

/**
 * Premium upgrade screen showing Free vs Premium comparison.
 * Displays localized price from Google Play Store.
 */
@Composable
fun PremiumScreen(
    isPremium: Boolean,
    localizedPrice: String?,
    message: String? = null,
    onUnlockPremium: () -> Unit,
    onRestorePurchase: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    // Every number in the table is read from SecurityPrefs instead of being typed
    // here. The previous copy hard-coded "3" and "5" while the enforcement code
    // read its own constants, so the two drifted and the paywall could promise
    // something the app refused to deliver.
    //
    // Plan A split: capture (photo, location, email) is free and uncapped, so the
    // paywall cannot sell it any more. What Premium sells is *keeping* the
    // evidence - retention and an auto-restarting countdown - and both of those
    // are stated from the same constants the cleanup worker and the scheduler use.
    val freeRetentionValue = if (SecurityPrefs.FREE_PHOTO_RETENTION_DAYS == Long.MAX_VALUE) {
        stringResource(R.string.premium_retention_forever)
    } else {
        stringResource(
            R.string.premium_retention_days,
            SecurityPrefs.FREE_PHOTO_RETENTION_DAYS.toInt()
        )
    }
    val premiumRetentionValue = if (SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS == Long.MAX_VALUE) {
        stringResource(R.string.premium_retention_forever)
    } else {
        stringResource(
            R.string.premium_retention_days,
            SecurityPrefs.PREMIUM_PHOTO_RETENTION_DAYS.toInt()
        )
    }

    // The paywall is shown in a full-width Dialog, which draws no background of its
    // own: without this surface the main screen showed through the table and buttons.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = stringResource(R.string.premium_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        if (isPremium) {
            // Already premium - show status
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.premium_active_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.premium_active_text),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Free vs Premium Comparison Table
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Headers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.premium_feature),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(2f)
                    )
                    Text(
                        text = stringResource(R.string.premium_free),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.premium_premium),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Feature rows. Every capture feature is free and uncapped on both
                // tiers now, so these are honest ticks rather than a paid upsell:
                // the difference between the tiers lives in the last two rows.
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_core_protection),
                    freeValue = "✓",
                    premiumValue = "✓"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_photo_evidence),
                    freeValue = "✓",
                    premiumValue = "✓"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_location_evidence),
                    freeValue = "✓",
                    premiumValue = "✓"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_email_alerts),
                    freeValue = "✓",
                    premiumValue = "✓"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_photo_retention),
                    freeValue = freeRetentionValue,
                    premiumValue = premiumRetentionValue,
                    highlight = true
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_continuous_protection),
                    freeValue = "✗",
                    premiumValue = "✓",
                    highlight = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Purchase / restore outcome, or why the paywall opened.
        if (message != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // CTA Buttons
        if (!isPremium) {
            val price = localizedPrice
            // Play owns the price. Rendering an invented one would mislead the user,
            // and that is exactly what every Free user sees until the product exists
            // in the Console - so the button waits for the real figure instead.
            Button(
                onClick = onUnlockPremium,
                enabled = price != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = if (price != null) {
                        stringResource(R.string.premium_unlock, price)
                    } else {
                        stringResource(R.string.premium_unavailable)
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRestorePurchase,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(text = stringResource(R.string.premium_restore))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Subtext
        Text(
            text = stringResource(R.string.premium_lifetime_text),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onDismiss) {
            Text(text = stringResource(android.R.string.cancel))
        }
    }
    }
}

@Composable
private fun PremiumFeatureRow(
    feature: String,
    freeValue: String,
    premiumValue: String,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = feature,
            modifier = Modifier.weight(2f),
            fontSize = 14.sp
        )
        Text(
            text = freeValue,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = if (freeValue == "✗") Color.Red else Color.Unspecified
        )
        Text(
            text = premiumValue,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.primary else Color.Unspecified
        )
    }
}
