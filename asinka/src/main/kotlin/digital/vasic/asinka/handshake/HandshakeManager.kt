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


package digital.vasic.asinka.handshake

import digital.vasic.asinka.models.ObjectSchema
import digital.vasic.asinka.proto.HandshakeRequest
import digital.vasic.asinka.proto.HandshakeResponse
import digital.vasic.asinka.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import com.google.protobuf.ByteString

interface HandshakeManager {
    suspend fun createHandshakeRequest(
        appId: String,
        appName: String,
        appVersion: String,
        deviceId: String,
        exposedSchemas: List<ObjectSchema>,
        capabilities: Map<String, String>
    ): HandshakeRequest

    suspend fun processHandshakeRequest(request: HandshakeRequest): HandshakeResponse

    suspend fun validateHandshakeResponse(
        request: HandshakeRequest,
        response: HandshakeResponse
    ): HandshakeResult
}

sealed class HandshakeResult {
    data class Success(
        val sessionId: String,
        val remotePublicKey: ByteArray,
        val remoteSchemas: List<ObjectSchema>,
        val remoteCapabilities: Map<String, String>
    ) : HandshakeResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Success

            if (sessionId != other.sessionId) return false
            if (!remotePublicKey.contentEquals(other.remotePublicKey)) return false
            if (remoteSchemas != other.remoteSchemas) return false
            if (remoteCapabilities != other.remoteCapabilities) return false

            return true
        }

        override fun hashCode(): Int {
            var result = sessionId.hashCode()
            result = 31 * result + remotePublicKey.contentHashCode()
            result = 31 * result + remoteSchemas.hashCode()
            result = 31 * result + remoteCapabilities.hashCode()
            return result
        }
    }

    data class Failure(val errorMessage: String) : HandshakeResult()
}

class DefaultHandshakeManager(
    private val securityManager: SecurityManager,
    private val localAppId: String,
    private val localAppName: String,
    private val localAppVersion: String,
    private val localDeviceId: String,
    private val localSchemas: List<ObjectSchema>,
    private val localCapabilities: Map<String, String>
) : HandshakeManager {

    companion object {
        private val SUPPORTED_PROTOCOLS = listOf("asinka-v1")
    }

    override suspend fun createHandshakeRequest(
        appId: String,
        appName: String,
        appVersion: String,
        deviceId: String,
        exposedSchemas: List<ObjectSchema>,
        capabilities: Map<String, String>
    ): HandshakeRequest {
        val publicKey = securityManager.getPublicKey()
            ?: throw IllegalStateException("Public key not available")

        return HandshakeRequest.newBuilder()
            .setAppId(appId)
            .setAppName(appName)
            .setAppVersion(appVersion)
            .setDeviceId(deviceId)
            .setPublicKey(ByteString.copyFrom(publicKey.encoded))
            .addAllSupportedProtocols(SUPPORTED_PROTOCOLS)
            .addAllExposedSchemas(exposedSchemas.map { it.toProto() })
            .putAllCapabilities(capabilities)
            .build()
    }

    override suspend fun processHandshakeRequest(request: HandshakeRequest): HandshakeResponse {
        return try {
            val hasCommonProtocol = request.supportedProtocolsList.any { it in SUPPORTED_PROTOCOLS }

            if (!hasCommonProtocol) {
                return HandshakeResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage("No compatible protocol version")
                    .build()
            }

            val sessionId = UUID.randomUUID().toString()
            val publicKey = securityManager.getPublicKey()
                ?: throw IllegalStateException("Public key not available")

            HandshakeResponse.newBuilder()
                .setSuccess(true)
                .setSessionId(sessionId)
                .setPublicKey(ByteString.copyFrom(publicKey.encoded))
                .addAllExposedSchemas(localSchemas.map { it.toProto() })
                .putAllCapabilities(localCapabilities)
                .build()
        } catch (e: Exception) {
            HandshakeResponse.newBuilder()
                .setSuccess(false)
                .setErrorMessage("Handshake processing failed: ${e.message}")
                .build()
        }
    }

    override suspend fun validateHandshakeResponse(
        request: HandshakeRequest,
        response: HandshakeResponse
    ): HandshakeResult {
        if (!response.success) {
            return HandshakeResult.Failure(response.errorMessage)
        }

        if (response.sessionId.isEmpty()) {
            return HandshakeResult.Failure("Invalid session ID")
        }

        if (response.publicKey.isEmpty) {
            return HandshakeResult.Failure("Missing public key")
        }

        val remoteSchemas = response.exposedSchemasList.map { ObjectSchema.fromProto(it) }
        val remoteCapabilities = response.capabilitiesMap

        return HandshakeResult.Success(
            sessionId = response.sessionId,
            remotePublicKey = response.publicKey.toByteArray(),
            remoteSchemas = remoteSchemas,
            remoteCapabilities = remoteCapabilities
        )
    }
}