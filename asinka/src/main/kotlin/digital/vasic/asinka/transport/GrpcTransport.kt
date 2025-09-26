package digital.vasic.asinka.transport

import digital.vasic.asinka.proto.AsinkaServiceGrpcKt
import digital.vasic.asinka.proto.EventMessage
import digital.vasic.asinka.proto.EventResponse
import digital.vasic.asinka.proto.HandshakeRequest
import digital.vasic.asinka.proto.HandshakeResponse
import digital.vasic.asinka.proto.HeartbeatRequest
import digital.vasic.asinka.proto.HeartbeatResponse
import digital.vasic.asinka.proto.SyncMessage
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

interface Transport {
    suspend fun startServer(port: Int)
    suspend fun stopServer()
    suspend fun connectToClient(host: String, port: Int): TransportClient
    suspend fun disconnectClient(clientId: String)
}

interface TransportClient {
    val clientId: String
    suspend fun handshake(request: HandshakeRequest): HandshakeResponse
    suspend fun syncObjects(outgoing: Flow<SyncMessage>): Flow<SyncMessage>
    suspend fun sendEvent(event: EventMessage): EventResponse
    suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse
    suspend fun close()
}

data class TransportConfig(
    val maxMessageSize: Int = 4 * 1024 * 1024,
    val keepAliveTime: Long = 30,
    val keepAliveTimeout: Long = 10,
    val keepAliveWithoutCalls: Boolean = true,
    val idleTimeout: Long = 300
)

class GrpcTransport(
    private val config: TransportConfig = TransportConfig(),
    private val serviceImpl: AsinkaServiceImpl
) : Transport {

    private var server: Server? = null
    private val clients = mutableMapOf<String, GrpcTransportClient>()

    override suspend fun startServer(port: Int) {
        server = NettyServerBuilder.forPort(port)
            .addService(serviceImpl)
            .maxInboundMessageSize(config.maxMessageSize)
            .keepAliveTime(config.keepAliveTime, TimeUnit.SECONDS)
            .keepAliveTimeout(config.keepAliveTimeout, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(config.keepAliveWithoutCalls)
            .build()
            .start()
    }

    override suspend fun stopServer() {
        server?.shutdown()
        server?.awaitTermination(5, TimeUnit.SECONDS)
        server = null
    }

    override suspend fun connectToClient(host: String, port: Int): TransportClient {
        val clientId = "$host:$port"

        return clients.getOrPut(clientId) {
            val channel = NettyChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(config.maxMessageSize)
                .keepAliveTime(config.keepAliveTime, TimeUnit.SECONDS)
                .keepAliveTimeout(config.keepAliveTimeout, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(config.keepAliveWithoutCalls)
                .idleTimeout(config.idleTimeout, TimeUnit.SECONDS)
                .build()

            GrpcTransportClient(clientId, channel)
        }
    }

    override suspend fun disconnectClient(clientId: String) {
        clients.remove(clientId)?.close()
    }

    suspend fun shutdown() {
        stopServer()
        clients.values.forEach { it.close() }
        clients.clear()
    }
}

class GrpcTransportClient(
    override val clientId: String,
    private val channel: ManagedChannel
) : TransportClient {

    private val stub = AsinkaServiceGrpcKt.AsinkaServiceCoroutineStub(channel)

    override suspend fun handshake(request: HandshakeRequest): HandshakeResponse {
        return stub.handshake(request)
    }

    override suspend fun syncObjects(outgoing: Flow<SyncMessage>): Flow<SyncMessage> {
        return stub.syncObjects(outgoing)
    }

    override suspend fun sendEvent(event: EventMessage): EventResponse {
        return stub.sendEvent(event)
    }

    override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        return stub.heartbeat(request)
    }

    override suspend fun close() {
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }
}

abstract class AsinkaServiceImpl : AsinkaServiceGrpcKt.AsinkaServiceCoroutineImplBase() {
    private val _syncFlow = MutableSharedFlow<SyncMessage>()
    val syncFlow = _syncFlow.asSharedFlow()

    protected suspend fun emitSyncMessage(message: SyncMessage) {
        _syncFlow.emit(message)
    }
}