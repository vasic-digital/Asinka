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


package digital.vasic.asinka.sync

import digital.vasic.asinka.models.SyncableObject
import digital.vasic.asinka.models.SyncableObjectData
import digital.vasic.asinka.proto.ObjectUpdate
import digital.vasic.asinka.proto.SyncMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

interface SyncManager {
    suspend fun registerObject(obj: SyncableObject)
    suspend fun unregisterObject(objectId: String)
    suspend fun updateObject(objectId: String, fields: Map<String, Any?>)
    suspend fun deleteObject(objectId: String)
    fun getObject(objectId: String): SyncableObject?
    fun observeObject(objectId: String): Flow<SyncableObject>
    fun observeAllChanges(): Flow<SyncChange>
    suspend fun processRemoteUpdate(update: ObjectUpdate)
}

sealed class SyncChange {
    data class Updated(val obj: SyncableObject) : SyncChange()
    data class Deleted(val objectId: String, val objectType: String) : SyncChange()
}

class DefaultSyncManager : SyncManager {
    private val objects = ConcurrentHashMap<String, SyncableObject>()
    private val objectFlows = ConcurrentHashMap<String, MutableSharedFlow<SyncableObject>>()
    private val _changesFlow = MutableSharedFlow<SyncChange>()
    private val changesFlow = _changesFlow.asSharedFlow()

    override suspend fun registerObject(obj: SyncableObject) {
        objects[obj.objectId] = obj
        objectFlows.getOrPut(obj.objectId) { MutableSharedFlow() }.emit(obj)
        _changesFlow.emit(SyncChange.Updated(obj))
    }

    override suspend fun unregisterObject(objectId: String) {
        val obj = objects.remove(objectId)
        if (obj != null) {
            objectFlows.remove(objectId)
            _changesFlow.emit(SyncChange.Deleted(objectId, obj.objectType))
        }
    }

    override suspend fun updateObject(objectId: String, fields: Map<String, Any?>) {
        val obj = objects[objectId] ?: return
        obj.fromFieldMap(fields)
        objectFlows[objectId]?.emit(obj)
        _changesFlow.emit(SyncChange.Updated(obj))
    }

    override suspend fun deleteObject(objectId: String) {
        unregisterObject(objectId)
    }

    override fun getObject(objectId: String): SyncableObject? {
        return objects[objectId]
    }

    override fun observeObject(objectId: String): Flow<SyncableObject> {
        return objectFlows.getOrPut(objectId) { MutableSharedFlow() }.asSharedFlow()
    }

    override fun observeAllChanges(): Flow<SyncChange> {
        return changesFlow
    }

    override suspend fun processRemoteUpdate(update: ObjectUpdate) {
        val existingObj = objects[update.objectId]

        if (existingObj != null && existingObj.version >= update.version) {
            return
        }

        val updatedObj = SyncableObjectData.fromProtoUpdate(update)
        objects[update.objectId] = updatedObj
        objectFlows.getOrPut(update.objectId) { MutableSharedFlow() }.emit(updatedObj)
        _changesFlow.emit(SyncChange.Updated(updatedObj))
    }
}