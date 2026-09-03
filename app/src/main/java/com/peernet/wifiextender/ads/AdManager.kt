package com.peernet.wifiextender.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.peernet.wifiextender.host.Entitlements
import com.peernet.wifiextender.diag.Diagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var rewardedAd: RewardedAd? = null
    private var isRewardedLoading = false
    private var onUserEarnedReward: (() -> Unit)? = null

    private var interstitialAd: InterstitialAd? = null
    private var isInterstitialLoading = false

    val isReady: Boolean get() = rewardedAd != null
    val loading: Boolean get() = isRewardedLoading

    fun loadAd(onLoaded: (() -> Unit)? = null) {
        if (rewardedAd != null || isRewardedLoading) {
            onLoaded?.invoke()
            return
        }
        isRewardedLoading = true
        val request = AdRequest.Builder().build()
        RewardedAd.load(context, REWARDED_UNIT_ID, request, object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardedAd = ad
                isRewardedLoading = false
                Diagnostics.note("ads", "REWARDED_AD_LOADED")
                onLoaded?.invoke()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                rewardedAd = null
                isRewardedLoading = false
                Diagnostics.note("ads", "REWARDED_AD_FAILED ${error.message}")
            }
        })
    }

    fun showAd(activity: Activity, onRewarded: () -> Unit) {
        val ad = rewardedAd ?: return
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

    fun loadInterstitial(onLoaded: (() -> Unit)? = null) {
        if (interstitialAd != null || isInterstitialLoading) {
            onLoaded?.invoke()
            return
        }
        isInterstitialLoading = true
        val request = AdRequest.Builder().build()
        InterstitialAd.load(context, INTERSTITIAL_UNIT_ID, request, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
                isInterstitialLoading = false
                Diagnostics.note("ads", "INTERSTITIAL_AD_LOADED")
                onLoaded?.invoke()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialAd = null
                isInterstitialLoading = false
                Diagnostics.note("ads", "INTERSTITIAL_AD_FAILED ${error.message}")
            }
        })
    }

    fun showInterstitial(activity: Activity): Boolean {
        val ad = interstitialAd ?: return false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                Diagnostics.note("ads", "INTERSTITIAL_SHOW_FAILED ${error.message}")
                loadInterstitial()
            }
        }
        ad.show(activity)
        return true
    }

    companion object {
        private const val REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        private const val INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
        const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    }
}
