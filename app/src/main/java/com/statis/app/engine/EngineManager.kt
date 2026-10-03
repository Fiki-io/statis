package com.statis.app.engine

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.statis.app.model.NetworkMode
import com.statis.app.native.NativeBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EngineManager private constructor(private val context: Context) {

    private val tag = "EngineManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val wifiLockEngine = WifiLatencyLockEngine(context)
    private val networkDiagnostics = NetworkDiagnostics(context)
    private var wakeLock: PowerManager.WakeLock? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _networkMode = MutableStateFlow(NetworkMode.WIFI)
    val networkMode: StateFlow<NetworkMode> = _networkMode.asStateFlow()

    private val _gatewayPingMs = MutableStateFlow(0.0)
    val gatewayPingMs: StateFlow<Double> = _gatewayPingMs.asStateFlow()

    private val _internetPingMs = MutableStateFlow(0.0)
    val internetPingMs: StateFlow<Double> = _internetPingMs.asStateFlow()

    private val _jitterMs = MutableStateFlow(0.0)
    val jitterMs: StateFlow<Double> = _jitterMs.asStateFlow()

    private val _radioInfo = MutableStateFlow(RadioInfo("--", "-- Mbps", "-- dBm", "192.168.1.1"))
    val radioInfo: StateFlow<RadioInfo> = _radioInfo.asStateFlow()

    private val _hardwareLockActive = MutableStateFlow(false)
    val hardwareLockActive: StateFlow<Boolean> = _hardwareLockActive.asStateFlow()

    private val _acVoActive = MutableStateFlow(false)
    val acVoActive: StateFlow<Boolean> = _acVoActive.asStateFlow()

    private var monitorJob: Job? = null

    fun setMode(mode: NetworkMode) {
        if (!_isRunning.value) {
            _networkMode.value = mode
        }
    }

    @Synchronized
    fun startEngine() {
        if (_isRunning.value) return
        Log.i(tag, "Starting Statis Latency Engine in mode: ${_networkMode.value.name}")

        // 1. WakeLock safety
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Statis:EngineWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(tag, "WakeLock error: ${e.message}")
        }

        // 2. Initialize Native Linux Socket Layer with WMM AC_VO (Priority 6) & DSCP EF
        val socketReady = NativeBridge.initSocketEngine()
        _acVoActive.value = socketReady

        // 3. Apply mode-specific optimization
        val currentRadio = networkDiagnostics.getRadioInfo()
        _radioInfo.value = currentRadio

        if (_networkMode.value == NetworkMode.WIFI) {
            val locked = wifiLockEngine.acquireLowLatencyLock()
            _hardwareLockActive.value = locked
            // Start Wi-Fi PHY Active Keeper
            NativeBridge.startWifiPhyKeeper(currentRadio.gatewayIp)
        } else {
            // Cellular mode: Start native Anti-DRX micro-cadence thread
            NativeBridge.startCellularCadence("8.8.8.8", 53)
            _hardwareLockActive.value = true
        }

        _isRunning.value = true

        // 4. Start dual-hop measurement loop
        startMeasurementLoop()
    }

    @Synchronized
    fun stopEngine() {
        if (!_isRunning.value) return
        Log.i(tag, "Stopping Statis Latency Engine")

        monitorJob?.cancel()
        monitorJob = null

        if (_networkMode.value == NetworkMode.WIFI) {
            wifiLockEngine.releaseLock()
            NativeBridge.stopWifiPhyKeeper()
        } else {
            NativeBridge.stopCellularCadence()
        }

        NativeBridge.releaseSocketEngine()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null

        _hardwareLockActive.value = false
        _acVoActive.value = false
        _isRunning.value = false
        _gatewayPingMs.value = 0.0
        _internetPingMs.value = 0.0
        _jitterMs.value = 0.0
    }

    private fun startMeasurementLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var counter = 0

            while (isActive && _isRunning.value) {
                try {
                    // Update physical radio info every 2 seconds
                    if (counter % 3 == 0) {
                        _radioInfo.value = networkDiagnostics.getRadioInfo()
                    }
                    counter++

                    val currentRadio = _radioInfo.value

                    // Probe Hop 1: Local Wi-Fi Router Gateway (Physical Airwave Latency)
                    if (_networkMode.value == NetworkMode.WIFI) {
                        val gwMetrics = NativeBridge.measureLatency(currentRadio.gatewayIp, 53)
                        if (gwMetrics.size >= 3 && gwMetrics[2] < 0.5 && gwMetrics[0] > 0.0) {
                            val gwRtt = gwMetrics[0]
                            if (_gatewayPingMs.value > 0.0) {
                                _gatewayPingMs.value = (_gatewayPingMs.value * 0.7) + (gwRtt * 0.3)
                            } else {
                                _gatewayPingMs.value = gwRtt
                            }
                        }
                    }

                    // Probe Hop 2: Internet Edge Server (8.8.8.8 Anycast)
                    val netMetrics = NativeBridge.measureLatency("8.8.8.8", 53)
                    if (netMetrics.size >= 3 && netMetrics[2] < 0.5 && netMetrics[0] > 0.0) {
                        val netRtt = netMetrics[0]
                        val netJitter = netMetrics[1]

                        if (_internetPingMs.value > 0.0) {
                            _internetPingMs.value = (_internetPingMs.value * 0.7) + (netRtt * 0.3)
                        } else {
                            _internetPingMs.value = netRtt
                        }
                        _jitterMs.value = netJitter
                    }

                } catch (e: Exception) {
                    Log.e(tag, "Measurement loop error: ${e.message}")
                }
                delay(700)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: EngineManager? = null

        fun getInstance(context: Context): EngineManager {
            return instance ?: synchronized(this) {
                instance ?: EngineManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
