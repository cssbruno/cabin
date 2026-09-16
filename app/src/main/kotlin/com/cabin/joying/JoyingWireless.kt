package com.cabin.joying

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.*
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Joying's Android Bluetooth RFCOMM and soft-AP paths, independent of the stock APK. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class JoyingWireless(
    private val context: Context,
    private val dispatch: (() -> Unit) -> Unit,
    private val command: (Int, IntArray, List<String>) -> Unit,
    private val btState: (String) -> Unit,
    private val btBytes: (ByteArray) -> Unit,
    private val status: (String) -> Unit,
) : Closeable {
    companion object {
        val IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        fun bondedPhones(context: Context): List<Pair<String, String>> = try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name ?: it.address) }.sortedBy { it.second }
        } catch (_: SecurityException) { emptyList() }
    }
    private val closed = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor()
    private val writer = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(32))
    private var selectedAddress: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var ownsAp = false
    private var receiverRegistered = false
    private var oldConfig: WifiConfiguration? = null
    private var wifiWasEnabled = false
    private var ownedSsid: String? = null
    private var apGeneration = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra("wifi_state", -1) == 13) dispatch { if (ownsAp && !closed.get()) publishAp() }
            if (intent.getIntExtra("wifi_state", -1) == 14) status("Carlink Wi-Fi hotspot failed to start")
        }
    }

    fun connect(address: String) {
        check(!closed.get())
        check(socket == null) { "A Bluetooth connection is already active. Disconnect before choosing another phone." }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Bluetooth is unavailable")
        check(adapter.isEnabled) { "Enable Bluetooth before connecting a phone" }
        val device = adapter.bondedDevices.firstOrNull { it.address == address } ?: error("Pair this phone in Android Bluetooth settings first")
        val localAddress = adapter.address
        check(!localAddress.isNullOrBlank() && localAddress != "02:00:00:00:00:00") {
            "Carlink firmware must grant Cabin access to the local Bluetooth address"
        }
        var name = adapter.name ?: "Cabin"
        while (name.toByteArray(Charsets.UTF_8).size > 63) name = name.dropLast(1)
        command(226, intArrayOf(), listOf(name, localAddress.replace(":", "")))
        selectedAddress = address
        val next = device.createRfcommSocketToServiceRecord(IAP2_UUID)
        socket = next
        io.execute {
            try {
                status("Connecting to phone over Bluetooth…")
                next.connect()
                dispatch { if (!closed.get() && socket === next) btState("SV") }
                val buffer = ByteArray(1024)
                while (!closed.get()) {
                    val count = next.inputStream.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        val copy = buffer.copyOf(count)
                        dispatch { if (!closed.get() && socket === next) btBytes(copy) }
                    }
                }
                if (!closed.get()) status("Bluetooth link disconnected")
            } catch (e: Exception) {
                if (!closed.get()) status(e.message ?: "Bluetooth connection failed")
            } finally {
                runCatching { next.close() }
                if (socket === next) socket = null
            }
        }
    }

    fun reconnect(factoryAddress: String?) {
        if (socket != null) return
        connect(selectedAddress ?: factoryAddress ?: error("Choose a paired phone before reconnecting"))
    }
    fun disconnectBluetooth() {
        val old = socket
        socket = null
        runCatching { old?.close() }
    }

    fun write(bytes: ByteArray) {
        if (closed.get()) return
        val target = socket ?: return
        try { writer.execute {
            try { target.outputStream.write(bytes); target.outputStream.flush() }
            catch (e: Exception) { if (!closed.get()) status(e.message ?: "Bluetooth write failed"); runCatching { target.close() } }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { target.close() }
            status("Bluetooth output queue full; reconnect the phone")
        }
    }

    fun hotspot(enabled: Boolean) {
        check(!closed.get())
        if (!enabled) { stopAp(); return }
        if (ownsAp) { publishAp(); return }
        // This backend is pinned to the inspected Android 10 soft-AP contract.
        check(Build.VERSION.SDK_INT in 27..29) { "This Carlink hotspot interface requires Android 8.1–10 firmware" }
        val getConfig = wifi.javaClass.getMethod("getWifiApConfiguration")
        val currentState = wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int
        check(currentState != 13 && currentState != 12) { "Another hotspot is active. Turn it off before starting Cabin wireless CarPlay." }
        oldConfig = getConfig.invoke(wifi) as? WifiConfiguration
        val config = WifiConfiguration().apply {
            SSID = "CabinCarPlay-" + UUID.randomUUID().toString().take(4)
            preSharedKey = UUID.randomUUID().toString().replace("-", "").take(20)
            allowedKeyManagement.set(4) // WPA2_PSK in the inspected WifiConfiguration API.
            javaClass.getField("apBand").setInt(this, 0)
            javaClass.getField("apChannel").setInt(this, 6)
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(context, receiver, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), ContextCompat.RECEIVER_EXPORTED)
            receiverRegistered = true
        }
        wifiWasEnabled = wifi.isWifiEnabled
        if (wifiWasEnabled) check(wifi.setWifiEnabled(false)) { "Cannot release Wi-Fi client mode for CarPlay" }
        ownsAp = true
        try {
            val configured = wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, config) as Boolean
            check(configured) { "Firmware rejected Cabin's hotspot configuration" }
            ownedSsid = config.SSID
            val currentGeneration = ++apGeneration
            val callback = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: android.os.Bundle?) {
                    dispatch {
                        if (!closed.get() && ownsAp && apGeneration == currentGeneration) {
                            if (resultCode == 0) publishAp()
                            else { stopAp(); status("Firmware rejected CarPlay tethering: $resultCode") }
                        }
                    }
                }
            }
            val service = connectivityService()
            Class.forName("android.net.IConnectivityManager").getMethod("startTethering",
                Int::class.javaPrimitiveType, android.os.ResultReceiver::class.java,
                Boolean::class.javaPrimitiveType, String::class.java)
                .invoke(service, 0, callback, true, context.packageName)
        } catch (e: Exception) { stopAp(); throw e }
    }

    private fun publishAp() {
        if (!ownsAp || ownedSsid == null) return
        val config = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as WifiConfiguration
        check(config.SSID == ownedSsid) { "Hotspot ownership changed; reconnect wireless CarPlay" }
        val channel = config.javaClass.getField("apChannel").getInt(config)
        val band = config.javaClass.getField("apBand").getInt(config)
        check(channel > 0 && config.allowedKeyManagement[4]) { "Hotspot channel or WPA2 configuration is unavailable" }
        command(225, intArrayOf(channel, 2, if (band == 1) 1 else 0), listOf(config.SSID, config.preSharedKey))
        status("CarPlay Wi-Fi is ready; connect a paired phone")
    }

    private fun connectivityService(): Any {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        return android.net.ConnectivityManager::class.java.getDeclaredField("mService").apply { isAccessible = true }.get(manager)!!
    }

    private fun stopAp() {
        if (!ownsAp) return
        ownsAp = false
        apGeneration++
        val current = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        if (ownedSsid != null && current?.SSID != ownedSsid) {
            ownedSsid = null
            return // Another owner replaced the AP; do not stop or overwrite their network.
        }
        Class.forName("android.net.IConnectivityManager").getMethod("stopTethering", Int::class.javaPrimitiveType, String::class.java)
            .invoke(connectivityService(), 0, context.packageName)
        oldConfig?.let { wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, it) }
        if (wifiWasEnabled) wifi.setWifiEnabled(true)
        ownedSsid = null
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false }
        runCatching { stopAp() }.onFailure { status(it.message ?: "Hotspot cleanup failed") }
        io.shutdownNow(); writer.shutdownNow()
    }
}
