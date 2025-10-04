package digital.vasic.asinka.models

import digital.vasic.asinka.proto.FieldValue
import digital.vasic.asinka.proto.ObjectUpdate
import com.google.protobuf.ByteString

interface SyncableObject {
    val objectId: String
    val objectType: String
    val version: Int
    fun toFieldMap(): Map<String, Any?>
    fun fromFieldMap(fields: Map<String, Any?>)
}

data class SyncableObjectData(
    override val objectId: String,
    override val objectType: String,
    override val version: Int,
    val fields: MutableMap<String, Any?> = mutableMapOf()
) : SyncableObject {

    override fun toFieldMap(): Map<String, Any?> = fields

    override fun fromFieldMap(fields: Map<String, Any?>) {
        this.fields.clear()
        this.fields.putAll(fields)
    }

    fun toProtoUpdate(sessionId: String): ObjectUpdate {
        val protoFields = fields.mapValues { (_, value) ->
            when (value) {
                is String -> FieldValue.newBuilder().setStringValue(value).build()
                is Int -> FieldValue.newBuilder().setIntValue(value.toLong()).build()
                is Long -> FieldValue.newBuilder().setIntValue(value).build()
                is Double -> FieldValue.newBuilder().setDoubleValue(value).build()
                is Float -> FieldValue.newBuilder().setDoubleValue(value.toDouble()).build()
                is Boolean -> FieldValue.newBuilder().setBoolValue(value).build()
                is ByteArray -> FieldValue.newBuilder().setBytesValue(ByteString.copyFrom(value)).build()
                null -> FieldValue.getDefaultInstance()
                else -> FieldValue.newBuilder().setStringValue(value.toString()).build()
            }
        }

        return ObjectUpdate.newBuilder()
            .setObjectId(objectId)
            .setObjectType(objectType)
            .setVersion(version)
            .setTimestamp(System.currentTimeMillis())
            .putAllFields(protoFields)
            .setSessionId(sessionId)
            .build()
    }

    companion object {
        fun fromProtoUpdate(update: ObjectUpdate): SyncableObjectData {
            val fields = update.fieldsMap.mapValues { (_, fieldValue) ->
                when {
                    fieldValue.hasStringValue() -> fieldValue.stringValue as Any?
                    fieldValue.hasIntValue() -> fieldValue.intValue as Any?
                    fieldValue.hasDoubleValue() -> fieldValue.doubleValue as Any?
                    fieldValue.hasBoolValue() -> fieldValue.boolValue as Any?
                    fieldValue.hasBytesValue() -> fieldValue.bytesValue.toByteArray() as Any?
                    else -> null
                }
            }.toMutableMap() as MutableMap<String, Any?>

            return SyncableObjectData(
                objectId = update.objectId,
                objectType = update.objectType,
                version = update.version,
                fields = fields
            )
        }
    }
}