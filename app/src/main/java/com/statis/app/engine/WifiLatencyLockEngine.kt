package com.statis.app.engine

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class WifiLatencyLockEngine(private val context: Context) {

    private val tag = "WifiLatencyLock"
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun acquireLowLatencyLock(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.e(tag, "WifiManager unavailable")
                return false
            }

            if (wifiLock == null) {
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }

                wifiLock = wifiManager.createWifiLock(lockMode, "Statis:LowLatencyLock").apply {
                    setReferenceCounted(false)
                }
            }

            wifiLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.i(tag, "WIFI_MODE_FULL_LOW_LATENCY acquired (Power Save Mode disabled)")
                }
            }

            // Acquire multicast lock to prevent packet drops on filtered multicast/broadcast streams
            if (multicastLock == null) {
                multicastLock = wifiManager.createMulticastLock("Statis:MulticastLock").apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                }
            }

            true
        } catch (e: Exception) {
            Log.e(tag, "Error acquiring low latency lock: ${e.message}", e)
            false
        }
    }

    fun releaseLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(tag, "Low latency wifi lock released")
                }
            }
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing wifi lock: ${e.message}", e)
        } finally {
            wifiLock = null
            multicastLock = null
        }
    }

    fun isLockHeld(): Boolean {
        return wifiLock?.isHeld == true
    }
}
