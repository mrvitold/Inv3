package com.vitol.inv3

import androidx.lifecycle.ViewModel
import com.vitol.inv3.analytics.AppAnalytics
import com.vitol.inv3.auth.AuthManager
import com.vitol.inv3.data.remote.SupabaseRepository
import com.vitol.inv3.ui.scan.MergedFormData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Activity-scoped state survives [Routes.ReviewScan] navigation when [imageUri] changes
 * (new NavBackStackEntry = new route-scoped [com.vitol.inv3.ui.scan.ReviewScanViewModel]).
 * Pending merge from page 1 must live here so page 2 can read it.
 */
@HiltViewModel
class MainActivityViewModel @Inject constructor(
    val repo: SupabaseRepository,
    val authManager: AuthManager,
    val appAnalytics: AppAnalytics
) : ViewModel() {

    private var pendingReviewScanMerge: MergedFormData? = null

    fun setPendingReviewScanMerge(data: MergedFormData) {
        pendingReviewScanMerge = data
    }

    fun getPendingReviewScanMerge(): MergedFormData? = pendingReviewScanMerge

    fun clearPendingReviewScanMerge() {
        pendingReviewScanMerge = null
    }
}

