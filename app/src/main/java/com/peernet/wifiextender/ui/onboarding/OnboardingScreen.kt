package com.peernet.wifiextender.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Onboarding entry point (Section 19.3). Milestone 12 adds the full
 * permission flow, VPN consent explanation and battery optimization step.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Welcome to PeerNet", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "PeerNet shares your phone's internet with nearby devices over a local " +
                "Wi-Fi Direct network.\n\nAll traffic stays local between your devices. " +
                "No cloud relay. No data collection by default.",
            style = MaterialTheme.typography.bodyLarge
        )

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}
