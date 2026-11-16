package app.dayf

/**
 * Public client configuration. The Supabase publishable key is designed to ship inside apps:
 * what protects the data is Row Level Security on every table (see supabase/schema_v2.sql), not secrecy of this key.
 * NEVER put the service_role key or any secret here.
 */
object Config {
    const val SUPABASE_URL = "https://mrlpokoaelgyylpxckjl.supabase.co"
    const val SUPABASE_KEY = "sb_publishable_tizWzH10nyrf1crH4v7-wg_v2dQ6dkz"
    const val REDIRECT = "com.tikguest://auth-callback"
    const val KEYS_URL = "https://aistudio.google.com/apikey"
    /** Set to the hosted privacy policy page before publishing (see docs/). */
    const val PRIVACY_URL = ""
}
