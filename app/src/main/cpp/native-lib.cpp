#include <jni.h>
#include <string>
#include <cstring>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <time.h>
#include <thread>
#include <atomic>
#include <cmath>
#include <android/log.h>

#define TAG "StatisNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

int g_socket_fd = -1;
std::atomic<bool> g_cadence_running{false};
std::thread g_cadence_thread;

// Metrics tracking
double g_last_rtt_ms = 0.0;
double g_smoothed_jitter_ms = 0.0;

// High precision monotonic clock helper
double get_monotonic_time_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_RAW, &ts);
    return (ts.tv_sec * 1000.0) + (ts.tv_nsec / 1000000.0);
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_statis_app_native_NativeBridge_initSocketEngine(JNIEnv *env, jobject /* this */) {
    if (g_socket_fd >= 0) {
        close(g_socket_fd);
        g_socket_fd = -1;
    }

    g_socket_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_socket_fd < 0) {
        LOGE("Failed to create UDP socket: %s", strerror(errno));
        return JNI_FALSE;
    }

    // 1. Set SO_PRIORITY to 6 (maps to WMM AC_VO - Voice/Gaming Queue in mac80211)
    int priority = 6;
    if (setsockopt(g_socket_fd, SOL_SOCKET, SO_PRIORITY, &priority, sizeof(priority)) < 0) {
        LOGE("Warning: setsockopt SO_PRIORITY failed: %s", strerror(errno));
    } else {
        LOGI("SO_PRIORITY set to %d (AC_VO Voice hardware queue active)", priority);
    }

    // 2. Set IP_TOS to 0xB8 (DSCP 46 / Expedited Forwarding)
    int tos = 0xB8;
    if (setsockopt(g_socket_fd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos)) < 0) {
        LOGE("Warning: setsockopt IP_TOS failed: %s", strerror(errno));
    } else {
        LOGI("IP_TOS set to 0x%X (DSCP Expedited Forwarding)", tos);
    }

    // 3. Set Socket Timeout to 600ms to avoid blocking
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 600000;
    setsockopt(g_socket_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(g_socket_fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    return JNI_TRUE;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_statis_app_native_NativeBridge_measureLatency(
    JNIEnv *env,
    jobject /* this */,
    jstring host_jstr,
    jint port
) {
    jdoubleArray result = env->NewDoubleArray(3);
    if (result == nullptr) return nullptr;

    jdouble metrics[3] = {-1.0, -1.0, 1.0}; // [RTT, Jitter, LostFlag]

    if (g_socket_fd < 0) {
        env->SetDoubleArrayRegion(result, 0, 3, metrics);
        return result;
    }

    const char *host_cstr = env->GetStringUTFChars(host_jstr, nullptr);
    if (!host_cstr) {
        env->SetDoubleArrayRegion(result, 0, 3, metrics);
        return result;
    }

    struct sockaddr_in target_addr;
    std::memset(&target_addr, 0, sizeof(target_addr));
    target_addr.sin_family = AF_INET;
    target_addr.sin_port = htons(static_cast<uint16_t>(port));

    if (inet_pton(AF_INET, host_cstr, &target_addr.sin_addr) <= 0) {
        // Resolve hostname if not an IP
        struct hostent *he = gethostbyname(host_cstr);
        if (he && he->h_addr_list && he->h_addr_list[0]) {
            std::memcpy(&target_addr.sin_addr, he->h_addr_list[0], sizeof(struct in_addr));
        } else {
            env->ReleaseStringUTFChars(host_jstr, host_cstr);
            env->SetDoubleArrayRegion(result, 0, 3, metrics);
            return result;
        }
    }
    env->ReleaseStringUTFChars(host_jstr, host_cstr);

    // Micro probe payload: 8-byte timestamp
    uint64_t probe_id = static_cast<uint64_t>(get_monotonic_time_ms());
    double t_start = get_monotonic_time_ms();

    ssize_t sent = sendto(
        g_socket_fd,
        &probe_id,
        sizeof(probe_id),
        0,
        reinterpret_cast<struct sockaddr *>(&target_addr),
        sizeof(target_addr)
    );

    if (sent < 0) {
        metrics[2] = 1.0; // lost
        env->SetDoubleArrayRegion(result, 0, 3, metrics);
        return result;
    }

    uint8_t recv_buffer[128];
    struct sockaddr_in from_addr;
    socklen_t from_len = sizeof(from_addr);

    ssize_t received = recvfrom(
        g_socket_fd,
        recv_buffer,
        sizeof(recv_buffer),
        0,
        reinterpret_cast<struct sockaddr *>(&from_addr),
        &from_len
    );

    double t_end = get_monotonic_time_ms();

    if (received >= 0) {
        double current_rtt = t_end - t_start;
        if (current_rtt < 0.1) current_rtt = 0.1;

        // Calculate RFC 3550 style jitter
        if (g_last_rtt_ms > 0.0) {
            double diff = std::abs(current_rtt - g_last_rtt_ms);
            g_smoothed_jitter_ms = (g_smoothed_jitter_ms * 0.85) + (diff * 0.15);
        } else {
            g_smoothed_jitter_ms = 0.5;
        }
        g_last_rtt_ms = current_rtt;

        metrics[0] = current_rtt;
        metrics[1] = g_smoothed_jitter_ms;
        metrics[2] = 0.0; // Success
    } else {
        metrics[2] = 1.0; // Timeout / packet drop
    }

    env->SetDoubleArrayRegion(result, 0, 3, metrics);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_statis_app_native_NativeBridge_startCellularCadence(
    JNIEnv *env,
    jobject /* this */,
    jstring host_jstr,
    jint port
) {
    if (g_cadence_running.load()) {
        return JNI_TRUE;
    }

    const char *host_cstr = env->GetStringUTFChars(host_jstr, nullptr);
    std::string host = host_cstr ? host_cstr : "1.1.1.1";
    if (host_cstr) env->ReleaseStringUTFChars(host_jstr, host_cstr);

    g_cadence_running.store(true);

    g_cadence_thread = std::thread([host, port]() {
        LOGI("Cellular Anti-DRX Cadence thread started");
        
        int cadence_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
        if (cadence_sock < 0) {
            LOGE("Failed to create cadence socket");
            g_cadence_running.store(false);
            return;
        }

        // Apply Priority 6 for Anti-DRX heartbeat
        int prio = 6;
        setsockopt(cadence_sock, SOL_SOCKET, SO_PRIORITY, &prio, sizeof(prio));

        struct sockaddr_in target_addr;
        std::memset(&target_addr, 0, sizeof(target_addr));
        target_addr.sin_family = AF_INET;
        target_addr.sin_port = htons(static_cast<uint16_t>(port));
        inet_pton(AF_INET, host.c_str(), &target_addr.sin_addr);

        uint8_t beat = 0xAA;

        while (g_cadence_running.load()) {
            // Send micro heartbeat (1 byte)
            sendto(
                cadence_sock,
                &beat,
                sizeof(beat),
                0,
                reinterpret_cast<struct sockaddr *>(&target_addr),
                sizeof(target_addr)
            );

            // Sleep 75ms (below the 100ms LTE/5G RRC InactivityTimer)
            std::this_thread::sleep_for(std::chrono::milliseconds(75));
        }

        close(cadence_sock);
        LOGI("Cellular Anti-DRX Cadence thread terminated");
    });

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_statis_app_native_NativeBridge_stopCellularCadence(JNIEnv * /* env */, jobject /* this */) {
    if (g_cadence_running.load()) {
        g_cadence_running.store(false);
        if (g_cadence_thread.joinable()) {
            g_cadence_thread.join();
        }
        LOGI("Cellular Anti-DRX Cadence stopped");
    }
}

JNIEXPORT void JNICALL
Java_com_statis_app_native_NativeBridge_releaseSocketEngine(JNIEnv * /* env */, jobject /* this */) {
    g_cadence_running.store(false);
    if (g_cadence_thread.joinable()) {
        g_cadence_thread.join();
    }

    if (g_socket_fd >= 0) {
        close(g_socket_fd);
        g_socket_fd = -1;
    }
    LOGI("Statis Native Engine released");
}

} // extern "C"
