package com.statis.app.native

object NativeBridge {

    init {
        System.loadLibrary("statis-native")
    }

    external fun initSocketEngine(): Boolean

    /**
     * Measures latency using high precision monotonic clocks and socket priority.
     * Returns double array: [0] = RTT in ms, [1] = Jitter in ms, [2] = Lost flag (0.0 = OK, 1.0 = Drop)
     */
    external fun measureLatency(host: String, port: Int): DoubleArray

    /**
     * Starts continuous low-latency Wi-Fi PHY active keeper to prevent WLAN MAC/PHY downclocking.
     */
    external fun startWifiPhyKeeper(gateway: String): Boolean

    /**
     * Stops Wi-Fi PHY active keeper thread.
     */
    external fun stopWifiPhyKeeper()

    /**
     * Starts native thread sending micro-cadence pulses (~1500ms) to prevent RRC C-DRX sleep.
     */
    external fun startCellularCadence(host: String, port: Int): Boolean

    /**
     * Stops cellular cadence thread.
     */
    external fun stopCellularCadence()

    /**
     * Closes native sockets and releases threads.
     */
    external fun releaseSocketEngine()
}
