# Future Plans & Implementation Roadmap

**Last Updated:** December 11, 2025  
**Status:** Active Planning

## Recently Completed (December 11, 2025)

### ✅ Authentication System - COMPLETED
- Email/password authentication (sign up and sign in)
- Google Sign-In integration
- Password confirmation field during registration
- Email confirmation notification after sign up
- Forgot password/reset password functionality
- Deep link handling for email confirmation
- Logout functionality
- Delete account button (currently signs out - full deletion needs implementation)
- User data isolation via Row-Level Security (RLS) policies
- Session persistence using DataStore

**Files Created:**
- `app/src/main/java/com/vitol/inv3/auth/AuthManager.kt`
- `app/src/main/java/com/vitol/inv3/ui/auth/LoginScreen.kt`
- `app/src/main/java/com/vitol/inv3/ui/auth/AuthViewModel.kt`
- `app/src/main/java/com/vitol/inv3/di/AuthModule.kt`
- `supabase/migrations/migration_add_user_security.sql`

**Database Changes:**
- Added `user_id` columns to `invoices` and `companies` tables
- Enabled Row-Level Security (RLS) on both tables
- Created RLS policies for SELECT, INSERT, UPDATE, DELETE operations
- Added indexes for performance

## High Priority - Immediate Next Steps

### 1. Full Account Deletion Implementation ⚠️ NEEDS WORK
**Current Status:** Delete account button signs user out and clears session, but doesn't actually delete the account from Supabase.

**Why:** Supabase Kotlin client doesn't have a direct `deleteUser()` method. Full account deletion requires:
- Admin API access (service role key - NOT recommended in mobile app)
- A server-side Edge Function to handle deletion
- Manual deletion through Supabase dashboard

**Implementation Options:**
1. **Create Supabase Edge Function** (Recommended)
   - Create a function that uses service role key to delete user
   - Call this function from the app
   - Function should delete user from `auth.users` table
   - RLS policies will automatically cascade delete user's invoices and companies

2. **Use Admin API** (Not Recommended)
   - Requires exposing service role key (security risk)
   - Should never be done in mobile app

3. **Manual Process** (Current)
   - User requests deletion
   - Admin deletes manually from Supabase dashboard

**Action Items:**
- [ ] Create Supabase Edge Function for account deletion
- [ ] Add API endpoint call in AuthManager
- [ ] Test account deletion flow
- [ ] Update UI to show proper confirmation message

**Estimated Effort:** 2-3 hours

### 2. Create Separate "users" Table in Supabase 📋 PLANNED
**Purpose:** Store additional user profile information beyond what's in `auth.users`.

**Why:** 
- `auth.users` table is managed by Supabase Auth and has limited fields
- Need to store app-specific user data (preferences, settings, profile info)
- Better separation of concerns

**Schema Design:**
```sql
CREATE TABLE public.users (
  id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  email text,
  display_name text,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now(),
  preferences jsonb DEFAULT '{}'::jsonb,
  -- Add other user-specific fields as needed
);

CREATE INDEX idx_users_email ON public.users(email);

-- RLS Policy
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can view own profile" ON public.users 
  FOR SELECT USING (auth.uid() = id);

CREATE POLICY "Users can update own profile" ON public.users 
  FOR UPDATE USING (auth.uid() = id);
```

**Implementation Steps:**
- [ ] Create migration SQL file
- [ ] Run migration in Supabase
- [ ] Create UserProfile data class in Kotlin
- [ ] Add methods to SupabaseRepository for user profile operations
- [ ] Create user profile screen (optional)
- [ ] Sync user data on sign up/sign in

**Estimated Effort:** 2-3 hours

### 3. Verify and Fix Google Sign-In 🔍 NEEDS TESTING
**Current Status:** Google Sign-In is implemented but needs thorough testing.

**Action Items:**
- [ ] Test Google Sign-In flow end-to-end
- [ ] Verify ID token is received correctly
- [ ] Check if Supabase accepts the ID token
- [ ] Test error handling (cancelled sign-in, network errors)
- [ ] Verify user data is created correctly in Supabase
- [ ] Test sign-in persistence (app restart)
- [ ] Check if Google icon displays correctly
- [ ] Verify callback URL is configured correctly in Supabase

**Potential Issues to Check:**
- Google client ID format and configuration
- Supabase Google OAuth configuration
- Redirect URL matching
- ID token format and validation

**Estimated Effort:** 1-2 hours

## Medium Priority - Short Term

### 4. Email Confirmation Deep Link Handling 🔧 NEEDS IMPROVEMENT
**Current Status:** Deep link handling is implemented but may need refinement.

**Action Items:**
- [ ] Test email confirmation flow end-to-end
- [ ] Verify deep link opens app correctly
- [ ] Check if Supabase processes the confirmation token
- [ ] Handle error cases (expired token, invalid token)
- [ ] Show success/error messages to user
- [ ] Auto-login after email confirmation

