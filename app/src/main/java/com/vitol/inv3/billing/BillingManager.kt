package com.vitol.inv3.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    private val context: Context
) {
    private var billingClient: BillingClient? = null
    private var _activePurchase: Purchase? = null
    /** True when last launchBillingFlow was a plan-change (replacement) attempt. */
    private var _lastLaunchWasReplacement = false

    private val _subscriptionStatus = MutableStateFlow<SubscriptionStatus?>(null)
    val subscriptionStatus: StateFlow<SubscriptionStatus?> = _subscriptionStatus.asStateFlow()
    
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    private val _purchaseComplete = MutableStateFlow<Purchase?>(null)
    val purchaseComplete: StateFlow<Purchase?> = _purchaseComplete.asStateFlow()
    
    init {
        initializeBillingClient()
    }
    
    private fun initializeBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    _lastLaunchWasReplacement = false
                    handlePurchases(purchases)
                } else if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    handleBillingError(billingResult.responseCode, billingResult.debugMessage)
                    _lastLaunchWasReplacement = false
                }
            }
            .enablePendingPurchases()
            .build()
        
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _isReady.value = true
                    queryPurchases()
                    Timber.d("Billing client ready")
                } else {
                    Timber.e("Billing setup failed: ${billingResult.debugMessage}")
                    _errorMessage.value = "Billing service unavailable. Please check your internet connection."
                }
            }
            
            override fun onBillingServiceDisconnected() {
                _isReady.value = false
                Timber.w("Billing service disconnected")
            }
        })
    }
    
    fun queryPurchases() {
        val billingClient = billingClient ?: return
        
        if (!_isReady.value) {
            Timber.w("Billing client not ready, cannot query purchases")
            return
        }
        
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            } else {
                handleBillingError(billingResult.responseCode, billingResult.debugMessage)
            }
        }
    }
    
    private fun handlePurchases(purchases: List<Purchase>) {
        val unacknowledgedPurchases = purchases.filter { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            !purchase.isAcknowledged
        }
        
        unacknowledgedPurchases.forEach { purchase ->
            acknowledgePurchase(purchase)
            // Notify that purchase completed
            _purchaseComplete.value = purchase
        }
        
        updateSubscriptionStatus(purchases)
    }
    
    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        
        billingClient?.acknowledgePurchase(acknowledgeParams) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Timber.d("Purchase acknowledged: ${purchase.products.firstOrNull()}")
            } else {
                Timber.e("Failed to acknowledge purchase: ${billingResult.debugMessage}")
            }
        }
    }
    
    private fun updateSubscriptionStatus(purchases: List<Purchase>) {
        val activeSubscription = purchases.firstOrNull { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            (purchase.products.contains(SubscriptionPlan.BASIC.planId) ||
             purchase.products.contains(SubscriptionPlan.PRO.planId) ||
             purchase.products.contains(SubscriptionPlan.ACCOUNTING.planId))
        }

        _activePurchase = activeSubscription

        val plan = when {
            activeSubscription?.products?.contains(SubscriptionPlan.ACCOUNTING.planId) == true -> SubscriptionPlan.ACCOUNTING
            activeSubscription?.products?.contains(SubscriptionPlan.PRO.planId) == true -> SubscriptionPlan.PRO
            activeSubscription?.products?.contains(SubscriptionPlan.BASIC.planId) == true -> SubscriptionPlan.BASIC
            else -> SubscriptionPlan.FREE
        }

        // FREE plan is always active (no subscription needed)
        // Paid plans are active only if there's an active subscription
        val isActive = plan == SubscriptionPlan.FREE || activeSubscription != null
        val now = System.currentTimeMillis()
        val invoiceLimit = plan.getInvoiceLimit(now)

        // This will be combined with usage tracker in ViewModel
        _subscriptionStatus.value = SubscriptionStatus(
            plan = plan,
            isActive = isActive,
            invoicesUsed = 0, // Will be updated from UsageTracker
            invoicesRemaining = invoiceLimit,
            invoiceLimit = invoiceLimit,
            resetDate = now, // Will be updated from UsageTracker
            isFirstMonth = false
        )

        Timber.d("Subscription status updated: ${plan.planId}, active: ${activeSubscription != null}")
    }
    
    fun launchBillingFlow(activity: Activity, plan: SubscriptionPlan, callback: (BillingResult) -> Unit) {
        val billingClient = billingClient ?: run {
            callback(
                BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
                    .setDebugMessage("Billing client not initialized")
                    .build()
            )
            return
        }
        
        if (!_isReady.value) {
            callback(
                BillingResult.newBuilder()
                    .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
                    .setDebugMessage("Billing service not ready")
                    .build()
            )
            return
        }
        
        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(plan.planId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        
        billingClient.queryProductDetailsAsync(productDetailsParams) { billingResult, productDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && productDetailsResult != null) {
                // Handle both List (billing-ktx) and QueryProductDetailsResult (standard API)
                val productDetails = when {
                    productDetailsResult is List<*> -> (productDetailsResult as List<*>).firstOrNull() as? ProductDetails
                    else -> try {
                        val method = productDetailsResult.javaClass.getMethod("getProductDetailsList")
                        (method.invoke(productDetailsResult) as? List<*>)?.firstOrNull() as? ProductDetails
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get product details from result")
                        null
                    }
                }
                
                if (productDetails != null) {
                    // Billing Library 7+ requires offer token for subscriptions
                    val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
                    if (offerToken == null) {
                        Timber.e("No subscription offer found for ${plan.planId}")
                        _errorMessage.value = "Subscription not configured. Please try again later."
                        callback(
                            BillingResult.newBuilder()
                                .setResponseCode(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
                                .setDebugMessage("No subscription offer for ${plan.planId}")
                                .build()
                        )
                        return@queryProductDetailsAsync
                    }
                    val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(offerToken)

                    val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))

                    // For plan change (upgrade/downgrade): pass existing subscription for replacement.
                    // Requires all plans (basic_monthly, pro_monthly, accounting_monthly) to be in the
                    // SAME subscription group in Google Play Console. Otherwise replacement fails with Code 7.
                    val activePurchase = _activePurchase
                    val currentPlanId = activePurchase?.products?.firstOrNull()
                    if (activePurchase != null && currentPlanId != null && currentPlanId != plan.planId) {
                        try {
                            val subscriptionUpdateParams = BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldPurchaseToken(activePurchase.purchaseToken)
                                .setSubscriptionReplacementMode(
                                    BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION
                                )
                                .build()
                            billingFlowParamsBuilder.setSubscriptionUpdateParams(subscriptionUpdateParams)
                            _lastLaunchWasReplacement = true
                            Timber.d("Subscription replacement: $currentPlanId -> ${plan.planId}")
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to set subscription update params")
                        }
                    }

                    val billingFlowParams = billingFlowParamsBuilder.build()
                    
                    val response = billingClient.launchBillingFlow(activity, billingFlowParams)
                    
                    when (response.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            Timber.d("Billing flow launched successfully for ${plan.planId}")
                        }
                        BillingClient.BillingResponseCode.USER_CANCELED -> {
                            Timber.d("User canceled purchase")
                            _errorMessage.value = null // Not an error
                        }
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                            Timber.d("User already owns this subscription")
                            _errorMessage.value = if (_lastLaunchWasReplacement) {
                                "Plan change failed. Open Google Play to manage your subscription (cancel, then subscribe to a new plan)."
                            } else {
                                "You already have an active subscription."
                            }
                            queryPurchases() // Refresh status
                        }
                        else -> {
                            handleBillingError(response.responseCode, response.debugMessage)
                        }
                    }
                    
                    callback(response)
                } else {
                    Timber.e("Product details not found for ${plan.planId}")
                    _errorMessage.value = "Product not found. Install the app from Google Play (internal testing) and ensure subscriptions are active in Play Console."
                    callback(
                        BillingResult.newBuilder()
                            .setResponseCode(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE)
                            .setDebugMessage("Product details not found for ${plan.planId}")
                            .build()
                    )
                }
            } else {
                Timber.e("Failed to query product details: ${billingResult.debugMessage}")
                handleBillingError(billingResult.responseCode, billingResult.debugMessage)
                callback(billingResult)
            }
        }
    }
    
    private fun handleBillingError(responseCode: Int, debugMessage: String) {
        val userMessage = when (responseCode) {
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                "Billing service is temporarily unavailable. Please check your internet connection and try again."
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                "Billing is not available on this device. Please update Google Play Store."
            }
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
                "This subscription is temporarily unavailable. Please try again later."
            }
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                "Billing configuration error. Make sure the app is installed from Google Play (internal testing) and subscriptions are set up in Play Console."
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                if (_lastLaunchWasReplacement) {
                    "Plan change failed. To switch plans: open Google Play, cancel your current subscription, then subscribe to the new plan here. Or ensure all plans are in the same subscription group in Play Console."
                } else {
                    "You already have an active subscription."
                }
            }
            else -> {
                "Unable to process purchase. Error code: $responseCode"
            }
        }
        
        _errorMessage.value = userMessage
        Timber.e("Billing error: $debugMessage (Code: $responseCode)")
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    fun clearPurchaseComplete() {
        _purchaseComplete.value = null
    }
}

