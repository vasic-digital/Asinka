package digital.vasic.asinka

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import digital.vasic.asinka.models.FieldSchema
import digital.vasic.asinka.models.FieldType
import digital.vasic.asinka.models.ObjectSchema
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AsinkaClientTest {

    private lateinit var context: Context
    private lateinit var config: AsinkaConfig
    private lateinit var asinkaClient: AsinkaClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

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

        config = AsinkaConfig(
            appId = "com.test.app",
            appName = "Test App",
            appVersion = "1.0.0",
            deviceId = "test-device-123",
            serverPort = 9999,
            exposedSchemas = schemas,
            capabilities = mapOf("sync" to "enabled", "events" to "enabled")
        )

        asinkaClient = AsinkaClient.create(context, config)
    }

    @Test
    fun testAsinkaConfigCreation() {
        assertEquals("com.test.app", config.appId)
        assertEquals("Test App", config.appName)
        assertEquals("1.0.0", config.appVersion)
        assertEquals("test-device-123", config.deviceId)
        assertEquals(9999, config.serverPort)
        assertEquals(1, config.exposedSchemas.size)
        assertEquals(2, config.capabilities.size)
    }

    @Test
    fun testAsinkaConfigWithDefaults() {
        val defaultConfig = AsinkaConfig(
            appId = "app",
            appName = "App",
            appVersion = "1.0"
        )

        assertNotNull(defaultConfig.deviceId)
        assertEquals(8888, defaultConfig.serverPort)
        assertTrue(defaultConfig.exposedSchemas.isEmpty())
        assertTrue(defaultConfig.capabilities.isEmpty())
    }

    @Test
    fun testAsinkaClientCreation() {
        assertNotNull(asinkaClient)
        assertNotNull(asinkaClient.config)
        assertNotNull(asinkaClient.securityManager)
        assertNotNull(asinkaClient.discoveryManager)
        assertNotNull(asinkaClient.handshakeManager)
        assertNotNull(asinkaClient.syncManager)
        assertNotNull(asinkaClient.eventManager)
    }

    @Test
    fun testAsinkaClientConfig() {
        assertEquals(config.appId, asinkaClient.config.appId)
        assertEquals(config.appName, asinkaClient.config.appName)
        assertEquals(config.appVersion, asinkaClient.config.appVersion)
        assertEquals(config.deviceId, asinkaClient.config.deviceId)
        assertEquals(config.serverPort, asinkaClient.config.serverPort)
    }

    @Test
    fun testAsinkaClientManagers() {
        assertNotNull(asinkaClient.securityManager)
        assertNotNull(asinkaClient.discoveryManager)
        assertNotNull(asinkaClient.handshakeManager)
        assertNotNull(asinkaClient.syncManager)
        assertNotNull(asinkaClient.eventManager)
    }

    @Test
    fun testGetSessionsInitiallyEmpty() {
        val sessions = asinkaClient.getSessions()
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testSessionInfoCreation() {
        val sessionInfo = AsinkaClient.SessionInfo(
            sessionId = "session-123",
            remoteHost = "192.168.1.100",
            remotePort = 8888,
            remotePublicKey = byteArrayOf(1, 2, 3),
            remoteSchemas = emptyList(),
            remoteCapabilities = mapOf("feature" to "enabled")
        )

        assertEquals("session-123", sessionInfo.sessionId)
        assertEquals("192.168.1.100", sessionInfo.remoteHost)
        assertEquals(8888, sessionInfo.remotePort)
        assertArrayEquals(byteArrayOf(1, 2, 3), sessionInfo.remotePublicKey)
        assertTrue(sessionInfo.remoteSchemas.isEmpty())
        assertEquals(1, sessionInfo.remoteCapabilities.size)
    }

    @Test
    fun testMultipleAsinkaClientInstances() {
        val config1 = AsinkaConfig(
            appId = "app1",
            appName = "App 1",
            appVersion = "1.0",
            serverPort = 8001
        )

        val config2 = AsinkaConfig(
            appId = "app2",
            appName = "App 2",
            appVersion = "2.0",
            serverPort = 8002
        )

        val client1 = AsinkaClient.create(context, config1)
        val client2 = AsinkaClient.create(context, config2)

        assertNotEquals(client1.config.appId, client2.config.appId)
        assertNotEquals(client1.config.serverPort, client2.config.serverPort)
    }
}