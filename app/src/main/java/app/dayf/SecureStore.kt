package app.dayf

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a key that lives in the Android Keystore (never leaves the secure hardware/TEE). */
object SecureStore {
    private const val ALIAS = "tikguest_main_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun put(c: Context, name: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            Prefs.putNow(c, "sec_$name", Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP))
        } catch (e: Exception) {
            AppLog.add("تعذّر التشفير المحلي: ${e.javaClass.simpleName}")
        }
    }

    fun get(c: Context, name: String): String {
        val s = Prefs.get(c, "sec_$name")
        if (s.isBlank()) return ""
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun remove(c: Context, name: String) {
        Prefs.putNow(c, "sec_$name", "")
    }
}

/** The user's own API keys: encrypted on the phone, and synced (encrypted server-side) to their account. */
object Secrets {
    fun gemini(c: Context): String = SecureStore.get(c, "gemini")
    fun eleven(c: Context): String = SecureStore.get(c, "eleven")

    fun setLocal(c: Context, name: String, value: String) {
        if (value.isBlank()) SecureStore.remove(c, name) else SecureStore.put(c, name, value)
    }

    /** Saves locally, then uploads to the account in the background. */
    fun save(c: Context, name: String, value: String) {
        setLocal(c, name, value)
        val app = c.applicationContext
        Thread {
            val s = Repo.supa(app) ?: return@Thread
            if (value.isBlank()) s.deleteSecret(name) else {
                val ok = s.saveSecret(name, value)
                if (!ok) AppLog.add("تعذّرت مزامنة المفتاح مع الحساب: ${s.lastError}")
            }
        }.start()
    }

    /** On a new device: pull the keys back from the account. */
    fun restore(c: Context, s: Supa) {
        for (n in listOf("gemini", "eleven")) {
            if (SecureStore.get(c, n).isBlank()) {
                val v = s.getSecret(n)
                if (!v.isNullOrBlank()) {
                    SecureStore.put(c, n, v)
                    AppLog.add("استُرجع مفتاح $n من حسابك")
                }
            }
        }
    }
}
