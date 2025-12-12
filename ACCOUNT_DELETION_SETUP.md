# Account Deletion Setup Guide

## Overview
Account deletion now uses a Supabase Edge Function to securely delete user accounts. The service role key is kept secure on the server side.

## Step 1: Deploy the Edge Function

### Prerequisites
1. Install Supabase CLI: https://supabase.com/docs/guides/cli
2. Login: `supabase login`
3. Link your project: `supabase link --project-ref your-project-ref`

### Deploy
```bash
cd supabase/functions/delete_user_account
supabase functions deploy delete_user_account
```

Or from project root:
```bash
supabase functions deploy delete_user_account --project-ref your-project-ref
```

## Step 2: Verify Deployment

1. Go to Supabase Dashboard → Edge Functions
2. You should see `delete_user_account` function
3. Test it using the Supabase dashboard or curl

## Step 3: Test in App

1. Build and run the app
2. Go to Settings → Delete My Account
3. Confirm deletion
4. The account should be permanently deleted

## How It Works

1. **User clicks "Delete My Account"** in Settings
2. **App calls Edge Function** with user's JWT token
3. **Edge Function verifies** the token and gets user ID
4. **Edge Function deletes user** using service role key (admin API)
5. **Database CASCADE** automatically deletes:
   - User profile from `users` table
   - All invoices (via `user_id` foreign key)
   - All companies (via `user_id` foreign key)
6. **App signs out** and clears local session

## Security Features

- ✅ Service role key never exposed to client
- ✅ User can only delete their own account (verified via JWT)
- ✅ All data automatically deleted via CASCADE
- ✅ Proper error handling and user feedback

## Troubleshooting

### Function Not Found (404)
- Make sure the function is deployed
- Check function name matches: `delete_user_account`
- Verify project ref is correct

### Unauthorized (401)
- User's JWT token might be expired
- User needs to be signed in

### Function Error (500)
- Check Supabase logs in dashboard
- Verify service role key is set in environment variables
- Check function code for syntax errors

## Manual Testing

Test the function directly:

```bash
curl -X POST \
  'https://YOUR_PROJECT_REF.supabase.co/functions/v1/delete_user_account' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'apikey: YOUR_ANON_KEY' \
  -H 'Content-Type: application/json'
```

Replace:
- `YOUR_PROJECT_REF` with your Supabase project reference
- `YOUR_JWT_TOKEN` with a valid user JWT token
- `YOUR_ANON_KEY` with your Supabase anon key

## Notes

- The function automatically handles CASCADE deletes
- User data is permanently deleted (cannot be recovered)
- The app will sign out after successful deletion
- If function fails, user is still signed out but account remains (error message shown)

