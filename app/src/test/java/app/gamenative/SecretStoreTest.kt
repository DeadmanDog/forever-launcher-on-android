package app.gamenative

import android.app.Application
import android.content.Context
import app.gamenative.ui.screen.wow.BattleNetSignIn
import app.gamenative.wow.addons.provider.CurseForgeKey
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The e-mail and the CurseForge key are sealed like the password; older unsealed values are moved over on the first read. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class SecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Stands in for the Android KeyStore (Robolectric has none): reversible, and visibly not the plain text. */
    private object XorSealer : SecretStore.Sealer {
        override fun seal(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.isNotEmpty() && sealed[0] == 1.toByte())
            return sealed.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun seal() { SecretStore.sealerOverride = XorSealer }

    @After fun clear() {
        SecretStore.sealerOverride = null
        prefs("battle_net_login").edit().clear().commit()
        prefs("wow_addons").edit().clear().commit()
        prefs("secret_test").edit().clear().commit()
    }

    private fun allText(name: String) = prefs(name).all.values.joinToString(" ")

    @Test fun putSealsAndGetOpens() {
        val p = prefs("secret_test")
        assertTrue(SecretStore.put(p, "v_sealed", "v", "hello"))
        assertNull(p.getString("v", null))
        assertFalse(allText("secret_test").contains("hello"))
        assertEquals("hello", SecretStore.get(p, "v_sealed", "v"))
        assertTrue(SecretStore.put(p, "v_sealed", "v", ""))
        assertNull(SecretStore.get(p, "v_sealed", "v"))
    }

    @Test fun aPlainValueIsMigratedOnTheFirstRead() {
        val p = prefs("secret_test")
        p.edit().putString("v", "legacy").commit()
        assertEquals("legacy", SecretStore.get(p, "v_sealed", "v"))
        assertNull("the plain entry is removed", p.getString("v", null))
        assertNotEquals(null, p.getString("v_sealed", null))
        assertEquals("legacy", SecretStore.get(p, "v_sealed", "v"))
    }

    @Test fun withoutAKeyStoreValuesStayPlainAndKeepWorking() {
        SecretStore.sealerOverride = null
        // Robolectric has no AndroidKeyStore provider: the store falls back without throwing.
        assertNull(SecretStore.sealer())
        val p = prefs("secret_test")
        assertTrue(SecretStore.put(p, "v_sealed", "v", "plain"))
        assertEquals("plain", SecretStore.get(p, "v_sealed", "v"))
    }

    @Test fun aSealedValueThatCannotBeOpenedReadsAsMissing() {
        val p = prefs("secret_test")
        p.edit().putString("v_sealed", "AAAA").commit()
        assertNull(SecretStore.get(p, "v_sealed", "v"))
    }

    @Test fun battleNetLoginIsStoredSealedAndRoundTrips() {
        BattleNetSignIn.save(context, BattleNetSignIn.Login("player@example.com", "secret-pass"))
        val stored = allText("battle_net_login")
        assertFalse(stored.contains("player@example.com"))
        assertFalse(stored.contains("secret-pass"))
        assertTrue(BattleNetSignIn.hasSaved(context))
        assertEquals(BattleNetSignIn.Login("player@example.com", "secret-pass"), BattleNetSignIn.load(context))
    }

    @Test fun anUnsealedEmailOfAnOlderVersionIsSealedOnLoad() {
        val password = SecretStore.sealToString("secret-pass")!!
        prefs("battle_net_login").edit().putString("email", "old@example.com").putString("password", password).commit()
        assertTrue(BattleNetSignIn.hasSaved(context))
        assertEquals("old@example.com", BattleNetSignIn.load(context)?.email)
        assertNull(prefs("battle_net_login").getString("email", null))
        assertFalse(allText("battle_net_login").contains("old@example.com"))
        assertEquals("old@example.com", BattleNetSignIn.load(context)?.email)
    }

    @Test fun aLoginIsNotSavedWithoutAKeyStore() {
        SecretStore.sealerOverride = null
        assertThrows(IllegalStateException::class.java) {
            BattleNetSignIn.save(context, BattleNetSignIn.Login("player@example.com", "secret-pass"))
        }
        assertFalse(BattleNetSignIn.hasSaved(context))
    }

    @Test fun curseForgeKeyIsSealedAndAnOldPlainKeyIsMigrated() {
        prefs("wow_addons").edit().putString("curseforge_api_key", "old-key").commit()
        assertEquals("old-key", CurseForgeKey.load(context))
        assertNull(prefs("wow_addons").getString("curseforge_api_key", null))
        assertFalse(allText("wow_addons").contains("old-key"))
        CurseForgeKey.save(context, "  new-key ")
        assertEquals("new-key", CurseForgeKey.value)
        assertFalse(allText("wow_addons").contains("new-key"))
        assertEquals("new-key", CurseForgeKey.load(context))
    }
}
