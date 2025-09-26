package digital.vasic.asinka.events

import digital.vasic.asinka.proto.EventMessage
import digital.vasic.asinka.proto.FieldValue
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.UUID

interface EventManager {
    suspend fun sendEvent(event: AsinkaEvent)
    suspend fun processRemoteEvent(eventMessage: EventMessage)
    fun observeEvents(eventType: String? = null): Flow<AsinkaEvent>
    fun registerEventReceiver(receiver: EventReceiver)
    fun unregisterEventReceiver(receiver: EventReceiver)
}

data class AsinkaEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val data: Map<String, Any?> = emptyMap(),
    val priority: EventPriority = EventPriority.NORMAL
) {
    fun toProto(sessionId: String): EventMessage {
        val protoData = data.mapValues { (_, value) ->
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

        return EventMessage.newBuilder()
            .setEventId(eventId)
            .setEventType(eventType)
            .setTimestamp(timestamp)
            .putAllData(protoData)
            .setSessionId(sessionId)
            .setPriority(priority.ordinal)
            .build()
    }

    companion object {
        fun fromProto(message: EventMessage): AsinkaEvent {
            val data = message.dataMap.mapValues { (_, fieldValue) ->
                when {
                    fieldValue.hasStringValue() -> fieldValue.stringValue
                    fieldValue.hasIntValue() -> fieldValue.intValue
                    fieldValue.hasDoubleValue() -> fieldValue.doubleValue
                    fieldValue.hasBoolValue() -> fieldValue.boolValue
                    fieldValue.hasBytesValue() -> fieldValue.bytesValue.toByteArray()
                    else -> null
                }
            }

            return AsinkaEvent(
                eventId = message.eventId,
                eventType = message.eventType,
                timestamp = message.timestamp,
                data = data,
                priority = EventPriority.values().getOrElse(message.priority) { EventPriority.NORMAL }
            )
        }
    }
}

enum class EventPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

interface EventReceiver {
    val eventTypes: List<String>
    suspend fun onEventReceived(event: AsinkaEvent)
}

class DefaultEventManager : EventManager {
    private val _eventsFlow = MutableSharedFlow<AsinkaEvent>(replay = 0, extraBufferCapacity = 100)
    private val eventsFlow = _eventsFlow.asSharedFlow()
    private val receivers = mutableSetOf<EventReceiver>()

    override suspend fun sendEvent(event: AsinkaEvent) {
        _eventsFlow.emit(event)
    }

    override suspend fun processRemoteEvent(eventMessage: EventMessage) {
        val event = AsinkaEvent.fromProto(eventMessage)
        _eventsFlow.emit(event)

        receivers.forEach { receiver ->
            if (receiver.eventTypes.isEmpty() || event.eventType in receiver.eventTypes) {
                receiver.onEventReceived(event)
            }
        }
    }

    override fun observeEvents(eventType: String?): Flow<AsinkaEvent> {
        return if (eventType != null) {
            eventsFlow.filter { it.eventType == eventType }
        } else {
            eventsFlow
        }
    }

    override fun registerEventReceiver(receiver: EventReceiver) {
        receivers.add(receiver)
    }

    override fun unregisterEventReceiver(receiver: EventReceiver) {
        receivers.remove(receiver)
    }
}

abstract class AsinkaEventReceiver : EventReceiver {
    override val eventTypes: List<String> = emptyList()

    override suspend fun onEventReceived(event: AsinkaEvent) {
        handleEvent(event)
    }

    abstract suspend fun handleEvent(event: AsinkaEvent)
}