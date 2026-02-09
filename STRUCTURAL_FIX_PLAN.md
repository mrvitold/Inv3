# Structural Fix Plan for Duplicate Azure Analysis Bug

## Root Cause Analysis
1. **UI Layer Triggers Analysis**: LaunchedEffect directly calls `processPageWithAzure` which triggers Azure
2. **Unstable LaunchedEffect Keys**: Keys include `ownCompanyNumber`, `ownCompanyVatNumber`, `activeCompanyId`, `isConfirmed` - these change and re-trigger
3. **UI-Level Idempotency**: `processedUris`, `processingUris`, `ocrTriggeredUris` are UI-scoped and reset on recomposition
4. **Confirm Button Issue**: Sets `isConfirmed=true` but LaunchedEffect re-triggers when other keys change

## Structural Fixes Required

### 1. ViewModel.ensureAnalysisStarted() ✅ (Already added)
- Centralized entry point for analysis
- Uses ViewModel state machine for idempotency
- Checks state atomically before starting

### 2. Refactor LaunchedEffect
- Use STABLE key: `urisHash` (hash of URI list)
- Remove unstable keys: `ownCompanyNumber`, `ownCompanyVatNumber`, `activeCompanyId`, `isConfirmed`
- Call `viewModel.ensureAnalysisStarted()` instead of `processPageWithAzure`

### 3. Remove UI-Level Idempotency Sets
- Remove `processedUris`, `processingUris`, `ocrTriggeredUris`
- Rely entirely on ViewModel state machine

### 4. Remove processPageWithAzure
- Move logic to ViewModel
- UI should only call ViewModel methods

### 5. Fix Confirm Button
- Only call `viewModel.confirm()` which sets CONFIRMED state
- Never trigger analysis
- Remove UI-level state manipulation

## Implementation Order
1. Fix ensureAnalysisStarted to handle cache properly
2. Refactor LaunchedEffect with stable keys
3. Remove UI idempotency sets
4. Remove processPageWithAzure references
5. Fix Confirm button

