package dev.naijun.dununlocker.proxy

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class ProxyEndpointKind {
    HOTSPOT,
    USB_TETHER,
    BLUETOOTH_TETHER,
    WIFI,
    OTHER
}

data class ProxyEndpoint(
    val interfaceName: String,
    val ip: String,
    val isTetheringInterface: Boolean,
    val kind: ProxyEndpointKind = ProxyEndpointKind.OTHER
)

internal object NetworkAddressResolver {
    private val hotspotPrefixes = listOf("swlan", "ap", "softap")
    private val usbTetherPrefixes = listOf("rndis", "usb", "ncm")
    private val btTetherPrefixes = listOf("bt-pan", "tether")
    private val lanPrefixes = listOf("wlan", "eth")
    private val ignoredPrefixes = listOf(
        "lo", "rmnet", "ccmni", "pdp", "v4-", "tun", "tap", "ppp", "dummy", "ipsec", "epdg"
    )

    fun detectEndpoints(): List<ProxyEndpoint> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        val endpoints = mutableListOf<ProxyEndpoint>()
        for (iface in interfaces) {
            val name = iface.name?.lowercase().orEmpty()
            if (name.isEmpty() || ignoredPrefixes.any { name.startsWith(it) }) continue
            val isUp = try {
                iface.isUp && !iface.isLoopback
            } catch (_: Exception) {
                false
            }
            if (!isUp) continue

            val kind = when {
                hotspotPrefixes.any { name.startsWith(it) } -> ProxyEndpointKind.HOTSPOT
                usbTetherPrefixes.any { name.startsWith(it) } -> ProxyEndpointKind.USB_TETHER
                btTetherPrefixes.any { name.startsWith(it) } -> ProxyEndpointKind.BLUETOOTH_TETHER
                lanPrefixes.any { name.startsWith(it) } -> ProxyEndpointKind.WIFI
                else -> continue
            }
            val isTether = kind == ProxyEndpointKind.HOTSPOT ||
                kind == ProxyEndpointKind.USB_TETHER ||
                kind == ProxyEndpointKind.BLUETOOTH_TETHER

            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && isPrivateOrLoopback(addr)) {
                    val hostAddress = addr.hostAddress ?: continue
                    endpoints.add(
                        ProxyEndpoint(
                            interfaceName = iface.name,
                            ip = hostAddress,
                            isTetheringInterface = isTether,
                            kind = kind
                        )
                    )
                }
            }
        }

        return endpoints
            .distinctBy { it.ip }
            .sortedWith(compareByDescending<ProxyEndpoint> { it.isTetheringInterface }.thenBy { it.interfaceName })
    }

    fun isPrivateOrLoopback(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) {
            return true
        }
        val bytes = address.address
        if (address is Inet4Address && bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 100.64.0.0/10 (CGNAT/tether), 169.254.0.0/16
            return b0 == 10 ||
                (b0 == 172 && b1 in 16..31) ||
                (b0 == 192 && b1 == 168) ||
                (b0 == 100 && b1 in 64..127) ||
                (b0 == 169 && b1 == 254)
        }
        if (address is Inet6Address && bytes.size == 16) {
            // ULA fc00::/7
            val b0 = bytes[0].toInt() and 0xFE
            return b0 == 0xFC
        }
        return false
    }
}
