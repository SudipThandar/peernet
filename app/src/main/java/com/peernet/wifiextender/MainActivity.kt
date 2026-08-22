package com.peernet.wifiextender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peernet.wifiextender.ui.navigation.PeerNetRoot
import com.peernet.wifiextender.ui.theme.PeerNetTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PeerNetTheme {
                PeerNetRoot()
            }
        }
    }
}
