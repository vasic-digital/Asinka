package digital.vasic.asinka.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SecurityManagerTest {

    private lateinit var context: Context
    private lateinit var securityManager: AndroidSecurityManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        securityManager = AndroidSecurityManager(context)
    }

    @Test
    fun testKeyPairGeneration() {
        val keyPair = securityManager.generateKeyPair()
        assertNotNull(keyPair)
        assertNotNull(keyPair.public)
        assertNotNull(keyPair.private)
    }

    @Test
    fun testGetPublicKey() {
        val publicKey = securityManager.getPublicKey()
        assertNotNull(publicKey)
    }

    @Test
    fun testGetPrivateKey() {
        val privateKey = securityManager.getPrivateKey()
        assertNotNull(privateKey)
    }

    @Test
    fun testSessionKeyGeneration() {
        val sessionKey = securityManager.generateSessionKey()
        assertNotNull(sessionKey)
        assertEquals(256 / 8, sessionKey.encoded.size)
    }

    @Test
    fun testEncryptDecryptWithSessionKey() {
        val sessionKey = securityManager.generateSessionKey()
        val originalData = "Hello, Asinka!".toByteArray()

        val encrypted = securityManager.encryptWithSessionKey(originalData, sessionKey)
        assertNotNull(encrypted.data)
        assertNotNull(encrypted.iv)
        assertFalse(encrypted.data.contentEquals(originalData))

        val decrypted = securityManager.decryptWithSessionKey(encrypted, sessionKey)
        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun testSignAndVerify() {
        val data = "Test data for signing".toByteArray()
        val signature = securityManager.sign(data)
        assertNotNull(signature)

        val publicKey = securityManager.getPublicKey()
        assertNotNull(publicKey)

        val isValid = securityManager.verify(data, signature, publicKey!!)
        assertTrue(isValid)

        val tamperedData = "Tampered data".toByteArray()
        val isInvalid = securityManager.verify(tamperedData, signature, publicKey)
        assertFalse(isInvalid)
    }
}