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


package digital.vasic.asinka

import android.content.Context
import digital.vasic.asinka.discovery.NsdServiceDiscovery
import digital.vasic.asinka.discovery.ServiceDiscovery
import digital.vasic.asinka.events.DefaultEventManager
import digital.vasic.asinka.events.EventManager
import digital.vasic.asinka.handshake.DefaultHandshakeManager
import digital.vasic.asinka.handshake.HandshakeManager
import digital.vasic.asinka.handshake.HandshakeResult
import digital.vasic.asinka.models.ObjectSchema
import digital.vasic.asinka.security.AndroidSecurityManager
import digital.vasic.asinka.security.SecurityManager
import digital.vasic.asinka.sync.DefaultSyncManager
import digital.vasic.asinka.sync.SyncManager
import digital.vasic.asinka.transport.GrpcTransport
import digital.vasic.asinka.transport.TransportConfig
import digital.vasic.asinka.transport.AsinkaServiceImpl
import digital.vasic.asinka.proto.HandshakeRequest
import digital.vasic.asinka.proto.HandshakeResponse
import digital.vasic.asinka.proto.EventMessage
import digital.vasic.asinka.proto.EventResponse
import digital.vasic.asinka.proto.HeartbeatRequest
import digital.vasic.asinka.proto.HeartbeatResponse
import digital.vasic.asinka.proto.SyncMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

data class AsinkaConfig(
    val appId: String,
    val appName: String,
    val appVersion: String,
    val deviceId: String = UUID.randomUUID().toString(),
    val serverPort: Int = 8888,
    val serviceName: String = "default-sync",
    val exposedSchemas: List<ObjectSchema> = emptyList(),
    val capabilities: Map<String, String> = emptyMap(),
    val transportConfig: TransportConfig = TransportConfig()
)

