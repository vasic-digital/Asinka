package digital.vasic.asinka

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import digital.vasic.asinka.models.FieldSchema
import digital.vasic.asinka.models.FieldType
import digital.vasic.asinka.models.ObjectSchema
import digital.vasic.asinka.models.SyncableObjectData
import digital.vasic.asinka.sync.SyncChange
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive automation tests for Asinka sync mechanisms.
 * Validates real-time synchronization between multiple app instances.
 */
@RunWith(AndroidJUnit4::class)
class SyncMechanismAutomationTest {

    private lateinit var context: Context
    private lateinit var serverApp: AsinkaClient
    private lateinit var clientApp: AsinkaClient

    private val testTimeout = 10000L // 10 seconds
    private val serverPort = 9800

    // Test schemas
    private val userSchema = ObjectSchema(
        objectType = "User",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("name", FieldType.STRING),
            FieldSchema("email", FieldType.STRING),
            FieldSchema("age", FieldType.INT),
            FieldSchema("isActive", FieldType.BOOL),
            FieldSchema("lastLogin", FieldType.LONG)
        ),
        permissions = listOf("read", "write", "sync")
    )

    private val documentSchema = ObjectSchema(
        objectType = "Document",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("title", FieldType.STRING),
            FieldSchema("content", FieldType.STRING),
            FieldSchema("size", FieldType.INT),
            FieldSchema("lastModified", FieldType.LONG)
        ),
        permissions = listOf("read", "write", "sync")
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Server app configuration
        val serverConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.sync.server",
            appName = "Sync Test Server",
            appVersion = "1.0.0",
            serverPort = serverPort,
            exposedSchemas = listOf(userSchema, documentSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "real_time" to "enabled"
            )
        )

        // Client app configuration
        val clientConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.sync.client",
            appName = "Sync Test Client",
            appVersion = "1.0.0",
            serverPort = serverPort + 1,
            exposedSchemas = listOf(userSchema, documentSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "real_time" to "enabled"
            )
        )

        serverApp = AsinkaClient.create(context, serverConfig)
        clientApp = AsinkaClient.create(context, clientConfig)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            serverApp.stop()
            clientApp.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun testBasicObjectSync() = runTest {
        val syncReceived = AtomicBoolean(false)
        val syncedObject = AtomicReference<SyncableObjectData>()

        // Monitor sync changes on client
        launch {
            clientApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectId == "basic_user") {
                    syncedObject.set(change.obj as SyncableObjectData)
                    syncReceived.set(true)
                }
            }
        }

        // Start server and connect client
        serverApp.start()
        delay(200)

        val connectionResult = clientApp.connect("localhost", serverPort)
        assertTrue("Connection failed", connectionResult.isSuccess)
        delay(500)

        // Create and register object on server
        val user = createTestUser("basic_user", "John Doe", "john@test.com", 30)
        serverApp.syncManager.registerObject(user)

        // Wait for sync
        val success = waitForCondition(syncReceived, testTimeout)
        assertTrue("Basic sync failed", success)

        // Verify synced data
        val synced = syncedObject.get()
        assertNotNull("Synced object is null", synced)
        assertEquals("John Doe", synced.fields["name"])
        assertEquals("john@test.com", synced.fields["email"])
        assertEquals(30, synced.fields["age"])
    }

    @Test
    fun testBidirectionalSync() = runTest {
        val serverSyncCount = AtomicInteger(0)
        val clientSyncCount = AtomicInteger(0)
        val bidirectionalComplete = AtomicBoolean(false)

        // Monitor server syncs
        launch {
            serverApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectId.startsWith("client_")) {
                    val count = serverSyncCount.incrementAndGet()
                    if (count >= 1 && clientSyncCount.get() >= 1) {
                        bidirectionalComplete.set(true)
                    }
                }
            }
        }

        // Monitor client syncs
        launch {
            clientApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectId.startsWith("server_")) {
                    val count = clientSyncCount.incrementAndGet()
                    if (count >= 1 && serverSyncCount.get() >= 1) {
                        bidirectionalComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create objects on both sides
        val serverUser = createTestUser("server_user", "Server User", "server@test.com", 25)
        val clientUser = createTestUser("client_user", "Client User", "client@test.com", 35)

        serverApp.syncManager.registerObject(serverUser)
        delay(100)
        clientApp.syncManager.registerObject(clientUser)

        // Wait for bidirectional sync
        val success = waitForCondition(bidirectionalComplete, testTimeout)
        assertTrue("Bidirectional sync failed", success)
        assertTrue("Server didn't receive client object", serverSyncCount.get() >= 1)
        assertTrue("Client didn't receive server object", clientSyncCount.get() >= 1)
    }

    @Test
    fun testRealTimeObjectUpdate() = runTest {
        val updateCount = AtomicInteger(0)
        val latestValue = AtomicReference<String>()
        val updatesComplete = AtomicBoolean(false)

        // Monitor updates on client
        launch {
            clientApp.syncManager.observeObject("realtime_user").collect { obj ->
                val data = obj as SyncableObjectData
                updateCount.incrementAndGet()
                latestValue.set(data.fields["name"] as String)
                if (updateCount.get() >= 5) {
                    updatesComplete.set(true)
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create initial object
        val user = createTestUser("realtime_user", "Initial Name", "user@test.com", 30)
        serverApp.syncManager.registerObject(user)
        delay(300)

        // Perform rapid updates
        repeat(5) { i ->
            serverApp.syncManager.updateObject("realtime_user", mapOf(
                "name" to "Updated Name $i",
                "age" to 30 + i
            ))
            delay(100)
        }

        // Wait for all updates
        val success = waitForCondition(updatesComplete, testTimeout)
        assertTrue("Real-time updates failed", success)
        assertEquals("Updated Name 4", latestValue.get())
        assertTrue("Not enough updates received", updateCount.get() >= 5)
    }

    @Test
    fun testObjectDeletion() = runTest {
        val objectCreated = AtomicBoolean(false)
        val objectDeleted = AtomicBoolean(false)
        val deletedObjectId = AtomicReference<String>()

        // Monitor changes on client
        launch {
            clientApp.syncManager.observeAllChanges().collect { change ->
                when (change) {
                    is SyncChange.Updated -> {
                        if (change.obj.objectId == "delete_test_user") {
                            objectCreated.set(true)
                        }
                    }
                    is SyncChange.Deleted -> {
                        if (change.objectId == "delete_test_user") {
                            deletedObjectId.set(change.objectId)
                            objectDeleted.set(true)
                        }
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create object
        val user = createTestUser("delete_test_user", "Delete Test", "delete@test.com", 25)
        serverApp.syncManager.registerObject(user)

        // Wait for creation sync
        waitForCondition(objectCreated, testTimeout)
        assertTrue("Object creation not synced", objectCreated.get())

        // Verify object exists on client
        assertNotNull("Object not found on client", clientApp.syncManager.getObject("delete_test_user"))

        // Delete object on server
        serverApp.syncManager.deleteObject("delete_test_user")

        // Wait for deletion sync
        val success = waitForCondition(objectDeleted, testTimeout)
        assertTrue("Object deletion not synced", success)
        assertEquals("delete_test_user", deletedObjectId.get())

        // Verify object is deleted on client
        assertNull("Object still exists on client", clientApp.syncManager.getObject("delete_test_user"))
    }

    @Test
    fun testMultipleObjectTypes() = runTest {
        val userSynced = AtomicBoolean(false)
        val documentSynced = AtomicBoolean(false)
        val allTypesSynced = AtomicBoolean(false)

        // Monitor different object types
        launch {
            clientApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated) {
                    when (change.obj.objectType) {
                        "User" -> {
                            if (change.obj.objectId == "multi_user") {
                                userSynced.set(true)
                            }
                        }
                        "Document" -> {
                            if (change.obj.objectId == "multi_doc") {
                                documentSynced.set(true)
                            }
                        }
                    }

                    if (userSynced.get() && documentSynced.get()) {
                        allTypesSynced.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create different object types
        val user = createTestUser("multi_user", "Multi User", "multi@test.com", 28)
        val document = createTestDocument("multi_doc", "Multi Doc", "Test content", 100)

        serverApp.syncManager.registerObject(user)
        delay(100)
        serverApp.syncManager.registerObject(document)

        // Wait for all types to sync
        val success = waitForCondition(allTypesSynced, testTimeout)
        assertTrue("Multiple object types sync failed", success)
        assertTrue("User not synced", userSynced.get())
        assertTrue("Document not synced", documentSynced.get())

        // Verify objects exist on client
        val syncedUser = clientApp.syncManager.getObject("multi_user") as? SyncableObjectData
        val syncedDoc = clientApp.syncManager.getObject("multi_doc") as? SyncableObjectData

        assertNotNull("User not found on client", syncedUser)
        assertNotNull("Document not found on client", syncedDoc)
        assertEquals("Multi User", syncedUser?.fields?.get("name"))
        assertEquals("Multi Doc", syncedDoc?.fields?.get("title"))
    }

    @Test
    fun testVersionConflictResolution() = runTest {
        val finalVersion = AtomicInteger(0)
        val conflictResolved = AtomicBoolean(false)

        // Monitor version updates on client
        launch {
            clientApp.syncManager.observeObject("version_test").collect { obj ->
                val data = obj as SyncableObjectData
                finalVersion.set(data.version)
                if (data.version >= 3) {
                    conflictResolved.set(true)
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create initial object (version 1)
        val user = createTestUser("version_test", "Version Test", "version@test.com", 30)
        serverApp.syncManager.registerObject(user)
        delay(300)

        // Update to version 2
        serverApp.syncManager.updateObject("version_test", mapOf(
            "name" to "Version 2",
            "age" to 31
        ))
        delay(200)

        // Update to version 3
        serverApp.syncManager.updateObject("version_test", mapOf(
            "name" to "Version 3",
            "age" to 32
        ))

        // Wait for conflict resolution
        val success = waitForCondition(conflictResolved, testTimeout)
        assertTrue("Version conflict resolution failed", success)
        assertTrue("Final version incorrect", finalVersion.get() >= 3)

        // Verify final state
        val finalUser = clientApp.syncManager.getObject("version_test") as? SyncableObjectData
        assertNotNull("Final user not found", finalUser)
        assertEquals("Version 3", finalUser?.fields?.get("name"))
        assertEquals(32, finalUser?.fields?.get("age"))
    }

    @Test
    fun testHighVolumeSync() = runTest {
        val syncCount = AtomicInteger(0)
        val highVolumeSyncComplete = AtomicBoolean(false)
        val objectCount = 50

        // Monitor high volume sync
        launch {
            clientApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectId.startsWith("volume_")) {
                    val count = syncCount.incrementAndGet()
                    if (count >= objectCount) {
                        highVolumeSyncComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create high volume of objects
        launch {
            repeat(objectCount) { i ->
                val user = createTestUser("volume_user_$i", "Volume User $i", "volume$i@test.com", 20 + i)
                serverApp.syncManager.registerObject(user)
                if (i % 10 == 0) delay(50) // Brief pause every 10 objects
            }
        }

        // Wait for high volume sync
        val success = waitForCondition(highVolumeSyncComplete, testTimeout * 3)
        assertTrue("High volume sync failed", success)
        assertEquals("Not all objects synced", objectCount, syncCount.get())

        // Verify random objects
        val user5 = clientApp.syncManager.getObject("volume_user_5") as? SyncableObjectData
        val user25 = clientApp.syncManager.getObject("volume_user_25") as? SyncableObjectData

        assertNotNull("User 5 not synced", user5)
        assertNotNull("User 25 not synced", user25)
        assertEquals("Volume User 5", user5?.fields?.get("name"))
        assertEquals("Volume User 25", user25?.fields?.get("name"))
    }

    @Test
    fun testSyncPerformanceMeasurement() = runTest {
        val syncStartTime = AtomicReference<Long>()
        val syncEndTime = AtomicReference<Long>()
        val performanceTestComplete = AtomicBoolean(false)
        val testObjectCount = 20

        // Monitor sync performance
        launch {
            var syncedCount = 0
            clientApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectId.startsWith("perf_")) {
                    syncedCount++
                    if (syncedCount == 1) {
                        syncStartTime.set(System.currentTimeMillis())
                    }
                    if (syncedCount >= testObjectCount) {
                        syncEndTime.set(System.currentTimeMillis())
                        performanceTestComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(200)
        clientApp.connect("localhost", serverPort)
        delay(500)

        // Create batch of objects for performance test
        val batchStartTime = System.currentTimeMillis()
        repeat(testObjectCount) { i ->
            val user = createTestUser("perf_user_$i", "Perf User $i", "perf$i@test.com", 25 + i)
            serverApp.syncManager.registerObject(user)
        }

        // Wait for performance test completion
        val success = waitForCondition(performanceTestComplete, testTimeout)
        assertTrue("Performance test failed", success)

        // Calculate and verify performance metrics
        val totalSyncTime = syncEndTime.get() - syncStartTime.get()
        val objectsPerSecond = (testObjectCount * 1000) / totalSyncTime.toDouble()

        println("Sync Performance: $testObjectCount objects in ${totalSyncTime}ms (${String.format("%.2f", objectsPerSecond)} objects/sec)")

        // Performance assertions
        assertTrue("Sync took too long: ${totalSyncTime}ms", totalSyncTime < 10000) // Under 10 seconds
        assertTrue("Sync rate too slow: ${String.format("%.2f", objectsPerSecond)} objects/sec", objectsPerSecond > 1.0)
    }

    // Helper functions
    private fun createTestUser(id: String, name: String, email: String, age: Int): SyncableObjectData {
        return SyncableObjectData(
            objectId = id,
            objectType = "User",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "name" to name,
                "email" to email,
                "age" to age,
                "isActive" to true,
                "lastLogin" to System.currentTimeMillis()
            )
        )
    }

    private fun createTestDocument(id: String, title: String, content: String, size: Int): SyncableObjectData {
        return SyncableObjectData(
            objectId = id,
            objectType = "Document",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "title" to title,
                "content" to content,
                "size" to size,
                "lastModified" to System.currentTimeMillis()
            )
        )
    }

    private suspend fun waitForCondition(condition: AtomicBoolean, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!condition.get()) {
                delay(50)
            }
            true
        } ?: false
    }
}