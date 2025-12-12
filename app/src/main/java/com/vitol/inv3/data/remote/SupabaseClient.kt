package com.vitol.inv3.data.remote

import android.app.Application
import com.vitol.inv3.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.ExternalAuthAction
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseFactory {
    fun create(app: Application): SupabaseClient? {
        val url = BuildConfig.SUPABASE_URL
        val key = BuildConfig.SUPABASE_ANON_KEY
        if (url.isBlank() || key.isBlank()) return null
        return createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Postgrest)
            install(Auth) {
                // Configure auth for deep links
                scheme = "com.vitol.inv3" // Your app's custom scheme
                host = "auth" // Your app's custom host
                defaultExternalAuthAction = ExternalAuthAction.CustomTabs()
            }
            install(Functions)
        }
    }
}

