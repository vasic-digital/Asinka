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

import org.junit.Assert.*
import org.junit.Test

class ObjectSchemaTest {

    @Test
    fun testObjectSchemaCreation() {
        val schema = ObjectSchema(
            objectType = "Task",
            version = "1.0",
            fields = listOf(
                FieldSchema("title", FieldType.STRING, false),
                FieldSchema("completed", FieldType.BOOLEAN, false)
            ),
            permissions = listOf("read", "write")
        )

        assertEquals("Task", schema.objectType)
        assertEquals("1.0", schema.version)
        assertEquals(2, schema.fields.size)
        assertEquals(2, schema.permissions.size)
    }

    @Test
    fun testObjectSchemaToProto() {
        val schema = ObjectSchema(
            objectType = "User",
            version = "2.0",
            fields = listOf(
                FieldSchema("name", FieldType.STRING, false),
                FieldSchema("age", FieldType.INT, false)
            )
        )

        val proto = schema.toProto()

        assertEquals("User", proto.objectType)
        assertEquals("2.0", proto.version)
        assertEquals(2, proto.fieldsCount)
    }

    @Test
    fun testObjectSchemaFromProto() {
        val originalSchema = ObjectSchema(
            objectType = "Product",
            version = "1.5",
            fields = listOf(
                FieldSchema("name", FieldType.STRING),
                FieldSchema("price", FieldType.DOUBLE)
            )
        )

        val proto = originalSchema.toProto()
        val reconstructed = ObjectSchema.fromProto(proto)

        assertEquals(originalSchema.objectType, reconstructed.objectType)
        assertEquals(originalSchema.version, reconstructed.version)
        assertEquals(originalSchema.fields.size, reconstructed.fields.size)
    }

    @Test
    fun testSyncableObjectDataCreation() {
        val obj = SyncableObjectData(
            objectId = "task-1",
            objectType = "Task",
            version = 1,
            fields = mutableMapOf(
                "title" to "Buy groceries",
                "completed" to false
            )
        )

        assertEquals("task-1", obj.objectId)
        assertEquals("Task", obj.objectType)
        assertEquals(1, obj.version)
        assertEquals(2, obj.fields.size)
    }

    @Test
    fun testSyncableObjectToFieldMap() {
        val obj = SyncableObjectData(
            objectId = "task-2",
            objectType = "Task",
            version = 1,
            fields = mutableMapOf(
                "title" to "Complete project",
                "priority" to 5
            )
        )

        val fieldMap = obj.toFieldMap()
        assertEquals("Complete project", fieldMap["title"])
        assertEquals(5, fieldMap["priority"])
    }

    @Test
    fun testSyncableObjectFromFieldMap() {
        val obj = SyncableObjectData(
            objectId = "task-3",
            objectType = "Task",
            version = 1
        )

        val fields = mapOf(
            "title" to "New task",
            "completed" to true
        )

        obj.fromFieldMap(fields)

        assertEquals("New task", obj.fields["title"])
        assertEquals(true, obj.fields["completed"])
    }
}