-- Migration: Remove unique constraint on company_number to allow multiple users to have the same company
-- This enables multiple accountants/users to add the same company as their own company

-- First, drop the existing unique constraint on company_number
ALTER TABLE public.companies
DROP CONSTRAINT IF EXISTS companies_company_number_key;

-- Note: We're removing the unique constraint entirely because:
-- 1. Multiple users should be able to have the same company as their own company
-- 2. The shared verification database (user_id = NULL) can have duplicate company_numbers
-- 3. Each user's own companies are already filtered by user_id in queries
-- 4. The application logic handles duplicate prevention per user

-- If we wanted to prevent a single user from having duplicate company_numbers,
-- we could add a composite unique constraint: (company_number, user_id)
-- However, this would require handling NULL user_id specially, which is complex.
-- Instead, we rely on application logic to prevent duplicates per user.

