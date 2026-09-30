package it.paolostefani.tclremote.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/**
 * Discovers Android TVs on the local network via mDNS / NSD, browsing for the
 * Android TV Remote v2 service type (_androidtvremote2._tcp).
 */
class TvDiscovery(private val context: Context) {

    data class TvDevice(val name: String, val host: String, val port: Int)

    suspend fun discover(timeoutMs: Long = 4000): List<TvDevice> = withContext(Dispatchers.IO) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = CopyOnWriteArrayList<TvDevice>()
        suspendCancellableCoroutine { cont ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    found.add(TvDevice(info.serviceName, host, info.port))
                }
            }

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, resolveListener)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                    nsd.stopServiceDiscovery(this)
                }
                override fun onStopDiscoveryFailed(serviceType: String, error: Int) {
                    nsd.stopServiceDiscovery(this)
                }
            }

            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            cont.invokeOnCancellation {
                try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
            }

            Thread {
                try {
                    Thread.sleep(timeoutMs)
                } catch (_: InterruptedException) {}
                try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
                cont.resume(found.toList())
            }.apply { isDaemon = true }.start()
        }
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
