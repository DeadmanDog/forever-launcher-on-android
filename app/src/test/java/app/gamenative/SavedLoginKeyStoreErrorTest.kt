package app.gamenative

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.wow.BattleNetSignIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Review 3 (9): a passing KeyStore error no longer deletes the saved Battle.net login; a value that can never be opened does. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class SavedLoginKeyStoreErrorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class Sealer(var failure: Exception? = null) : SecretStore.Sealer {
        override fun seal(plain: ByteArray) = byteArrayOf(1) + plain
        override fun open(sealed: ByteArray): ByteArray {
            failure?.let { throw it }
            return sealed.drop(1).toByteArray()
        }
    }

    @After fun clear() {
        SecretStore.sealerOverride = null
        context.getSharedPreferences("battle_net_login", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aPassingKeyStoreErrorKeepsTheSavedLogin() {
        val sealer = Sealer()
        SecretStore.sealerOverride = sealer
        BattleNetSignIn.save(context, BattleNetSignIn.Login("me@example.com", "secret"))
        sealer.failure = java.security.ProviderException("keystore busy")
        assertNull(BattleNetSignIn.load(context))
        assertTrue(BattleNetSignIn.hasSaved(context))
        sealer.failure = null
        assertEquals("secret", BattleNetSignIn.load(context)!!.password)
    }

    @Test fun aLoginThatCanNeverBeOpenedAgainIsForgotten() {
        val sealer = Sealer()
        SecretStore.sealerOverride = sealer
        BattleNetSignIn.save(context, BattleNetSignIn.Login("me@example.com", "secret"))
        sealer.failure = javax.crypto.AEADBadTagException("key was replaced")
        assertNull(BattleNetSignIn.load(context))
        assertFalse(BattleNetSignIn.hasSaved(context))
    }

    @Test fun definiteFailuresAreRecognisedThroughTheirCause() {
        assertTrue(SecretStore.definite(RuntimeException(javax.crypto.AEADBadTagException())))
        assertTrue(SecretStore.definite(IllegalArgumentException("bad base64")))
        assertFalse(SecretStore.definite(java.security.ProviderException("busy")))
        assertFalse(SecretStore.definite(IllegalStateException("not now")))
    }
}
