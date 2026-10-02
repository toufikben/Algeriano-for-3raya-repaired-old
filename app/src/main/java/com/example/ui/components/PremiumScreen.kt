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

/**
 * Premium upgrade screen showing Free vs Premium comparison.
 * Displays localized price from Google Play Store.
 */
@Composable
fun PremiumScreen(
    isPremium: Boolean,
    localizedPrice: String,
    evidenceUsage: Pair<Int, Int>, // (current, limit)
    historyLimit: Int,
    onUnlockPremium: () -> Unit,
    onRestorePurchase: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

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

                // Feature rows
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
                    feature = stringResource(R.string.premium_evidence_monthly),
                    freeValue = "${evidenceUsage.second}/mo",
                    premiumValue = "50/mo"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_event_history),
                    freeValue = "5",
                    premiumValue = "50"
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_continuous_protection),
                    freeValue = "✗",
                    premiumValue = "✓",
                    highlight = true
                )
                PremiumFeatureRow(
                    feature = stringResource(R.string.premium_extended_retention),
                    freeValue = "✗",
                    premiumValue = "✓",
                    highlight = true
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Current usage indicator
        if (!isPremium) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.premium_current_usage),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = (evidenceUsage.first.toFloat() / evidenceUsage.second.coerceAtLeast(1)).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${evidenceUsage.first} / ${evidenceUsage.second} ${stringResource(R.string.premium_events_this_month)}",
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // CTA Buttons
        if (!isPremium) {
            Button(
                onClick = onUnlockPremium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.premium_unlock, localizedPrice),
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
