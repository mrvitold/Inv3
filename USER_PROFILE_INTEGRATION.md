# User Profile Integration Guide

## Overview
This document describes the Kotlin integration for the `users` table in Supabase.

## Data Class

### UserProfile
Located in `app/src/main/java/com/vitol/inv3/data/remote/SupabaseRepository.kt`

```kotlin
@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    val display_name: String? = null,
    val full_name: String? = null,
    val avatar_url: String? = null,
    val phone: String? = null,
    val preferences: Map<String, String>? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)
```

## Repository Methods

All methods are in `SupabaseRepository` class:

### 1. Get User Profile

```kotlin
// Get profile by user ID
suspend fun getUserProfile(userId: String): UserProfile?

// Get current user's profile
suspend fun getCurrentUserProfile(): UserProfile?
```

**Usage:**
```kotlin
val profile = repo.getCurrentUserProfile()
profile?.displayName?.let { name ->
    // Use display name
}
```

### 2. Update User Profile

```kotlin
// Update entire profile
suspend fun updateUserProfile(profile: UserProfile): UserProfile?
```

**Usage:**
```kotlin
val currentProfile = repo.getCurrentUserProfile()
val updated = currentProfile?.copy(
    display_name = "John Doe",
    phone = "+1234567890"
)
updated?.let { repo.updateUserProfile(it) }
```

### 3. Update Individual Fields

```kotlin
// Update display name
suspend fun updateDisplayName(displayName: String): Result<Unit>

// Update full name
suspend fun updateFullName(fullName: String): Result<Unit>

// Update phone number
suspend fun updatePhone(phone: String): Result<Unit>

// Update avatar URL
suspend fun updateAvatarUrl(avatarUrl: String): Result<Unit>
```

**Usage:**
```kotlin
scope.launch {
    repo.updateDisplayName("John Doe")
        .onSuccess {
            // Show success message
        }
        .onFailure { error ->
            // Handle error
        }
}
```

### 4. Preferences Management

```kotlin
// Update multiple preferences (merges with existing)
suspend fun updatePreferences(newPreferences: Map<String, String>): Result<Unit>

// Set a single preference
suspend fun setPreference(key: String, value: String): Result<Unit>

// Get a preference value
suspend fun getPreference(key: String): String?
```

**Usage:**
```kotlin
// Set a preference
scope.launch {
    repo.setPreference("theme", "dark")
        .onSuccess {
            // Preference saved
        }
}

// Get a preference
scope.launch {
    val theme = repo.getPreference("theme") ?: "light"
    // Use theme
}

// Update multiple preferences
scope.launch {
    repo.updatePreferences(mapOf(
        "theme" to "dark",
        "language" to "en",
        "notifications" to "enabled"
    ))
}
```

## Example: ViewModel Usage

```kotlin
@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val repo: SupabaseRepository
) : ViewModel() {
    
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()
    
    init {
        viewModelScope.launch {
            _profile.value = repo.getCurrentUserProfile()
        }
    }
    
    fun updateDisplayName(name: String) {
        viewModelScope.launch {
            repo.updateDisplayName(name)
                .onSuccess {
                    // Refresh profile
                    _profile.value = repo.getCurrentUserProfile()
                }
        }
    }
}
```

## Example: Composable Usage

```kotlin
@Composable
fun UserProfileScreen(
    repo: SupabaseRepository = hiltViewModel<MainActivityViewModel>().repo
) {
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var displayName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    
    LaunchedEffect(Unit) {
        profile = repo.getCurrentUserProfile()
        displayName = profile?.display_name ?: ""
    }
    
    Column {
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display Name") }
        )
        
        Button(
            onClick = {
                scope.launch {
                    repo.updateDisplayName(displayName)
                        .onSuccess {
                            profile = repo.getCurrentUserProfile()
                        }
                }
            }
        ) {
            Text("Save")
        }
    }
}
```

## Error Handling

All methods return `Result<Unit>` or nullable types, so handle errors appropriately:

```kotlin
scope.launch {
    repo.updateDisplayName("New Name")
        .onSuccess {
            // Success
        }
        .onFailure { error ->
            // Handle error
            Timber.e(error, "Failed to update display name")
        }
}
```

## Notes

1. **Authentication Required**: All methods require the user to be authenticated. They will return `null` or `Result.failure` if not authenticated.

2. **RLS Policies**: All operations are protected by Row-Level Security. Users can only access their own profile.

3. **Automatic Profile Creation**: When a user signs up, a profile is automatically created via database trigger.

4. **Preferences**: The `preferences` field is stored as JSONB in the database and handled as `Map<String, String>` in Kotlin.

5. **Timestamps**: `created_at` and `updated_at` are automatically managed by the database.

## Next Steps

1. Run the migration SQL in Supabase
2. Test the repository methods
3. Create a User Profile screen in the app
4. Integrate profile display in the home screen or settings

