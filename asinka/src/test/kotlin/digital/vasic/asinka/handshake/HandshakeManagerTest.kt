package digital.vasic.asinka.handshake

import digital.vasic.asinka.models.FieldSchema
import digital.vasic.asinka.models.FieldType
import digital.vasic.asinka.models.ObjectSchema
import digital.vasic.asinka.proto.HandshakeRequest
import digital.vasic.asinka.proto.HandshakeResponse
import digital.vasic.asinka.security.SecurityManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator

class HandshakeManagerTest {

    private lateinit var securityManager: SecurityManager
    private lateinit var handshakeManager: HandshakeManager
    private lateinit var testKeyPair: KeyPair

    @Before
    fun setup() {
        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048)
        testKeyPair = keyPairGen.generateKeyPair()

        securityManager = mockk()
        every { securityManager.getPublicKey() } returns testKeyPair.public

        val schemas = listOf(
            ObjectSchema(
                objectType = "Task",
                version = "1.0",
                fields = listOf(
                    FieldSchema("title", FieldType.STRING),
                    FieldSchema("completed", FieldType.BOOLEAN)
                )
            )
        )

        handshakeManager = DefaultHandshakeManager(
            securityManager = securityManager,
            localAppId = "test-app",
            localAppName = "Test App",
            localAppVersion = "1.0.0",
            localDeviceId = "device-123",
            localSchemas = schemas,
            localCapabilities = mapOf("feature1" to "enabled")
        )
    }

    @Test
    fun testCreateHandshakeRequest() = runTest {
        val schemas = listOf(
            ObjectSchema(
                objectType = "User",
                version = "1.0",
                fields = listOf(FieldSchema("name", FieldType.STRING))
            )
        )

        val request = handshakeManager.createHandshakeRequest(
            appId = "app-1",
            appName = "App 1",
            appVersion = "1.0",
            deviceId = "device-1",
            exposedSchemas = schemas,
            capabilities = mapOf("sync" to "enabled")
        )

        assertEquals("app-1", request.appId)
        assertEquals("App 1", request.appName)
        assertEquals("1.0", request.appVersion)
        assertEquals("device-1", request.deviceId)
        assertFalse(request.publicKey.isEmpty)
        assertEquals(1, request.exposedSchemasCount)
        assertEquals(1, request.capabilitiesCount)
    }

    @Test
    fun testProcessHandshakeRequest() = runTest {
        val request = HandshakeRequest.newBuilder()
            .setAppId("remote-app")
            .setAppName("Remote App")
            .setAppVersion("1.0")
            .setDeviceId("remote-device")
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(testKeyPair.public.encoded))
            .addSupportedProtocols("asinka-v1")
            .build()

        val response = handshakeManager.processHandshakeRequest(request)

        assertTrue(response.success)
        assertFalse(response.sessionId.isEmpty())
        assertFalse(response.publicKey.isEmpty)
    }

    @Test
    fun testProcessHandshakeRequestIncompatibleProtocol() = runTest {
        val request = HandshakeRequest.newBuilder()
            .setAppId("remote-app")
            .setAppName("Remote App")
            .setAppVersion("1.0")
            .setDeviceId("remote-device")
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(testKeyPair.public.encoded))
            .addSupportedProtocols("unknown-protocol")
            .build()

        val response = handshakeManager.processHandshakeRequest(request)

        assertFalse(response.success)
        assertTrue(response.errorMessage.contains("protocol"))
    }

    @Test
    fun testValidateHandshakeResponseSuccess() = runTest {
        val request = HandshakeRequest.newBuilder()
            .setAppId("app-1")
            .build()

        val response = HandshakeResponse.newBuilder()
            .setSuccess(true)
            .setSessionId("session-123")
            .setPublicKey(com.google.protobuf.ByteString.copyFrom(testKeyPair.public.encoded))
            .addExposedSchemas(
                digital.vasic.asinka.proto.ObjectSchema.newBuilder()
                    .setObjectType("Task")
                    .setVersion("1.0")
                    .build()
            )
            .putCapabilities("sync", "enabled")
            .build()

        val result = handshakeManager.validateHandshakeResponse(request, response)

        assertTrue(result is HandshakeResult.Success)
        val success = result as HandshakeResult.Success
        assertEquals("session-123", success.sessionId)
        assertEquals(1, success.remoteSchemas.size)
        assertEquals(1, success.remoteCapabilities.size)
    }

    @Test
    fun testValidateHandshakeResponseFailure() = runTest {
        val request = HandshakeRequest.newBuilder().build()

        val response = HandshakeResponse.newBuilder()
            .setSuccess(false)
            .setErrorMessage("Connection refused")
            .build()

        val result = handshakeManager.validateHandshakeResponse(request, response)

        assertTrue(result is HandshakeResult.Failure)
        val failure = result as HandshakeResult.Failure
        assertEquals("Connection refused", failure.errorMessage)
    }

    @Test
    fun testValidateHandshakeResponseMissingSessionId() = runTest {
        val request = HandshakeRequest.newBuilder().build()

        val response = HandshakeResponse.newBuilder()
            .setSuccess(true)
            .setSessionId("")
            .build()

        val result = handshakeManager.validateHandshakeResponse(request, response)

        assertTrue(result is HandshakeResult.Failure)
    }

    @Test
    fun testValidateHandshakeResponseMissingPublicKey() = runTest {
        val request = HandshakeRequest.newBuilder().build()

        val response = HandshakeResponse.newBuilder()
            .setSuccess(true)
            .setSessionId("session-123")
            .build()

        val result = handshakeManager.validateHandshakeResponse(request, response)

        assertTrue(result is HandshakeResult.Failure)
    }

    @Test
    fun testHandshakeResultEquality() {
        val result1 = HandshakeResult.Success(
            sessionId = "session-1",
            remotePublicKey = byteArrayOf(1, 2, 3),
            remoteSchemas = emptyList(),
            remoteCapabilities = emptyMap()
        )

        val result2 = HandshakeResult.Success(
            sessionId = "session-1",
            remotePublicKey = byteArrayOf(1, 2, 3),
            remoteSchemas = emptyList(),
            remoteCapabilities = emptyMap()
        )

        assertEquals(result1, result2)
        assertEquals(result1.hashCode(), result2.hashCode())
    }
}