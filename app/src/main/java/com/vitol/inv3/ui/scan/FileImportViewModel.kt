package com.vitol.inv3.ui.scan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vitol.inv3.utils.FileImportService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class FileImportViewModel @Inject constructor() : ViewModel() {
    
    private val _processingQueue = MutableStateFlow<List<Uri>>(emptyList())
    val processingQueue: StateFlow<List<Uri>> = _processingQueue.asStateFlow()
    
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()
    
    // Cache for pre-processed OCR results (background processing)
    private val _ocrResultsCache = MutableStateFlow<Map<Uri, Result<String>>>(emptyMap())
    val ocrResultsCache: StateFlow<Map<Uri, Result<String>>> = _ocrResultsCache.asStateFlow()
    
    // Track which URIs are currently being processed in background
    private val _processingInBackground = MutableStateFlow<Set<Uri>>(emptySet())
    val processingInBackground: StateFlow<Set<Uri>> = _processingInBackground.asStateFlow()
    
    // Background processing progress tracking
    private val _backgroundProcessingProgress = MutableStateFlow<BackgroundProcessingProgress>(
        BackgroundProcessingProgress(total = 0, completed = 0, failed = 0)
    )
    val backgroundProcessingProgress: StateFlow<BackgroundProcessingProgress> = _backgroundProcessingProgress.asStateFlow()
    
    // Maximum cache size (limit to prevent memory issues)
    private val maxCacheSize = 50 // Maximum number of cached OCR results
    
    // Track cache entry timestamps for cleanup
    private val _cacheTimestamps = MutableStateFlow<Map<Uri, Long>>(emptyMap())
    
    // Track retry counts for failed processing
    private val _retryCounts = MutableStateFlow<Map<Uri, Int>>(emptyMap())
    private val maxRetries = 2 // Maximum retry attempts for failed processing
    
    // Invoice type for current batch (P = Purchase, S = Sales)
    private val _invoiceType = MutableStateFlow<String?>(null)
    val invoiceType: StateFlow<String?> = _invoiceType.asStateFlow()
    
    fun setInvoiceType(type: String?) {
        _invoiceType.value = type
        Timber.d("Invoice type set to: $type")
    }
    
    fun addToQueue(uris: List<Uri>) {
        _processingQueue.value = uris
        _currentIndex.value = 0
        Timber.d("Added ${uris.size} items to processing queue")
    }
    
    fun getNextUri(): Uri? {
        val queue = _processingQueue.value
        val index = _currentIndex.value
        return if (index < queue.size) {
            queue[index]
        } else {
            null
        }
    }
    
    fun moveToNext() {
        val current = _currentIndex.value
        val queue = _processingQueue.value
        if (current < queue.size - 1) {
            _currentIndex.value = current + 1
            Timber.d("Moved to next item: ${_currentIndex.value + 1}/${queue.size}")
        } else {
            // Queue completed
            _processingQueue.value = emptyList()
            _currentIndex.value = 0
            Timber.d("Processing queue completed")
        }
    }
    
    fun moveToPrevious() {
        val current = _currentIndex.value
        if (current > 0) {
            _currentIndex.value = current - 1
            Timber.d("Moved to previous item: ${_currentIndex.value + 1}/${_processingQueue.value.size}")
        }
    }
    
    fun hasNext(): Boolean {
        return _currentIndex.value < _processingQueue.value.size - 1
    }
    
    fun hasPrevious(): Boolean {
        return _currentIndex.value > 0
    }
    
    fun getProgressText(): String {
        val queue = _processingQueue.value
        val index = _currentIndex.value
        return if (queue.isNotEmpty()) {
            "Processing invoice ${index + 1} of ${queue.size}"
        } else {
            ""
        }
    }
    
    fun clearQueue() {
        _processingQueue.value = emptyList()
        _currentIndex.value = 0
        _ocrResultsCache.value = emptyMap()
        _processingInBackground.value = emptySet()
        _cacheTimestamps.value = emptyMap()
        _retryCounts.value = emptyMap()
        _backgroundProcessingProgress.value = BackgroundProcessingProgress(total = 0, completed = 0, failed = 0)
    }
    
    /**
     * Get cached OCR result for a URI, if available
     */
    fun getCachedOcrResult(uri: Uri): Result<String>? {
        return _ocrResultsCache.value[uri]
    }
    
    /**
     * Remove a cached result (e.g., if it failed and needs retry)
     */
    fun removeCachedResult(uri: Uri) {
        val currentCache = _ocrResultsCache.value.toMutableMap()
        val currentTimestamps = _cacheTimestamps.value.toMutableMap()
        currentCache.remove(uri)
        currentTimestamps.remove(uri)
        _ocrResultsCache.value = currentCache
        _cacheTimestamps.value = currentTimestamps
        Timber.d("Removed cached result for URI: $uri")
    }
    
    /**
     * Store OCR result in cache with size limits and cleanup
     */
    fun cacheOcrResult(uri: Uri, result: Result<String>) {
        val currentCache = _ocrResultsCache.value.toMutableMap()
        val currentTimestamps = _cacheTimestamps.value.toMutableMap()
        
        // Add new entry
        currentCache[uri] = result
        currentTimestamps[uri] = System.currentTimeMillis()
        
        // Cleanup if cache exceeds max size
        if (currentCache.size > maxCacheSize) {
            // Remove oldest entries (LRU - Least Recently Used)
            val sortedByTime = currentTimestamps.toList().sortedBy { it.second }
            val toRemove = sortedByTime.take(currentCache.size - maxCacheSize)
            
            toRemove.forEach { (oldUri, _) ->
                currentCache.remove(oldUri)
                currentTimestamps.remove(oldUri)
                Timber.d("Removed old cache entry: $oldUri (cache size limit reached)")
            }
        }
        
        _ocrResultsCache.value = currentCache
        _cacheTimestamps.value = currentTimestamps
        Timber.d("Cached OCR result for URI: $uri (cache size: ${currentCache.size}/$maxCacheSize)")
    }
    
    /**
     * Check if a URI is currently being processed in background
     */
    fun isProcessingInBackground(uri: Uri): Boolean {
        return _processingInBackground.value.contains(uri)
    }
    
    /**
     * Mark URI as being processed in background
     */
    private fun markProcessingInBackground(uri: Uri, isProcessing: Boolean) {
        val currentSet = _processingInBackground.value.toMutableSet()
        if (isProcessing) {
            currentSet.add(uri)
        } else {
            currentSet.remove(uri)
        }
        _processingInBackground.value = currentSet
    }
    
    /**
     * Trigger background processing for remaining invoices in queue
     * @param currentUri The URI currently being displayed (skip this one)
     * @param processCallback Function to process a single URI with index and callback (index is for staggered delays)
     */
    fun triggerBackgroundProcessing(
        currentUri: Uri,
        processCallback: (Uri, Int, (Result<String>) -> Unit) -> Unit
    ) {
        viewModelScope.launch {
            val queue = _processingQueue.value
            val currentIdx = _currentIndex.value
            
            // Calculate total remaining invoices to process
            val remainingUris = queue.subList(currentIdx + 1, queue.size)
                .filter { uri -> 
                    !_ocrResultsCache.value.containsKey(uri) && 
                    !_processingInBackground.value.contains(uri)
                }
            
            val totalToProcess = remainingUris.size
            
            // Initialize progress tracking
            _backgroundProcessingProgress.value = BackgroundProcessingProgress(
                total = totalToProcess,
                completed = 0,
                failed = 0
            )
            
            if (totalToProcess == 0) {
                Timber.d("No invoices to process in background (all cached or processing)")
                return@launch
            }
            
            Timber.d("Starting background processing for $totalToProcess invoices")
            
            // Process all remaining invoices in background
            remainingUris.forEachIndexed { index, uri ->
                // Mark as processing
                markProcessingInBackground(uri, true)
                
                // Process in background
                launch {
                    try {
                        val invoiceNumber = currentIdx + 1 + index + 1
                        Timber.d("Starting background processing for invoice $invoiceNumber/${queue.size}: $uri")
                        
                        // Add timeout handling
                        val startTime = System.currentTimeMillis()
                        var completed = false
                        
                        // Pass index for staggered delays (each invoice starts with a small offset to prevent rate limiting)
                        processCallback(uri, index) { result ->
                            if (!completed) {
                                completed = true
                                val processingTime = System.currentTimeMillis() - startTime
                                
                                result.fold(
                                    onSuccess = {
                                        // Success - cache result and clear retry count
                                        cacheOcrResult(uri, result)
                                        val retryCounts = _retryCounts.value.toMutableMap()
                                        retryCounts.remove(uri)
                                        _retryCounts.value = retryCounts
                                        _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                            completed = _backgroundProcessingProgress.value.completed + 1
                                        )
                                        Timber.d("Background processing completed for invoice $invoiceNumber/${queue.size} in ${processingTime}ms: $uri")
                                    },
                                    onFailure = { error ->
                                        val retryCount = _retryCounts.value[uri] ?: 0
                                        
                                        if (retryCount < maxRetries) {
                                            // Retry processing
                                            val retryCounts = _retryCounts.value.toMutableMap()
                                            retryCounts[uri] = retryCount + 1
                                            _retryCounts.value = retryCounts
                                            
                                            Timber.w("Background processing failed for invoice $invoiceNumber/${queue.size} (attempt ${retryCount + 1}/$maxRetries), retrying: $uri")
                                            
                                            // Reset completion flag and schedule retry in coroutine
                                            markProcessingInBackground(uri, false)
                                            // Retry after a short delay (launch in coroutine scope)
                                            launch {
                                                kotlinx.coroutines.delay(2000) // 2 second delay before retry
                                                triggerBackgroundProcessingForSingle(uri, processCallback, invoiceNumber, queue.size)
                                            }
                                        } else {
                                            // Max retries reached - don't cache failure, let it process normally when user navigates to it
                                            val retryCounts = _retryCounts.value.toMutableMap()
                                            retryCounts.remove(uri)
                                            _retryCounts.value = retryCounts
                                            _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                                failed = _backgroundProcessingProgress.value.failed + 1
                                            )
                                            Timber.e(error, "Background processing failed for invoice $invoiceNumber/${queue.size} after ${maxRetries + 1} attempts and ${processingTime}ms: $uri (will process normally when user navigates to it)")
                                        }
                                    }
                                )
                                if (result.isSuccess || (_retryCounts.value[uri] ?: 0) >= maxRetries) {
                                    markProcessingInBackground(uri, false)
                                }
                            }
                        }
                        
                        // Timeout after 90 seconds (Azure can take up to 30s for polling + submission + network delays)
                        kotlinx.coroutines.delay(90000)
                        if (!completed) {
                            completed = true
                            val retryCount = _retryCounts.value[uri] ?: 0
                            
                            if (retryCount < maxRetries) {
                                // Retry on timeout
                                val retryCounts = _retryCounts.value.toMutableMap()
                                retryCounts[uri] = retryCount + 1
                                _retryCounts.value = retryCounts
                                
                                Timber.w("Background processing timeout for invoice $invoiceNumber/${queue.size} (attempt ${retryCount + 1}/$maxRetries), retrying: $uri")
                                
                                markProcessingInBackground(uri, false)
                                // Retry after a short delay (launch in coroutine scope)
                                launch {
                                    kotlinx.coroutines.delay(2000)
                                    triggerBackgroundProcessingForSingle(uri, processCallback, invoiceNumber, queue.size)
                                }
                            } else {
                                // Don't cache timeout failures - let it process normally when user navigates to it
                                val retryCounts = _retryCounts.value.toMutableMap()
                                retryCounts.remove(uri)
                                _retryCounts.value = retryCounts
                                _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                    failed = _backgroundProcessingProgress.value.failed + 1
                                )
                                markProcessingInBackground(uri, false)
                                Timber.e("Background processing timeout for invoice $invoiceNumber/${queue.size} after 90 seconds (${maxRetries + 1} attempts): $uri (will process normally when user navigates to it)")
                            }
                        }
                    } catch (e: Exception) {
                        val invoiceNumber = currentIdx + 1 + index + 1
                        val retryCount = _retryCounts.value[uri] ?: 0
                        
                        if (retryCount < maxRetries) {
                            // Retry on exception
                            val retryCounts = _retryCounts.value.toMutableMap()
                            retryCounts[uri] = retryCount + 1
                            _retryCounts.value = retryCounts
                            
                            Timber.w(e, "Background processing exception for invoice $invoiceNumber/${queue.size} (attempt ${retryCount + 1}/$maxRetries), retrying: $uri")
                            
                            markProcessingInBackground(uri, false)
                            // Retry after a short delay (launch in coroutine scope)
                            launch {
                                kotlinx.coroutines.delay(2000)
                                triggerBackgroundProcessingForSingle(uri, processCallback, invoiceNumber, queue.size)
                            }
                        } else {
                            // Don't cache exceptions - let it process normally when user navigates to it
                            Timber.e(e, "Background processing exception for invoice $invoiceNumber/${queue.size} after ${maxRetries + 1} attempts: $uri (will process normally when user navigates to it)")
                            val retryCounts = _retryCounts.value.toMutableMap()
                            retryCounts.remove(uri)
                            _retryCounts.value = retryCounts
                            _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                failed = _backgroundProcessingProgress.value.failed + 1
                            )
                            markProcessingInBackground(uri, false)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Process a single invoice in background (used for retries)
     */
    private fun triggerBackgroundProcessingForSingle(
        uri: Uri,
        processCallback: (Uri, Int, (Result<String>) -> Unit) -> Unit,
        invoiceNumber: Int,
        totalInvoices: Int
    ) {
        viewModelScope.launch {
            // Skip if already cached or currently processing
            if (_ocrResultsCache.value.containsKey(uri) || _processingInBackground.value.contains(uri)) {
                Timber.d("Skipping retry for URI (already cached or processing): $uri")
                return@launch
            }
            
            markProcessingInBackground(uri, true)
            
            launch {
                try {
                    val startTime = System.currentTimeMillis()
                    var completed = false
                    
                    // For retries, use index 0 (no additional delay beyond what's in the callback)
                    processCallback(uri, 0) { result ->
                        if (!completed) {
                            completed = true
                            val processingTime = System.currentTimeMillis() - startTime
                            
                            result.fold(
                                onSuccess = {
                                    cacheOcrResult(uri, result)
                                    val retryCounts = _retryCounts.value.toMutableMap()
                                    retryCounts.remove(uri)
                                    _retryCounts.value = retryCounts
                                    _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                        completed = _backgroundProcessingProgress.value.completed + 1
                                    )
                                    Timber.d("Background processing retry succeeded for invoice $invoiceNumber/$totalInvoices in ${processingTime}ms: $uri")
                                },
                                onFailure = { error ->
                                    // Max retries already checked - don't cache failure, let it process normally when user navigates to it
                                    val retryCounts = _retryCounts.value.toMutableMap()
                                    retryCounts.remove(uri)
                                    _retryCounts.value = retryCounts
                                    _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                                        failed = _backgroundProcessingProgress.value.failed + 1
                                    )
                                    Timber.e(error, "Background processing retry failed for invoice $invoiceNumber/$totalInvoices after ${processingTime}ms: $uri (will process normally when user navigates to it)")
                                }
                            )
                            markProcessingInBackground(uri, false)
                        }
                    }
                    
                    kotlinx.coroutines.delay(90000)
                    if (!completed) {
                        completed = true
                        // Don't cache timeout failures - let it process normally when user navigates to it
                        val retryCounts = _retryCounts.value.toMutableMap()
                        retryCounts.remove(uri)
                        _retryCounts.value = retryCounts
                        _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                            failed = _backgroundProcessingProgress.value.failed + 1
                        )
                        markProcessingInBackground(uri, false)
                        Timber.e("Background processing retry timeout for invoice $invoiceNumber/$totalInvoices after 90 seconds: $uri (will process normally when user navigates to it)")
                    }
                } catch (e: Exception) {
                    // Don't cache exceptions - let it process normally when user navigates to it
                    Timber.e(e, "Background processing retry exception for invoice $invoiceNumber/$totalInvoices: $uri (will process normally when user navigates to it)")
                    val retryCounts = _retryCounts.value.toMutableMap()
                    retryCounts.remove(uri)
                    _retryCounts.value = retryCounts
                    _backgroundProcessingProgress.value = _backgroundProcessingProgress.value.copy(
                        failed = _backgroundProcessingProgress.value.failed + 1
                    )
                    markProcessingInBackground(uri, false)
                }
            }
        }
    }
    
    /**
     * Clear old cache entries (older than specified hours)
     */
    fun cleanupOldCache(olderThanHours: Int = 24) {
        val currentCache = _ocrResultsCache.value.toMutableMap()
        val currentTimestamps = _cacheTimestamps.value.toMutableMap()
        val cutoffTime = System.currentTimeMillis() - (olderThanHours * 60 * 60 * 1000L)
        
        val toRemove = currentTimestamps.filter { it.value < cutoffTime }.keys
        
        toRemove.forEach { uri ->
            currentCache.remove(uri)
            currentTimestamps.remove(uri)
        }
        
        if (toRemove.isNotEmpty()) {
            _ocrResultsCache.value = currentCache
            _cacheTimestamps.value = currentTimestamps
            Timber.d("Cleaned up ${toRemove.size} old cache entries (older than $olderThanHours hours)")
        }
    }
}

/**
 * Data class for tracking background processing progress
 */
data class BackgroundProcessingProgress(
    val total: Int,
    val completed: Int,
    val failed: Int
) {
    val inProgress: Int
        get() = total - completed - failed
    
    val isComplete: Boolean
        get() = completed + failed >= total
    
    val progressPercentage: Float
        get() = if (total == 0) 0f else ((completed + failed).toFloat() / total) * 100f
}

