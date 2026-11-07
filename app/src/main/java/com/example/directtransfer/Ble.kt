package com.example.directtransfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DiscoveredDevice(val address: String, val name: String, val device: BluetoothDevice, val rssi: Int)

/**
 * Lado RECEPTOR: expone un servidor GATT con la información de la red Wi-Fi Direct y
 * se anuncia por BLE para que el emisor pueda encontrarlo.
 */
@SuppressLint("MissingPermission")
class BleHost(private val ctx: Context) {
    private val manager = ContextCompat.getSystemService(ctx, BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null
    @Volatile private var payload = ByteArray(0)
    private val mtus = ConcurrentHashMap<String, Int>()

    suspend fun start(linkJson: String) {
        payload = linkJson.toByteArray(Charsets.UTF_8)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) throw IOException("Activa el Bluetooth")
        val adv = adapter.bluetoothLeAdvertiser
            ?: throw IOException("Este dispositivo no puede anunciarse por Bluetooth LE")

        val serviceAdded = CompletableDeferred<Unit>()
        val srv = manager?.openGattServer(ctx, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) serviceAdded.complete(Unit)
                else serviceAdded.completeExceptionally(IOException("No se pudo registrar el servicio BLE ($status)"))
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                mtus[device.address] = mtu
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) mtus.remove(device.address)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val data = payload
                // Se trocea de forma explícita según el MTU pactado (23 por defecto => 22 bytes útiles).
                val maxChunk = ((mtus[device.address] ?: 23) - 1).coerceAtLeast(1)
                val chunk = if (offset <= data.size) data.copyOfRange(offset, minOf(data.size, offset + maxChunk)) else ByteArray(0)
                server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, chunk)
            }
        }) ?: throw IOException("No se pudo abrir el servidor GATT")
        server = srv

        val service = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.LINK_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        srv.addService(service)
        serviceAdded.await()

        advertiser = adv
        startAdvertising()
    }

    /** Empieza (o reanuda) el anuncio BLE. */
    suspend fun startAdvertising() {
        val adv = advertiser ?: throw IOException("Bluetooth LE no iniciado")
        if (advCallback != null) return // ya anunciándose
        val started = CompletableDeferred<Unit>()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                started.complete(Unit)
            }

            override fun onStartFailure(errorCode: Int) {
                started.completeExceptionally(IOException("No se pudo anunciar por Bluetooth LE (código $errorCode)"))
            }
        }
        advCallback = cb
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        try {
            adv.startAdvertising(settings, data, scanResponse, cb)
            started.await()
        } catch (e: Exception) {
            advCallback = null
            throw e
        }
    }

    /**
     * Para el anuncio BLE. Durante la transferencia Bluetooth y Wi-Fi comparten la banda de 2,4 GHz
     * (y normalmente el chip), así que el anuncio continuo reduce mucho la velocidad.
     */
    fun stopAdvertising() {
        advCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        advCallback = null
    }

    fun stop() {
        stopAdvertising()
        runCatching { server?.close() }
        server = null
    }
}

/** Lado EMISOR: busca receptores anunciándose por BLE. */
@SuppressLint("MissingPermission")
class BleScanner(
    ctx: Context,
    private val onError: (String) -> Unit = {},
    private val onResult: (DiscoveredDevice) -> Unit,
) {
    private val manager = ContextCompat.getSystemService(ctx, BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: "Dispositivo ${dev.address.takeLast(5)}"
            onResult(DiscoveredDevice(dev.address, name, dev, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            onError("La búsqueda Bluetooth falló (código $errorCode). Desactiva y activa el Bluetooth e inténtalo de nuevo.")
        }
    }

    fun start() {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) throw IOException("Activa el Bluetooth")
        val s = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth LE no disponible")
        scanner = s
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(listOf(filter), settings, callback)
    }

    fun stop() {
        runCatching { scanner?.stopScan(callback) }
        scanner = null
    }
}

/** Conecta por GATT al receptor y lee el JSON con los datos de la red Wi-Fi Direct. */
@SuppressLint("MissingPermission")
suspend fun readLinkInfo(ctx: Context, device: BluetoothDevice): String =
    suspendCancellableCoroutine { cont ->
        var gatt: BluetoothGatt? = null

        fun fail(g: BluetoothGatt, msg: String) {
            if (cont.isActive) cont.resumeWithException(IOException(msg))
            g.close()
        }

        fun succeed(g: BluetoothGatt, value: ByteArray?) {
            if (cont.isActive) {
                if (value == null || value.isEmpty()) cont.resumeWithException(IOException("Respuesta BLE vacía"))
                else cont.resume(String(value, Charsets.UTF_8))
            }
            g.close()
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // MTU grande: el mensaje cabe en una sola lectura (las lecturas largas fallan en algunos móviles).
                    if (!g.requestMtu(517)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    fail(g, "Conexión Bluetooth perdida (estado $status)")
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.LINK_CHAR_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) fail(g, "Servicio BLE no encontrado")
                else if (!g.readCharacteristic(ch)) fail(g, "No se pudo leer la característica BLE")
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) succeed(g, value) else fail(g, "Error de lectura BLE ($status)")
            }

            @Deprecated("Usado en Android < 13")
            override fun onCharacteristicRead(
                g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) succeed(g, characteristic.value)
                else fail(g, "Error de lectura BLE ($status)")
            }
        }
        gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, cb)
        cont.invokeOnCancellation { runCatching { gatt?.close() } }
    }
