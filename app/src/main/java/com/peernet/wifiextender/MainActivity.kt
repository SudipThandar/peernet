package com.peernet.wifiextender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.peernet.wifiextender.ads.AdManager
import com.peernet.wifiextender.ui.navigation.PeerNetRoot
import com.peernet.wifiextender.ui.theme.PeerNetTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var adManager: AdManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        adManager.loadInterstitial {
            adManager.showInterstitial(this)
        }
        setContent {
            PeerNetTheme {
                PeerNetRoot()
            }
        }
    }
}
