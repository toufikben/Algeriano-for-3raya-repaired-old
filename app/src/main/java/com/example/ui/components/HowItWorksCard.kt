package com.example.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.CyberCardBorder
import com.example.ui.theme.CyberNavySurfaceVariant
import com.example.ui.theme.EmeraldActive

/**
 * Three-step walkthrough shown once, above the controls it describes.
 *
 * Placement matters: a first-run user opened this app because someone got into
 * their phone, and the screen they land on is a wall of toggle cards with a
 * permission list under it. This card sits directly under the hero count and
 * states the whole mechanism in three lines - what is watched, what is captured,
 * where the evidence goes - so the permission prompts further down have a reason.
 *
 * Dismissal is persisted by the caller (SecurityPrefs.howItWorksDismissed); the
 * card itself stays stateless so a preview or a screenshot test can render it
 * without a preference store.
 */
@Composable
fun HowItWorksCard(
    onDismiss: () -> Unit,
    onOpenPremium: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = CyberNavySurfaceVariant.copy(alpha = 0.92f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCardBorder.copy(alpha = 0.7f))
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.onboarding_subtitle),
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color(0xFF0F172A), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.onboarding_dismiss),
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            StepRow(
                step = 1,
                title = R.string.onboarding_step_arm_title,
                body = R.string.onboarding_step_arm_body
            )
            StepRow(
                step = 2,
                title = R.string.onboarding_step_capture_title,
                body = R.string.onboarding_step_capture_body
            )
            StepRow(
                step = 3,
                title = R.string.onboarding_step_evidence_title,
                body = R.string.onboarding_step_evidence_body,
                last = true
            )

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpenPremium) {
                    Text(
                        text = stringResource(R.string.onboarding_see_premium),
                        color = CyanAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    step: Int,
    @StringRes title: Int,
    @StringRes body: Int,
    last: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Numbered marker rather than an icon: the order is the point - the
        // countdown only means anything once protection is armed.
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    Brush.linearGradient(listOf(EmeraldActive, CyanAccent)),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step.toString(),
                color = Color(0xFF04121C),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                color = Color(0xFFE2E8F0),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(body),
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            if (!last) Spacer(Modifier.height(14.dp))
        }
    }
}
