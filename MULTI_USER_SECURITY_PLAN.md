# Multi-User Security Implementation Plan

**Status:** Planned for Future Implementation  
**Date:** December 2024

## Overview
Plan for implementing secure multi-user architecture using Supabase Authentication and Row-Level Security (RLS). Each user will only see their own companies and invoices, with a shared `all_companies` table for admin access.

## Architecture Decision

### Approach: Row-Level Security (RLS) with user_id
**Selected:** Option A - Each user has their own company records (can customize, isolated)

**Why this approach:**
- ✅ Standard Supabase pattern
- ✅ Secure by default (enforced at database level)
- ✅ Scalable (no separate tables per user)
- ✅ Easy to manage (single schema)
- ✅ Users can customize company data
- ✅ Complete data isolation

## Database Schema Changes (Future)

### 1. Add user_id to Existing Tables
```sql
-- Add user_id to invoices
ALTER TABLE public.invoices 
ADD COLUMN user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE;

-- Add user_id to companies
ALTER TABLE public.companies 
ADD COLUMN user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE;

-- Add indexes for performance
CREATE INDEX idx_invoices_user_id ON public.invoices(user_id);
CREATE INDEX idx_companies_user_id ON public.companies(user_id);
```

### 2. Create all_companies Table
```sql
-- All companies from all users (unique, for admin)
CREATE TABLE public.all_companies (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  company_number text UNIQUE NOT NULL,
  company_name text,
  vat_number text,
  first_seen_at timestamptz DEFAULT now(),
  last_seen_at timestamptz DEFAULT now(),
  user_count integer DEFAULT 1,
  created_at timestamptz DEFAULT now()
);

CREATE INDEX idx_all_companies_company_number ON public.all_companies(company_number);
```

### 3. Sync Trigger
```sql
-- Function to sync company to all_companies
CREATE OR REPLACE FUNCTION sync_to_all_companies()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO public.all_companies (company_number, company_name, vat_number, first_seen_at, last_seen_at, user_count)
  VALUES (NEW.company_number, NEW.company_name, NEW.vat_number, NOW(), NOW(), 1)
  ON CONFLICT (company_number) 
  DO UPDATE SET
    company_name = COALESCE(EXCLUDED.company_name, all_companies.company_name),
    vat_number = COALESCE(EXCLUDED.vat_number, all_companies.vat_number),
    last_seen_at = NOW(),
    user_count = (
      SELECT COUNT(DISTINCT user_id) 
      FROM public.companies 
      WHERE company_number = NEW.company_number
    );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger on companies table
CREATE TRIGGER sync_all_companies_trigger
AFTER INSERT OR UPDATE ON public.companies
FOR EACH ROW
EXECUTE FUNCTION sync_to_all_companies();
```

### 4. Row-Level Security Policies
```sql
-- Enable RLS
ALTER TABLE public.invoices ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.all_companies ENABLE ROW LEVEL SECURITY;

-- Policies for invoices
CREATE POLICY "Users can view own invoices" ON public.invoices FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert own invoices" ON public.invoices FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own invoices" ON public.invoices FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own invoices" ON public.invoices FOR DELETE USING (auth.uid() = user_id);

-- Policies for companies
CREATE POLICY "Users can view own companies" ON public.companies FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert own companies" ON public.companies FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own companies" ON public.companies FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own companies" ON public.companies FOR DELETE USING (auth.uid() = user_id);

-- Policies for all_companies (read-only for users)
CREATE POLICY "Users can view all companies" ON public.all_companies FOR SELECT TO authenticated USING (true);
```

## Implementation Steps (Future)

### Phase 1: Authentication Setup
1. Add Supabase Auth dependency to `build.gradle.kts`
2. Create `AuthManager` class
3. Create `LoginScreen` UI
4. Update `SupabaseClient` to include Auth module
5. Store auth session in DataStore

### Phase 2: Database Migration
1. Create migration SQL to add user_id columns
2. Create all_companies table with triggers
3. Set up RLS policies
4. Create admin user

### Phase 3: Code Updates
1. Update `SupabaseRepository` to get current user_id
2. Update all repository methods
3. Add sync logic for all_companies
4. Create AdminRepository (optional)

### Phase 4: UI Updates
1. Add LoginScreen to navigation
2. Add logout functionality
3. Handle auth state
4. Create AdminScreen (optional)

## Key Design Decisions

### Company Records: Separate per User
- Each user has their own company records
- Same company_number can exist for multiple users
- Users can customize company data
- Complete data isolation
- `all_companies` table deduplicates for admin view

### Admin Access
- Use `SUPABASE_SERVICE_ROLE_KEY` for admin operations
- Never expose service role key in mobile app
- Admin can query all_companies table directly
- Or create admin UI with service role key (server-side only)

## Security Considerations

1. **RLS Policies**: Must be enabled on all tables
2. **API Keys**: Use anon key for client (respects RLS), service role only for admin
3. **Data Isolation**: All queries automatically filtered by RLS
4. **Admin Access**: Via service role key or admin flag in user_profiles

## Migration Strategy

For existing data:
1. Create migration script to assign existing data to default user
2. Or create "migration user" and assign all existing data
3. Users can claim their data if needed

## Files to Create/Modify (Future)

### New Files
- `app/src/main/java/com/vitol/inv3/auth/AuthManager.kt`
- `app/src/main/java/com/vitol/inv3/ui/auth/LoginScreen.kt`
- `app/src/main/java/com/vitol/inv3/ui/auth/AuthViewModel.kt`
- `supabase/migration_add_user_security.sql`

### Modified Files
- `app/build.gradle.kts` - Add Supabase Auth dependency
- `app/src/main/java/com/vitol/inv3/data/remote/SupabaseClient.kt` - Add Auth module
- `app/src/main/java/com/vitol/inv3/data/remote/SupabaseRepository.kt` - Add user_id handling
- `app/src/main/java/com/vitol/inv3/MainActivity.kt` - Add auth state check
- `supabase/schema.sql` - Add new tables and columns

## Questions to Resolve (Before Implementation)

1. **Authentication method**: Email/password, OAuth (Google, Apple), or both?
2. **Admin access**: How should admin access all_companies? Separate admin app, web dashboard, or in-app?
3. **Existing data**: How to handle existing invoices/companies in database?
4. **User registration**: Open registration or invite-only?
5. **Password reset**: Should users be able to reset passwords?

