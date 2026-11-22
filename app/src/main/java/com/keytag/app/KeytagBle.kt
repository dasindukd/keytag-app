package com.keytag.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Finds the keytag over Bluetooth Low Energy, keeps the connection alive,
 * and sends it the song info + picture. All work runs on the main thread.
 */
@SuppressLint("MissingPermission")
class KeytagBle(
    private val ctx: Context,
    private val onReady: () -> Unit,
) {
    companion object {
        // Must match the Arduino sketch
        val SERVICE: UUID = UUID.fromString("a3c87500-8ed3-4bdf-8a39-a01bebede295")
        val META: UUID = UUID.fromString("a3c87501-8ed3-4bdf-8a39-a01bebede295")
        val ART: UUID = UUID.fromString("a3c87502-8ed3-4bdf-8a39-a01bebede295")
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var mtu = 23
    private var scanning = false
    private var stopped = true
    private val queue = ArrayDeque<Pair<UUID, ByteArray>>()
    private var busy = false

    val isReady: Boolean get() = service != null

    fun start() {
        stopped = false
        scheduleConnect(0)
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.close()
        gatt = null
        service = null
        queue.clear()
        busy = false
    }

    fun reconnect() {
        stop()
        start()
    }

    // ---------- sending ----------

    fun sendMeta(data: ByteArray) {
        if (!isReady) return
        val max = (mtu - 3).coerceAtLeast(20)
        val bytes = if (data.size > max) data.copyOf(max) else data
        queue.removeAll { it.first == META }
        queue.addLast(META to bytes)
        pump()
    }

    /** bits = 512-byte image, or null to tell the keytag there is no picture. */
    fun sendArt(bits: ByteArray?) {
        if (!isReady) return
        queue.removeAll { it.first == ART }
        if (bits == null) {
            queue.addLast(ART to byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        } else {
            val chunk = (mtu - 5).coerceIn(16, 200)
            var off = 0
            while (off < bits.size) {
                val n = minOf(chunk, bits.size - off)
                val pkt = ByteArray(n + 2)
                pkt[0] = (off shr 8).toByte()
                pkt[1] = (off and 0xFF).toByte()
                System.arraycopy(bits, off, pkt, 2, n)
                queue.addLast(ART to pkt)
                off += n
            }
        }
        pump()
    }

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val s = service ?: return
        val (uuid, data) = queue.removeFirstOrNull() ?: return
        val ch = s.getCharacteristic(uuid) ?: return
        busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = data
                g.writeCharacteristic(ch)
            }
        }
        if (!ok) {                       // drop this packet, try the next one shortly
            busy = false
            main.postDelayed({ pump() }, 50)
        }
    }

    // ---------- finding + connecting ----------

    private fun hasPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private val connectRunnable = Runnable { tryScan() }

    private fun scheduleConnect(delayMs: Long) {
        main.removeCallbacks(connectRunnable)
        main.postDelayed(connectRunnable, delayMs)
    }

    private fun tryScan() {
        if (stopped || gatt != null || scanning) return
        val adapter = manager?.adapter
        if (!hasPermissions()) { Status.connection = "Bluetooth permission needed (step 1)"; scheduleConnect(5000); return }
        if (adapter == null || !adapter.isEnabled) { Status.connection = "Turn Bluetooth on"; scheduleConnect(5000); return }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { scheduleConnect(5000); return }

        Status.connection = "Searching for keytag..."
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = true
        try {
            scanner.startScan(filters, settings, scanCb)
        } catch (e: Exception) {
            scanning = false
            scheduleConnect(10000)
            return
        }
        main.postDelayed(scanTimeout, 15000)
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            Status.connection = "Keytag not found - is it switched on? Retrying..."
            scheduleConnect(15000)       // Android limits how often apps can scan
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        main.removeCallbacks(scanTimeout)
        try { manager?.adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning || gatt != null) return@post
                stopScan()
                Status.connection = "Connecting..."
                gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                main.removeCallbacks(scanTimeout)
                scheduleConnect(10000)
            }
        }
    }

    private var discoverStarted = false

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    discoverStarted = false
                    g.requestMtu(247)
                    // some phones never answer the MTU request - carry on anyway
                    main.postDelayed({ startDiscovery(g) }, 1500)
                } else {
                    g.close()
                    if (gatt == g) { gatt = null; service = null }
                    queue.clear()
                    busy = false
                    Status.connection = "Disconnected - retrying..."
                    if (!stopped) scheduleConnect(3000)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
                startDiscovery(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val s = g.getService(SERVICE)
                if (s == null) { g.disconnect(); return@post }
                service = s
                Status.connection = "Connected to keytag"
                onReady()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                busy = false
                pump()
            }
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discoverStarted || gatt != g) return
        discoverStarted = true
        g.discoverServices()
    }
}
