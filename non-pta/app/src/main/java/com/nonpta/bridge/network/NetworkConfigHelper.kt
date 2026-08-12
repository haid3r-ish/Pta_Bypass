package com.nonpta.bridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager

/**
 * Detects the Wi-Fi DHCP gateway IP address.
 *
 * Primary path: ConnectivityManager → LinkProperties → default route gateway (API 29+).
 * Fallback: WifiManager → DhcpInfo (deprecated but reliable on older paths).
 */
object NetworkConfigHelper {

    fun getGatewayIp(context: Context): String? {
        return getGatewayFromLinkProperties(context)
            ?: getGatewayFromDhcpInfo(context)
    }

    private fun getGatewayFromLinkProperties(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProps = cm.getLinkProperties(network) ?: return null

            for (route in linkProps.routes) {
                if (route.isDefaultRoute) {
                    val gateway = route.gateway ?: continue
                    val addr = gateway.hostAddress ?: continue
                    if (addr != "0.0.0.0" && addr != "::") {
                        return addr
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getGatewayFromDhcpInfo(context: Context): String? {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifiManager.dhcpInfo
            if (dhcp.gateway != 0) {
                return intToIp(dhcp.gateway)
            }
        } catch (_: Exception) { }
        return null
    }

    /** Converts a little-endian int IP (from DhcpInfo) to dotted-quad string. */
    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
