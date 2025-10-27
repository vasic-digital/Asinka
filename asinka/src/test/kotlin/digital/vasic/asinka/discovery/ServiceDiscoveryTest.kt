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
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServiceDiscoveryTest {

    private lateinit var context: Context
    private lateinit var serviceDiscovery: NsdServiceDiscovery

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        serviceDiscovery = NsdServiceDiscovery(context)
    }

    @Test
    fun testServiceDiscoveryCreation() {
        assertNotNull(serviceDiscovery)
    }

    @Test
    fun testAdvertisingStateIdle() = runTest {
        val flow = serviceDiscovery.startAdvertising("test-service", 8888)
        assertNotNull(flow)
    }

    @Test
    fun testDiscoveryEventFlow() = runTest {
        val flow = serviceDiscovery.startDiscovery()
        assertNotNull(flow)
    }

    @Test
    fun testServiceInfoCreation() {
        val serviceInfo = ServiceInfo(
            serviceName = "test-service",
            serviceType = "_asinka._tcp.",
            host = "192.168.1.1",
            port = 8888,
            attributes = mapOf("version" to "1.0")
        )

        assertEquals("test-service", serviceInfo.serviceName)
        assertEquals("_asinka._tcp.", serviceInfo.serviceType)
        assertEquals("192.168.1.1", serviceInfo.host)
        assertEquals(8888, serviceInfo.port)
        assertEquals(1, serviceInfo.attributes.size)
        assertEquals("1.0", serviceInfo.attributes["version"])
    }

    @Test
    fun testAdvertisingStateTypes() {
        val idleState = AdvertisingState.Idle
        assertTrue(idleState is AdvertisingState.Idle)

        val advertisingState = AdvertisingState.Advertising("test", 8888)
        assertTrue(advertisingState is AdvertisingState.Advertising)
        assertEquals("test", advertisingState.serviceName)
        assertEquals(8888, advertisingState.port)

        val errorState = AdvertisingState.Error(1, "Test error")
        assertTrue(errorState is AdvertisingState.Error)
        assertEquals(1, errorState.errorCode)
        assertEquals("Test error", errorState.message)
    }

    @Test
    fun testDiscoveryEventTypes() {
        val serviceInfo = ServiceInfo(
            serviceName = "test",
            serviceType = "_asinka._tcp.",
            host = "127.0.0.1",
            port = 8888
        )

        val foundEvent = DiscoveryEvent.ServiceFound(serviceInfo)
        assertTrue(foundEvent is DiscoveryEvent.ServiceFound)
        assertEquals("test", foundEvent.serviceInfo.serviceName)

        val lostEvent = DiscoveryEvent.ServiceLost("test")
        assertTrue(lostEvent is DiscoveryEvent.ServiceLost)
        assertEquals("test", lostEvent.serviceName)

        val errorEvent = DiscoveryEvent.Error(1, "Error")
        assertTrue(errorEvent is DiscoveryEvent.Error)
        assertEquals(1, errorEvent.errorCode)
    }

    @Test
    fun testStopAdvertising() {
        serviceDiscovery.stopAdvertising()
    }

    @Test
    fun testStopDiscovery() {
        serviceDiscovery.stopDiscovery()
    }
}