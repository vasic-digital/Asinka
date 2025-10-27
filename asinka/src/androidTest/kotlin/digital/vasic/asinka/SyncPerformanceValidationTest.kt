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
import kotlin.random.Random

/**
 * Comprehensive performance validation tests for Asinka sync mechanisms.
 * Tests throughput, latency, memory usage, and scalability under various loads.
 */
@RunWith(AndroidJUnit4::class)
class SyncPerformanceValidationTest {

    private lateinit var context: Context
    private lateinit var performanceServer: AsinkaClient
    private lateinit var performanceClient: AsinkaClient

    private val testTimeout = 30000L // 30 seconds for performance tests
    private val basePort = 9880

    private val performanceDataSchema = ObjectSchema(
        objectType = "PerformanceData",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("payload", FieldType.STRING),
            FieldSchema("size", FieldType.INT),
            FieldSchema("timestamp", FieldType.LONG),
            FieldSchema("sequenceNumber", FieldType.INT),
            FieldSchema("batchId", FieldType.STRING)
        ),
        permissions = listOf("read", "write", "sync")
    )

    private val largeDataSchema = ObjectSchema(
        objectType = "LargeData",
        version = "1.0",
        fields = listOf(
            FieldSchema("id", FieldType.STRING),
            FieldSchema("data", FieldType.BYTES),
            FieldSchema("metadata", FieldType.STRING),
            FieldSchema("checksum", FieldType.STRING),
            FieldSchema("size", FieldType.INT),
            FieldSchema("timestamp", FieldType.LONG)
        ),
        permissions = listOf("read", "write", "sync")
    )

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        val serverConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.perf.server",
            appName = "Performance Server",
            appVersion = "1.0.0",
            serverPort = basePort,
            exposedSchemas = listOf(performanceDataSchema, largeDataSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "high_performance" to "enabled",
                "batch_processing" to "enabled"
            )
        )

        val clientConfig = AsinkaConfig(
            appId = "digital.vasic.asinka.perf.client",
            appName = "Performance Client",
            appVersion = "1.0.0",
            serverPort = basePort + 1,
            exposedSchemas = listOf(performanceDataSchema, largeDataSchema),
            capabilities = mapOf(
                "sync" to "enabled",
                "high_performance" to "enabled",
                "batch_processing" to "enabled"
            )
        )

        performanceServer = AsinkaClient.create(context, serverConfig)
        performanceClient = AsinkaClient.create(context, clientConfig)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            performanceServer.stop()
            performanceClient.stop()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    @Test
    fun testThroughputPerformance() = runTest {
        val throughputResults = ThroughputResults()
        val throughputComplete = AtomicBoolean(false)
        val objectCount = 1000

        // Monitor throughput on client
        launch {
            var receivedCount = 0
            val startTime = AtomicLong(0)

            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "PerformanceData" &&
                    change.obj.objectId.startsWith("throughput_")) {

                    receivedCount++

                    if (receivedCount == 1) {
                        startTime.set(System.currentTimeMillis())
                    }

                    if (receivedCount >= objectCount) {
                        val endTime = System.currentTimeMillis()
                        val totalTime = endTime - startTime.get()

                        throughputResults.objectsPerSecond = (objectCount * 1000.0) / totalTime
                        throughputResults.totalTime = totalTime
                        throughputResults.objectCount = receivedCount

                        throughputComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        // Send high-volume data for throughput test
        val batchStartTime = System.currentTimeMillis()
        repeat(objectCount) { i ->
            val data = PerformanceData(
                id = "throughput_$i",
                payload = "Throughput test data $i",
                size = 50,
                sequenceNumber = i,
                batchId = "throughput_batch"
            )

            performanceServer.syncManager.registerObject(data.toSyncableObject())

            // Brief pause every 50 objects to prevent overwhelming
            if (i % 50 == 0 && i > 0) {
                delay(10)
            }
        }

        // Wait for throughput test completion
        val success = waitForCondition(throughputComplete, testTimeout)
        assertTrue("Throughput performance test failed", success)

        // Validate performance metrics
        assertTrue("Throughput too low: ${throughputResults.objectsPerSecond} objects/sec",
                 throughputResults.objectsPerSecond > 10.0)

        assertTrue("Total time too high: ${throughputResults.totalTime}ms",
                 throughputResults.totalTime < testTimeout)

        println("Throughput Performance:")
        println("  Objects: ${throughputResults.objectCount}")
        println("  Time: ${throughputResults.totalTime}ms")
        println("  Throughput: ${String.format("%.2f", throughputResults.objectsPerSecond)} objects/sec")
    }

    @Test
    fun testLatencyDistribution() = runTest {
        val latencyMeasurements = mutableListOf<Long>()
        val latencyTestComplete = AtomicBoolean(false)
        val measurementCount = 100

        // Measure individual object latencies
        launch {
            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "PerformanceData" &&
                    change.obj.objectId.startsWith("latency_")) {

                    val obj = change.obj as SyncableObjectData
                    val sentTime = obj.fields["timestamp"] as Long
                    val receivedTime = System.currentTimeMillis()
                    val latency = receivedTime - sentTime

                    latencyMeasurements.add(latency)

                    if (latencyMeasurements.size >= measurementCount) {
                        latencyTestComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        // Send objects with precise timing for latency measurement
        repeat(measurementCount) { i ->
            val sendTime = System.currentTimeMillis()
            val data = PerformanceData(
                id = "latency_$i",
                payload = "Latency test $i",
                size = 25,
                sequenceNumber = i,
                batchId = "latency_batch",
                timestamp = sendTime
            )

            performanceServer.syncManager.registerObject(data.toSyncableObject())
            delay(100) // 100ms between measurements for accuracy
        }

        // Wait for latency measurements
        val success = waitForCondition(latencyTestComplete, testTimeout)
        assertTrue("Latency distribution test failed", success)

        // Calculate latency statistics
        val sortedLatencies = latencyMeasurements.sorted()
        val stats = LatencyStats(
            min = sortedLatencies.first(),
            max = sortedLatencies.last(),
            mean = latencyMeasurements.average(),
            median = sortedLatencies[sortedLatencies.size / 2],
            p95 = sortedLatencies[(sortedLatencies.size * 0.95).toInt()],
            p99 = sortedLatencies[(sortedLatencies.size * 0.99).toInt()]
        )

        println("Latency Distribution:")
        println("  Min: ${stats.min}ms")
        println("  Max: ${stats.max}ms")
        println("  Mean: ${String.format("%.2f", stats.mean)}ms")
        println("  Median: ${stats.median}ms")
        println("  95th percentile: ${stats.p95}ms")
        println("  99th percentile: ${stats.p99}ms")

        // Performance assertions
        assertTrue("Mean latency too high: ${String.format("%.2f", stats.mean)}ms", stats.mean < 1000)
        assertTrue("95th percentile too high: ${stats.p95}ms", stats.p95 < 2000)
        assertTrue("99th percentile too high: ${stats.p99}ms", stats.p99 < 5000)
    }

    @Test
    fun testLargeObjectPerformance() = runTest {
        val largeObjectResults = LargeObjectResults()
        val largeObjectComplete = AtomicBoolean(false)
        val objectSizes = listOf(1024, 10240, 102400, 1048576) // 1KB, 10KB, 100KB, 1MB

        // Monitor large object sync performance
        launch {
            var processedObjects = 0
            val startTime = System.currentTimeMillis()

            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "LargeData" &&
                    change.obj.objectId.startsWith("large_")) {

                    val obj = change.obj as SyncableObjectData
                    val size = obj.fields["size"] as Int
                    val data = obj.fields["data"] as ByteArray

                    processedObjects++
                    largeObjectResults.totalBytesTransferred += size
                    largeObjectResults.objectsSynced++

                    // Verify data integrity
                    if (data.size == size) {
                        largeObjectResults.integrityChecks++
                    }

                    if (processedObjects >= objectSizes.size) {
                        val endTime = System.currentTimeMillis()
                        largeObjectResults.totalTime = endTime - startTime
                        largeObjectResults.mbPerSecond = (largeObjectResults.totalBytesTransferred / 1024.0 / 1024.0) /
                                                        (largeObjectResults.totalTime / 1000.0)
                        largeObjectComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        // Create and sync large objects of different sizes
        objectSizes.forEachIndexed { index, size ->
            val data = ByteArray(size) { (it % 256).toByte() }
            val checksum = data.contentHashCode().toString()

            val largeData = LargeData(
                id = "large_$index",
                data = data,
                metadata = "Large object test $size bytes",
                checksum = checksum,
                size = size
            )

            performanceServer.syncManager.registerObject(largeData.toSyncableObject())
            delay(500) // Allow time for large object transfer
        }

        // Wait for large object performance test
        val success = waitForCondition(largeObjectComplete, testTimeout * 2)
        assertTrue("Large object performance test failed", success)

        println("Large Object Performance:")
        println("  Objects synced: ${largeObjectResults.objectsSynced}")
        println("  Total bytes: ${largeObjectResults.totalBytesTransferred}")
        println("  Time: ${largeObjectResults.totalTime}ms")
        println("  Throughput: ${String.format("%.2f", largeObjectResults.mbPerSecond)} MB/sec")
        println("  Integrity checks passed: ${largeObjectResults.integrityChecks}/${largeObjectResults.objectsSynced}")

        // Performance assertions
        assertTrue("Large object sync failed", largeObjectResults.objectsSynced >= objectSizes.size)
        assertTrue("Data integrity issues", largeObjectResults.integrityChecks >= objectSizes.size)
        assertTrue("Transfer rate too slow", largeObjectResults.mbPerSecond > 0.1) // At least 100 KB/sec
    }

    @Test
    fun testBatchProcessingPerformance() = runTest {
        val batchResults = BatchResults()
        val batchComplete = AtomicBoolean(false)
        val batchSize = 200
        val numberOfBatches = 5

        // Monitor batch processing performance
        launch {
            val batchCounts = ConcurrentHashMap<String, AtomicInteger>()

            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "PerformanceData" &&
                    change.obj.objectId.startsWith("batch_")) {

                    val obj = change.obj as SyncableObjectData
                    val batchId = obj.fields["batchId"] as String

                    val count = batchCounts.getOrPut(batchId) { AtomicInteger(0) }.incrementAndGet()
                    batchResults.totalObjectsReceived++

                    if (count == batchSize) {
                        batchResults.completedBatches++
                        println("Completed batch: $batchId")
                    }

                    if (batchResults.completedBatches >= numberOfBatches) {
                        batchResults.endTime = System.currentTimeMillis()
                        batchComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        batchResults.startTime = System.currentTimeMillis()

        // Send multiple batches
        repeat(numberOfBatches) { batchIndex ->
            val batchId = "batch_$batchIndex"

            // Send batch rapidly
            launch {
                repeat(batchSize) { objectIndex ->
                    val data = PerformanceData(
                        id = "batch_${batchIndex}_$objectIndex",
                        payload = "Batch $batchIndex object $objectIndex",
                        size = 30,
                        sequenceNumber = objectIndex,
                        batchId = batchId
                    )

                    performanceServer.syncManager.registerObject(data.toSyncableObject())

                    if (objectIndex % 25 == 0) delay(10) // Brief pause every 25 objects
                }
            }

            delay(200) // Stagger batch starts
        }

        // Wait for batch processing completion
        val success = waitForCondition(batchComplete, testTimeout)
        assertTrue("Batch processing performance test failed", success)

        val totalTime = batchResults.endTime - batchResults.startTime
        val objectsPerSecond = (batchResults.totalObjectsReceived * 1000.0) / totalTime

        println("Batch Processing Performance:")
        println("  Batches: $numberOfBatches")
        println("  Batch size: $batchSize")
        println("  Total objects: ${batchResults.totalObjectsReceived}")
        println("  Completed batches: ${batchResults.completedBatches}")
        println("  Time: ${totalTime}ms")
        println("  Throughput: ${String.format("%.2f", objectsPerSecond)} objects/sec")

        // Performance assertions
        assertTrue("Not all batches completed", batchResults.completedBatches >= numberOfBatches)
        assertTrue("Not all objects received", batchResults.totalObjectsReceived >= batchSize * numberOfBatches)
        assertTrue("Batch throughput too low", objectsPerSecond > 5.0)
    }

    @Test
    fun testMemoryUsageUnderLoad() = runTest {
        val memoryResults = MemoryResults()
        val memoryTestComplete = AtomicBoolean(false)
        val loadObjectCount = 500

        // Monitor memory usage during load
        val runtime = Runtime.getRuntime()
        val initialMemory = runtime.totalMemory() - runtime.freeMemory()

        // Monitor object sync for memory test
        launch {
            var syncedCount = 0

            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "PerformanceData" &&
                    change.obj.objectId.startsWith("memory_")) {

                    syncedCount++

                    // Sample memory usage periodically
                    if (syncedCount % 50 == 0) {
                        val currentMemory = runtime.totalMemory() - runtime.freeMemory()
                        val memoryIncrease = currentMemory - initialMemory
                        memoryResults.memorySamples.add(memoryIncrease)

                        // Force garbage collection for accurate measurement
                        System.gc()
                    }

                    if (syncedCount >= loadObjectCount) {
                        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
                        memoryResults.finalMemoryIncrease = finalMemory - initialMemory

                        memoryTestComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        // Create load for memory testing
        repeat(loadObjectCount) { i ->
            val payload = "Memory test data ".repeat(10) // ~170 bytes per object
            val data = PerformanceData(
                id = "memory_$i",
                payload = payload,
                size = payload.length,
                sequenceNumber = i,
                batchId = "memory_batch"
            )

            performanceServer.syncManager.registerObject(data.toSyncableObject())

            if (i % 25 == 0) delay(20) // Controlled load
        }

        // Wait for memory test completion
        val success = waitForCondition(memoryTestComplete, testTimeout)
        assertTrue("Memory usage test failed", success)

        val avgMemoryIncrease = memoryResults.memorySamples.average()
        val maxMemoryIncrease = memoryResults.memorySamples.maxOrNull() ?: 0L

        println("Memory Usage Under Load:")
        println("  Objects processed: $loadObjectCount")
        println("  Initial memory: ${initialMemory / 1024 / 1024}MB")
        println("  Final memory increase: ${memoryResults.finalMemoryIncrease / 1024 / 1024}MB")
        println("  Average memory increase: ${(avgMemoryIncrease / 1024 / 1024).toLong()}MB")
        println("  Peak memory increase: ${maxMemoryIncrease / 1024 / 1024}MB")

        // Memory efficiency assertions
        val memoryPerObject = memoryResults.finalMemoryIncrease / loadObjectCount
        assertTrue("Memory usage per object too high: ${memoryPerObject} bytes/object",
                 memoryPerObject < 5000) // Less than 5KB per object

        assertTrue("Peak memory increase too high: ${maxMemoryIncrease / 1024 / 1024}MB",
                 maxMemoryIncrease < 100 * 1024 * 1024) // Less than 100MB peak
    }

    @Test
    fun testConcurrentSyncPerformance() = runTest {
        val concurrentResults = ConcurrentResults()
        val concurrentComplete = AtomicBoolean(false)
        val concurrentStreams = 3
        val objectsPerStream = 100

        // Monitor concurrent sync performance
        launch {
            val streamCounts = ConcurrentHashMap<String, AtomicInteger>()

            performanceClient.syncManager.observeAllChanges().collect { change ->
                if (change is SyncChange.Updated &&
                    change.obj.objectType == "PerformanceData" &&
                    change.obj.objectId.startsWith("concurrent_")) {

                    val obj = change.obj as SyncableObjectData
                    val batchId = obj.fields["batchId"] as String

                    val count = streamCounts.getOrPut(batchId) { AtomicInteger(0) }.incrementAndGet()
                    concurrentResults.totalReceived++

                    if (count == objectsPerStream) {
                        concurrentResults.completedStreams++
                    }

                    if (concurrentResults.completedStreams >= concurrentStreams) {
                        concurrentResults.endTime = System.currentTimeMillis()
                        concurrentComplete.set(true)
                    }
                }
            }
        }

        // Start communication
        performanceServer.start()
        delay(300)
        performanceClient.connect("localhost", basePort)
        delay(500)

        concurrentResults.startTime = System.currentTimeMillis()

        // Launch concurrent sync streams
        repeat(concurrentStreams) { streamIndex ->
            launch {
                repeat(objectsPerStream) { objectIndex ->
                    val data = PerformanceData(
                        id = "concurrent_${streamIndex}_$objectIndex",
                        payload = "Concurrent stream $streamIndex object $objectIndex",
                        size = 40,
                        sequenceNumber = objectIndex,
                        batchId = "stream_$streamIndex"
                    )

                    performanceServer.syncManager.registerObject(data.toSyncableObject())
                    delay(20) // Small delay to simulate realistic timing
                }
            }
        }

        // Wait for concurrent sync completion
        val success = waitForCondition(concurrentComplete, testTimeout)
        assertTrue("Concurrent sync performance test failed", success)

        val totalTime = concurrentResults.endTime - concurrentResults.startTime
        val concurrentThroughput = (concurrentResults.totalReceived * 1000.0) / totalTime

        println("Concurrent Sync Performance:")
        println("  Concurrent streams: $concurrentStreams")
        println("  Objects per stream: $objectsPerStream")
        println("  Total objects: ${concurrentResults.totalReceived}")
        println("  Completed streams: ${concurrentResults.completedStreams}")
        println("  Time: ${totalTime}ms")
        println("  Concurrent throughput: ${String.format("%.2f", concurrentThroughput)} objects/sec")

        // Performance assertions
        assertTrue("Not all concurrent streams completed", concurrentResults.completedStreams >= concurrentStreams)
        assertTrue("Concurrent throughput too low", concurrentThroughput > 3.0)
    }

    // Helper Classes and Data Structures
    data class PerformanceData(
        val id: String,
        val payload: String,
        val size: Int,
        val sequenceNumber: Int,
        val batchId: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "PerformanceData",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "payload" to payload,
                "size" to size,
                "timestamp" to timestamp,
                "sequenceNumber" to sequenceNumber,
                "batchId" to batchId
            )
        )
    }

    data class LargeData(
        val id: String,
        val data: ByteArray,
        val metadata: String,
        val checksum: String,
        val size: Int,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun toSyncableObject() = SyncableObjectData(
            objectId = id,
            objectType = "LargeData",
            version = 1,
            fields = mutableMapOf(
                "id" to id,
                "data" to data,
                "metadata" to metadata,
                "checksum" to checksum,
                "size" to size,
                "timestamp" to timestamp
            )
        )
    }

    data class ThroughputResults(
        var objectsPerSecond: Double = 0.0,
        var totalTime: Long = 0L,
        var objectCount: Int = 0
    )

    data class LatencyStats(
        val min: Long,
        val max: Long,
        val mean: Double,
        val median: Long,
        val p95: Long,
        val p99: Long
    )

    data class LargeObjectResults(
        var objectsSynced: Int = 0,
        var totalBytesTransferred: Long = 0L,
        var integrityChecks: Int = 0,
        var totalTime: Long = 0L,
        var mbPerSecond: Double = 0.0
    )

    data class BatchResults(
        var startTime: Long = 0L,
        var endTime: Long = 0L,
        var completedBatches: Int = 0,
        var totalObjectsReceived: Int = 0
    )

    data class MemoryResults(
        val memorySamples: MutableList<Long> = mutableListOf(),
        var finalMemoryIncrease: Long = 0L
    )

    data class ConcurrentResults(
        var startTime: Long = 0L,
        var endTime: Long = 0L,
        var completedStreams: Int = 0,
        var totalReceived: Int = 0
    )

    private suspend fun waitForCondition(condition: AtomicBoolean, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!condition.get()) {
                delay(50)
            }
            true
        } ?: false
    }
}