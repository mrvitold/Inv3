# Fix: jlink.exe does not exist

The build fails because Android Studio's bundled JBR (JetBrains Runtime) does not include `jlink.exe`, which the Android Gradle Plugin needs.

## Solution: Use a full JDK for Gradle

### Option A: Install JDK 17 and configure Gradle (recommended)

1. **Install Eclipse Temurin JDK 17:**
   ```powershell
   winget install EclipseAdoptium.Temurin.17.JDK
   ```

2. **Find the install path** (usually one of):
   - `C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`
   - `C:\Program Files\Eclipse Adoptium\jdk-17.x.x.x-hotspot`

3. **Configure Gradle** – add to `gradle.properties` (uncomment and fix path):
   ```properties
   org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.18.8-hotspot
   ```
   Or set **Gradle JDK** in Android Studio: **File → Settings → Build, Execution, Deployment → Build Tools → Gradle** and choose the installed JDK 17.

### Option B: Use user-level Gradle config

Add to `C:\Users\<you>\.gradle\gradle.properties`:
```properties
org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.18.8-hotspot
```
(Replace with your actual JDK path.)

### Verify

After configuration, stop the Gradle daemon and rebuild:
```powershell
cd C:\Users\vitol\AndroidStudioProjects\Inv3
.\gradlew --stop
.\gradlew :app:assembleDebug
```