class AsinkaClient private constructor(
    private val context: Context,
    val config: AsinkaConfig,
    val securityManager: SecurityManager,
    val discoveryManager: ServiceDiscovery,
    val handshakeManager: HandshakeManager,
    val syncManager: SyncManager,
    val eventManager: EventManager,
    private val transport: GrpcTransport
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, SessionInfo>()
    private var isStarted = false

    data class SessionInfo(
        val sessionId: String,
        val remoteHost: String,
        val remotePort: Int,
        val remotePublicKey: ByteArray,
        val remoteSchemas: List<ObjectSchema>,
        val remoteCapabilities: Map<String, String>
    )

    suspend fun start() {
        if (isStarted) return
        isStarted = true

        transport.startServer(config.serverPort)

        // Start advertising this service
        scope.launch {
            discoveryManager.startAdvertising(config.serviceName, config.serverPort).collect { state ->
                when (state) {
                    is digital.vasic.asinka.discovery.AdvertisingState.Advertising -> {
                        // Successfully advertising
                    }
                    is digital.vasic.asinka.discovery.AdvertisingState.Error -> {
                        // Handle advertising error
                    }
                    else -> {}
                }
            }
        }

        // Start discovery of other services
        scope.launch {
            discoveryManager.startDiscovery().collect { event ->
                when (event) {
                    is digital.vasic.asinka.discovery.DiscoveryEvent.ServiceFound -> {
                        val service = event.serviceInfo
                        // Only connect to services of the same type, not our own
                        if (service.serviceName.contains(config.serviceName) && !service.serviceName.contains(config.appId)) {
                            try {
                                connect(service.host, service.port)
                            } catch (e: Exception) {
                                // Handle connection error
                            }
                        }
                    }
                    is digital.vasic.asinka.discovery.DiscoveryEvent.ServiceLost -> {
                        // Handle service lost
                    }
                    else -> {}
                }
            }
        }

        scope.launch {
            syncManager.observeAllChanges().collect { change ->
                broadcastSyncChange(change)
            }
        }

        scope.launch {
            eventManager.observeEvents().collect { event ->
                broadcastEvent(event)
            }
        }
    }

    suspend fun stop() {
        isStarted = false
        discoveryManager.stopDiscovery()
        discoveryManager.stopAdvertising()
        transport.shutdown()
        sessions.clear()
        scope.cancel()
    }

    suspend fun connect(host: String, port: Int): Result<SessionInfo> {
        return try {
            val client = transport.connectToClient(host, port)

            val handshakeRequest = handshakeManager.createHandshakeRequest(
                appId = config.appId,
                appName = config.appName,
                appVersion = config.appVersion,
                deviceId = config.deviceId,
                exposedSchemas = config.exposedSchemas,
                capabilities = config.capabilities
            )

            val handshakeResponse = client.handshake(handshakeRequest)

            when (val result = handshakeManager.validateHandshakeResponse(handshakeRequest, handshakeResponse)) {
                is HandshakeResult.Success -> {
                    val sessionInfo = SessionInfo(
                        sessionId = result.sessionId,
                        remoteHost = host,
                        remotePort = port,
                        remotePublicKey = result.remotePublicKey,
                        remoteSchemas = result.remoteSchemas,
                        remoteCapabilities = result.remoteCapabilities
                    )
                    sessions[result.sessionId] = sessionInfo

                    startHeartbeat(result.sessionId, client)
                    startSyncStream(result.sessionId, client)

                    Result.success(sessionInfo)
                }
                is HandshakeResult.Failure -> {
                    Result.failure(Exception(result.errorMessage))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun disconnect(sessionId: String) {
        sessions.remove(sessionId)
        transport.disconnectClient(sessionId)
    }

    fun getSessions(): List<SessionInfo> = sessions.values.toList()

    private fun startHeartbeat(sessionId: String, client: digital.vasic.asinka.transport.TransportClient) {
        scope.launch {
            while (sessions.containsKey(sessionId)) {
                try {
                    val request = HeartbeatRequest.newBuilder()
                        .setSessionId(sessionId)
                        .setTimestamp(System.currentTimeMillis())
                        .build()

                    client.heartbeat(request)
                    kotlinx.coroutines.delay(30000)
                } catch (e: Exception) {
                    sessions.remove(sessionId)
                    break
                }
            }
        }
    }

    private fun startSyncStream(
        sessionId: String,
        client: digital.vasic.asinka.transport.TransportClient
    ) {
        scope.launch {
            try {
                val outgoingFlow = syncManager.observeAllChanges()
                val incomingFlow = client.syncObjects(kotlinx.coroutines.flow.flow {
                    syncManager.observeAllChanges().collect { change ->
                        when (change) {
                            is digital.vasic.asinka.sync.SyncChange.Updated -> {
                                val syncableData = change.obj as? digital.vasic.asinka.models.SyncableObjectData
                                syncableData?.let {
                                    emit(
                                        SyncMessage.newBuilder()
                                            .setUpdate(it.toProtoUpdate(sessionId))
                                            .build()
                                    )
                                }
                            }
                            is digital.vasic.asinka.sync.SyncChange.Deleted -> {
                                emit(
                                    SyncMessage.newBuilder()
                                        .setDelete(
                                            digital.vasic.asinka.proto.ObjectDelete.newBuilder()
                                                .setObjectId(change.objectId)
                                                .setObjectType(change.objectType)
                                                .setTimestamp(System.currentTimeMillis())
                                                .setSessionId(sessionId)
                                                .build()
                                        )
                                        .build()
                                )
                            }
                        }
                    }
                })

                incomingFlow.collect { syncMessage ->
                    when {
                        syncMessage.hasUpdate() -> {
                            syncManager.processRemoteUpdate(syncMessage.update)
                        }
                        syncMessage.hasDelete() -> {
                            syncManager.deleteObject(syncMessage.delete.objectId)
                        }
                    }
                }
            } catch (e: Exception) {
                sessions.remove(sessionId)
            }
        }
    }

    private suspend fun broadcastSyncChange(change: digital.vasic.asinka.sync.SyncChange) {
    }

    private suspend fun broadcastEvent(event: digital.vasic.asinka.events.AsinkaEvent) {
        sessions.forEach { (sessionId, _) ->
            scope.launch {
                try {
                    val client = transport.connectToClient(
                        sessions[sessionId]?.remoteHost ?: return@launch,
                        sessions[sessionId]?.remotePort ?: return@launch
                    )
                    client.sendEvent(event.toProto(sessionId))
                } catch (e: Exception) {
                }
            }
        }
    }

    companion object {
        fun create(context: Context, config: AsinkaConfig): AsinkaClient {
            val securityManager = AndroidSecurityManager(context)
            val discoveryManager = NsdServiceDiscovery(context)
            val syncManager = DefaultSyncManager()
            val eventManager = DefaultEventManager()

            val handshakeManager = DefaultHandshakeManager(
                securityManager = securityManager,
                localAppId = config.appId,
                localAppName = config.appName,
                localAppVersion = config.appVersion,
                localDeviceId = config.deviceId,
                localSchemas = config.exposedSchemas,
                localCapabilities = config.capabilities
            )

            val serviceImpl = object : AsinkaServiceImpl() {
                override suspend fun handshake(request: HandshakeRequest): HandshakeResponse {
                    return handshakeManager.processHandshakeRequest(request)
                }

                override suspend fun sendEvent(request: EventMessage): EventResponse {
                    eventManager.processRemoteEvent(request)
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
                    return kotlinx.coroutines.flow.flow {
                        requests.collect { message ->
                            when {
                                message.hasUpdate() -> syncManager.processRemoteUpdate(message.update)
                                message.hasDelete() -> syncManager.deleteObject(message.delete.objectId)
                            }
                        }
                    }
                }
            }

            val transport = GrpcTransport(config.transportConfig, serviceImpl)

            return AsinkaClient(
                context = context,
                config = config,
                securityManager = securityManager,
                discoveryManager = discoveryManager,
                handshakeManager = handshakeManager,
                syncManager = syncManager,
                eventManager = eventManager,
                transport = transport
            )
        }
    }
}