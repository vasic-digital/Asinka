/*
 * Copyright (c) 2025 MeTube Share
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */


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