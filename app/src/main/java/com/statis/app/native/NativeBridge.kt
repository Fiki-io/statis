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
     * Starts native thread sending micro-cadence pulses (~75ms) to prevent RRC C-DRX sleep.
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
