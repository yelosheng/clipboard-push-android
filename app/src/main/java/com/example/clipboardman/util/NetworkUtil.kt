package com.example.clipboardman.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtil {

    data class NetworkInfo(
        val ip: String,
        val cidr: String
    )

    fun getLocalNetworkInfo(context: Context): NetworkInfo? {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
            
            if (linkProperties != null) {
                for (linkAddress in linkProperties.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: ""
                        val prefixLength = linkAddress.prefixLength
                        // Calculate CIDR base address (simple approximation for logging/identity)
                        // Actually V4 spec just wants "CIDR string" e.g. "192.168.1.5/24" or Network ID "192.168.1.0/24"
                        // The spec example shows: "cidr": "192.168.1.0/24".
                        // Use prefixLength to calculate network address.
                        val cidr = calculateCidr(address, prefixLength)
                        return NetworkInfo(ip, cidr)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun calculateCidr(address: Inet4Address, prefixLength: Int): String {
        val mask = -1 shl (32 - prefixLength)
        val ipInt = inet4AddressToInt(address)
        val networkInt = ipInt and mask
        val networkAddress = intToInet4Address(networkInt)
        return "${networkAddress.hostAddress}/$prefixLength"
    }

    private fun inet4AddressToInt(inet4Address: Inet4Address): Int {
        val bytes = inet4Address.address
        return ((bytes[0].toInt() and 0xFF) shl 24) or
               ((bytes[1].toInt() and 0xFF) shl 16) or
               ((bytes[2].toInt() and 0xFF) shl 8) or
               (bytes[3].toInt() and 0xFF)
    }

    private fun intToInet4Address(ipInt: Int): Inet4Address {
        val bytes = ByteArray(4)
        bytes[0] = ((ipInt ushr 24) and 0xFF).toByte()
        bytes[1] = ((ipInt ushr 16) and 0xFF).toByte()
        bytes[2] = ((ipInt ushr 8) and 0xFF).toByte()
        bytes[3] = (ipInt and 0xFF).toByte()
        return InetAddress.getByAddress(bytes) as Inet4Address
    }
}
