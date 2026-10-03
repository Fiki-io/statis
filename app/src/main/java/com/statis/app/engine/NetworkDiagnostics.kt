package com.statis.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.util.Locale

data class RadioInfo(
    val band: String,
    val linkSpeed: String,
    val signalStrength: String,
    val gatewayIp: String
)

class NetworkDiagnostics(private val context: Context) {

    fun getRadioInfo(): RadioInfo {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        var band = "--"
        var speed = "-- Mbps"
        var signal = "-- dBm"
        var gateway = "192.168.1.1"

        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val linkProps = connectivityManager?.getLinkProperties(activeNetwork)

            // 1. Universal Gateway Discovery from kernel route table
            linkProps?.routes?.forEach { route ->
                if (route.isDefaultRoute && route.gateway != null) {
                    val host = route.gateway?.hostAddress
                    if (!host.isNullOrEmpty() && host != "0.0.0.0") {
                        gateway = host
                    }
                }
            }

            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                @Suppress("DEPRECATION")
                val wifiInfo: WifiInfo? = wifiManager?.connectionInfo

                if (wifiInfo != null) {
                    val freq = wifiInfo.frequency
                    band = when {
                        freq >= 5925 -> "6 GHz"
                        freq >= 4900 -> "5 GHz"
                        freq > 0 -> "2.4 GHz"
                        else -> "Wi-Fi"
                    }

                    val linkSpeed = wifiInfo.linkSpeed
                    if (linkSpeed > 0) {
                        speed = "$linkSpeed Mbps"
                    }

                    val rssi = wifiInfo.rssi
                    if (rssi != -127 && rssi != 0) {
                        val quality = when {
                            rssi >= -55 -> "Sangat Kuat"
                            rssi >= -67 -> "Kuat"
                            rssi >= -80 -> "Cukup"
                            else -> "Lemah"
                        }
                        signal = "$rssi dBm ($quality)"
                    }
                }

                // Fallback gateway from DHCP if route table was empty
                if (gateway == "192.168.1.1") {
                    val dhcpInfo = wifiManager?.dhcpInfo
                    val gw = dhcpInfo?.gateway ?: 0
                    if (gw != 0) {
                        gateway = String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            gw and 0xFF,
                            (gw shr 8) and 0xFF,
                            (gw shr 16) and 0xFF,
                            (gw shr 24) and 0xFF
                        )
                    }
                }
            } else if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                band = "Cellular (4G/5G)"
                speed = "Broadband"
                signal = "Active"
                gateway = "8.8.8.8"
            }
        } catch (_: Exception) {
        }

        return RadioInfo(
            band = band,
            linkSpeed = speed,
            signalStrength = signal,
            gatewayIp = gateway
        )
    }
}
