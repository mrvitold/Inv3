# Structural Fix Summary - Duplicate Azure Analysis Bug

## ✅ Completed Changes

1. **Added ViewModel.ensureAnalysisStarted()** - Centralized analysis entry point
2. **Fixed Confirm Button** - Removed UI-level state manipulation

## 🔧 Remaining Critical Changes Needed

### 1. Refactor LaunchedEffect (Lines 560-763)
**Current Problem:**
- Uses unstable keys: `allImageUris, ownCompanyNumber, ownCompanyVatNumber, activeCompanyId, isConfirmed`
- Directly calls `processPageWithAzure` which triggers Azure
- Uses UI-level idempotency sets that reset on recomposition

**Fix Required:**
```kotlin
// Use STABLE key only (URIs hash)
val urisHash = remember(allImageUris) { 
    allImageUris.joinToString(",") { it.toString() }.hashCode()
}

LaunchedEffect(urisHash) {
    if (isConfirmed) return@LaunchedEffect
    
    // Wait for company data if needed (but don't include in keys)
    if (activeCompanyId != null && ownCompanyNumber == null && ownCompanyVatNumber == null) {
        return@LaunchedEffect
    }
    
    // Process cached URIs first (UI handles display)
    // Then call ViewModel.ensureAnalysisStarted() for non-cached URIs
    val urisNeedingAnalysis = allImageUris.filter { uri ->
        val cached = fileImportViewModel.getCachedOcrResult(uri)
        cached == null || cached.isFailure
    }
    
    if (urisNeedingAnalysis.isNotEmpty()) {
        viewModel.ensureAnalysisStarted(
            uris = urisNeedingAnalysis,
            excludeCompanyId = activeCompanyId,
            excludeOwnCompanyNumber = ownCompanyNumber,
            excludeOwnVatNumber = ownCompanyVatNumber,
            excludeOwnCompanyName = ownCompanyName,
            invoiceType = invoiceType,
            onResult = { uri, result, method ->
                // Handle result in UI
            }
        )
    }
}
```

### 2. Remove UI-Level Idempotency Sets (Lines 322-331)
**Remove:**
```kotlin
var processedUris by remember(urisKey) { mutableStateOf<Set<Uri>>(emptySet()) }
var processingUris by remember(urisKey) { mutableStateOf<Set<Uri>>(emptySet()) }
var ocrTriggeredUris by remember(urisKey) { mutableStateOf<Set<Uri>>(emptySet()) }
```

**Replace with:**
```kotlin
// STRUCTURAL FIX: Idempotency handled by ViewModel state machine
// No UI-level tracking needed
```

### 3. Remove processPageWithAzure (Lines 417-558)
**Action:** Delete this function entirely. All analysis goes through ViewModel.

### 4. Fix ensureAnalysisStarted Method Parameter
**Current:** Uses `excludeCompanyId` parameter
**Fix:** Should match `startAnalysis` signature

## Success Criteria Verification

After fixes:
- ✅ Single Azure call per URI (check logs for `[AZURE_API_CALL]`)
- ✅ Confirm button never triggers analysis
- ✅ No loading loop after Confirm
- ✅ Import flow and camera flow use same pipeline

## Testing Checklist

1. Import single-page invoice → Check logs for exactly 1 Azure call
2. Import multi-page invoice → Check logs for exactly N Azure calls (N = pages)
3. Press Confirm → Verify no new Azure calls in logs
4. Navigate back/forward → Verify no duplicate calls
5. Rapid navigation → Verify idempotency holds

