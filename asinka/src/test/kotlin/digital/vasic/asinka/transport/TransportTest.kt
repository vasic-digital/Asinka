package digital.vasic.asinka.transport

import digital.vasic.asinka.proto.EventMessage
import digital.vasic.asinka.proto.EventResponse
import digital.vasic.asinka.proto.HandshakeRequest
import digital.vasic.asinka.proto.HandshakeResponse
import digital.vasic.asinka.proto.HeartbeatRequest
import digital.vasic.asinka.proto.HeartbeatResponse
import digital.vasic.asinka.proto.SyncMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransportTest {

    private lateinit var transportConfig: TransportConfig
    private lateinit var serviceImpl: AsinkaServiceImpl

    @Before
    fun setup() {
        transportConfig = TransportConfig(
            maxMessageSize = 4 * 1024 * 1024,
            keepAliveTime = 30,
            keepAliveTimeout = 10,
            keepAliveWithoutCalls = true,
            idleTimeout = 300
        )

        serviceImpl = object : AsinkaServiceImpl() {
            override suspend fun handshake(request: HandshakeRequest): HandshakeResponse {
                return HandshakeResponse.newBuilder()
                    .setSuccess(true)
                    .setSessionId("test-session")
                    .build()
            }

            override suspend fun sendEvent(request: EventMessage): EventResponse {
                return EventResponse.newBuilder()
                    .setSuccess(true)
                    .setEventId(request.eventId)
                    .build()
            }

            override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
                return HeartbeatResponse.newBuilder()
                    .setSuccess(true)
                    .setServerTimestamp(System.currentTimeMillis())
                    .build()
            }

            override fun syncObjects(requests: Flow<SyncMessage>): Flow<SyncMessage> {
                return flowOf()
            }
        }
    }

    @Test
    fun testTransportConfigCreation() {
        assertEquals(4 * 1024 * 1024, transportConfig.maxMessageSize)
        assertEquals(30L, transportConfig.keepAliveTime)
        assertEquals(10L, transportConfig.keepAliveTimeout)
        assertTrue(transportConfig.keepAliveWithoutCalls)
        assertEquals(300L, transportConfig.idleTimeout)
    }

    @Test
    fun testTransportConfigDefaults() {
        val defaultConfig = TransportConfig()
        assertEquals(4 * 1024 * 1024, defaultConfig.maxMessageSize)
        assertEquals(30L, defaultConfig.keepAliveTime)
    }

    @Test
    fun testGrpcTransportCreation() = runTest {
        val transport = GrpcTransport(transportConfig, serviceImpl)
        assertNotNull(transport)
    }

    @Test
    fun testServiceImplHandshake() = runTest {
        val request = HandshakeRequest.newBuilder()
            .setAppId("test-app")
            .build()

        val response = serviceImpl.handshake(request)

        assertTrue(response.success)
        assertEquals("test-session", response.sessionId)
    }

    @Test
    fun testServiceImplSendEvent() = runTest {
        val request = EventMessage.newBuilder()
            .setEventId("event-1")
            .setEventType("test")
            .setTimestamp(System.currentTimeMillis())
            .setSessionId("session-1")
            .build()

        val response = serviceImpl.sendEvent(request)

        assertTrue(response.success)
        assertEquals("event-1", response.eventId)
    }

    @Test
    fun testServiceImplHeartbeat() = runTest {
        val request = HeartbeatRequest.newBuilder()
            .setSessionId("session-1")
            .setTimestamp(System.currentTimeMillis())
            .build()

        val response = serviceImpl.heartbeat(request)

        assertTrue(response.success)
        assertTrue(response.serverTimestamp > 0)
    }

    @Test
    fun testServiceImplSyncObjects() = runTest {
        val requests = flowOf(
            SyncMessage.newBuilder().build()
        )

        val responses = serviceImpl.syncObjects(requests)
        assertNotNull(responses)
    }

    @Test
    fun testTransportClientInterface() {
        val mockClient = mockk<TransportClient>()
        coEvery { mockClient.clientId } returns "test-client"

        assertEquals("test-client", mockClient.clientId)
    }

    @Test
    fun testServiceImplSyncFlow() = runTest {
        val flow = serviceImpl.syncFlow
        assertNotNull(flow)
    }
}