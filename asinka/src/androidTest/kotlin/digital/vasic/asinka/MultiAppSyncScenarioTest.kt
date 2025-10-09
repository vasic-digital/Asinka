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

/**
 * Tests complex multi-app synchronization scenarios with 3+ apps.
 * Validates mesh networking, broadcast propagation, and complex sync patterns.
 */
@RunWith(AndroidJUnit4::class)
class MultiAppSyncScenarioTest {

    private lateinit var context: Context
    private lateinit var serverApp: AsinkaClient
    private lateinit var clientApp1: AsinkaClient
    private lateinit var clientApp2: AsinkaClient
    private lateinit var clientApp3: AsinkaClient

    private val testTimeout = 15000L
    private val basePort = 9840

    private val sharedDataSchema = ObjectSchema(
        objectType = "SharedData",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("value", FieldType.STRING),
            FieldSchema("source", FieldType.STRING),
            FieldSchema("timestamp", FieldType.LONG),
            FieldSchema("priority", FieldType.INT)
        ),
        permissions = listOf("read", "write", "sync", "broadcast")
    )

    private val teamTaskSchema = ObjectSchema(
        objectType = "TeamTask",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("title", FieldType.STRING),
            FieldSchema("assignee", FieldType.STRING),
            FieldSchema("status", FieldType.STRING),
            FieldSchema("priority", FieldType.INT),
            FieldSchema("lastUpdate", FieldType.LONG),
            FieldSchema("updatedBy", FieldType.STRING)
        ),
        permissions = listOf("read", "write", "sync", "broadcast")
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Server app (central hub)
        val serverConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.multi.server",
            appName = "Multi-App Server",
            appVersion = "1.0.0",
            serverPort = basePort,
            exposedSchemas = listOf(sharedDataSchema, teamTaskSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "broadcast" to "enabled",
                "mesh_networking" to "enabled"
            )
        )

        // Client apps
        val client1Config = AsinkaConfig(
            appId = "digital.vasic.asinka.multi.client1",
            appName = "Multi-App Client 1",
            appVersion = "1.0.0",
            serverPort = basePort + 1,
            exposedSchemas = listOf(sharedDataSchema, teamTaskSchema),
            capabilities = mapOf("sync" to "enabled", "broadcast" to "enabled")
        )

        val client2Config = AsinkaConfig(
            appId = "digital.vasic.asinka.multi.client2",
            appName = "Multi-App Client 2",
            appVersion = "1.0.0",
            serverPort = basePort + 2,
            exposedSchemas = listOf(sharedDataSchema, teamTaskSchema),
            capabilities = mapOf("sync" to "enabled", "broadcast" to "enabled")
        )

        val client3Config = AsinkaConfig(
            appId = "digital.vasic.asinka.multi.client3",
            appName = "Multi-App Client 3",
            appVersion = "1.0.0",
            serverPort = basePort + 3,
            exposedSchemas = listOf(sharedDataSchema, teamTaskSchema),
            capabilities = mapOf("sync" to "enabled", "broadcast" to "enabled")
        )

        serverApp = AsinkaClient.create(context, serverConfig)
        clientApp1 = AsinkaClient.create(context, client1Config)
        clientApp2 = AsinkaClient.create(context, client2Config)
        clientApp3 = AsinkaClient.create(context, client3Config)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            serverApp.stop()
            clientApp1.stop()
            clientApp2.stop()
            clientApp3.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun testThreeWayBroadcastSync() = runTest {
        val client1Syncs = ConcurrentHashMap<String, SharedData>()
        val client2Syncs = ConcurrentHashMap<String, SharedData>()
        val client3Syncs = ConcurrentHashMap<String, SharedData>()
        val broadcastComplete = AtomicBoolean(false)

        fun checkBroadcastComplete() {
            if (client1Syncs.size >= 3 && client2Syncs.size >= 3 && client3Syncs.size >= 3) {
                broadcastComplete.set(true)
            }
        }

        // Monitor syncs on all clients
        launch {
            clientApp1.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectType == "SharedData") {
                    val data = SharedData.fromSyncableObject(change.obj as SyncableObjectData)
                    client1Syncs[data.id] = data
                    checkBroadcastComplete()
                }
            }
        }

        launch {
            clientApp2.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectType == "SharedData") {
                    val data = SharedData.fromSyncableObject(change.obj as SyncableObjectData)
                    client2Syncs[data.id] = data
                    checkBroadcastComplete()
                }
            }
        }

        launch {
            clientApp3.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated && change.obj.objectType == "SharedData") {
                    val data = SharedData.fromSyncableObject(change.obj as SyncableObjectData)
                    client3Syncs[data.id] = data
                    checkBroadcastComplete()
                }
            }
        }

        // Start server and connect all clients
        serverApp.start()
        delay(300)

        clientApp1.connect("localhost", basePort)
        delay(200)
        clientApp2.connect("localhost", basePort)
        delay(200)
        clientApp3.connect("localhost", basePort)
        delay(500)

        // Create shared data from server (should broadcast to all clients)
        val data1 = SharedData("broadcast_1", "Server Data 1", "server", priority = 1)
        val data2 = SharedData("broadcast_2", "Server Data 2", "server", priority = 2)
        val data3 = SharedData("broadcast_3", "Server Data 3", "server", priority = 3)

        serverApp.syncManager.registerObject(data1.toSyncableObject())
        delay(200)
        serverApp.syncManager.registerObject(data2.toSyncableObject())
        delay(200)
        serverApp.syncManager.registerObject(data3.toSyncableObject())

        // Wait for three-way broadcast
        val success = waitForCondition(broadcastComplete, testTimeout)
        assertTrue("Three-way broadcast sync failed", success)

        // Verify all clients received all data
        assertEquals("Client 1 missing data", 3, client1Syncs.size)
        assertEquals("Client 2 missing data", 3, client2Syncs.size)
        assertEquals("Client 3 missing data", 3, client3Syncs.size)

        // Verify data consistency across all clients
        listOf("broadcast_1", "broadcast_2", "broadcast_3").forEach { id ->
            val c1Data = client1Syncs[id]
            val c2Data = client2Syncs[id]
            val c3Data = client3Syncs[id]

            assertNotNull("Client 1 missing $id", c1Data)
            assertNotNull("Client 2 missing $id", c2Data)
            assertNotNull("Client 3 missing $id", c3Data)

            assertEquals("Data inconsistent between clients", c1Data?.value, c2Data?.value)
            assertEquals("Data inconsistent between clients", c2Data?.value, c3Data?.value)
        }
    }

    @Test
    fun testRoundRobinSyncPattern() = runTest {
        val serverUpdates = AtomicInteger(0)
        val totalExpectedUpdates = 3 // One from each client
        val roundRobinComplete = AtomicBoolean(false)

        // Monitor updates reaching server
        launch {
            serverApp.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "TeamTask" &&
                    change.obj.objectId == "round_robin_task") {
                    val count = serverUpdates.incrementAndGet()
                    if (count >= totalExpectedUpdates) {
                        roundRobinComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(300)
        clientApp1.connect("localhost", basePort)
        clientApp2.connect("localhost", basePort)
        clientApp3.connect("localhost", basePort)
        delay(500)

        // Create initial task
        val task = TeamTask(
            id = "round_robin_task",
            title = "Round Robin Task",
            assignee = "none",
            status = "created",
            priority = 1,
            updatedBy = "system"
        )
        serverApp.syncManager.registerObject(task.toSyncableObject())
        delay(500)

        // Round robin updates: Client1 -> Client2 -> Client3
        clientApp1.syncManager.updateObject("round_robin_task", mapOf(
            "assignee" to "user1",
            "status" to "assigned",
            "updatedBy" to "client1",
            "lastUpdate" to System.currentTimeMillis()
        ))
        delay(300)

        clientApp2.syncManager.updateObject("round_robin_task", mapOf(
            "status" to "in_progress",
            "updatedBy" to "client2",
            "lastUpdate" to System.currentTimeMillis()
        ))
        delay(300)

        clientApp3.syncManager.updateObject("round_robin_task", mapOf(
            "status" to "completed",
            "updatedBy" to "client3",
            "lastUpdate" to System.currentTimeMillis()
        ))

        // Wait for round robin completion
        val success = waitForCondition(roundRobinComplete, testTimeout)
        assertTrue("Round robin sync pattern failed", success)
        assertEquals("Not all updates received", totalExpectedUpdates, serverUpdates.get())

        // Verify final state
        val finalTask = serverApp.syncManager.getObject("round_robin_task") as? SyncableObjectData
        assertNotNull("Final task not found", finalTask)
        assertEquals("completed", finalTask?.fields?.get("status"))
        assertEquals("client3", finalTask?.fields?.get("updatedBy"))
    }

    @Test
    fun testMeshNetworkingSimulation() = runTest {
        val meshUpdates = ConcurrentHashMap<String, AtomicInteger>()
        val meshComplete = AtomicBoolean(false)
        val expectedMeshUpdates = 4 // Server + 3 clients

        // Initialize counters for each app
        meshUpdates["server"] = AtomicInteger(0)
        meshUpdates["client1"] = AtomicInteger(0)
        meshUpdates["client2"] = AtomicInteger(0)
        meshUpdates["client3"] = AtomicInteger(0)

        // Monitor mesh propagation on all apps
        fun setupMeshMonitoring(app: AsinkaClient, appName: String) {
            launch {
                app.syncManager.observeAllChanges().collect { change ->
                    if (change is SyncChange.Updated &&
                        change.obj.objectType == "SharedData" &&
                        change.obj.objectId.startsWith("mesh_")) {

                        meshUpdates[appName]?.incrementAndGet()

                        // Check if all apps have received updates from all sources
                        val allHaveUpdates = meshUpdates.values.all { it.get() >= expectedMeshUpdates }
                        if (allHaveUpdates) {
                            meshComplete.set(true)
                        }
                    }
                }
            }
        }

        setupMeshMonitoring(serverApp, "server")
        setupMeshMonitoring(clientApp1, "client1")
        setupMeshMonitoring(clientApp2, "client2")
        setupMeshMonitoring(clientApp3, "client3")

        // Start mesh network
        serverApp.start()
        delay(300)
        clientApp1.connect("localhost", basePort)
        clientApp2.connect("localhost", basePort)
        clientApp3.connect("localhost", basePort)
        delay(500)

        // Each app creates data (simulating mesh networking)
        val serverData = SharedData("mesh_server", "From Server", "server", priority = 1)
        val client1Data = SharedData("mesh_client1", "From Client1", "client1", priority = 2)
        val client2Data = SharedData("mesh_client2", "From Client2", "client2", priority = 3)
        val client3Data = SharedData("mesh_client3", "From Client3", "client3", priority = 4)

        // Staggered creation to simulate realistic mesh scenario
        serverApp.syncManager.registerObject(serverData.toSyncableObject())
        delay(200)
        clientApp1.syncManager.registerObject(client1Data.toSyncableObject())
        delay(200)
        clientApp2.syncManager.registerObject(client2Data.toSyncableObject())
        delay(200)
        clientApp3.syncManager.registerObject(client3Data.toSyncableObject())

        // Wait for mesh propagation
        val success = waitForCondition(meshComplete, testTimeout)
        assertTrue("Mesh networking simulation failed", success)

        // Verify all apps received all mesh data
        meshUpdates.forEach { (appName, count) ->
            assertTrue("$appName didn't receive all mesh updates: ${count.get()}",
                     count.get() >= expectedMeshUpdates)
        }

        // Verify data consistency across mesh
        val meshIds = listOf("mesh_server", "mesh_client1", "mesh_client2", "mesh_client3")
        val apps = listOf(serverApp, clientApp1, clientApp2, clientApp3)

        meshIds.forEach { id ->
            apps.forEach { app ->
                val obj = app.syncManager.getObject(id) as? SyncableObjectData
                assertNotNull("Object $id not found on app", obj)
            }
        }
    }

    @Test
    fun testPriorityBasedSyncOrdering() = runTest {
        val priorityUpdates = mutableListOf<PriorityUpdate>()
        val priorityTestComplete = AtomicBoolean(false)

        data class PriorityUpdate(val objectId: String, val priority: Int, val timestamp: Long)

        // Monitor priority-based updates
        launch {
            clientApp1.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "SharedData" &&
                    change.obj.objectId.startsWith("priority_")) {

                    val data = change.obj as SyncableObjectData
                    val priority = data.fields["priority"] as Int
                    priorityUpdates.add(PriorityUpdate(
                        objectId = data.objectId,
                        priority = priority,
                        timestamp = System.currentTimeMillis()
                    ))

                    if (priorityUpdates.size >= 5) {
                        priorityTestComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        serverApp.start()
        delay(300)
        clientApp1.connect("localhost", basePort)
        delay(500)

        // Create data with different priorities (reverse order)
        val priorities = listOf(5, 1, 3, 2, 4)
        priorities.forEachIndexed { index, priority ->
            val data = SharedData(
                id = "priority_$priority",
                value = "Priority $priority Data",
                source = "server",
                priority = priority
            )
            serverApp.syncManager.registerObject(data.toSyncableObject())
            delay(100)
        }

        // Wait for priority-based sync
        val success = waitForCondition(priorityTestComplete, testTimeout)
        assertTrue("Priority-based sync failed", success)

        // Verify all priority updates received
        assertEquals("Not all priority updates received", 5, priorityUpdates.size)

        // Verify objects exist with correct priorities
        (1..5).forEach { priority ->
            val obj = clientApp1.syncManager.getObject("priority_$priority") as? SyncableObjectData
            assertNotNull("Priority $priority object not found", obj)
            assertEquals("Priority mismatch", priority, obj?.fields?.get("priority"))
        }
    }

    @Test
    fun testCascadingUpdatePropagation() = runTest {
        val cascadeSteps = mutableListOf<String>()
        val cascadeComplete = AtomicBoolean(false)

        // Monitor cascading updates across all apps
        fun setupCascadeMonitoring(app: AsinkaClient, appName: String) {
            launch {
                app.syncManager.observeObject("cascade_task").collect { obj ->
                    val data = obj as SyncableObjectData
                    val status = data.fields["status"] as String
                    val updatedBy = data.fields["updatedBy"] as String

                    if (status != "created") {
                        cascadeSteps.add("$appName received: $status from $updatedBy")

                        // Trigger next step in cascade
                        when (appName to status) {
                            "client1" to "assigned" -> {
                                delay(200)
                                app.syncManager.updateObject("cascade_task", mapOf(
                                    "status" to "started",
                                    "updatedBy" to "client1",
                                    "lastUpdate" to System.currentTimeMillis()
                                ))
                            }
                            "client2" to "started" -> {
                                delay(200)
                                app.syncManager.updateObject("cascade_task", mapOf(
                                    "status" to "reviewed",
                                    "updatedBy" to "client2",
                                    "lastUpdate" to System.currentTimeMillis()
                                ))
                            }
                            "client3" to "reviewed" -> {
                                delay(200)
                                app.syncManager.updateObject("cascade_task", mapOf(
                                    "status" to "completed",
                                    "updatedBy" to "client3",
                                    "lastUpdate" to System.currentTimeMillis()
                                ))
                                cascadeComplete.set(true)
                            }
                        }
                    }
                }
            }
        }

        setupCascadeMonitoring(clientApp1, "client1")
        setupCascadeMonitoring(clientApp2, "client2")
        setupCascadeMonitoring(clientApp3, "client3")

        // Start communication
        serverApp.start()
        delay(300)
        clientApp1.connect("localhost", basePort)
        clientApp2.connect("localhost", basePort)
        clientApp3.connect("localhost", basePort)
        delay(500)

        // Create initial task and start cascade
        val task = TeamTask(
            id = "cascade_task",
            title = "Cascading Task",
            assignee = "user1",
            status = "created",
            priority = 1,
            updatedBy = "server"
        )
        serverApp.syncManager.registerObject(task.toSyncableObject())
        delay(300)

        // Start the cascade by updating to "assigned"
        serverApp.syncManager.updateObject("cascade_task", mapOf(
            "status" to "assigned",
            "updatedBy" to "server",
            "lastUpdate" to System.currentTimeMillis()
        ))

        // Wait for cascading completion
        val success = waitForCondition(cascadeComplete, testTimeout)
        assertTrue("Cascading update propagation failed", success)

        // Verify cascade steps
        assertTrue("Not enough cascade steps", cascadeSteps.size >= 4)
        println("Cascade steps: ${cascadeSteps.joinToString(" -> ")}")

        // Verify final state
        val finalTask = serverApp.syncManager.getObject("cascade_task") as? SyncableObjectData
        assertNotNull("Final task not found", finalTask)
        assertEquals("completed", finalTask?.fields?.get("status"))
        assertEquals("client3", finalTask?.fields?.get("updatedBy"))
    }

    // Helper Classes
    data class SharedData(
        val id: String,
        val value: String,
        val source: String,
        val timestamp: Long = System.currentTimeMillis(),
        val priority: Int = 0
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "SharedData",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "value" to value,
                "source" to source,
                "timestamp" to timestamp,
                "priority" to priority
            )
        )

        companion object {
            fun fromSyncableObject(obj: SyncableObjectData): SharedData {
                val fields = obj.fields
                return SharedData(
                    id = fields["id"] as String,
                    value = fields["value"] as String,
                    source = fields["source"] as String,
                    timestamp = fields["timestamp"] as Long,
                    priority = fields["priority"] as Int
                )
            }
        }
    }

    data class TeamTask(
        val id: String,
        val title: String,
        val assignee: String,
        val status: String,
        val priority: Int,
        val lastUpdate: Long = System.currentTimeMillis(),
        val updatedBy: String
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "TeamTask",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "title" to title,
                "assignee" to assignee,
                "status" to status,
                "priority" to priority,
                "lastUpdate" to lastUpdate,
                "updatedBy" to updatedBy
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