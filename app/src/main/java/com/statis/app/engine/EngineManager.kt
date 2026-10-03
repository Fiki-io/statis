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

        // 1. Acquire partial wake lock to prevent CPU throttling
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Statis:EngineWakeLock")?.apply {
            acquire(12 * 60 * 60 * 1000L) // 12 hours max safety
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
            NativeBridge.startCellularCadence("1.1.1.1", 53)
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

        wakeLock?.let {
            if (it.isHeld) it.release()
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
            // Target DNS/Game gateway: Cloudflare (1.1.1.1) or Google (8.8.8.8) on DNS port 53 UDP
            val host = "1.1.1.1"
            val port = 53

            while (isActive && _isRunning.value) {
                try {
                    val metrics = NativeBridge.measureLatency(host, port)
                    if (metrics.size >= 3) {
                        val rtt = metrics[0]
                        val jitter = metrics[1]
                        val drop = metrics[2] > 0.5

                        if (!drop && rtt > 0.0) {
                            _pingMs.value = rtt
                            _jitterMs.value = jitter
                            _packetLoss.value = false
                        } else {
                            _packetLoss.value = true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Latency measurement error: ${e.message}")
                }
                delay(600)
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
