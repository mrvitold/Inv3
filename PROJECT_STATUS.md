# Inv3 - Invoice Processing Android App

## Project Overview
Android app for accountants to extract invoice information using OCR and export to Excel. Built with Kotlin + Jetpack Compose.

**Repository:** https://github.com/mrvitold/Inv3.git  
**Supabase Project:** Inv3 (login: vitold1990@gmail.com)

## Current Status: ✅ **APP BUILDS AND RUNS SUCCESSFULLY**

### Tech Stack
- **Language:** Kotlin 1.9.25
- **UI Framework:** Jetpack Compose (Material3)
- **Architecture:** MVVM with Hilt Dependency Injection
- **OCR:** Google ML Kit Text Recognition v2 (on-device)
- **Camera:** CameraX
- **Database:** Supabase (PostgREST)
- **Local Storage:** DataStore Preferences
- **Export:** Apache POI (Excel .xlsx)
- **Navigation:** Navigation Compose

## Key Features Implemented

### 1. Camera Scanning (`ScanScreen`)
- CameraX integration for photo capture
- Permission handling
- FileProvider for secure file sharing (Android 7.0+)
- Navigation to ReviewScreen after capture

### 2. OCR Processing (`TextRecognition.kt`)
- ML Kit Text Recognition v2
- Image preprocessing (contrast enhancement)
- Text extraction to blocks

### 3. Invoice Parsing (`InvoiceParser.kt`, `KeywordMapping.kt`)
- Multilingual keyword mapping (English/Lithuanian)
- Regex-based field extraction:
  - Invoice_ID
  - Date
  - Company_name
  - Amount_without_VAT_EUR
  - VAT_amount_EUR
  - VAT_number
  - Company_number

### 4. Review & Edit (`ReviewScreen`)
- Display parsed fields
- Manual field editing
- Confirm to save to Supabase

### 5. Data Storage
- **Supabase Tables:**
  - `invoices` (invoice_id, date, company_name, amount_without_vat_eur, vat_amount_eur, vat_number, company_number)
  - `companies` (company_number, company_name, vat_number)
- **Local:** DataStore for company templates (learning feature)

### 6. Companies Management (`CompaniesScreen`)
- View all companies
- Add/edit company information
- Load from Supabase

### 7. Excel Export (`ExcelExporter.kt`)
- Generate .xlsx files using Apache POI
- Share via Android Intent
- FileProvider for secure file access

### 8. Settings (`SettingsScreen`)
- Placeholder for future cloud OCR toggle
- Privacy information

## Critical Fixes Applied

### Build Configuration
1. **Kotlin Version:** Updated to 1.9.25 (compatible with Compose Compiler 1.5.15)
2. **Serialization:** Downgraded to 1.6.3 (compatible with Kotlin 1.9.25)
3. **Supabase Client:** Fixed PostgREST installation syntax
4. **Material Icons:** Added `material-icons-extended` dependency
5. **Lifecycle Compose:** Added `lifecycle-runtime-compose` for LocalLifecycleOwner

### Code Fixes
1. **SupabaseClient.kt:** Fixed `install(Postgrest)` syntax (was using lowercase `postgrest`)
2. **AppModule.kt:** Removed redundant `Application` provider (Hilt provides it automatically)
3. **ReviewScreen.kt:** Fixed field population from OCR results
4. **Navigation:** Fixed route pattern from `"review?uri={uri}"` to `"review/{uri}"`
5. **FileProvider:** Replaced deprecated `Uri.fromFile()` with `FileProvider.getUriForFile()`
6. **Camera Lifecycle:** Fixed unsafe cast to use `LocalLifecycleOwner`
7. **TextRecognition.kt:** Fixed expression body to block body syntax

### File Structure
```
app/src/main/
├── java/com/vitol/inv3/
│   ├── MainActivity.kt (Navigation setup)
│   ├── Inv3App.kt (Hilt Application)
│   ├── di/
│   │   ├── AppModule.kt (empty - Hilt provides Application)
│   │   ├── SupabaseModule.kt
│   │   ├── RepositoryModule.kt
│   │   ├── OcrModule.kt
│   │   └── TemplateModule.kt
│   ├── data/
│   │   ├── remote/
│   │   │   ├── SupabaseClient.kt
│   │   │   └── SupabaseRepository.kt
│   │   └── local/
│   │       └── TemplateStore.kt
│   ├── ocr/
│   │   ├── TextRecognition.kt
│   │   ├── InvoiceParser.kt
│   │   ├── KeywordMapping.kt
│   │   └── CompanyRecognition.kt
│   ├── export/
│   │   └── ExcelExporter.kt
│   └── ui/
│       ├── scan/ScanScreen.kt
│       ├── review/ReviewScreen.kt
│       ├── companies/CompaniesScreen.kt
│       ├── exports/ExportsScreen.kt
│       └── settings/SettingsScreen.kt
└── res/
    ├── xml/file_paths.xml (FileProvider paths)
    └── values/ (colors, strings, themes)
```

## Configuration Required

### Supabase Credentials
Add to `~/.gradle/gradle.properties`:
```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
```

### Database Schema
See `supabase/schema.sql` for table definitions.

## Next Steps / TODO

### High Priority
1. **Test OCR Accuracy:** Test with real invoices, adjust keyword mappings
2. **Error Handling:** Add proper error handling for Supabase operations
3. **Loading States:** Improve UI feedback during async operations
4. **Field Validation:** Validate invoice data before saving

### Medium Priority
5. **Company Recognition:** Implement heuristic company matching
6. **Template Learning:** Implement per-company field region learning
7. **Export Filtering:** Add date range filters for Excel export
8. **Review Queue:** Implement queue of pending invoices

### Low Priority
9. **Cloud OCR Option:** Add toggle for cloud-based OCR (future)
10. **Image Preprocessing:** Improve image enhancement algorithms
11. **Offline Support:** Cache invoices locally when offline
12. **UI Polish:** Improve Nordic-style design consistency

## Known Issues / Limitations
- Upsert for companies uses simple insert (conflicts handled by DB unique constraint)
- No authentication yet (using Supabase anon key)
- Template learning not yet implemented
- Export uses sample data (needs to fetch from Supabase)

## Dependencies (Key)
- `androidx.compose:compose-bom:2024.10.01`
- `io.github.jan-tennert.supabase:bom:2.5.0`
- `com.google.mlkit:text-recognition:16.0.0`
- `androidx.camera:camera-*:1.3.4`
- `org.apache.poi:poi-ooxml:5.3.0`
- `com.google.dagger:hilt-android:2.52`

## Build Commands
```bash
# Clean and rebuild
./gradlew clean assembleDebug

# Run on device
./gradlew installDebug
```

## Important Notes for Future Development
1. **Hilt:** Application is automatically provided - don't create custom providers
2. **FileProvider:** Always use FileProvider for file URIs (Android 7.0+)
3. **Navigation:** Use path parameters `{param}` not query strings `?param=value`
4. **Supabase:** Only PostgREST is installed (no auth for MVP)
5. **Kotlin Version:** Must stay at 1.9.25 until Compose Compiler is updated

---
**Last Updated:** After fixing all compilation errors - app successfully builds and runs
**Status:** ✅ Ready for testing and feature development

