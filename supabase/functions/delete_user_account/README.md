# Delete User Account Edge Function

This Supabase Edge Function handles secure user account deletion using the service role key.

## Deployment Instructions

### Prerequisites
1. Install Supabase CLI: https://supabase.com/docs/guides/cli
2. Login to Supabase: `supabase login`
3. Link your project: `supabase link --project-ref your-project-ref`

### Deploy the Function

1. **Navigate to your project root** (where `supabase` folder is located)

2. **Deploy the function:**
   ```bash
   supabase functions deploy delete_user_account
   ```

3. **Verify deployment:**
   - Go to Supabase Dashboard → Edge Functions
   - You should see `delete_user_account` function listed

## How It Works

1. The function receives a request with the user's JWT token in the Authorization header
2. It verifies the token and extracts the user ID
3. Uses the service role key (server-side only) to delete the user via admin API
4. All related data is automatically deleted due to CASCADE constraints in the database

## Security

- ✅ Service role key is never exposed to the client
- ✅ User can only delete their own account (verified via JWT)
- ✅ All database CASCADE deletes are handled automatically
- ✅ CORS headers are included for web compatibility

## Testing

You can test the function using curl:

```bash
curl -X POST \
  'https://your-project-ref.supabase.co/functions/v1/delete_user_account' \
  -H 'Authorization: Bearer YOUR_JWT_TOKEN' \
  -H 'apikey: YOUR_ANON_KEY'
```

## Troubleshooting

- **401 Unauthorized**: Check that the JWT token is valid and not expired
- **400 Bad Request**: The user might not exist or there's an issue with deletion
- **500 Internal Server Error**: Check Supabase logs in the dashboard

