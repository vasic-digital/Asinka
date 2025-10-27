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


package digital.vasic.asinka.events

import digital.vasic.asinka.proto.EventMessage
import digital.vasic.asinka.proto.FieldValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventManagerTest {

    private lateinit var eventManager: EventManager

    @Before
    fun setup() {
        eventManager = DefaultEventManager()
    }

    @Test
    fun testSendEvent() = runTest {
        val event = AsinkaEvent(
            eventId = "event-1",
            eventType = "notification",
            data = mapOf("message" to "Hello"),
            priority = EventPriority.NORMAL
        )

        eventManager.sendEvent(event)
    }

    @Test
    fun testObserveEvents() = runTest {
        val events = mutableListOf<AsinkaEvent>()

        launch {
            eventManager.observeEvents().take(2).toList(events)
        }

        val event1 = AsinkaEvent(
            eventType = "type1",
            data = mapOf("key" to "value1")
        )

        val event2 = AsinkaEvent(
            eventType = "type2",
            data = mapOf("key" to "value2")
        )

        eventManager.sendEvent(event1)
        eventManager.sendEvent(event2)

        kotlinx.coroutines.delay(100)

        assertEquals(2, events.size)
    }

    @Test
    fun testObserveEventsByType() = runTest {
        val events = mutableListOf<AsinkaEvent>()

        launch {
            eventManager.observeEvents("specific-type").take(1).toList(events)
        }

        val event1 = AsinkaEvent(
            eventType = "other-type",
            data = emptyMap()
        )

        val event2 = AsinkaEvent(
            eventType = "specific-type",
            data = mapOf("data" to "test")
        )

        eventManager.sendEvent(event1)
        eventManager.sendEvent(event2)

        kotlinx.coroutines.delay(100)

        assertEquals(1, events.size)
        assertEquals("specific-type", events[0].eventType)
    }

    @Test
    fun testProcessRemoteEvent() = runTest {
        val protoEvent = EventMessage.newBuilder()
            .setEventId("remote-1")
            .setEventType("remote-event")
            .setTimestamp(System.currentTimeMillis())
            .putData("key", FieldValue.newBuilder().setStringValue("value").build())
            .setSessionId("session-1")
            .setPriority(2)
            .build()

        eventManager.processRemoteEvent(protoEvent)
    }

    @Test
    fun testRegisterEventReceiver() = runTest {
        val receivedEvents = mutableListOf<AsinkaEvent>()

        val receiver = object : AsinkaEventReceiver() {
            override val eventTypes = listOf("test-type")

            override suspend fun handleEvent(event: AsinkaEvent) {
                receivedEvents.add(event)
            }
        }

        eventManager.registerEventReceiver(receiver)

        val event = AsinkaEvent(
            eventType = "test-type",
            data = mapOf("test" to "data")
        )

        val protoEvent = event.toProto("session-1")
        eventManager.processRemoteEvent(protoEvent)

        kotlinx.coroutines.delay(100)

        assertTrue(receivedEvents.size > 0)
        assertEquals("test-type", receivedEvents[0].eventType)
    }

    @Test
    fun testUnregisterEventReceiver() = runTest {
        val receivedEvents = mutableListOf<AsinkaEvent>()

        val receiver = object : AsinkaEventReceiver() {
            override suspend fun handleEvent(event: AsinkaEvent) {
                receivedEvents.add(event)
            }
        }

        eventManager.registerEventReceiver(receiver)
        eventManager.unregisterEventReceiver(receiver)

        val event = AsinkaEvent(
            eventType = "test",
            data = emptyMap()
        )

        eventManager.sendEvent(event)

        kotlinx.coroutines.delay(100)

        assertTrue(receivedEvents.isEmpty())
    }

    @Test
    fun testEventReceiverWithEmptyEventTypes() = runTest {
        val receivedEvents = mutableListOf<AsinkaEvent>()

        val receiver = object : AsinkaEventReceiver() {
            override val eventTypes = emptyList<String>()

            override suspend fun handleEvent(event: AsinkaEvent) {
                receivedEvents.add(event)
            }
        }

        eventManager.registerEventReceiver(receiver)

        val event = AsinkaEvent(
            eventType = "any-type",
            data = emptyMap()
        )

        val protoEvent = event.toProto("session-1")
        eventManager.processRemoteEvent(protoEvent)

        kotlinx.coroutines.delay(100)

        assertTrue(receivedEvents.size > 0)
    }

    @Test
    fun testAsinkaEventToProto() {
        val event = AsinkaEvent(
            eventId = "event-123",
            eventType = "notification",
            timestamp = 1234567890L,
            data = mapOf(
                "string" to "text",
                "int" to 42,
                "long" to 100L,
                "double" to 3.14,
                "float" to 2.5f,
                "bool" to true,
                "bytes" to byteArrayOf(1, 2, 3)
            ),
            priority = EventPriority.HIGH
        )

        val proto = event.toProto("session-1")

        assertEquals("event-123", proto.eventId)
        assertEquals("notification", proto.eventType)
        assertEquals(1234567890L, proto.timestamp)
        assertEquals("session-1", proto.sessionId)
        assertEquals(EventPriority.HIGH.ordinal, proto.priority)
        assertEquals(7, proto.dataMap.size)
    }

    @Test
    fun testAsinkaEventFromProto() {
        val proto = EventMessage.newBuilder()
            .setEventId("proto-event")
            .setEventType("test-event")
            .setTimestamp(9876543210L)
            .putData("string", FieldValue.newBuilder().setStringValue("value").build())
            .putData("int", FieldValue.newBuilder().setIntValue(99).build())
            .putData("double", FieldValue.newBuilder().setDoubleValue(1.23).build())
            .putData("bool", FieldValue.newBuilder().setBoolValue(false).build())
            .putData("bytes", FieldValue.newBuilder().setBytesValue(
                com.google.protobuf.ByteString.copyFrom(byteArrayOf(4, 5, 6))
            ).build())
            .setSessionId("session-2")
            .setPriority(3)
            .build()

        val event = AsinkaEvent.fromProto(proto)

        assertEquals("proto-event", event.eventId)
        assertEquals("test-event", event.eventType)
        assertEquals(9876543210L, event.timestamp)
        assertEquals(EventPriority.URGENT, event.priority)
        assertEquals("value", event.data["string"])
        assertEquals(99L, event.data["int"])
        assertEquals(1.23, event.data["double"])
        assertEquals(false, event.data["bool"])
        assertArrayEquals(byteArrayOf(4, 5, 6), event.data["bytes"] as ByteArray)
    }

    @Test
    fun testEventPriorityValues() {
        assertEquals(0, EventPriority.LOW.ordinal)
        assertEquals(1, EventPriority.NORMAL.ordinal)
        assertEquals(2, EventPriority.HIGH.ordinal)
        assertEquals(3, EventPriority.URGENT.ordinal)
    }

    @Test
    fun testEventWithNullData() {
        val event = AsinkaEvent(
            eventType = "test",
            data = mapOf("nullValue" to null)
        )

        val proto = event.toProto("session")
        assertNotNull(proto.dataMap["nullValue"])
    }

    @Test
    fun testEventWithUnknownDataType() {
        class CustomType(val value: String)

        val event = AsinkaEvent(
            eventType = "test",
            data = mapOf("custom" to CustomType("test"))
        )

        val proto = event.toProto("session")
        assertTrue(proto.dataMap["custom"]!!.hasStringValue())
    }

    @Test
    fun testEventFromProtoWithInvalidPriority() {
        val proto = EventMessage.newBuilder()
            .setEventId("test")
            .setEventType("test")
            .setTimestamp(System.currentTimeMillis())
            .setSessionId("session")
            .setPriority(999)
            .build()

        val event = AsinkaEvent.fromProto(proto)
        assertEquals(EventPriority.NORMAL, event.priority)
    }
}