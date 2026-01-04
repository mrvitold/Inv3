# Keystore Setup for Release Signing

This guide explains how to set up app signing for Google Play Store releases.

## Option 1: Google Play App Signing (Recommended)

**Google Play App Signing** is the recommended approach. Google manages your app signing key securely, and you upload an upload key instead.

### Benefits:
- Google securely manages your signing key
- Key loss protection
- Automatic key rotation
- Easier team collaboration

### Setup Steps:

1. **Create an upload keystore** (this is what you'll use locally):
   ```bash
   keytool -genkey -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload-key
   ```

2. **When uploading your first app to Play Console:**
   - Upload your AAB file
   - Google will ask if you want to use Google Play App Signing
   - Choose "Yes" and follow the prompts
   - Google will generate and manage your app signing key

3. **For subsequent releases:**
   - Continue using your upload keystore
   - Google will re-sign your app with the managed key

## Option 2: Self-Managed Signing

If you prefer to manage your own signing key:

### Step 1: Create a Keystore

Run this command in your project root directory:

```bash
keytool -genkey -v -keystore inv3-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias inv3-release-key
```

**Important details:**
- **Keystore file**: `inv3-release.jks` (you can name it differently)
- **Key alias**: `inv3-release-key` (you can name it differently)
- **Validity**: 10000 days (~27 years) - recommended for long-term apps
- **Algorithm**: RSA 2048-bit (required by Google Play)

**You'll be prompted for:**
- Keystore password (remember this!)
- Key password (can be same as keystore password)
- Your name and organization details

### Step 2: Store the Keystore Securely

**DO:**
- Store the keystore file in a secure location (outside the project directory)
- Back it up to multiple secure locations
- Use a password manager for passwords
- Consider using Google Play App Signing (Option 1)

**DON'T:**
- Commit the keystore file to git (already in .gitignore)
- Share the keystore file publicly
- Lose the keystore file or passwords (you cannot update your app without it!)

### Step 3: Configure keystore.properties

1. Copy the example file:
   ```bash
   cp keystore.properties.example keystore.properties
   ```

2. Edit `keystore.properties` and fill in your values:
   ```properties
   storeFile=../keystore/inv3-release.jks
   storePassword=your_actual_keystore_password
   keyAlias=inv3-release-key
   keyPassword=your_actual_key_password
   ```

   **Note:** Use relative paths from project root, or absolute paths.

3. Verify `keystore.properties` is in `.gitignore` (it should be)

### Step 4: Build Release AAB

Once configured, build your release bundle:

```bash
./gradlew bundleRelease
```

The signed AAB will be in: `app/build/outputs/bundle/release/app-release.aab`

## Security Best Practices

1. **Backup Strategy:**
   - Store keystore in encrypted cloud storage (Google Drive, Dropbox with encryption)
   - Keep a physical backup (USB drive in safe location)
   - Document passwords in a secure password manager

2. **Team Collaboration:**
   - Use Google Play App Signing (Option 1) for teams
   - If self-managing, share keystore securely (encrypted)
   - Consider using CI/CD with encrypted secrets

3. **Password Management:**
   - Use strong, unique passwords
   - Store passwords separately from keystore file
   - Use a password manager

## Troubleshooting

### "Keystore file not found"
- Check the path in `keystore.properties`
- Use absolute path if relative path doesn't work
- Ensure the file exists

### "Keystore was tampered with, or password was incorrect"
- Double-check your passwords
- Ensure no extra spaces in `keystore.properties`
- Try recreating the keystore if you're sure passwords are correct

### "Cannot recover key"
- If you lose your keystore or password, you cannot update your existing app
- You'll need to publish a new app with a new package name
- This is why Google Play App Signing is recommended

## Next Steps

After setting up signing:
1. Build a release AAB: `./gradlew bundleRelease`
2. Test the AAB locally before uploading
3. Upload to Google Play Console
4. Consider enabling Google Play App Signing for future releases

