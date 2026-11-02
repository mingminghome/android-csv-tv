package com.mmhw.csvtv.receiver

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object LanAddress {
    fun ipv4(context: Context? = null): String {
        return fromInterfaces() ?: context?.let { fromWifi(it) } ?: "127.0.0.1"
    }

    fun fromInterfaces(): String? {
        val found = ArrayList<Pair<String, String>>()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (_: Exception) {
            return null
        } ?: return null
        for (iface in ifaces) {
            try {
                if (!iface.isUp || iface.isLoopback) continue
            } catch (_: Exception) {
                continue
            }
            val name = iface.name.lowercase(Locale.US)
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    val host = addr.hostAddress ?: continue
                    found.add(name to host)
                }
            }
        }
        val wifi = found.firstOrNull { (name, _) ->
            name.contains("wlan") || name.contains("wifi") || name.startsWith("ap")
        }
        return wifi?.second ?: found.firstOrNull()?.second
    }

    @Suppress("DEPRECATION")
    fun fromWifi(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val ip = try {
            wm.connectionInfo?.ipAddress ?: 0
        } catch (_: Exception) {
            0
        }
        if (ip == 0) return null
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }
}
