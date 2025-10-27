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