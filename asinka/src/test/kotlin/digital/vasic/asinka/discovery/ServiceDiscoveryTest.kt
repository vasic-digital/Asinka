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