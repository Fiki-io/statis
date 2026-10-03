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
    private var wakeLock: PowerManager.WakeLock? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _networkMode = MutableStateFlow(NetworkMode.WIFI)
    val networkMode: StateFlow<NetworkMode> = _networkMode.asStateFlow()

    private val _pingMs = MutableStateFlow(0.0)
    val pingMs: StateFlow<Double> = _pingMs.asStateFlow()

    private val _jitterMs = MutableStateFlow(0.0)
    val jitterMs: StateFlow<Double> = _jitterMs.asStateFlow()

    private val _packetLoss = MutableStateFlow(false)
    val packetLoss: StateFlow<Boolean> = _packetLoss.asStateFlow()

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

        // 1. Acquire partial wake lock to prevent CPU sleep
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Statis:EngineWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max
            }
        } catch (e: Exception) {
            Log.e(tag, "WakeLock error: ${e.message}")
        }

        // 2. Initialize Native Linux Socket Layer with WMM AC_VO (Priority 6) & DSCP EF
        val socketReady = NativeBridge.initSocketEngine()
        _acVoActive.value = socketReady

        // 3. Apply mode-specific optimization
        if (_networkMode.value == NetworkMode.WIFI) {
            val locked = wifiLockEngine.acquireLowLatencyLock()
            _hardwareLockActive.value = locked
        } else {
            // Cellular mode: Start native Anti-DRX micro-cadence thread
            NativeBridge.startCellularCadence("8.8.8.8", 53)
            _hardwareLockActive.value = true
        }

        _isRunning.value = true

        // 4. Start real-time latency & jitter sampler loop
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
        } else {
            NativeBridge.stopCellularCadence()
        }

        NativeBridge.releaseSocketEngine()

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "WakeLock release error: ${e.message}")
        }
        wakeLock = null

        _hardwareLockActive.value = false
        _acVoActive.value = false
        _isRunning.value = false
        _pingMs.value = 0.0
        _jitterMs.value = 0.0
        _packetLoss.value = false
    }

    private fun startMeasurementLoop() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // Lock to local Indonesian Anycast edge nodes (Google Jakarta edge: 18-20ms)
            // Avoid rotating to overseas servers (like OpenDNS/Quad9 which introduce 120ms-245ms transpacific latency)
            val hosts = listOf("8.8.8.8", "8.8.4.4")
            var consecutiveDrops = 0
            var currentHost = "8.8.8.8"
            val port = 53

            while (isActive && _isRunning.value) {
                try {
                    val metrics = NativeBridge.measureLatency(currentHost, port)
                    if (metrics.size >= 3) {
                        val rtt = metrics[0]
                        val jitter = metrics[1]
                        val drop = metrics[2] > 0.5

                        if (!drop && rtt > 0.0) {
                            consecutiveDrops = 0
                            // Smooth moving average for stability
                            if (_pingMs.value > 0.0) {
                                _pingMs.value = (_pingMs.value * 0.7) + (rtt * 0.3)
                            } else {
                                _pingMs.value = rtt
                            }
                            _jitterMs.value = jitter
                            _packetLoss.value = false
                        } else {
                            consecutiveDrops++
                            _packetLoss.value = true
                            // Only switch between 8.8.8.8 and 8.8.4.4 if primary fails 3 times in a row
                            if (consecutiveDrops >= 3) {
                                currentHost = if (currentHost == "8.8.8.8") "8.8.4.4" else "8.8.8.8"
                                consecutiveDrops = 0
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Latency measurement error: ${e.message}")
                }
                delay(800)
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
