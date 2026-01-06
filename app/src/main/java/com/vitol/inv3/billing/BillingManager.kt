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
                    handlePurchases(purchases)
                } else if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    handleBillingError(billingResult.responseCode, billingResult.debugMessage)
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
             purchase.products.contains(SubscriptionPlan.PRO.planId))
        }
        
        val plan = when {
            activeSubscription?.products?.contains(SubscriptionPlan.PRO.planId) == true -> SubscriptionPlan.PRO
            activeSubscription?.products?.contains(SubscriptionPlan.BASIC.planId) == true -> SubscriptionPlan.BASIC
            else -> SubscriptionPlan.FREE
        }
        
        // FREE plan is always active (no subscription needed)
        // Paid plans are active only if there's an active subscription
        val isActive = plan == SubscriptionPlan.FREE || activeSubscription != null
        
        // This will be combined with usage tracker in ViewModel
        _subscriptionStatus.value = SubscriptionStatus(
            plan = plan,
            isActive = isActive,
            pagesUsed = 0, // Will be updated from UsageTracker
            pagesRemaining = plan.pagesPerMonth,
            resetDate = System.currentTimeMillis() // Will be updated from UsageTracker
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
                // In billing-ktx 7.1.1, access productDetailsList using reflection
                // The property name might be different in different versions
                val productDetails = try {
                    // Try getProductDetailsList() method first
                    val method = productDetailsResult.javaClass.getMethod("getProductDetailsList")
                    val list = method.invoke(productDetailsResult) as? List<*>
                    list?.firstOrNull() as? ProductDetails
                } catch (e: NoSuchMethodException) {
                    // If method doesn't exist, try accessing as a field/property
                    try {
                        val field = productDetailsResult.javaClass.getDeclaredField("productDetailsList")
                        field.isAccessible = true
                        val list = field.get(productDetailsResult) as? List<*>
                        list?.firstOrNull() as? ProductDetails
                    } catch (e2: Exception) {
                        Timber.e(e2, "Failed to access productDetailsList via reflection")
                        null
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to access productDetailsList")
                    null
                }
                
                if (productDetails != null) {
                    val productDetailsParamsList = listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )
                    
                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .build()
                    
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
                            _errorMessage.value = "You already have an active subscription"
                            queryPurchases() // Refresh status
                        }
                        else -> {
                            handleBillingError(response.responseCode, response.debugMessage)
                        }
                    }
                    
                    callback(response)
                } else {
                    Timber.e("Product details not found for ${plan.planId}")
                    _errorMessage.value = "Product not found. Please try again later."
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
                "Configuration error. Please contact support."
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

