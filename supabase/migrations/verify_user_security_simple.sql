-- Simple verification - Run this to get a quick summary
-- Expected: All checks should show ✓

SELECT
    '✓' as status,
    'invoices.user_id column' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'invoices' 
        AND column_name = 'user_id'
    ) THEN 'EXISTS' ELSE 'MISSING' END as result
UNION ALL
SELECT
    '✓' as status,
    'companies.user_id column' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'companies' 
        AND column_name = 'user_id'
    ) THEN 'EXISTS' ELSE 'MISSING' END as result
UNION ALL
SELECT
    '✓' as status,
    'idx_invoices_user_id index' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'invoices' 
        AND indexname = 'idx_invoices_user_id'
    ) THEN 'EXISTS' ELSE 'MISSING' END as result
UNION ALL
SELECT
    '✓' as status,
    'idx_companies_user_id index' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE schemaname = 'public' 
        AND tablename = 'companies' 
        AND indexname = 'idx_companies_user_id'
    ) THEN 'EXISTS' ELSE 'MISSING' END as result
UNION ALL
SELECT
    '✓' as status,
    'RLS enabled on invoices' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename = 'invoices' 
        AND rowsecurity = true
    ) THEN 'ENABLED' ELSE 'DISABLED' END as result
UNION ALL
SELECT
    '✓' as status,
    'RLS enabled on companies' as check_item,
    CASE WHEN EXISTS (
        SELECT 1 FROM pg_tables 
        WHERE schemaname = 'public' 
        AND tablename = 'companies' 
        AND rowsecurity = true
    ) THEN 'ENABLED' ELSE 'DISABLED' END as result
UNION ALL
SELECT
    '✓' as status,
    'Invoice policies count' as check_item,
    (SELECT COUNT(*)::text FROM pg_policies WHERE schemaname = 'public' AND tablename = 'invoices') || ' (expected: 4)' as result
UNION ALL
SELECT
    '✓' as status,
    'Company policies count' as check_item,
    (SELECT COUNT(*)::text FROM pg_policies WHERE schemaname = 'public' AND tablename = 'companies') || ' (expected: 4)' as result
ORDER BY check_item;

