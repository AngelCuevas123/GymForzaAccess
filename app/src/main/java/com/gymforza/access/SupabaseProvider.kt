package com.gymforza.access

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth

/**
 * Cliente único de Supabase. Solo usa la Publishable Key; nunca service_role.
 */
object SupabaseProvider {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    ) {
        install(Auth)
    }
}
