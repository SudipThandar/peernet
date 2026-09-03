package com.peernet.wifiextender.ads

import android.app.Activity
import android.content.Context
import android.os.Bundle
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.peernet.wifiextender.host.Entitlements
import com.peernet.wifiextender.diag.Diagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and shows a rewarded ad. Watching the ad grants unlimited sharing.
 *
 * Uses a test ad unit ID so every build shows test ads. Swap the ID for a
 * production one before publishing.
 */
@Singleton
class AdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false
    private var onUserEarnedReward: (() -> Unit)? = null

    /** Whether an ad is loaded and ready to show. */
    val isReady: Boolean get() = rewardedAd != null

    /** Whether an ad load is in progress. */
    val loading: Boolean get() = isLoading

    fun loadAd(onLoaded: (() -> Unit)? = null) {
        if (rewardedAd != null || isLoading) {
            onLoaded?.invoke()
            return
        }
        isLoading = true
        val request = AdRequest.Builder().build()
        RewardedAd.load(context, AD_UNIT_ID, request, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                isLoading = false
                Diagnostics.note("ads", "REWARDED_AD_LOADED")
                onLoaded?.invoke()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                rewardedAd = null
                isLoading = false
                Diagnostics.note("ads", "REWARDED_AD_FAILED ${error.message}")
            }
        })
    }

    fun showAd(activity: Activity, onRewarded: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            onRewarded()
            return
        }
        onUserEarnedReward = onRewarded
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                onUserEarnedReward = null
                loadAd()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                onUserEarnedReward = null
                Diagnostics.note("ads", "REWARDED_AD_SHOW_FAILED ${error.message}")
                onRewarded()
            }
        }
        ad.show(activity) {
            Diagnostics.note("ads", "REWARDED_AD_EARNED")
            Entitlements.setPremium(context, true)
            onUserEarnedReward?.invoke()
        }
    }

    companion object {
        // Test ad unit ID — shows test ads on every device.
        private const val AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}
