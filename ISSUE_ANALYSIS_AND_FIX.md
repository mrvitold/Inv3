# Issue Analysis: Partner Company Number Not Filled, Own Company Name Filled

## Problem Description

When processing invoices, the application was:
1. **NOT filling** the partner's company number (`300581697`) even though it was correctly extracted from the invoice
2. **Filling** the company name field with the user's own company name (`UAB "Statybų frontas"`) instead of the partner's company name (`UAB "Tavo finansininkas"`)

## Root Cause Analysis

### Workflow Overview

1. **OCR Extraction**: Invoice is processed and extracts:
   - Company number: `300581697` (partner's company number) ✅ Correctly extracted
   - VAT number: Extracted from invoice
   - Company name: Initially extracted (could be own or partner's)

2. **Database Lookup**: Code looks up company in database using VAT or company number

3. **Problem**: The lookup logic had a critical flaw

### The Bug

**Location**: `ReviewScreen.kt` lines 2132-2137 (Local OCR) and 2303-2315 (Azure OCR)

**Issue**: When both VAT and company number were extracted:
- The code **only used VAT** for database lookup (`lookupCompanyNumber = null`)
- This meant the extracted company number was **not passed** to the database lookup
- If VAT lookup found the **wrong company** (e.g., own company or different partner), it would:
  - Use that wrong company's name from database
  - Use that wrong company's company number (if available) OR fall back to extracted number
  - But the name was already wrong!

**Example Scenario**:
1. Invoice has partner's company number: `300581697`
2. Invoice has partner's VAT: `LT300581697` (example)
3. Code extracts both correctly
4. Code looks up **only by VAT** in database
5. Database lookup finds **wrong company** (maybe own company if VAT was incorrectly matched)
6. Code uses wrong company's name from database
7. Code uses wrong company's number OR extracted number (but name is already wrong)

### Additional Issues

1. **No Validation**: Code didn't validate that database company number matches extracted company number before using DB values
2. **Missing Company Number**: If database company didn't have a company number, code would fall back to extracted, but name was already wrong

## The Fix

### Changes Made

1. **Always Pass Both VAT and Company Number** (lines 2132-2137 → updated):
   - Changed from: Only pass VAT if available, otherwise pass company number
   - Changed to: **Always pass both** VAT and company number if available
   - This ensures database lookup can validate both values match

2. **Added Company Number Validation** (lines 2164-2181 → updated):
   - Added check: `extractedNumberMatchesDb` - validates DB company number matches extracted
   - If mismatch detected: Preserve extracted values instead of using wrong DB values
   - This prevents using wrong company when VAT lookup finds incorrect match

3. **Improved Logging**: Added detailed logging to track when mismatches occur

### Code Changes

**Before**:
```kotlin
val lookupCompanyNumber = if (lookupVatNumber == null) {
    parsedWithFilteredName.companyNumber?.takeIf { it.isNotBlank() }
} else {
    null // Don't pass company number if we have VAT - VAT is more reliable
}
```

**After**:
```kotlin
val lookupCompanyNumber = parsedWithFilteredName.companyNumber?.takeIf { it.isNotBlank() }
val extractedCompanyNumber = parsedWithFilteredName.companyNumber?.takeIf { it.isNotBlank() }
```

**Added Validation**:
```kotlin
// CRITICAL: Validate that DB company number matches extracted company number (if both exist)
val extractedNumberMatchesDb = extractedCompanyNumber == null || dbCompanyNumber == null || extractedCompanyNumber == dbCompanyNumber

if (!extractedNumberMatchesDb && extractedCompanyNumber != null) {
    Timber.w("Database lookup found company with mismatched company number! Preserving extracted values.")
    // Preserve extracted values instead of using DB values
    parsedWithFilteredName.copy(
        companyNumber = preservedCompanyNumber,
        vatNumber = preservedVatNumber
    )
}
```

## Expected Behavior After Fix

1. **Company Number**: Always preserved from extraction, even if database lookup finds wrong company
2. **Company Name**: Only used from database if:
   - Database company number matches extracted company number, OR
   - Database company number is null (fallback case)
   - Database company is not own company
3. **Validation**: If database lookup finds company with mismatched company number, extracted values are preserved

## Testing Recommendations

1. Test with invoice where partner's company number is extracted correctly
2. Test with invoice where VAT lookup might find wrong company
3. Test with invoice where database company doesn't have company number
4. Verify that extracted company number is always preserved
5. Verify that own company name is never used for partner invoices

## Files Modified

- `app/src/main/java/com/vitol/inv3/ui/review/ReviewScreen.kt`
  - `runOcr()` function (Local OCR path)
  - `runOcrWithDocumentAi()` function (Azure OCR path)

