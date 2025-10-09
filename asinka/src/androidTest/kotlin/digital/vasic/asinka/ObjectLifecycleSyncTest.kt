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
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests complete object lifecycle synchronization: creation, updates, state transitions, and deletion.
 * Validates that object states remain consistent throughout their entire lifecycle across apps.
 */
@RunWith(AndroidJUnit4::class)
class ObjectLifecycleSyncTest {

    private lateinit var context: Context
    private lateinit var producerApp: AsinkaClient
    private lateinit var consumerApp: AsinkaClient

    private val testTimeout = 12000L
    private val basePort = 9860

    private val productSchema = ObjectSchema(
        objectType = "Product",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("name", FieldType.STRING),
            FieldSchema("price", FieldType.DOUBLE),
            FieldSchema("quantity", FieldType.INT),
            FieldSchema("status", FieldType.STRING),
            FieldSchema("lastUpdated", FieldType.LONG),
            FieldSchema("isActive", FieldType.BOOL)
        ),
        permissions = listOf("read", "write", "sync", "delete")
    )

    private val orderSchema = ObjectSchema(
        objectType = "Order",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("productId", FieldType.STRING),
            FieldSchema("customerName", FieldType.STRING),
            FieldSchema("quantity", FieldType.INT),
            FieldSchema("status", FieldType.STRING),
            FieldSchema("totalAmount", FieldType.DOUBLE),
            FieldSchema("createdAt", FieldType.LONG),
            FieldSchema("processedAt", FieldType.LONG)
        ),
        permissions = listOf("read", "write", "sync", "delete")
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        val producerConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.lifecycle.producer",
            appName = "Lifecycle Producer",
            appVersion = "1.0.0",
            serverPort = basePort,
            exposedSchemas = listOf(productSchema, orderSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "lifecycle_tracking" to "enabled"
            )
        )

        val consumerConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.lifecycle.consumer",
            appName = "Lifecycle Consumer",
            appVersion = "1.0.0",
            serverPort = basePort + 1,
            exposedSchemas = listOf(productSchema, orderSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "lifecycle_tracking" to "enabled"
            )
        )

        producerApp = AsinkaClient.create(context, producerConfig)
        consumerApp = AsinkaClient.create(context, consumerConfig)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            producerApp.stop()
            consumerApp.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun testCompleteObjectLifecycle() = runTest {
        val lifecycleEvents = mutableListOf<LifecycleEvent>()
        val lifecycleComplete = AtomicBoolean(false)

        data class LifecycleEvent(val type: String, val objectId: String, val status: String?, val timestamp: Long)

        // Monitor complete lifecycle on consumer
        launch {
            consumerApp.syncManager.observeAllChanges().collect { change ->
                val event = when (change) {
                    is SyncChange.Updated -> {
                        val obj = change.obj as SyncableObjectData
                        val status = obj.fields["status"] as? String
                        LifecycleEvent("UPDATE", obj.objectId, status, System.currentTimeMillis())
                    }
                    is SyncChange.Deleted -> {
                        LifecycleEvent("DELETE", change.objectId, null, System.currentTimeMillis())
                    }
                }

                lifecycleEvents.add(event)

                // Complete when we see creation, multiple updates, and deletion
                val hasCreation = lifecycleEvents.any { it.type == "UPDATE" && it.status == "created" }
                val hasUpdates = lifecycleEvents.count { it.type == "UPDATE" } >= 4
                val hasDeletion = lifecycleEvents.any { it.type == "DELETE" }

                if (hasCreation && hasUpdates && hasDeletion) {
                    lifecycleComplete.set(true)
                }
            }
        }

        // Start communication
        producerApp.start()
        delay(200)
        consumerApp.connect("localhost", basePort)
        delay(500)

        // 1. Object Creation
        val product = Product(
            id = "lifecycle_product",
            name = "Lifecycle Test Product",
            price = 99.99,
            quantity = 100,
            status = "created"
        )
        producerApp.syncManager.registerObject(product.toSyncableObject())
        delay(300)

        // 2. Status Transitions
        producerApp.syncManager.updateObject("lifecycle_product", mapOf(
            "status" to "active",
            "isActive" to true,
            "lastUpdated" to System.currentTimeMillis()
        ))
        delay(200)

        producerApp.syncManager.updateObject("lifecycle_product", mapOf(
            "price" to 89.99,
            "status" to "discounted",
            "lastUpdated" to System.currentTimeMillis()
        ))
        delay(200)

        producerApp.syncManager.updateObject("lifecycle_product", mapOf(
            "quantity" to 50,
            "status" to "low_stock",
            "lastUpdated" to System.currentTimeMillis()
        ))
        delay(200)

        // 3. Final state before deletion
        producerApp.syncManager.updateObject("lifecycle_product", mapOf(
            "status" to "discontinued",
            "isActive" to false,
            "lastUpdated" to System.currentTimeMillis()
        ))
        delay(200)

        // 4. Object Deletion
        producerApp.syncManager.deleteObject("lifecycle_product")

        // Wait for complete lifecycle
        val success = waitForCondition(lifecycleComplete, testTimeout)
        assertTrue("Complete object lifecycle failed", success)

        // Verify lifecycle events sequence
        assertTrue("No creation event", lifecycleEvents.any { it.status == "created" })
        assertTrue("No status transitions", lifecycleEvents.any { it.status == "active" })
        assertTrue("No deletion event", lifecycleEvents.any { it.type == "DELETE" })

        // Verify object is deleted on consumer
        val deletedProduct = consumerApp.syncManager.getObject("lifecycle_product")
        assertNull("Product should be deleted", deletedProduct)

        println("Lifecycle events: ${lifecycleEvents.map { "${it.type}:${it.status}" }}")
    }

    @Test
    fun testRelatedObjectsLifecycle() = runTest {
        val orderEvents = mutableListOf<String>()
        val productEvents = mutableListOf<String>()
        val relatedLifecycleComplete = AtomicBoolean(false)

        // Monitor related objects lifecycle
        launch {
            consumerApp.syncManager.observeAllChanges().collect { change ->
                when (change) {
                    is SyncChange.Updated -> {
                        val obj = change.obj as SyncableObjectData
                        when (obj.objectType) {
                            "Product" -> {
                                val status = obj.fields["status"] as String
                                productEvents.add("Product:$status")
                            }
                            "Order" -> {
                                val status = obj.fields["status"] as String
                                orderEvents.add("Order:$status")
                            }
                        }
                    }
                    is SyncChange.Deleted -> {
                        if (change.objectType == "Product") {
                            productEvents.add("Product:DELETED")
                        } else if (change.objectType == "Order") {
                            orderEvents.add("Order:DELETED")
                        }
                    }
                }

                // Complete when both objects have gone through lifecycle
                if (productEvents.size >= 3 && orderEvents.size >= 3) {
                    relatedLifecycleComplete.set(true)
                }
            }
        }

        // Start communication
        producerApp.start()
        delay(200)
        consumerApp.connect("localhost", basePort)
        delay(500)

        // 1. Create product
        val product = Product(
            id = "related_product",
            name = "Related Product",
            price = 50.0,
            quantity = 20,
            status = "available"
        )
        producerApp.syncManager.registerObject(product.toSyncableObject())
        delay(300)

        // 2. Create order for the product
        val order = Order(
            id = "related_order",
            productId = "related_product",
            customerName = "John Doe",
            quantity = 2,
            status = "pending",
            totalAmount = 100.0
        )
        producerApp.syncManager.registerObject(order.toSyncableObject())
        delay(300)

        // 3. Process the order
        producerApp.syncManager.updateObject("related_order", mapOf(
            "status" to "processing",
            "processedAt" to System.currentTimeMillis()
        ))
        delay(200)

        // 4. Update product inventory
        producerApp.syncManager.updateObject("related_product", mapOf(
            "quantity" to 18, // 20 - 2
            "status" to "in_stock",
            "lastUpdated" to System.currentTimeMillis()
        ))
        delay(200)

        // 5. Complete the order
        producerApp.syncManager.updateObject("related_order", mapOf(
            "status" to "completed",
            "processedAt" to System.currentTimeMillis()
        ))
        delay(200)

        // 6. Archive completed objects
        producerApp.syncManager.updateObject("related_product", mapOf(
            "status" to "archived"
        ))

        // Wait for related lifecycle completion
        val success = waitForCondition(relatedLifecycleComplete, testTimeout)
        assertTrue("Related objects lifecycle failed", success)

        // Verify both objects went through expected states
        assertTrue("Product lifecycle incomplete", productEvents.contains("Product:available"))
        assertTrue("Product lifecycle incomplete", productEvents.contains("Product:in_stock"))
        assertTrue("Order lifecycle incomplete", orderEvents.contains("Order:pending"))
        assertTrue("Order lifecycle incomplete", orderEvents.contains("Order:completed"))

        println("Product events: $productEvents")
        println("Order events: $orderEvents")
    }

    @Test
    fun testVersionedObjectUpdates() = runTest {
        val versionHistory = mutableListOf<Int>()
        val versioningComplete = AtomicBoolean(false)

        // Monitor version changes
        launch {
            consumerApp.syncManager.observeObject("versioned_product").collect { obj ->
                val data = obj as SyncableObjectData
                versionHistory.add(data.version)

                if (versionHistory.size >= 5) {
                    versioningComplete.set(true)
                }
            }
        }

        // Start communication
        producerApp.start()
        delay(200)
        consumerApp.connect("localhost", basePort)
        delay(500)

        // Create initial version
        val product = Product(
            id = "versioned_product",
            name = "Versioned Product",
            price = 100.0,
            quantity = 10,
            status = "v1"
        )
        producerApp.syncManager.registerObject(product.toSyncableObject())
        delay(200)

        // Multiple version updates
        repeat(4) { i ->
            producerApp.syncManager.updateObject("versioned_product", mapOf(
                "price" to (100.0 + i * 10),
                "status" to "v${i + 2}",
                "lastUpdated" to System.currentTimeMillis()
            ))
            delay(200)
        }

        // Wait for versioning completion
        val success = waitForCondition(versioningComplete, testTimeout)
        assertTrue("Versioned object updates failed", success)

        // Verify version progression
        assertEquals("Version history incorrect size", 5, versionHistory.size)

        // Verify versions are increasing
        for (i in 1 until versionHistory.size) {
            assertTrue("Version not increasing: ${versionHistory[i-1]} -> ${versionHistory[i]}",
                     versionHistory[i] >= versionHistory[i-1])
        }

        // Verify final object state
        val finalProduct = consumerApp.syncManager.getObject("versioned_product") as? SyncableObjectData
        assertNotNull("Final versioned product not found", finalProduct)
        assertEquals("v5", finalProduct?.fields?.get("status"))
        assertEquals(140.0, finalProduct?.fields?.get("price"))

        println("Version history: $versionHistory")
    }

    @Test
    fun testBatchObjectLifecycle() = runTest {
        val batchCreations = AtomicInteger(0)
        val batchUpdates = AtomicInteger(0)
        val batchDeletions = AtomicInteger(0)
        val batchLifecycleComplete = AtomicBoolean(false)
        val batchSize = 10

        // Monitor batch lifecycle
        launch {
            consumerApp.syncManager.observeAllChanges().collect { change ->
                when (change) {
                    is SyncChange.Updated -> {
                        val obj = change.obj as SyncableObjectData
                        if (obj.objectId.startsWith("batch_product_")) {
                            val status = obj.fields["status"] as String
                            when (status) {
                                "created" -> batchCreations.incrementAndGet()
                                "updated" -> batchUpdates.incrementAndGet()
                            }
                        }
                    }
                    is SyncChange.Deleted -> {
                        if (change.objectId.startsWith("batch_product_")) {
                            batchDeletions.incrementAndGet()
                        }
                    }
                }

                // Complete when all batch operations are done
                if (batchCreations.get() >= batchSize &&
                    batchUpdates.get() >= batchSize &&
                    batchDeletions.get() >= batchSize) {
                    batchLifecycleComplete.set(true)
                }
            }
        }

        // Start communication
        producerApp.start()
        delay(200)
        consumerApp.connect("localhost", basePort)
        delay(500)

        // 1. Batch creation
        repeat(batchSize) { i ->
            val product = Product(
                id = "batch_product_$i",
                name = "Batch Product $i",
                price = 10.0 + i,
                quantity = i + 1,
                status = "created"
            )
            producerApp.syncManager.registerObject(product.toSyncableObject())
            if (i % 3 == 0) delay(50) // Occasional pause
        }

        delay(500)

        // 2. Batch updates
        repeat(batchSize) { i ->
            producerApp.syncManager.updateObject("batch_product_$i", mapOf(
                "status" to "updated",
                "price" to (20.0 + i),
                "lastUpdated" to System.currentTimeMillis()
            ))
            if (i % 3 == 0) delay(50)
        }

        delay(500)

        // 3. Batch deletion
        repeat(batchSize) { i ->
            producerApp.syncManager.deleteObject("batch_product_$i")
            if (i % 3 == 0) delay(50)
        }

        // Wait for batch lifecycle completion
        val success = waitForCondition(batchLifecycleComplete, testTimeout * 2)
        assertTrue("Batch object lifecycle failed", success)

        // Verify batch operations
        assertEquals("Batch creations incomplete", batchSize, batchCreations.get())
        assertEquals("Batch updates incomplete", batchSize, batchUpdates.get())
        assertEquals("Batch deletions incomplete", batchSize, batchDeletions.get())

        // Verify all objects are deleted
        repeat(batchSize) { i ->
            val obj = consumerApp.syncManager.getObject("batch_product_$i")
            assertNull("Batch product $i should be deleted", obj)
        }

        println("Batch lifecycle: ${batchCreations.get()} created, ${batchUpdates.get()} updated, ${batchDeletions.get()} deleted")
    }

    @Test
    fun testConditionalObjectTransitions() = runTest {
        val transitionStates = mutableListOf<String>()
        val conditionalTransitionsComplete = AtomicBoolean(false)

        // Monitor conditional state transitions
        launch {
            consumerApp.syncManager.observeObject("conditional_order").collect { obj ->
                val data = obj as SyncableObjectData
                val status = data.fields["status"] as String
                val quantity = data.fields["quantity"] as Int

                transitionStates.add("$status:$quantity")

                // Complete when we see the expected transition sequence
                if (transitionStates.size >= 5) {
                    conditionalTransitionsComplete.set(true)
                }
            }
        }

        // Start communication
        producerApp.start()
        delay(200)
        consumerApp.connect("localhost", basePort)
        delay(500)

        // Create order with conditional logic
        val order = Order(
            id = "conditional_order",
            productId = "product_123",
            customerName = "Alice",
            quantity = 1,
            status = "pending",
            totalAmount = 50.0
        )
        producerApp.syncManager.registerObject(order.toSyncableObject())
        delay(200)

        // Conditional transitions based on quantity
        // Small order: pending -> approved -> processing -> shipped -> delivered
        producerApp.syncManager.updateObject("conditional_order", mapOf(
            "status" to "approved",
            "processedAt" to System.currentTimeMillis()
        ))
        delay(200)

        producerApp.syncManager.updateObject("conditional_order", mapOf(
            "status" to "processing",
            "processedAt" to System.currentTimeMillis()
        ))
        delay(200)

        producerApp.syncManager.updateObject("conditional_order", mapOf(
            "status" to "shipped",
            "processedAt" to System.currentTimeMillis()
        ))
        delay(200)

        producerApp.syncManager.updateObject("conditional_order", mapOf(
            "status" to "delivered",
            "processedAt" to System.currentTimeMillis()
        ))

        // Wait for conditional transitions
        val success = waitForCondition(conditionalTransitionsComplete, testTimeout)
        assertTrue("Conditional object transitions failed", success)

        // Verify transition sequence
        assertTrue("Missing pending state", transitionStates.any { it.startsWith("pending:") })
        assertTrue("Missing approved state", transitionStates.any { it.startsWith("approved:") })
        assertTrue("Missing delivered state", transitionStates.any { it.startsWith("delivered:") })

        // Verify final state
        val finalOrder = consumerApp.syncManager.getObject("conditional_order") as? SyncableObjectData
        assertNotNull("Final order not found", finalOrder)
        assertEquals("delivered", finalOrder?.fields?.get("status"))

        println("Transition states: $transitionStates")
    }

    // Helper Classes
    data class Product(
        val id: String,
        val name: String,
        val price: Double,
        val quantity: Int,
        val status: String,
        val lastUpdated: Long = System.currentTimeMillis(),
        val isActive: Boolean = true
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "Product",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "name" to name,
                "price" to price,
                "quantity" to quantity,
                "status" to status,
                "lastUpdated" to lastUpdated,
                "isActive" to isActive
            )
        )
    }

    data class Order(
        val id: String,
        val productId: String,
        val customerName: String,
        val quantity: Int,
        val status: String,
        val totalAmount: Double,
        val createdAt: Long = System.currentTimeMillis(),
        val processedAt: Long = 0L
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "Order",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "productId" to productId,
                "customerName" to customerName,
                "quantity" to quantity,
                "status" to status,
                "totalAmount" to totalAmount,
                "createdAt" to createdAt,
                "processedAt" to processedAt
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