# Field Identification System - How It Works

## Overview
The app uses a **keyword-based and regex-based** approach to identify and extract invoice fields from OCR text. The system does NOT currently use template learning, although the infrastructure exists.

## Current Implementation Flow

### 1. OCR Text Extraction (`TextRecognition.kt`)
- Uses **Google ML Kit Text Recognition v2** (on-device)
- Processes the invoice image:
  - Loads bitmap from URI
  - Applies preprocessing (contrast enhancement)
  - Extracts text blocks and lines
  - Returns list of text lines

### 2. Field Identification (`InvoiceParser.kt`)
The parser uses a two-step approach:

#### Step A: Keyword Matching (`KeywordMapping.kt`)
Identifies field types by matching keywords in multiple languages:

| Field | Keywords (English/Lithuanian) |
|-------|------------------------------|
| Invoice_ID | "invoice number", "saskaitos numeris", "saskaitos serija", "fakturos serija" |
| Date | "data", "saskaitos data", "israsymo data", "invoice date", "proforma date" |
| Company_name | "imone", "kompanija", "bendrove" |
| Amount_without_VAT_EUR | "suma be pvm", "suma", "apmokestinamoji verte", "before vat", "without vat" |
| VAT_amount_EUR | "pvm", "pvm suma", "vat amount" |
| VAT_number | "pvm kodas", "pvm numeris", "vat number" |
| Company_number | "imones kodas", "imonenes registracijos numeris", "registracijos kodas" |

**How it works:**
- Each line is normalized (trimmed, lowercased)
- Checks if line contains any keyword from the mapping
- Returns the field name if match found

#### Step B: Value Extraction (`FieldExtractors.kt`)
Uses regex patterns to extract actual values:

| Field | Regex Pattern | Example |
|-------|--------------|---------|
| Date | `(?:date\|data)[^0-9]*([0-3]?[0-9][./-][01]?[0-9][./-](?:[0-9]{2}\|[0-9]{4}))` | "2024-03-15", "15.03.2024" |
| Amount | `([0-9]+(?:[.,][0-9]{2})?)` | "123.45", "1,234.56" |
| VAT_number | `(LT)?[0-9A-Z]{8,12}` | "LT123456789", "123456789" |
| Company_number | `[0-9]{7,14}` | "12345678" |

**Fallback for key-value pairs:**
- If regex fails, tries to extract value after colon (`:`) separator
- Example: "Invoice number: INV-123" → extracts "INV-123"

### 3. Parsing Logic (`InvoiceParser.parse()`)
```kotlin
lines.forEach { rawLine ->
    val line = rawLine.trim()
    val key = KeywordMapping.normalizeKey(line)  // Identify field type
    when (key) {
        "Date" -> date = FieldExtractors.tryExtractDate(line) ?: takeKeyValue(line)
        "VAT_number" -> vatNumber = FieldExtractors.tryExtractVatNumber(line) ?: takeKeyValue(line)
        // ... etc
    }
}
```

**Priority:**
1. Try regex extraction first (more accurate)
2. Fallback to key-value parsing (after colon)
3. Only set field if not already found (first match wins)

## Template Learning System (NOT CURRENTLY USED)

### Infrastructure Exists (`TemplateStore.kt`)
- **Purpose:** Learn field positions (x, y coordinates) per company
- **Storage:** DataStore Preferences (local)
- **Data Structure:** `FieldRegion(field, left, top, right, bottom)`
- **Methods:**
  - `saveTemplate(companyKey, regions)` - Save learned positions
  - `loadTemplate(companyKey)` - Load saved positions

### Why It's Not Used

The template learning system is **not integrated** into the parsing flow. It was planned but never fully implemented. Here's why:

#### 1. **Missing Integration Points**
- `InvoiceParser.parse()` does not call `TemplateStore.loadTemplate()` to check for saved templates
- No fallback logic to use templates when available
- Templates are never loaded or used during parsing

#### 2. **Missing Bounding Box Data**
- `TextRecognition.kt` only extracts text strings (line 29: `block.lines.map { it.text }`)
- ML Kit **does provide** bounding boxes (`block.boundingBox`, `line.boundingBox`), but they're not captured
- Without bounding boxes, we can't know where fields are located on the image
- `OcrBlock` data class only stores `text: String`, not coordinates

#### 3. **Missing UI Components**
- No UI for users to manually mark field positions on the invoice image
- No way to visualize or edit saved templates
- No template management screen

#### 4. **Missing Auto-Learning**
- No automatic template saving after successful invoice confirmation
- No mechanism to learn from user corrections
- No way to associate templates with companies

#### 5. **Project Status**
- Listed as "Medium Priority" TODO in `PROJECT_STATUS.md` (line 147)
- Marked as "not yet implemented" (line 160)
- Infrastructure exists but integration was deferred

#### What Would Be Needed to Enable It:
1. **Extend OCR to capture bounding boxes:**
   ```kotlin
   data class OcrBlock(
       val text: String,
       val boundingBox: Rect?  // Add this
   )
   ```

2. **Add UI for template creation:**
   - Allow users to tap/select regions on invoice image
   - Map selected regions to field names
   - Save as template for company

3. **Integrate into parser:**
   ```kotlin
   // In InvoiceParser.parse()
   val template = templateStore.loadTemplate(companyKey)
   if (template.isNotEmpty()) {
       // Extract from specific regions using bounding boxes
   } else {
       // Fallback to keyword/regex approach
   }
   ```

4. **Auto-save templates:**
   - After user confirms invoice, save field positions
   - Use OCR bounding boxes from matched fields

### How It Could Work (Future Enhancement)
1. User manually marks field positions on first invoice from a company
2. System saves coordinates relative to image size
3. On subsequent invoices from same company:
   - Load template
   - Extract text from specific regions
   - More accurate than keyword matching

## Company Recognition (`CompanyRecognition.kt`)
Uses heuristics to identify company information:
- Looks for company type keywords: "UAB", "AB", "MB", "LTD", "OY", "AS"
- Extracts VAT number and company number using regex
- Calculates confidence score based on found fields

## Current Limitations

1. **Language-dependent:** Requires keywords in supported languages
2. **Layout-dependent:** Assumes fields are labeled with keywords
3. **No learning:** Doesn't improve over time
4. **No position-based extraction:** Can't handle unlabeled fields
5. **Regex limitations:** May miss variations in format

## Recommendations for Improvement

1. **Integrate Template Learning:**
   - Add UI to mark field positions
   - Save templates after successful confirmations
   - Use templates for companies with saved layouts

2. **Hybrid Approach:**
   - Try template-based extraction first (if available)
   - Fallback to keyword/regex if no template

3. **Machine Learning:**
   - Train a model to recognize field types by position
   - Use OCR bounding boxes to identify field regions

4. **Better Regex:**
   - Add more date format patterns
   - Handle currency symbols
   - Support more VAT number formats

## Files Involved

- `app/src/main/java/com/vitol/inv3/ocr/TextRecognition.kt` - OCR extraction
- `app/src/main/java/com/vitol/inv3/ocr/InvoiceParser.kt` - Main parsing logic
- `app/src/main/java/com/vitol/inv3/ocr/KeywordMapping.kt` - Keyword matching
- `app/src/main/java/com/vitol/inv3/ocr/FieldExtractors.kt` - Regex extraction
- `app/src/main/java/com/vitol/inv3/ocr/CompanyRecognition.kt` - Company detection
- `app/src/main/java/com/vitol/inv3/data/local/TemplateStore.kt` - Template storage (unused)
- `app/src/main/java/com/vitol/inv3/ui/review/ReviewScreen.kt` - UI that calls parsing

