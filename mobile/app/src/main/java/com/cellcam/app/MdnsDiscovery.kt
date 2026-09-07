package com.cellcam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

data class MdnsServer(val ip: String, val port: Int, val preferUsb: Boolean)

private data class Subnet(val base: Long, val prefix: Int, val preferUsb: Boolean)

/**
 * Descoberta do desktop CellCam na rede.
 *
 * 1) Primeiro tenta mDNS (NsdManager) — rápido e com a porta exata.
 * 2) Se o mDNS falhar (bug conhecido do NsdManager em aparelhos Android 10,
 *    ex. Moto G7: "código 0") cai para VARRE tudo da subrede local: testa a
 *    porta 8081 via TCP em cada IP, priorizando interfaces de cabo (USB).
 */
class MdnsDiscovery(private val context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var retryJob: Job? = null
    private var timeoutJob: Job? = null
    private var active = false

    private val found = mutableMapOf<String, MdnsServer>()
    private var onDiscoveredCb: ((List<MdnsServer>) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null

    fun start(onDiscovered: (List<MdnsServer>) -> Unit, onError: (String) -> Unit) {
        if (active) return
        active = true
        onDiscoveredCb = onDiscovered
        onErrorCb = onError
        found.clear()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith("_cellcam.")) return
                runCatching { nsd.resolveService(serviceInfo, serviceResolveListener()) }
                    .onFailure { onErrorCb?.invoke("Falha ao resolver serviço mDNS") }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handleStartFailure(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        tryDiscoverMdns()
    }

    private fun tryDiscoverMdns() {
        if (!active) return
        val ok = runCatching {
            nsd.discoverServices("_cellcam.", NsdManager.PROTOCOL_DNS_SD, discoveryListener!!)
        }.isSuccess
        if (ok) {
            timeoutJob = mainScope.launch {
                delay(MDNS_WINDOW_MS)
                if (!active) return@launch
                // Nada encontrado via mDNS; cai para varredura da subrede
                fallbackScan()
            }
        } else {
            handleStartFailure(FAILURE_INTERNAL_ERROR)
        }
    }

    private fun handleStartFailure(errorCode: Int) {
        if (!active) return
        if (errorCode != FAILURE_INTERNAL_ERROR) {
            // Falha transiente (ex. busca já ativa): tenta mais uma vez
            retryJob = mainScope.launch {
                delay(700)
                if (active) tryDiscoverMdns()
            }
        } else {
            // mDNS indisponível no aparelho: varre a subrede
            fallbackScan()
        }
    }

    private fun serviceResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val ip = serviceInfo.host?.hostAddress ?: return
            if (ip == "0.0.0.0") return
            val key = "$ip:${serviceInfo.port}"
            found[key] = MdnsServer(ip, serviceInfo.port, isUsbHost(ip))
            onDiscoveredCb?.invoke(prioritizeUsb(found.values.toList()))
        }
    }

    // ------------------------------------------------------------------
    // Fallback: varredura das subredes locais (porta 8081), cabo primeiro
    // ------------------------------------------------------------------

    private fun fallbackScan() {
        mainScope.launch {
            bindingStatus("Procurando desktop na rede (varredura)...")
            val subnets = collectSubnets()
            if (subnets.isEmpty() || !active) {
                onErrorCb?.invoke("Nenhuma rede ativa para procurar o desktop")
                return@launch
            }
            val foundServer = scanSubnets(subnets)
            if (foundServer != null && active) {
                onDiscoveredCb?.invoke(listOf(foundServer))
            } else if (active) {
                onErrorCb?.invoke("Desktop não encontrado na rede. Verifique se ele está ligado.")
            }
            stop()
        }
    }

    private suspend fun bindingStatus(message: String) {
        // avisa a UI para o texto de status, sem mudar a interface do MdnsDiscovery
        onStatusCb?.invoke(message)
    }

    private fun collectSubnets(): List<Subnet> {
        val result = mutableListOf<Subnet>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name
                val prefer = isUsbName(name)
                for (addr in Collections.list(iface.inetAddresses)) {
                    val ipv4 = addr.address ?: continue
                    if (ipv4.size != 4) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue
                    val value = ((ipv4[0].toLong() and 0xFF) shl 24) or
                        ((ipv4[1].toLong() and 0xFF) shl 16) or
                        ((ipv4[2].toLong() and 0xFF) shl 8) or
                        (ipv4[3].toLong() and 0xFF)
                    result.add(Subnet(value and 0xFFFFFF00L, 24, prefer))
                }
            }
        } catch (_: Exception) {
        }
        return result.distinctBy { it.base to it.preferUsb }.sortedBy { it.preferUsb.compareTo(false) }
    }

    private fun isUsbName(name: String): Boolean =
        name.contains("usb", true) ||
            name.contains("rndis", true) ||
            name.contains("ncm", true) ||
            name.contains("eth", true)

    private suspend fun scanSubnets(subnets: List<Subnet>): MdnsServer? {
        for (subnet in subnets) {
            if (!active) return null
            val meio = if (subnet.preferUsb) "cabo USB" else "WiFi"
            onStatusCb?.invoke("Verificando subrede $meio...")
            val server = withTimeoutOrNull(SCAN_SUBNET_TIMEOUT_MS) {
                scanSubnet(subnet)
            }
            if (server != null) return server
        }
        return null
    }

    private suspend fun scanSubnet(subnet: Subnet): MdnsServer? = withContext(Dispatchers.IO) {
        var foundServer: MdnsServer? = null
        coroutineScope {
            val semaphore = Semaphore(SCAN_PARALLELISM)
            val jobs = (1..254).filter { ip -> ip != localHostTail() }.map { ip ->
                async {
                    semaphore.withPermit {
                        if (foundServer != null) return@withPermit
                        val candidate = targetIp(subnet, ip)
                        if (tcpOpen(candidate)) {
                            foundServer = MdnsServer(candidate, SERVER_PORT_DEFAULT, subnet.preferUsb)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        }
        foundServer
    }

    private fun localHostTail(): Int? {
        // não testar o próprio IP é apenas uma pequena otimização
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    val b = addr.address ?: continue
                    if (b.size == 4 && !addr.hostAddress.startsWith("127.")) return b[3].toInt() and 0xFF
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun targetIp(subnet: Subnet, tail: Int): String {
        val a = (subnet.base shr 24) and 0xFF
        val b = (subnet.base shr 16) and 0xFF
        val c = (subnet.base shr 8) and 0xFF
        return "$a.$b.$c.$tail"
    }

    private fun tcpOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, SERVER_PORT_DEFAULT), TCP_TIMEOUT_MS); true }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------

    fun stop() {
        active = false
        retryJob?.cancel()
        timeoutJob?.cancel()
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    /** IPs das redes USB preferidas primeiro (cabo), WiFi depois até o restante. */
    private fun prioritizeUsb(servers: List<MdnsServer>): List<MdnsServer> =
        servers.sortedBy { it.preferUsb.compareTo(false) }

    /** True quando o host de mDNS pertence a uma interface de cabo (RNDIS/USB/Ethernet). */
    private fun isUsbHost(targetIp: String): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            val target = InetAddress.getByName(targetIp)
            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (isUsbInterface(lp) && covers(lp, target)) return true
                if (isUsbTransport(caps) && covers(lp, target)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isUsbTransport(caps: NetworkCapabilities): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)

    private fun isUsbInterface(lp: LinkProperties): Boolean = isUsbName(lp.interfaceName ?: "")

    private fun covers(lp: LinkProperties, target: InetAddress): Boolean =
        lp.routes.any { route -> route.destination?.contains(target) == true }

    companion object {
        private const val MDNS_WINDOW_MS = 3000L
        private const val SCAN_SUBNET_TIMEOUT_MS = 2500L
        private const val SCAN_PARALLELISM = 40
        private const val TCP_TIMEOUT_MS = 150
        private const val SERVER_PORT_DEFAULT = 8081
        private const val FAILURE_INTERNAL_ERROR = 0
    }

    /** Callback textual opcional para o andamento (mostrado no status do app). */
    var onStatusCb: ((String) -> Unit)? = null
}