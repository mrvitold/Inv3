-- Verification script for user security migration
-- Run this in Supabase SQL Editor to verify everything is set up correctly

-- 1. Check if user_id columns exist
SELECT 
    table_name,
    column_name,
    data_type,
    is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
    AND table_name IN ('invoices', 'companies')
    AND column_name = 'user_id'
ORDER BY table_name;

-- 2. Check if foreign key constraints exist
SELECT
    tc.table_name,
    kcu.column_name,
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
    AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
    AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY'
    AND tc.table_schema = 'public'
    AND tc.table_name IN ('invoices', 'companies')
    AND kcu.column_name = 'user_id'
ORDER BY tc.table_name;

-- 3. Check if indexes exist
SELECT
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE schemaname = 'public'
    AND tablename IN ('invoices', 'companies')
    AND indexname LIKE '%user_id%'
ORDER BY tablename, indexname;

-- 4. Check if Row Level Security is enabled
SELECT
    schemaname,
    tablename,
    rowsecurity
FROM pg_tables
WHERE schemaname = 'public'
    AND tablename IN ('invoices', 'companies')
ORDER BY tablename;

-- 5. Check if all policies exist
SELECT
    schemaname,
    tablename,
    policyname,
    permissive,
    roles,
    cmd,
    qual,
    with_check
FROM pg_policies
WHERE schemaname = 'public'
    AND tablename IN ('invoices', 'companies')
ORDER BY tablename, policyname;

-- 6. Summary check (should return 2 rows if everything is correct)
SELECT
    'invoices' as table_name,
    CASE WHEN EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'invoices' 
        AND column_name = 'user_id'
    ) THEN '✓ user_id column exists' ELSE '✗ user_id column missing' END as user_id_check,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'invoices' 
        AND indexname = 'idx_invoices_user_id'
    ) THEN '✓ index exists' ELSE '✗ index missing' END as index_check,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename = 'invoices' 
        AND rowsecurity = true
    ) THEN '✓ RLS enabled' ELSE '✗ RLS not enabled' END as rls_check,
    (SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'invoices') as policy_count
UNION ALL
SELECT
    'companies' as table_name,
    CASE WHEN EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'companies' 
        AND column_name = 'user_id'
    ) THEN '✓ user_id column exists' ELSE '✗ user_id column missing' END as user_id_check,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'companies' 
        AND indexname = 'idx_companies_user_id'
    ) THEN '✓ index exists' ELSE '✗ index missing' END as index_check,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename = 'companies' 
        AND rowsecurity = true
    ) THEN '✓ RLS enabled' ELSE '✗ RLS not enabled' END as rls_check,
    (SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = 'companies') as policy_count;

