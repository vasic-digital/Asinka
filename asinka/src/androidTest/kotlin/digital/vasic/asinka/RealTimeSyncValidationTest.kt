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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Specialized tests for real-time synchronization validation.
 * Tests instant propagation, timing, and consistency of real-time sync.
 */
@RunWith(AndroidJUnit4::class)
class RealTimeSyncValidationTest {

    private lateinit var context: Context
    private lateinit var primaryApp: AsinkaClient
    private lateinit var secondaryApp: AsinkaClient

    private val testTimeout = 15000L // 15 seconds for real-time tests
    private val basePort = 9820

    private val chatMessageSchema = ObjectSchema(
        objectType = "ChatMessage",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("sender", FieldType.STRING),
            FieldSchema("message", FieldType.STRING),
            FieldSchema("timestamp", FieldType.LONG),
            FieldSchema("roomId", FieldType.STRING),
            FieldSchema("isEdited", FieldType.BOOL)
        ),
        permissions = listOf("read", "write", "sync", "realtime")
    )

    private val liveDocumentSchema = ObjectSchema(
        objectType = "LiveDocument",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("content", FieldType.STRING),
            FieldSchema("cursorPosition", FieldType.INT),
            FieldSchema("lastEdit", FieldType.LONG),
            FieldSchema("editedBy", FieldType.STRING),
            FieldSchema("revision", FieldType.INT)
        ),
        permissions = listOf("read", "write", "sync", "realtime")
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        val primaryConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.realtime.primary",
            appName = "RealTime Primary",
            appVersion = "1.0.0",
            serverPort = basePort,
            exposedSchemas = listOf(chatMessageSchema, liveDocumentSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "realtime" to "enabled",
                "instant_propagation" to "true"
            )
        )

        val secondaryConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.realtime.secondary",
            appName = "RealTime Secondary",
            appVersion = "1.0.0",
            serverPort = basePort + 1,
            exposedSchemas = listOf(chatMessageSchema, liveDocumentSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "realtime" to "enabled",
                "instant_propagation" to "true"
            )
        )

        primaryApp = AsinkaClient.create(context, primaryConfig)
        secondaryApp = AsinkaClient.create(context, secondaryConfig)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            primaryApp.stop()
            secondaryApp.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun testInstantMessageSync() = runTest {
        val messagesReceived = ConcurrentHashMap<String, ChatMessage>()
        val instantSyncComplete = AtomicBoolean(false)
        val messageCount = 10

        // Monitor incoming messages on secondary
        launch {
            secondaryApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectType == "ChatMessage") {
                    val msg = ChatMessage.fromSyncableObject(change.obj as SyncableObjectData)
                    messagesReceived[msg.id] = msg

                    if (messagesReceived.size >= messageCount) {
                        instantSyncComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Send rapid messages (simulating real chat)
        val startTime = System.currentTimeMillis()
        repeat(messageCount) { i ->
            val message = ChatMessage(
                id = "msg_$i",
                sender = "user1",
                message = "Real-time message $i",
                timestamp = System.currentTimeMillis(),
                roomId = "room1"
            )

            primaryApp.syncManager.registerObject(message.toSyncableObject())
            delay(50) // 50ms between messages (realistic chat speed)
        }

        // Wait for instant sync
        val success = waitForCondition(instantSyncComplete, testTimeout)
        val totalTime = System.currentTimeMillis() - startTime

        assertTrue("Instant message sync failed", success)
        assertEquals("Not all messages received", messageCount, messagesReceived.size)

        // Verify message order and content
        repeat(messageCount) { i ->
            val msg = messagesReceived["msg_$i"]
            assertNotNull("Message $i not received", msg)
            assertEquals("Real-time message $i", msg?.message)
        }

        println("Instant sync: $messageCount messages in ${totalTime}ms")
        assertTrue("Messages took too long to sync", totalTime < 2000) // Under 2 seconds
    }

    @Test
    fun testLiveDocumentEditing() = runTest {
        val documentUpdates = mutableListOf<LiveDocument>()
        val liveEditingComplete = AtomicBoolean(false)
        val expectedUpdates = 15

        // Monitor live document updates
        launch {
            secondaryApp.syncManager.observeObject("live_doc_1").collect { obj ->
                val doc = LiveDocument.fromSyncableObject(obj as SyncableObjectData)
                documentUpdates.add(doc)

                if (documentUpdates.size >= expectedUpdates) {
                    liveEditingComplete.set(true)
                }
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Create initial document
        val initialDoc = LiveDocument(
            id = "live_doc_1",
            content = "",
            cursorPosition = 0,
            editedBy = "user1",
            revision = 1
        )
        primaryApp.syncManager.registerObject(initialDoc.toSyncableObject())
        delay(200)

        // Simulate live typing (character by character)
        val text = "Hello real-time!"
        text.forEachIndexed { index, char ->
            val newContent = text.substring(0, index + 1)
            primaryApp.syncManager.updateObject("live_doc_1", mapOf(
                "content" to newContent,
                "cursorPosition" to index + 1,
                "lastEdit" to System.currentTimeMillis(),
                "revision" to index + 2
            ))
            delay(100) // 100ms per character (realistic typing speed)
        }

        // Wait for live editing completion
        val success = waitForCondition(liveEditingComplete, testTimeout)
        assertTrue("Live document editing failed", success)
        assertTrue("Not enough updates received", documentUpdates.size >= expectedUpdates)

        // Verify progressive content updates
        val finalDoc = documentUpdates.last()
        assertEquals("Hello real-time!", finalDoc.content)
        assertEquals(text.length, finalDoc.cursorPosition)
        assertTrue("Revision not updated", finalDoc.revision > 1)
    }

    @Test
    fun testRapidFireUpdates() = runTest {
        val updateTimestamps = mutableListOf<Long>()
        val rapidUpdatesComplete = AtomicBoolean(false)
        val updateCount = 50

        // Monitor rapid updates
        launch {
            secondaryApp.syncManager.observeObject("rapid_counter").collect { obj ->
                val data = obj as SyncableObjectData
                updateTimestamps.add(System.currentTimeMillis())

                val counter = data.fields["counter"] as Int
                if (counter >= updateCount) {
                    rapidUpdatesComplete.set(true)
                }
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Create initial counter
        val counter = SyncableObjectData(
            objectId = "rapid_counter",
            objectType = "Counter",
            version = 1,
            fields = mutableMapOf(
                "counter" to 0,
                "lastUpdate" to System.currentTimeMillis()
            )
        )
        primaryApp.syncManager.registerObject(counter)
        delay(200)

        // Rapid fire updates (every 20ms)
        val startTime = System.currentTimeMillis()
        repeat(updateCount) { i ->
            primaryApp.syncManager.updateObject("rapid_counter", mapOf(
                "counter" to i + 1,
                "lastUpdate" to System.currentTimeMillis()
            ))
            delay(20) // 20ms intervals = 50 updates per second
        }

        // Wait for rapid updates completion
        val success = waitForCondition(rapidUpdatesComplete, testTimeout)
        val totalTime = System.currentTimeMillis() - startTime

        assertTrue("Rapid fire updates failed", success)
        assertTrue("Not enough updates received", updateTimestamps.size >= updateCount * 0.8) // Allow 20% loss

        // Analyze update intervals
        val intervals = updateTimestamps.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average()
        val maxInterval = intervals.maxOrNull() ?: 0L

        println("Rapid updates: ${updateTimestamps.size} updates in ${totalTime}ms")
        println("Average interval: ${String.format("%.2f", avgInterval)}ms, Max interval: ${maxInterval}ms")

        assertTrue("Average interval too high", avgInterval < 200) // Under 200ms average
        assertTrue("Max interval too high", maxInterval < 1000) // No update took more than 1 second
    }

    @Test
    fun testConcurrentRealTimeEditing() = runTest {
        val primaryUpdates = AtomicInteger(0)
        val secondaryUpdates = AtomicInteger(0)
        val concurrentEditingComplete = AtomicBoolean(false)

        // Monitor updates on both apps
        launch {
            primaryApp.syncManager.observeObject("concurrent_doc").collect { obj ->
                val doc = obj as SyncableObjectData
                val editedBy = doc.fields["editedBy"] as String
                if (editedBy == "secondary") {
                    primaryUpdates.incrementAndGet()
                }
                checkConcurrentComplete()
            }
        }

        launch {
            secondaryApp.syncManager.observeObject("concurrent_doc").collect { obj ->
                val doc = obj as SyncableObjectData
                val editedBy = doc.fields["editedBy"] as String
                if (editedBy == "primary") {
                    secondaryUpdates.incrementAndGet()
                }
                checkConcurrentComplete()
            }
        }

        fun checkConcurrentComplete() {
            if (primaryUpdates.get() >= 5 && secondaryUpdates.get() >= 5) {
                concurrentEditingComplete.set(true)
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Create shared document
        val sharedDoc = LiveDocument(
            id = "concurrent_doc",
            content = "Shared document",
            cursorPosition = 0,
            editedBy = "system",
            revision = 1
        )
        primaryApp.syncManager.registerObject(sharedDoc.toSyncableObject())
        delay(300)

        // Concurrent editing from both apps
        launch {
            repeat(5) { i ->
                primaryApp.syncManager.updateObject("concurrent_doc", mapOf(
                    "content" to "Primary edit $i",
                    "editedBy" to "primary",
                    "lastEdit" to System.currentTimeMillis(),
                    "revision" to i + 10
                ))
                delay(150)
            }
        }

        launch {
            delay(75) // Offset to create overlap
            repeat(5) { i ->
                secondaryApp.syncManager.updateObject("concurrent_doc", mapOf(
                    "content" to "Secondary edit $i",
                    "editedBy" to "secondary",
                    "lastEdit" to System.currentTimeMillis(),
                    "revision" to i + 20
                ))
                delay(150)
            }
        }

        // Wait for concurrent editing completion
        val success = waitForCondition(concurrentEditingComplete, testTimeout)
        assertTrue("Concurrent real-time editing failed", success)
        assertTrue("Primary didn't receive secondary updates", primaryUpdates.get() >= 5)
        assertTrue("Secondary didn't receive primary updates", secondaryUpdates.get() >= 5)
    }

    @Test
    fun testSyncLatencyMeasurement() = runTest {
        val latencies = mutableListOf<Long>()
        val latencyTestComplete = AtomicBoolean(false)
        val testCount = 20

        // Measure sync latency
        launch {
            secondaryApp.syncManager.observeObject("latency_test").collect { obj ->
                val data = obj as SyncableObjectData
                val sentTime = data.fields["sentTime"] as Long
                val receivedTime = System.currentTimeMillis()
                val latency = receivedTime - sentTime

                latencies.add(latency)

                if (latencies.size >= testCount) {
                    latencyTestComplete.set(true)
                }
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Create initial object
        val testObj = SyncableObjectData(
            objectId = "latency_test",
            objectType = "LatencyTest",
            version = 1,
            fields = mutableMapOf(
                "counter" to 0,
                "sentTime" to System.currentTimeMillis()
            )
        )
        primaryApp.syncManager.registerObject(testObj)
        delay(200)

        // Send latency test updates
        repeat(testCount) { i ->
            val sentTime = System.currentTimeMillis()
            primaryApp.syncManager.updateObject("latency_test", mapOf(
                "counter" to i + 1,
                "sentTime" to sentTime
            ))
            delay(200) // 200ms between tests
        }

        // Wait for latency test completion
        val success = waitForCondition(latencyTestComplete, testTimeout)
        assertTrue("Sync latency test failed", success)

        // Analyze latencies
        val avgLatency = latencies.average()
        val minLatency = latencies.minOrNull() ?: 0L
        val maxLatency = latencies.maxOrNull() ?: 0L
        val p95Latency = latencies.sorted()[kotlin.math.ceil(latencies.size * 0.95).toInt() - 1]

        println("Sync Latency Statistics:")
        println("  Average: ${String.format("%.2f", avgLatency)}ms")
        println("  Min: ${minLatency}ms")
        println("  Max: ${maxLatency}ms")
        println("  95th percentile: ${p95Latency}ms")

        // Performance assertions
        assertTrue("Average latency too high: ${String.format("%.2f", avgLatency)}ms", avgLatency < 500)
        assertTrue("95th percentile latency too high: ${p95Latency}ms", p95Latency < 1000)
        assertTrue("Max latency too high: ${maxLatency}ms", maxLatency < 2000)
    }

    @Test
    fun testRealTimeConnectionRecovery() = runTest {
        val preDisconnectUpdates = AtomicInteger(0)
        val postReconnectUpdates = AtomicInteger(0)
        val recoveryComplete = AtomicBoolean(false)

        // Monitor updates through disconnection/reconnection
        launch {
            secondaryApp.syncManager.observeObject("recovery_test").collect { obj ->
                val data = obj as SyncableObjectData
                val phase = data.fields["phase"] as String

                when (phase) {
                    "pre_disconnect" -> preDisconnectUpdates.incrementAndGet()
                    "post_reconnect" -> {
                        postReconnectUpdates.incrementAndGet()
                        if (postReconnectUpdates.get() >= 3) {
                            recoveryComplete.set(true)
                        }
                    }
                }
            }
        }

        // Start communication
        primaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Create test object
        val testObj = SyncableObjectData(
            objectId = "recovery_test",
            objectType = "RecoveryTest",
            version = 1,
            fields = mutableMapOf(
                "counter" to 0,
                "phase" to "pre_disconnect",
                "timestamp" to System.currentTimeMillis()
            )
        )
        primaryApp.syncManager.registerObject(testObj)
        delay(200)

        // Send some updates before disconnection
        repeat(3) { i ->
            primaryApp.syncManager.updateObject("recovery_test", mapOf(
                "counter" to i + 1,
                "phase" to "pre_disconnect",
                "timestamp" to System.currentTimeMillis()
            ))
            delay(100)
        }

        delay(300)

        // Simulate disconnection
        secondaryApp.stop()
        delay(500)

        // Continue updates while disconnected
        repeat(2) { i ->
            primaryApp.syncManager.updateObject("recovery_test", mapOf(
                "counter" to i + 10,
                "phase" to "during_disconnect",
                "timestamp" to System.currentTimeMillis()
            ))
            delay(100)
        }

        // Reconnect
        secondaryApp.start()
        delay(200)
        secondaryApp.connect("localhost", basePort)
        delay(500)

        // Send updates after reconnection
        repeat(3) { i ->
            primaryApp.syncManager.updateObject("recovery_test", mapOf(
                "counter" to i + 20,
                "phase" to "post_reconnect",
                "timestamp" to System.currentTimeMillis()
            ))
            delay(100)
        }

        // Wait for recovery completion
        val success = waitForCondition(recoveryComplete, testTimeout)
        assertTrue("Real-time connection recovery failed", success)
        assertTrue("Pre-disconnect updates not received", preDisconnectUpdates.get() >= 3)
        assertTrue("Post-reconnect updates not received", postReconnectUpdates.get() >= 3)
    }

    // Helper Classes
    data class ChatMessage(
        val id: String,
        val sender: String,
        val message: String,
        val timestamp: Long,
        val roomId: String,
        val isEdited: Boolean = false
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "ChatMessage",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "sender" to sender,
                "message" to message,
                "timestamp" to timestamp,
                "roomId" to roomId,
                "isEdited" to isEdited
            )
        )

        companion object {
            fun fromSyncableObject(obj: SyncableObjectData): ChatMessage {
                val fields = obj.fields
                return ChatMessage(
                    id = fields["id"] as String,
                    sender = fields["sender"] as String,
                    message = fields["message"] as String,
                    timestamp = fields["timestamp"] as Long,
                    roomId = fields["roomId"] as String,
                    isEdited = fields["isEdited"] as Boolean
                )
            }
        }
    }

    data class LiveDocument(
        val id: String,
        val content: String,
        val cursorPosition: Int,
        val lastEdit: Long = System.currentTimeMillis(),
        val editedBy: String,
        val revision: Int
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "LiveDocument",
            version = revision,
            fields = mutableMapOf(
                "id" to id,
                "content" to content,
                "cursorPosition" to cursorPosition,
                "lastEdit" to lastEdit,
                "editedBy" to editedBy,
                "revision" to revision
            )
        )

        companion object {
            fun fromSyncableObject(obj: SyncableObjectData): LiveDocument {
                val fields = obj.fields
                return LiveDocument(
                    id = fields["id"] as String,
                    content = fields["content"] as String,
                    cursorPosition = fields["cursorPosition"] as Int,
                    lastEdit = fields["lastEdit"] as Long,
                    editedBy = fields["editedBy"] as String,
                    revision = fields["revision"] as Int
                )
            }
        }
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