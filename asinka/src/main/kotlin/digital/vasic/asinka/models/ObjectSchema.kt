package digital.vasic.asinka.models

import digital.vasic.asinka.proto.FieldSchema as ProtoFieldSchema
import digital.vasic.asinka.proto.ObjectSchema as ProtoObjectSchema

data class ObjectSchema(
    val objectType: String,
    val version: String,
    val fields: List<FieldSchema>,
    val permissions: List<String> = listOf("read", "write")
) {
    fun toProto(): ProtoObjectSchema {
        return ProtoObjectSchema.newBuilder()
            .setObjectType(objectType)
            .setVersion(version)
            .addAllFields(fields.map { it.toProto() })
            .addAllPermissions(permissions)
            .build()
    }

    companion object {
        fun fromProto(proto: ProtoObjectSchema): ObjectSchema {
            return ObjectSchema(
                objectType = proto.objectType,
                version = proto.version,
                fields = proto.fieldsList.map { FieldSchema.fromProto(it) },
                permissions = proto.permissionsList
            )
        }
    }
}

data class FieldSchema(
    val name: String,
    val type: FieldType,
    val nullable: Boolean = false
) {
    fun toProto(): ProtoFieldSchema {
        return ProtoFieldSchema.newBuilder()
            .setName(name)
            .setType(type.name)
            .setNullable(nullable)
            .build()
    }

    companion object {
        fun fromProto(proto: ProtoFieldSchema): FieldSchema {
            return FieldSchema(
                name = proto.name,
                type = FieldType.valueOf(proto.type),
                nullable = proto.nullable
            )
        }
    }
}

enum class FieldType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    BYTES
}