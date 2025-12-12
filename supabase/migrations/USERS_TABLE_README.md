# Users Table Documentation

## Overview
The `users` table stores app-specific user profile information that extends the basic authentication data in `auth.users`. This table is automatically populated when a user signs up.

## Table Structure

### Columns

| Column | Type | Description | Constraints |
|--------|------|-------------|-------------|
| `id` | uuid | Primary key, references `auth.users(id)` | PRIMARY KEY, NOT NULL, CASCADE DELETE |
| `email` | text | User's email address | Can be synced from auth.users |
| `display_name` | text | User's display name (for UI) | Optional |
| `full_name` | text | User's full name | Optional |
| `avatar_url` | text | URL to user's profile picture | Optional |
| `phone` | text | User's phone number | Optional |
| `preferences` | jsonb | User preferences and settings | Default: `{}` |
| `created_at` | timestamptz | When the profile was created | Default: `now()` |
| `updated_at` | timestamptz | When the profile was last updated | Auto-updated via trigger |

## Features

### 1. Automatic Profile Creation
When a user signs up (via email/password or Google Sign-In), a trigger automatically creates a profile in the `users` table:
- Extracts email from `auth.users`
- Sets display_name and full_name from metadata or email username
- Creates the profile with default preferences

### 2. Row-Level Security (RLS)
All operations are protected by RLS policies:
- Users can only view their own profile
- Users can only update their own profile
- Users can only delete their own profile
- Automatic CASCADE delete when user is deleted from `auth.users`

### 3. Automatic Timestamps
- `created_at` is set when the profile is created
- `updated_at` is automatically updated whenever the profile is modified

## Usage Examples

### Get User Profile
```sql
SELECT * FROM public.users WHERE id = auth.uid();
```

### Update Display Name
```sql
UPDATE public.users 
SET display_name = 'John Doe', updated_at = now()
WHERE id = auth.uid();
```

### Update Preferences
```sql
UPDATE public.users 
SET preferences = jsonb_set(
  COALESCE(preferences, '{}'::jsonb),
  '{theme}',
  '"dark"'
)
WHERE id = auth.uid();
```

### Get User Email
```sql
SELECT email FROM public.users WHERE id = auth.uid();
```

## Indexes
- `idx_users_email` - For fast email lookups
- `idx_users_created_at` - For sorting/filtering by creation date

## Migration Instructions

1. **Run the migration in Supabase:**
   - Go to Supabase Dashboard → SQL Editor
   - Copy the contents of `migration_create_users_table.sql`
   - Paste and execute

2. **Verify the table was created:**
   ```sql
   SELECT * FROM public.users LIMIT 1;
   ```

3. **Test RLS policies:**
   - Try to query another user's profile (should fail)
   - Update your own profile (should succeed)

## Integration with App

### Kotlin Data Class
```kotlin
@Serializable
data class UserProfile(
    val id: String,
    val email: String?,
    val displayName: String?,
    val fullName: String?,
    val avatarUrl: String?,
    val phone: String?,
    val preferences: Map<String, String>? = null,
    val createdAt: String?,
    val updatedAt: String?
)
```

### Repository Methods Needed
- `getUserProfile(userId: String): UserProfile?`
- `updateUserProfile(profile: UserProfile): UserProfile?`
- `updateDisplayName(name: String): Result<Unit>`
- `updatePreferences(preferences: Map<String, Any>): Result<Unit>`

## Future Enhancements

Potential additional columns to consider:
- `language` - User's preferred language
- `timezone` - User's timezone
- `notification_preferences` - JSONB for notification settings
- `subscription_status` - If you add premium features
- `last_login_at` - Track last login time
- `email_verified` - Whether email is verified (can sync from auth.users)

## Notes

- The `id` column must match `auth.users(id)` exactly
- When a user is deleted from `auth.users`, their profile is automatically deleted (CASCADE)
- The `preferences` JSONB field can store any key-value pairs for user settings
- All operations respect RLS policies automatically

