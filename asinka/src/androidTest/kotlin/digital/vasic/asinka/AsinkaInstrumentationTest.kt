package digital.vasic.asinka

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import digital.vasic.asinka.events.AsinkaEvent
import digital.vasic.asinka.events.EventPriority
import digital.vasic.asinka.models.FieldSchema
import digital.vasic.asinka.models.FieldType
import digital.vasic.asinka.models.ObjectSchema
import digital.vasic.asinka.models.SyncableObjectData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsinkaInstrumentationTest {

    private lateinit var context: Context
    private lateinit var asinkaClient: AsinkaClient

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        val config = AsinkaConfig(
            appId = "digital.vasic.asinka.test",
            appName = "Asinka Test",
            appVersion = "1.0.0",
            serverPort = 9876,
            serviceName = "test-sync",
            exposedSchemas = listOf(
                ObjectSchema(
                    objectType = "TestObject",
                    version = "1.0",
                    fields = listOf(
                        FieldSchema("name", FieldType.STRING),
                        FieldSchema("value", FieldType.INT)
                    )
                )
            ),
            capabilities = mapOf("test" to "enabled")
        )

        asinkaClient = AsinkaClient.create(context, config)
    }

    @Test
    fun testContextIsValid() {
        assertNotNull(context)
        assertEquals("digital.vasic.asinka.test", context.packageName)
    }

    @Test
    fun testAsinkaClientCreation() {
        assertNotNull(asinkaClient)
        assertNotNull(asinkaClient.config)
        assertEquals("digital.vasic.asinka.test", asinkaClient.config.appId)
    }

    @Test
    fun testSecurityManagerKeyGeneration() {
        val publicKey = asinkaClient.securityManager.getPublicKey()
        assertNotNull(publicKey)

        val privateKey = asinkaClient.securityManager.getPrivateKey()
        assertNotNull(privateKey)
    }

    @Test
    fun testSecurityManagerEncryption() {
        val sessionKey = asinkaClient.securityManager.generateSessionKey()
        assertNotNull(sessionKey)

        val testData = "Test data for encryption".toByteArray()
        val encrypted = asinkaClient.securityManager.encryptWithSessionKey(testData, sessionKey)

        assertNotNull(encrypted.data)
        assertNotNull(encrypted.iv)
        assertFalse(encrypted.data.contentEquals(testData))

        val decrypted = asinkaClient.securityManager.decryptWithSessionKey(encrypted, sessionKey)
        assertArrayEquals(testData, decrypted)
    }

    @Test
    fun testSecurityManagerSignature() {
        val testData = "Test data for signing".toByteArray()
        val signature = asinkaClient.securityManager.sign(testData)
        assertNotNull(signature)

        val publicKey = asinkaClient.securityManager.getPublicKey()
        assertNotNull(publicKey)

        val isValid = asinkaClient.securityManager.verify(testData, signature, publicKey!!)
        assertTrue(isValid)
    }

    @Test
    fun testSyncManagerObjectRegistration() = runBlocking {
        val obj = SyncableObjectData(
            objectId = "test-obj-1",
            objectType = "TestObject",
            version = 1,
            fields = mutableMapOf(
                "name" to "Test Object",
                "value" to 42
            )
        )

        asinkaClient.syncManager.registerObject(obj)

        val retrieved = asinkaClient.syncManager.getObject("test-obj-1")
        assertNotNull(retrieved)
        assertEquals("test-obj-1", retrieved?.objectId)
    }

    @Test
    fun testSyncManagerObjectUpdate() = runBlocking {
        val obj = SyncableObjectData(
            objectId = "test-obj-2",
            objectType = "TestObject",
            version = 1,
            fields = mutableMapOf("name" to "Original", "value" to 1)
        )

        asinkaClient.syncManager.registerObject(obj)

        asinkaClient.syncManager.updateObject("test-obj-2", mapOf(
            "name" to "Updated",
            "value" to 2
        ))

        val updated = asinkaClient.syncManager.getObject("test-obj-2") as SyncableObjectData
        assertEquals("Updated", updated.fields["name"])
        assertEquals(2, updated.fields["value"])
    }

    @Test
    fun testEventManagerSendEvent() = runBlocking {
        val event = AsinkaEvent(
            eventType = "test-event",
            data = mapOf(
                "message" to "Test message",
                "priority" to 1
            ),
            priority = EventPriority.NORMAL
        )

        asinkaClient.eventManager.sendEvent(event)
    }

    @Test
    fun testEventManagerEventReceiver() = runBlocking {
        val receivedEvents = mutableListOf<AsinkaEvent>()

        val receiver = object : digital.vasic.asinka.events.AsinkaEventReceiver() {
            override val eventTypes = listOf("test-receiver-event")

            override suspend fun handleEvent(event: AsinkaEvent) {
                receivedEvents.add(event)
            }
        }

        asinkaClient.eventManager.registerEventReceiver(receiver)

        val event = AsinkaEvent(
            eventType = "test-receiver-event",
            data = mapOf("test" to "data")
        )

        val protoEvent = event.toProto("session-test")
        asinkaClient.eventManager.processRemoteEvent(protoEvent)

        delay(200)

        assertTrue(receivedEvents.isNotEmpty())
        assertEquals("test-receiver-event", receivedEvents[0].eventType)

        asinkaClient.eventManager.unregisterEventReceiver(receiver)
    }

    @Test
    fun testHandshakeManagerCreateRequest() = runBlocking {
        val request = asinkaClient.handshakeManager.createHandshakeRequest(
            appId = asinkaClient.config.appId,
            appName = asinkaClient.config.appName,
            appVersion = asinkaClient.config.appVersion,
            deviceId = asinkaClient.config.deviceId,
            exposedSchemas = asinkaClient.config.exposedSchemas,
            capabilities = asinkaClient.config.capabilities
        )

        assertEquals(asinkaClient.config.appId, request.appId)
        assertEquals(asinkaClient.config.appName, request.appName)
        assertFalse(request.publicKey.isEmpty)
    }

    @Test
    fun testGetSessionsEmpty() {
        val sessions = asinkaClient.getSessions()
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testObjectSchemaConversion() {
        val schema = ObjectSchema(
            objectType = "TestType",
            version = "1.0",
            fields = listOf(
                FieldSchema("field1", FieldType.STRING),
                FieldSchema("field2", FieldType.INT)
            ),
            permissions = listOf("read", "write")
        )

        val proto = schema.toProto()
        val reconstructed = ObjectSchema.fromProto(proto)

        assertEquals(schema.objectType, reconstructed.objectType)
        assertEquals(schema.version, reconstructed.version)
        assertEquals(schema.fields.size, reconstructed.fields.size)
        assertEquals(schema.permissions, reconstructed.permissions)
    }

    @Test
    fun testSyncableObjectDataFieldTypes() {
        val obj = SyncableObjectData(
            objectId = "multi-type",
            objectType = "Test",
            version = 1,
            fields = mutableMapOf(
                "string" to "text",
                "int" to 42,
                "long" to 100L,
                "double" to 3.14,
                "bool" to true,
                "bytes" to byteArrayOf(1, 2, 3)
            )
        )

        val fieldMap = obj.toFieldMap()
        assertEquals("text", fieldMap["string"])
        assertEquals(42, fieldMap["int"])
        assertEquals(100L, fieldMap["long"])
        assertEquals(3.14, fieldMap["double"])
        assertEquals(true, fieldMap["bool"])
        assertArrayEquals(byteArrayOf(1, 2, 3), fieldMap["bytes"] as ByteArray)
    }
}