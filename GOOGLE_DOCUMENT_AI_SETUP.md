# Google Document AI Integration Setup Guide

## ✅ Completed Steps

1. ✅ Dependencies added to `build.gradle.kts`
2. ✅ Internet permission added to `AndroidManifest.xml`
3. ✅ `GoogleDocumentAiService.kt` created
4. ✅ Integration added to `ReviewScreen.kt`
5. ✅ Credentials file added to `.gitignore`

## 🔧 Configuration Required

### Step 1: Update Google Cloud Credentials

Open `app/src/main/java/com/vitol/inv3/ocr/GoogleDocumentAiService.kt` and update these values:

```kotlin
private val projectId = "your-project-id"  // Replace with your Google Cloud project ID
private val location = "us"  // or "eu" - location where you created the processor
private val processorId = "your-processor-id"  // Replace with your Document AI processor ID
```

**How to find these values:**

1. **Project ID**: 
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Your project ID is shown at the top of the page

2. **Location**: 
   - Where you created your Document AI processor
   - Usually "us" or "eu"

3. **Processor ID**: 
   - Go to [Document AI Console](https://console.cloud.google.com/ai/document-ai)
   - Click on your processor
   - The Processor ID is in the URL or processor details
   - Format: `projects/PROJECT_ID/locations/LOCATION/processors/PROCESSOR_ID`
   - You only need the last part (the actual processor ID)

### Step 2: Verify Credentials File

Make sure your credentials file is in the correct location:
- **Path**: `app/src/main/assets/documentai-credentials.json`
- **Note**: If your file is named `documentai-credentials.json.json`, rename it to `documentai-credentials.json`

### Step 3: Test the Integration

1. Build and run the app
2. Take a photo of an invoice
3. The app will automatically try Document AI first
4. If Document AI is not configured or fails, it will fall back to local OCR
5. Check Logcat for messages like:
   - "Starting Document AI processing for invoice"
   - "Document AI processing successful"
   - Or error messages if something is wrong

## 📋 How It Works

1. **First Pass**: Uses local OCR to quickly identify the company name
2. **Second Pass**: 
   - **Tries Document AI first** (more accurate)
   - **Falls back to local OCR** if Document AI fails or is not configured
   - Extracts all invoice fields

## 🔍 Troubleshooting

### Error: "Google Document AI not configured"
- **Solution**: Update `projectId` and `processorId` in `GoogleDocumentAiService.kt`

### Error: "Could not find credentials file"
- **Solution**: 
  - Make sure `documentai-credentials.json` is in `app/src/main/assets/`
  - Check the filename (should be exactly `documentai-credentials.json`)
  - Rebuild the app after adding the file

### Error: "Failed to load Google Cloud credentials"
- **Solution**: 
  - Verify the JSON file is valid
  - Make sure the service account has "Document AI API User" role
  - Check that Document AI API is enabled in your Google Cloud project

### Document AI always falls back to local OCR
- **Check Logcat** for specific error messages
- Verify your processor ID is correct
- Make sure you have internet connection
- Check Google Cloud Console for API quota/errors

## 💰 Cost Information

- **Pricing**: ~$1.50 per 1,000 pages
- **Free Tier**: Google Cloud provides $300 free credits for new accounts
- **Monitoring**: Check usage in [Google Cloud Console](https://console.cloud.google.com/billing)

## 🔐 Security Notes

- ✅ Credentials file is already added to `.gitignore`
- ⚠️ **Never commit** the credentials JSON file to version control
- ⚠️ For production, consider using a backend server to handle Document AI calls

## 📝 Next Steps

1. Update the configuration values in `GoogleDocumentAiService.kt`
2. Test with a sample invoice
3. Monitor accuracy and costs
4. Consider adding a toggle in settings to enable/disable Document AI

## 🎯 Benefits

- **Higher Accuracy**: Document AI is trained specifically for invoices
- **Better Field Extraction**: Automatically extracts structured data
- **Multi-language Support**: Works well with Lithuanian invoices
- **Automatic Fallback**: Falls back to local OCR if Document AI is unavailable