**Estimated Effort:** 1-2 hours

### 5. User Profile Management
**Features:**
- Display user email/name on home screen
- Edit profile information
- Change password
- Update email address
- Account settings screen

**Estimated Effort:** 3-4 hours

### 6. Session Management Improvements
**Features:**
- Auto-refresh expired sessions
- Handle token refresh errors gracefully
- Show "Session expired" message when needed
- Re-authentication flow

**Estimated Effort:** 2-3 hours

## Analysis: What's Done vs What's Left

### ✅ Completed Features (From Session Summaries)

#### Core Invoice Processing
- ✅ Camera scanning with CameraX
- ✅ OCR processing (Azure Document Intelligence + ML Kit fallback)
- ✅ Invoice parsing and field extraction
- ✅ Company name extraction with database lookup
- ✅ VAT number and company number extraction
- ✅ Date and amount extraction (Lithuanian formats)
- ✅ Invoice ID extraction (supports 11+ digits)
- ✅ Review and edit screen
- ✅ Save to Supabase

#### Own Company Management
- ✅ Own company selection and management
- ✅ Active company persistence (DataStore)
- ✅ Own company exclusion from extraction
- ✅ Duplicate prevention
- ✅ Edit and delete own companies

#### Export & Data Management
- ✅ Excel export functionality
- ✅ Monthly summaries
- ✅ Company breakdown
- ✅ Invoice editing from export view
- ✅ Invoice deletion (individual and monthly)
- ✅ Export all invoices for a year

#### Invoice Validation
- ✅ Comprehensive validation system
- ✅ Error detection and display
- ✅ VAT amount validation (Lithuanian rates)
- ✅ Format/length validation
- ✅ Date validation
- ✅ Duplicate detection

#### File Import
- ✅ PDF import with page separation
- ✅ Image import (JPG, PNG, HEIC)
- ✅ Batch processing queue
- ✅ Background processing
- ✅ Progress indicators
- ✅ Skip, Stop, Previous buttons
- ✅ Cache management

#### Authentication (Just Completed)
- ✅ Email/password sign up and sign in
- ✅ Google Sign-In
- ✅ Password reset
- ✅ Email confirmation flow
- ✅ Session management
- ✅ User data isolation (RLS)

### ⚠️ Partially Completed / Needs Work

#### Authentication
- ⚠️ Account deletion (signs out but doesn't delete from Supabase)
- ⚠️ Google Sign-In (implemented but needs testing)
- ⚠️ Email confirmation deep link (implemented but needs testing)
- ⚠️ Session refresh handling

#### Database
- ⚠️ Separate users table (not created yet)
- ⚠️ User profile management (not implemented)

### 📋 Planned / Not Started

#### High Priority
1. Full account deletion implementation
2. Create users table
3. Google Sign-In verification and fixes
4. Email confirmation deep link testing and fixes

#### Medium Priority
5. User profile management screen
6. Session refresh improvements
7. Better error handling for auth flows
8. Password change functionality
9. Email change functionality

#### Low Priority
10. Biometric authentication (fingerprint/Face ID)
11. "Remember me" option
12. Multi-device session management
13. Account recovery options

## Implementation Timeline

### Week 1 (December 11-17, 2025)
- [ ] Test and fix Google Sign-In
- [ ] Test email confirmation deep links
- [ ] Create users table migration
- [ ] Implement full account deletion (Edge Function)

### Week 2 (December 18-24, 2025)
- [ ] User profile management screen
- [ ] Session refresh improvements
- [ ] Password change functionality
- [ ] Email change functionality

### Week 3+ (Future)
- [ ] Biometric authentication
- [ ] Advanced session management
- [ ] Multi-device support

## Notes

### Account Deletion
The current implementation signs the user out and clears the session. Full account deletion in Supabase typically requires:
- Admin API access, or
- A server-side Edge Function, or
- Manual deletion through the Supabase dashboard

**Recommended Approach:** Create a Supabase Edge Function that uses the service role key to delete the user account. This keeps the service role key secure on the server side.

### Google Sign-In
Needs thorough testing to ensure:
- ID token is received correctly
- Supabase accepts the token
- User is created/authenticated properly
- Error cases are handled gracefully

### Users Table
Creating a separate `users` table allows storing app-specific user data that's not available in `auth.users`, such as:
- Display name
- User preferences
- App settings
- Profile information

## Questions to Resolve

1. **Account Deletion:** Should we implement Edge Function now or keep manual process?
2. **Users Table:** What additional fields do we need beyond email and display name?
3. **Google Sign-In:** Are there any specific error scenarios we need to handle?
4. **Email Confirmation:** Should we auto-login after confirmation or require sign-in?

---

**Last Updated:** December 11, 2025  
**Next Review:** After completing Week 1 tasks

