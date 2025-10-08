package digital.vasic.asinka.sync

import digital.vasic.asinka.models.SyncableObjectData
import digital.vasic.asinka.proto.FieldValue
import digital.vasic.asinka.proto.ObjectUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SyncManagerTest {

    private lateinit var syncManager: SyncManager

    @Before
    fun setup() {
        syncManager = DefaultSyncManager()
    }

    @Test
    fun testRegisterObject() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-1",
            objectType = "Task",
            version = 1,
            fields = mutableMapOf("title" to "Test task")
        )

        syncManager.registerObject(obj)

        val retrieved = syncManager.getObject("obj-1")
        assertNotNull(retrieved)
        assertEquals("obj-1", retrieved?.objectId)
        assertEquals("Task", retrieved?.objectType)
    }

    @Test
    fun testUnregisterObject() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-2",
            objectType = "Task",
            version = 1
        )

        syncManager.registerObject(obj)
        assertNotNull(syncManager.getObject("obj-2"))

        syncManager.unregisterObject("obj-2")
        assertNull(syncManager.getObject("obj-2"))
    }

    @Test
    fun testUpdateObject() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-3",
            objectType = "Task",
            version = 1,
            fields = mutableMapOf("title" to "Original")
        )

        syncManager.registerObject(obj)

        syncManager.updateObject("obj-3", mapOf("title" to "Updated"))

        val updated = syncManager.getObject("obj-3") as SyncableObjectData
        assertEquals("Updated", updated.fields["title"])
    }

    @Test
    fun testDeleteObject() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-4",
            objectType = "Task",
            version = 1
        )

        syncManager.registerObject(obj)
        assertNotNull(syncManager.getObject("obj-4"))

        syncManager.deleteObject("obj-4")
        assertNull(syncManager.getObject("obj-4"))
    }

    @Test
    fun testObserveObject() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-5",
            objectType = "Task",
            version = 1,
            fields = mutableMapOf("title" to "Test")
        )

        syncManager.registerObject(obj)

        val flow = syncManager.observeObject("obj-5")
        assertNotNull(flow)
    }

    @Test
    fun testObserveAllChanges() = runTest {
        val changesList = mutableListOf<SyncChange>()

        val obj = SyncableObjectData(
            objectId = "obj-6",
            objectType = "Task",
            version = 1
        )

        launch {
            syncManager.observeAllChanges().take(2).toList(changesList)
        }

        syncManager.registerObject(obj)
        syncManager.deleteObject("obj-6")

        kotlinx.coroutines.delay(100)

        assertTrue(changesList.size >= 1)
        assertTrue(changesList.any { it is SyncChange.Updated })
    }

    @Test
    fun testProcessRemoteUpdate() = runTest {
        val update = ObjectUpdate.newBuilder()
            .setObjectId("remote-obj-1")
            .setObjectType("Task")
            .setVersion(1)
            .setTimestamp(System.currentTimeMillis())
            .putFields("title", FieldValue.newBuilder().setStringValue("Remote task").build())
            .putFields("completed", FieldValue.newBuilder().setBoolValue(false).build())
            .setSessionId("session-1")
            .build()

        syncManager.processRemoteUpdate(update)

        val obj = syncManager.getObject("remote-obj-1")
        assertNotNull(obj)
        assertEquals("remote-obj-1", obj?.objectId)
        assertEquals("Task", obj?.objectType)
    }

    @Test
    fun testProcessRemoteUpdateVersionControl() = runTest {
        val obj = SyncableObjectData(
            objectId = "obj-7",
            objectType = "Task",
            version = 5,
            fields = mutableMapOf("title" to "Current")
        )

        syncManager.registerObject(obj)

        val oldUpdate = ObjectUpdate.newBuilder()
            .setObjectId("obj-7")
            .setObjectType("Task")
            .setVersion(3)
            .setTimestamp(System.currentTimeMillis())
            .putFields("title", FieldValue.newBuilder().setStringValue("Old").build())
            .setSessionId("session-1")
            .build()

        syncManager.processRemoteUpdate(oldUpdate)

        val retrieved = syncManager.getObject("obj-7") as SyncableObjectData
        assertEquals("Current", retrieved.fields["title"])
    }

    @Test
    fun testSyncChangeTypes() {
        val obj = SyncableObjectData(
            objectId = "test",
            objectType = "Task",
            version = 1
        )

        val updated = SyncChange.Updated(obj)
        assertTrue(updated is SyncChange.Updated)
        assertEquals(obj, updated.obj)

        val deleted = SyncChange.Deleted("test", "Task")
        assertTrue(deleted is SyncChange.Deleted)
        assertEquals("test", deleted.objectId)
        assertEquals("Task", deleted.objectType)
    }

    @Test
    fun testGetNonExistentObject() {
        val obj = syncManager.getObject("non-existent")
        assertNull(obj)
    }

    @Test
    fun testUpdateNonExistentObject() = runTest {
        syncManager.updateObject("non-existent", mapOf("field" to "value"))
        val obj = syncManager.getObject("non-existent")
        assertNull(obj)
    }

    @Test
    fun testProcessRemoteUpdateWithAllFieldTypes() = runTest {
        val update = ObjectUpdate.newBuilder()
            .setObjectId("all-types")
            .setObjectType("Test")
            .setVersion(1)
            .setTimestamp(System.currentTimeMillis())
            .putFields("string", FieldValue.newBuilder().setStringValue("text").build())
            .putFields("int", FieldValue.newBuilder().setIntValue(42).build())
            .putFields("double", FieldValue.newBuilder().setDoubleValue(3.14).build())
            .putFields("bool", FieldValue.newBuilder().setBoolValue(true).build())
            .putFields("bytes", FieldValue.newBuilder().setBytesValue(
                com.google.protobuf.ByteString.copyFrom(byteArrayOf(1, 2, 3))
            ).build())
            .setSessionId("session-1")
            .build()

        syncManager.processRemoteUpdate(update)

        val obj = syncManager.getObject("all-types") as SyncableObjectData
        assertEquals("text", obj.fields["string"])
        assertEquals(42L, obj.fields["int"])
        assertEquals(3.14, obj.fields["double"])
        assertEquals(true, obj.fields["bool"])
        assertArrayEquals(byteArrayOf(1, 2, 3), obj.fields["bytes"] as ByteArray)
    }
}