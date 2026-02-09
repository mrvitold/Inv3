# Comparison: Photo-Taking vs Import Flow

## Key Differences

### 1. **State Initialization**

**Photo-Taking Flow:**
- User takes photo → URI created immediately → Navigate to ReviewScreen with URI
- `imageUris` parameter is a simple list with one URI
- `processedUris` starts as empty set
- OCR processing starts immediately via LaunchedEffect

**Import Flow:**
- User selects file → File processed → URIs extracted → Navigate to ReviewScreen with URIs
- `imageUris` parameter comes from FileImportViewModel queue
- `processedUris` starts as empty set
- OCR processing starts immediately via LaunchedEffect

**Result:** Both flows use the same ReviewScreen code, so initialization is identical.

### 2. **OCR Processing Trigger**

**Both Flows:**
- Use the same `LaunchedEffect(stableUrisKey, activeCompanyId, ownCompanyNumber, ownCompanyVatNumber)` 
- Both call `processPageWithAzure` which calls `viewModel.runOcrWithDocumentAi`
- Both update `processedUris` in the `onDone` callback

**Result:** Processing logic is identical.

### 3. **State Update Mechanism**

**Current Implementation (Both Flows):**
```kotlin
// In processPageWithAzure onDone callback:
processedUris = processedUris + uri  // Update state
processingUris = processingUris - uri
```

**Problem:** 
- State is updated synchronously on main thread (via `withContext(Dispatchers.Main)`)
- But `isLoading` derived state reads `processed: 0` even after `processedUris.size` becomes 1
- This suggests Compose's snapshot system isn't detecting the change

### 4. **Loading State Derivation**

**Current Implementation:**
```kotlin
val isLoading = derivedStateOf {
    val currentProcessedUris = processedUris // Direct read
    val hasUnprocessed = uris.any { uri -> uri !in currentProcessedUris }
    hasUnprocessed
}.value
```

**Problem:**
- Derived state should automatically recompute when `processedUris` changes
- But logs show it's reading stale values (`processed: 0` when actual size is 1)

### 5. **Forced Recomposition Attempt**

**Current Fix Attempt:**
```kotlin
LaunchedEffect(processedUris.size) {
    // Force recomposition when processedUris.size changes
}
```

**Problem:**
- This LaunchedEffect restarts when `processedUris.size` changes
- But it doesn't force the derived state to recompute with new values
- The derived state still reads old values

## Root Cause Analysis

The issue is that when `processedUris` is updated inside a callback (even on main thread), Compose's snapshot system may not immediately detect the change. The derived state reads from a snapshot that was captured before the state update.

## Potential Solutions

1. **Use snapshotFlow to ensure snapshot tracking:**
   - Wrap state updates in snapshot-aware operations
   - But `snapshotFlow` is a suspend function, can't use in callback

2. **Use LaunchedEffect to update state:**
   - Move state updates to LaunchedEffect instead of callback
   - But this requires tracking completion differently

3. **Use StateFlow instead of mutableStateOf:**
   - StateFlow might provide better snapshot tracking
   - But requires refactoring

4. **Force recomposition by reading state in LaunchedEffect:**
   - Read `processedUris` in LaunchedEffect to ensure snapshot tracking
   - But this might cause infinite loops

5. **Use DisposableEffect or SideEffect:**
   - These might provide better snapshot tracking
   - But they're for side effects, not state updates

## Recommended Fix

The best approach is to ensure that state updates happen in a way that Compose's snapshot system can track. Since we're already on the main thread via `withContext(Dispatchers.Main)`, the issue might be that the snapshot isn't being committed.

**Solution:** Use `snapshotFlow` in a `LaunchedEffect` to track `processedUris.size` changes. When the flow emits a new value, it ensures the snapshot is committed and triggers recomposition, which causes the derived state to recompute.

**Implementation:**
```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { processedUris.size }.collect { size ->
        // Reading processedUris here ensures snapshot tracking
        // This will trigger recomposition and cause isLoading to recompute
    }
}
```

This ensures that whenever `processedUris.size` changes, the flow emits, triggering recomposition and causing `isLoading` to recompute with the updated value.

