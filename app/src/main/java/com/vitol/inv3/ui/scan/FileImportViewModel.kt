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
    
    fun hasNext(): Boolean {
        return _currentIndex.value < _processingQueue.value.size - 1
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
    }
}

