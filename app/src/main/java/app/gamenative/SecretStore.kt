package app.gamenative

import android.content.SharedPreferences
import android.util.Log
import java.util.Base64

/**
 * Small secrets (an e-mail address, an API key) kept in SharedPreferences sealed with the Android KeyStore key of [Crypto].
 *
 * Each value has a sealed entry and, for values written by older versions, a plain entry. Reading prefers the sealed entry; a plain
 * entry found next to a working KeyStore is sealed, stored and deleted on that first read (migration). Where the KeyStore cannot be
 * used (no AndroidKeyStore provider, e.g. a plain JVM or Robolectric test), values stay in the plain entry as before, so the feature
 * keeps working; the next read on a device with a working KeyStore migrates them.
 */
object SecretStore {
    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    private object KeyStoreSealer : Sealer {
        override fun seal(plain: ByteArray) = Crypto.encrypt(plain)
        override fun open(sealed: ByteArray) = Crypto.decrypt(sealed)
    }

    /** Tests set a sealer of their own; null = the Android KeyStore. */
    @Volatile internal var sealerOverride: Sealer? = null

    @Volatile private var keyStoreBroken = false

    /** The sealer to use, or null when the KeyStore cannot be used on this device. */
    fun sealer(): Sealer? {
        sealerOverride?.let { return it }
        if (keyStoreBroken) return null
        return try {
            // Touching Crypto loads the AndroidKeyStore; where it does not exist this throws (ExceptionInInitializerError and then
            // NoClassDefFoundError on later touches), so it is probed once and remembered.
            KeyStoreSealer.also { Crypto.hashCode() }
        } catch (t: Throwable) {
            keyStoreBroken = true
            Log.w(TAG, "Android KeyStore unavailable; secrets stay unsealed: ${t.javaClass.simpleName}")
            null
        }
    }

    /** Seals [value] with [sealer] into a Base64 string, or null when sealing failed. */
    fun sealToString(value: String, sealer: Sealer? = sealer()): String? {
        if (sealer == null || value.isEmpty()) return null
        return try {
            Base64.getEncoder().encodeToString(sealer.seal(value.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "sealing failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Opens a value made by [sealToString]; null when it cannot be opened. */
    fun openString(sealed: String, sealer: Sealer? = sealer()): String? {
        if (sealer == null) return null
        return try {
            String(sealer.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "opening a sealed value failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Writes [value] sealed into [sealedKey] and removes [plainKey]. Without a working KeyStore it is written into [plainKey] instead
     * (and [sealedKey] removed). An empty value removes both. Returns the commit result.
     */
    fun put(prefs: SharedPreferences, sealedKey: String, plainKey: String, value: String): Boolean {
        val editor = prefs.edit()
        if (value.isEmpty()) return editor.remove(sealedKey).remove(plainKey).commit()
        val sealed = sealToString(value)
        if (sealed != null) editor.putString(sealedKey, sealed).remove(plainKey)
        else editor.putString(plainKey, value).remove(sealedKey)
        return editor.commit()
    }

    /**
     * Reads the value: the sealed entry when there is one (null when it cannot be opened any more), otherwise the plain entry of an
     * older version, which is sealed and moved over on the spot when the KeyStore works.
     */
    fun get(prefs: SharedPreferences, sealedKey: String, plainKey: String): String? {
        prefs.getString(sealedKey, null)?.let { return openString(it) }
        val plain = prefs.getString(plainKey, null) ?: return null
        if (plain.isEmpty()) return plain
        val sealed = sealToString(plain)
        if (sealed != null) prefs.edit().putString(sealedKey, sealed).remove(plainKey).commit()
        return plain
    }

    private const val TAG = "SecretStore"
}
