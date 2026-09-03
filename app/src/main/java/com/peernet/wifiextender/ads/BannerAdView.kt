package com.peernet.wifiextender.ads

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun BannerAdView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AndroidView(
        factory = { ctx ->
            AdView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, AdSize.FULL_WIDTH))
                setAdUnitId(AdManager.BANNER_UNIT_ID)
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = modifier
    )
}
