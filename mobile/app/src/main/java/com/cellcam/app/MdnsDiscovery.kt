package com.cellcam.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
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
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

data class MdnsServer(
    val ip: String,
    val port: Int,
    val preferWifi: Boolean,
    val room: String? = null
)

private data class NetSubnet(
    val network: Network,
    val base: Long,
    val prefix: Int,
    val rank: Int,
    val isUsb: Boolean
)

/**
 * Descoberta do desktop CellCam na rede.
 *
 * 1) Tenta mDNS (NsdManager) — quando funciona é rápido e traz a porta exata.
 * 2) Se o mDNS falhar (bug no Moto G7 / Android 10: "código 0") faz a
 *    varredura da subrede local, testando a porta 8081 via TCP.
 *
 * A varredura usa as redes do ConnectivityManager e faz BIND explícito dos
 * sockets à rede de cada subrede (relevante quando a rede não é a "default").
 *
 * Ordem de prioridade: WiFi → cabo USB/Ethernet → dados móveis.
 * O transporte da mídia é o WiFi (cabo USB arquivado — ver CONTEXT.md).
 */
class MdnsDiscovery(private val context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var retryJob: Job? = null
    private var timeoutJob: Job? = null
    private var active = false

    private val found = mutableMapOf<String, MdnsServer>()
    private var onDiscoveredCb: ((List<MdnsServer>) -> Unit)? = null
    private var onErrorCb: ((String) -> Unit)? = null

    /** Exibe o andamento no status do app. */
    var onStatusCb: ((String) -> Unit)? = null

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
                if (active) fallbackScan()
            }
        } else {
            handleStartFailure(FAILURE_INTERNAL_ERROR)
        }
    }

    private fun handleStartFailure(errorCode: Int) {
        if (!active) return
        if (errorCode == FAILURE_INTERNAL_ERROR) {
            // mDNS indisponível no aparelho: varre a subrede (via bind por rede)
            fallbackScan()
        } else {
            retryJob = mainScope.launch {
                delay(700)
                if (active) tryDiscoverMdns()
            }
        }
    }

    private fun serviceResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val ip = serviceInfo.host?.hostAddress ?: return
            if (ip == "0.0.0.0") return
            val roomBytes = serviceInfo.attributes?.get("room")
            val room = roomBytes
                ?.let { String(it, Charsets.UTF_8) }
                ?.takeIf { it.length == 6 }
            val key = "$ip:${serviceInfo.port}"
            found[key] = MdnsServer(ip, serviceInfo.port, preferWifi = !isUsbHost(ip), room = room)
            onDiscoveredCb?.invoke(prioritizeWifi(found.values.toList()))
        }
    }

    // ------------------------------------------------------------------
    // Fallback: varredura das redes locais (porta 8081), WiFi primeiro
    // ------------------------------------------------------------------

    private fun fallbackScan() {
        mainScope.launch {
            onStatusCb?.invoke("Procurando desktop na rede (varredura)...")
            val subnets = collectSubnets()
            if (subnets.isEmpty() || !active) {
                onErrorCb?.invoke("Nenhuma rede ativa para procurar o desktop")
                return@launch
            }
            val server = scanNetworks(subnets)
            if (server != null && active) {
                onDiscoveredCb?.invoke(listOf(server))
            } else if (active) {
                onErrorCb?.invoke("Desktop não encontrado. Verifique se está ligado e tente de novo.")
            }
            stop()
        }
    }

    /** Lista sub-redes ativas com a rede (Network) à qual pertencem, WiFi primeiro. */
    private fun collectSubnets(): List<NetSubnet> {
        val result = mutableListOf<NetSubnet>()
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val caps = cm.getNetworkCapabilities(net) ?: continue
            val usb = isUsbTransport(caps) || isUsbInterface(lp)
            val rank = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                usb -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                else -> 3
            }
            for (la in lp.linkAddresses) {
                val ipv4 = la.address?.address ?: continue
                if (ipv4.size != 4) continue
                val host = la.address.hostAddress ?: continue
                if (host.startsWith("127.") || host.startsWith("169.254.")) continue
                val prefix = la.prefixLength
                if (prefix < 16 || prefix > 30) continue
                val value = ipv4ToLong(ipv4)
                val base = value and mask(prefix)
                result.add(NetSubnet(net, base, prefix, rank, usb))
            }
        }
        return result.distinctBy { it.base to it.rank }.sortedBy { it.rank }
    }

    private suspend fun scanNetworks(subnets: List<NetSubnet>): MdnsServer? {
        for (subnet in subnets) {
            if (!active) return null
            val meio = if (subnet.isUsb) "cabo USB" else rankName(subnet.rank)
            onStatusCb?.invoke("Verificando $meio...")
            val server = withTimeoutOrNull(SCAN_SUBNET_TIMEOUT_MS) {
                scanSubnet(subnet)
            }
            if (server != null) {
                // Garante que signaling e WebRTC usem a mesma rede do cabo
                runCatching { cm.bindProcessToNetwork(subnet.network) }
                return server
            }
        }
        return null
    }

    private fun rankName(rank: Int): String = when (rank) {
        0 -> "WiFi"
        1 -> "cabo USB"
        2 -> "rede móvel"
        else -> "rede local"
    }

    private suspend fun scanSubnet(subnet: NetSubnet): MdnsServer? = withContext(Dispatchers.IO) {
        var foundServer: MdnsServer? = null
        coroutineScope {
            val semaphore = Semaphore(SCAN_PARALLELISM)
            val jobs = (1..hostCount(subnet.prefix)).map { offset ->
                async {
                    semaphore.withPermit {
                        if (foundServer != null) return@withPermit
                        val ip = ipAt(subnet.base, subnet.prefix, offset)
                        if (tcpOpen(subnet.network, ip)) {
                            val room = httpRoom(subnet.network, ip)
                            foundServer = MdnsServer(ip, SERVER_PORT_DEFAULT, subnet.isUsb.not(), room)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
        }
        foundServer
    }

    private fun hostCount(prefix: Int): Int {
        if (prefix >= 24) return 254 // offset 1..254 não sai da faixa /24
        return (1 shl (32 - prefix)) - 2
    }

    private fun ipAt(base: Long, prefix: Int, offset: Int): String {
        val hosts = 1 shl (32 - prefix)
        val idx = if (prefix >= 24) offset else (offset % hosts)
        val addr = base or idx.toLong()
        return "${(addr shr 24) and 0xFF}.${(addr shr 16) and 0xFF}.${(addr shr 8) and 0xFF}.${addr and 0xFF}"
    }

    private fun tcpOpen(network: Network, ip: String): Boolean = try {
        Socket().use { socket ->
            network.bindSocket(socket)
            socket.connect(InetSocketAddress(ip, SERVER_PORT_DEFAULT), TCP_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }

    /** GET /api/room vinculado à rede (varredura de subrede). */
    private fun httpRoom(network: Network, ip: String): String? = try {
        Socket().use { socket ->
            network.bindSocket(socket)
            httpRoomRequest(socket, ip)
        }
    } catch (_: Exception) {
        null
    }

    private fun ipv4ToLong(ip: ByteArray): Long =
        ((ip[0].toLong() and 0xFF) shl 24) or
            ((ip[1].toLong() and 0xFF) shl 16) or
            ((ip[2].toLong() and 0xFF) shl 8) or
            (ip[3].toLong() and 0xFF)

    private fun mask(prefix: Int): Long =
        if (prefix >= 32) 0xFFFFFFFFL else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    // ------------------------------------------------------------------

    fun stop() {
        active = false
        retryJob?.cancel()
        timeoutJob?.cancel()
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    /** IPs WiFi primeiro (transporte da mídia), depois cabo. */
    private fun prioritizeWifi(servers: List<MdnsServer>): List<MdnsServer> =
        servers.sortedBy { it.preferWifi.compareTo(false) }

    private fun isUsbHost(targetIp: String): Boolean {
        return try {
            for (net in cm.allNetworks) {
                val lp = cm.getLinkProperties(net) ?: continue
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if ((isUsbInterface(lp) || isUsbTransport(caps)) && covers(lp, targetIp)) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isUsbTransport(caps: NetworkCapabilities): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)

    private fun isUsbInterface(lp: LinkProperties): Boolean {
        val name = lp.interfaceName ?: return false
        return name.contains("usb", true) ||
            name.contains("rndis", true) ||
            name.contains("ncm", true) ||
            name.contains("eth", true)
    }

    private fun covers(lp: LinkProperties, targetIp: String): Boolean {
        val target = java.net.InetAddress.getByName(targetIp)
        return lp.routes.any { route -> route.destination?.contains(target) == true }
    }

    companion object {
        private const val MDNS_WINDOW_MS = 3000L
        private const val SCAN_SUBNET_TIMEOUT_MS = 3000L
        private const val SCAN_PARALLELISM = 40
        private const val TCP_TIMEOUT_MS = 200
        private const val SERVER_PORT_DEFAULT = 8081
        private const val HTTP_PORT_DEFAULT = 8080
        private const val FAILURE_INTERNAL_ERROR = 0

        /** Sala do signaling do desktop (GET /api/room) via rede padrão (IP manual). */
        fun fetchRoomDirect(ip: String): String? = try {
            Socket().use { socket -> httpRoomRequest(socket, ip) }
        } catch (_: Exception) {
            null
        }

        private fun httpRoomRequest(socket: Socket, ip: String): String? {
            socket.connect(InetSocketAddress(ip, HTTP_PORT_DEFAULT), TCP_TIMEOUT_MS * 3)
            socket.soTimeout = TCP_TIMEOUT_MS * 3
            val output = socket.getOutputStream()
            output.write("GET /api/room HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray(Charsets.UTF_8))
            output.flush()
            val input = socket.getInputStream()
            val body = StringBuilder()
            val buf = ByteArray(1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                body.append(String(buf, 0, n, Charsets.UTF_8))
                if (n < buf.size) break
            }
            val headerEnd = body.indexOf("\r\n\r\n")
            val json = if (headerEnd >= 0) body.substring(headerEnd + 4) else body.toString()
            return JSONObject(json).optString("roomCode").takeIf { it.length == 6 }
        }
    }
}