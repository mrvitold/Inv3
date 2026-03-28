package com.vitol.inv3.analytics

import android.content.Context
import android.os.Bundle
import com.facebook.appevents.AppEventsConstants
import com.facebook.appevents.AppEventsLogger
import com.vitol.inv3.BuildConfig
import com.vitol.inv3.billing.SubscriptionPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Logs Meta (Facebook) App Events when [FACEBOOK_APP_ID] and [FACEBOOK_CLIENT_TOKEN] are set.
 * Used for Phase B optimization toward subscriptions in Meta Ads.
 */
@Singleton
class MetaAppEvents @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun logSubscriptionPurchase(plan: SubscriptionPlan) {
        if (BuildConfig.FACEBOOK_APP_ID.isEmpty()) return
        if (plan == SubscriptionPlan.FREE) return
        val value = plan.eurPriceOrNull() ?: return
        try {
            val params = Bundle().apply {
                putString(AppEventsConstants.EVENT_PARAM_CONTENT_ID, plan.planId)
                putString(AppEventsConstants.EVENT_PARAM_CONTENT_TYPE, "subscription")
                putString(AppEventsConstants.EVENT_PARAM_CURRENCY, "EUR")
            }
            AppEventsLogger.newLogger(context).logEvent(
                AppEventsConstants.EVENT_NAME_SUBSCRIBE,
                value,
                params
            )
        } catch (e: Exception) {
            Timber.e(e, "Meta App Events: log Subscribe failed")
        }
    }

    private fun SubscriptionPlan.eurPriceOrNull(): Double? = when (this) {
        SubscriptionPlan.BASIC -> 7.0
        SubscriptionPlan.PRO -> 17.0
        SubscriptionPlan.ACCOUNTING -> 39.0
        SubscriptionPlan.FREE -> null
    }
}
