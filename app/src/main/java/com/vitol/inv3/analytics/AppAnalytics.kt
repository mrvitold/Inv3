package com.vitol.inv3.analytics

import android.content.Context
import android.os.Bundle
import com.android.billingclient.api.BillingClient
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppAnalytics @Inject constructor(
    @ApplicationContext context: Context
) {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private var currentScreenName: String? = null
    private var currentScreenStartedAtMs: Long = 0L

    fun trackScreenViewed(route: String?) {
        val screen = routeToScreenName(route)
        val now = System.currentTimeMillis()
        val previousScreen = currentScreenName

        if (previousScreen != null && currentScreenStartedAtMs > 0L && previousScreen != screen) {
            logEvent(
                event = "screen_time_spent",
                params = mapOf(
                    "screen_name" to previousScreen,
                    "duration_ms" to (now - currentScreenStartedAtMs).coerceAtLeast(0L)
                )
            )
        }

        currentScreenName = screen
        currentScreenStartedAtMs = now

        logEvent(
            event = FirebaseAnalytics.Event.SCREEN_VIEW,
            params = mapOf(FirebaseAnalytics.Param.SCREEN_NAME to screen)
        )
    }

    fun trackHomeAction(action: String, allowed: Boolean, failureReason: String? = null) {
        logEvent(
            event = "home_action_clicked",
            params = mapOf(
                "action" to action,
                "allowed" to allowed.toString(),
                "failure_reason" to failureReason
            )
        )
    }

    fun trackFeedbackAction(source: String) {
        logEvent(
            event = "feedback_opened",
            params = mapOf("source" to source)
        )
    }

    fun trackPaywallViewed(source: String) {
        logEvent(
            event = "paywall_viewed",
            params = mapOf("source" to source)
        )
    }

    fun trackSubscriptionFlowStarted(planId: String) {
        logEvent(
            event = "subscription_flow_started",
            params = mapOf("plan_id" to planId)
        )
    }

    fun trackSubscriptionFlowResult(
        planId: String,
        responseCode: Int,
        debugMessage: String?
    ) {
        logEvent(
            event = "subscription_flow_result",
            params = mapOf(
                "plan_id" to planId,
                "response_code" to responseCode.toLong(),
                "result" to billingResultName(responseCode),
                "debug_message" to debugMessage
            )
        )
    }

    fun trackBillingError(responseCode: Int, debugMessage: String?) {
        logEvent(
            event = "billing_error",
            params = mapOf(
                "response_code" to responseCode.toLong(),
                "result" to billingResultName(responseCode),
                "debug_message" to debugMessage
            )
        )
    }

    fun trackOcrStarted(source: String, invoiceType: String, pageCount: Int? = null) {
        logEvent(
            event = "ocr_started",
            params = mapOf(
                "source" to source,
                "invoice_type" to invoiceType,
                "page_count" to pageCount?.toLong()
            )
        )
    }

    fun trackOcrCompleted(
        source: String,
        invoiceType: String,
        durationMs: Long,
        hasUsefulData: Boolean
    ) {
        logEvent(
            event = "ocr_completed",
            params = mapOf(
                "source" to source,
                "invoice_type" to invoiceType,
                "duration_ms" to durationMs,
                "has_useful_data" to hasUsefulData.toString()
            )
        )
    }

    fun trackOcrFailed(source: String, invoiceType: String, errorCode: String?, durationMs: Long? = null) {
        logEvent(
            event = "ocr_failed",
            params = mapOf(
                "source" to source,
                "invoice_type" to invoiceType,
                "error_code" to errorCode,
                "duration_ms" to durationMs
            )
        )
    }

    fun trackImportBuildStarted(fileCount: Int, invoiceType: String) {
        logEvent(
            event = "import_started",
            params = mapOf(
                "file_count" to fileCount.toLong(),
                "invoice_type" to invoiceType
            )
        )
    }

    fun trackImportBuildFailed(fileCount: Int, invoiceType: String, errorCode: String?) {
        logEvent(
            event = "import_build_failed",
            params = mapOf(
                "file_count" to fileCount.toLong(),
                "invoice_type" to invoiceType,
                "error_code" to errorCode
            )
        )
    }

    fun trackInvoicePersistStarted(action: String, source: String, invoiceType: String?) {
        logEvent(
            event = "invoice_save_started",
            params = mapOf(
                "action" to action,
                "source" to source,
                "invoice_type" to invoiceType
            )
        )
    }

    fun trackInvoicePersistSuccess(action: String, source: String, invoiceType: String?, durationMs: Long) {
        logEvent(
            event = "invoice_save_success",
            params = mapOf(
                "action" to action,
                "source" to source,
                "invoice_type" to invoiceType,
                "duration_ms" to durationMs
            )
        )
    }

    fun trackInvoicePersistFailed(
        action: String,
        source: String,
        invoiceType: String?,
        errorCode: String?,
        durationMs: Long? = null
    ) {
        logEvent(
            event = "invoice_save_failed",
            params = mapOf(
                "action" to action,
                "source" to source,
                "invoice_type" to invoiceType,
                "error_code" to errorCode,
                "duration_ms" to durationMs
            )
        )
    }

    fun trackAuthStarted(method: String) {
        logEvent(
            event = "login_started",
            params = mapOf("provider" to method)
        )
    }

    fun trackAuthSucceeded(method: String) {
        logEvent(
            event = "login_succeeded",
            params = mapOf("provider" to method)
        )
    }

    fun trackAuthFailed(method: String, errorCode: String?) {
        logEvent(
            event = "login_failed",
            params = mapOf(
                "provider" to method,
                "error_code" to errorCode
            )
        )
    }

    fun trackDeepLinkAuthReceived(type: String?) {
        logEvent(
            event = "deep_link_auth_received",
            params = mapOf("type" to type)
        )
    }

    fun trackDeepLinkAuthFailed(type: String?, errorCode: String?) {
        logEvent(
            event = "deep_link_auth_failed",
            params = mapOf(
                "type" to type,
                "error_code" to errorCode
            )
        )
    }

    fun trackRepositoryError(operation: String, entity: String, errorCode: String?) {
        logEvent(
            event = "repository_operation_failed",
            params = mapOf(
                "operation" to operation,
                "entity" to entity,
                "error_code" to errorCode
            )
        )
    }

    fun trackReviewScanAbandoned(
        source: String,
        invoiceType: String?,
        leaveType: String,
        timeOnScreenMs: Long
    ) {
        logEvent(
            event = "review_scan_abandoned",
            params = mapOf(
                "source" to source,
                "invoice_type" to invoiceType,
                "leave_type" to leaveType,
                "time_on_screen_ms" to timeOnScreenMs
            )
        )
    }

    fun trackProcessingWaitAbandoned(
        source: String,
        invoiceType: String?,
        leaveType: String,
        waitStage: String,
        waitDurationMs: Long
    ) {
        logEvent(
            event = "processing_wait_abandoned",
            params = mapOf(
                "source" to source,
                "invoice_type" to invoiceType,
                "leave_type" to leaveType,
                "wait_stage" to waitStage,
                "wait_duration_ms" to waitDurationMs
            )
        )
    }

    fun trackOwnCompanyFilled(mode: String, hasVatNumber: Boolean, source: String) {
        logEvent(
            event = "own_company_filled",
            params = mapOf(
                "mode" to mode,
                "has_vat_number" to hasVatNumber.toString(),
                "source" to source
            )
        )
    }

    fun trackExcelExportAction(channel: String, month: String) {
        logEvent(
            event = "export_excel_action",
            params = mapOf(
                "channel" to channel,
                "month" to month
            )
        )
    }

    fun trackXmlExportAction(channel: String, month: String) {
        logEvent(
            event = "export_xml_action",
            params = mapOf(
                "channel" to channel,
                "month" to month
            )
        )
    }

    private fun routeToScreenName(route: String?): String {
        if (route.isNullOrBlank()) return "unknown"
        val withoutArgs = route.substringBefore("/")
        return withoutArgs
            .replace("{", "")
            .replace("}", "")
            .replace("-", "_")
            .take(40)
    }

    private fun billingResultName(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.OK -> "ok"
        BillingClient.BillingResponseCode.USER_CANCELED -> "user_canceled"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "service_unavailable"
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "billing_unavailable"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "item_unavailable"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "developer_error"
        BillingClient.BillingResponseCode.ERROR -> "error"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "item_already_owned"
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "item_not_owned"
        else -> "other"
    }

    private fun logEvent(event: String, params: Map<String, Any?>) {
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> bundle.putString(key, value.take(100))
                is Long -> bundle.putLong(key, value)
                is Int -> bundle.putInt(key, value)
                is Double -> bundle.putDouble(key, value)
                is Float -> bundle.putFloat(key, value)
                is Boolean -> bundle.putString(key, value.toString())
                else -> bundle.putString(key, value.toString().take(100))
            }
        }
        analytics.logEvent(event, bundle)
    }
}
