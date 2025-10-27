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


package digital.vasic.asinka.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

interface ServiceDiscovery {
    fun startAdvertising(serviceName: String, port: Int): Flow<AdvertisingState>
    fun startDiscovery(): Flow<DiscoveryEvent>
    fun stopAdvertising()
    fun stopDiscovery()
}

sealed class AdvertisingState {
    data object Idle : AdvertisingState()
    data class Advertising(val serviceName: String, val port: Int) : AdvertisingState()
    data class Error(val errorCode: Int, val message: String) : AdvertisingState()
}

sealed class DiscoveryEvent {
    data class ServiceFound(val serviceInfo: ServiceInfo) : DiscoveryEvent()
    data class ServiceLost(val serviceName: String) : DiscoveryEvent()
    data class Error(val errorCode: Int, val message: String) : DiscoveryEvent()
}

data class ServiceInfo(
    val serviceName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
    val attributes: Map<String, String> = emptyMap()
)

class NsdServiceDiscovery(private val context: Context) : ServiceDiscovery {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        private const val SERVICE_TYPE = "_asinka._tcp."
        private const val SERVICE_PREFIX = "asinka-"
    }

    override fun startAdvertising(serviceName: String, port: Int): Flow<AdvertisingState> =
        callbackFlow {
            val fullServiceName = "$SERVICE_PREFIX$serviceName-${UUID.randomUUID().toString().take(8)}"

            val listener = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    trySend(AdvertisingState.Error(errorCode, "Registration failed: $errorCode"))
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    trySend(AdvertisingState.Error(errorCode, "Unregistration failed: $errorCode"))
                }

                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    serviceInfo?.let {
                        trySend(AdvertisingState.Advertising(it.serviceName, port))
                    }
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    trySend(AdvertisingState.Idle)
                }
            }

            registrationListener = listener

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = fullServiceName
                this.serviceType = SERVICE_TYPE
                this.port = port
            }

            try {
                nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                trySend(AdvertisingState.Error(-1, "Failed to register: ${e.message}"))
                close(e)
            }

            awaitClose {
                try {
                    registrationListener?.let { nsdManager.unregisterService(it) }
                } catch (e: Exception) {
                }
                registrationListener = null
            }
        }

    override fun startDiscovery(): Flow<DiscoveryEvent> = callbackFlow {
        val resolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                trySend(DiscoveryEvent.Error(errorCode, "Discovery start failed: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                trySend(DiscoveryEvent.Error(errorCode, "Discovery stop failed: $errorCode"))
            }

            override fun onDiscoveryStarted(serviceType: String?) {
            }

            override fun onDiscoveryStopped(serviceType: String?) {
            }

            override fun onServiceFound(service: NsdServiceInfo?) {
                service?.let { nsdService ->
                    if (nsdService.serviceName.startsWith(SERVICE_PREFIX)) {
                        val resolveListener = object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                resolveListeners.remove(nsdService.serviceName)
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                                serviceInfo?.let { resolved ->
                                    val info = ServiceInfo(
                                        serviceName = resolved.serviceName,
                                        serviceType = resolved.serviceType,
                                        host = resolved.host?.hostAddress ?: "",
                                        port = resolved.port,
                                        attributes = resolved.attributes?.mapKeys { it.key }
                                            ?.mapValues { String(it.value) } ?: emptyMap()
                                    )
                                    trySend(DiscoveryEvent.ServiceFound(info))
                                }
                                resolveListeners.remove(nsdService.serviceName)
                            }
                        }

                        resolveListeners[nsdService.serviceName] = resolveListener
                        try {
                            nsdManager.resolveService(nsdService, resolveListener)
                        } catch (e: Exception) {
                            resolveListeners.remove(nsdService.serviceName)
                        }
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo?) {
                service?.let {
                    trySend(DiscoveryEvent.ServiceLost(it.serviceName))
                }
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            trySend(DiscoveryEvent.Error(-1, "Failed to start discovery: ${e.message}"))
            close(e)
        }

        awaitClose {
            try {
                discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
            } catch (e: Exception) {
            }
            discoveryListener = null
            resolveListeners.clear()
        }
    }

    override fun stopAdvertising() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
        }
        registrationListener = null
    }

    override fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
        }
        discoveryListener = null
    }
}