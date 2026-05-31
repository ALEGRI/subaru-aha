package com.example.subaruaha

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.nio.*

data class SDLHeader(val frameType: Int, val sessionId: Int, val payloadSize: Int, val correlationId: Int)
data class AhaStation(val stationId: String, val name: String, val category: String, val imageUrl: String, val streamUrl: String)
data class GetStationsResponse(val GetStationsResponse: StationsContent)
data class StationsContent(val status: String = "SUCCESS", val stations: List<AhaStation>)

class AhaEmulatorService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val MAC_SUBARU = "F8:E8:77:8E:9C:9B"
    private val rpcHandler = AhaRpcHandler()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
        startBluetoothEngine()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothEngine() {
        scope.launch {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val device = adapter.getRemoteDevice(MAC_SUBARU)
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                val socket = method.invoke(device, 1) as BluetoothSocket
                socket.connect()
                val out = socket.outputStream
                val inp = socket.inputStream

                launch {
                    while (isActive) {
                        delay(4000)
                        out.write(rpcHandler.pack(0xFF, 0, 0, null))
                    }
                }

                val headerBytes = ByteArray(12)
                while (isActive) {
                    if (inp.read(headerBytes) == 12) {
                        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                        buf.get(); val type = buf.get().toInt() and 0xFF; buf.get()
                        val sid = buf.get().toInt(); val pSize = buf.getInt(); val cid = buf.getInt()
                        if (pSize > 0) {
                            val payload = ByteArray(pSize); inp.read(payload)
                            rpcHandler.handle(type, sid, cid, String(payload))?.let { out.write(it) }
                        }
                    }
                }
            } catch (e: Exception) { stopSelf() }
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("aha", "Subaru", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "aha").setContentTitle("Aha Server Active").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
    }
    override fun onBind(i: Intent?): IBinder? = null
}

class AhaRpcHandler {
    private val gson = Gson()
    fun handle(type: Int, sid: Int, cid: Int, json: String): ByteArray? {
        return when {
            json.contains("RegisterAppInterface") -> pack(0x00, sid, cid, """{"RegisterAppInterfaceResponse":{"status":"SUCCESS"}}""")
            json.contains("GetStations") -> pack(0x00, sid, cid, gson.toJson(GetStationsResponse(StationsContent(stations = listOf(AhaStation("101", "Subaru Synthwave", "Music", "", ""))))))
            else -> null
        }
    }
    fun pack(type: Int, sid: Int, cid: Int, payload: String?): ByteArray {
        val body = payload?.toByteArray() ?: byteArrayOf()
        return ByteBuffer.allocate(12 + body.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0x50.toByte()); put(type.toByte()); put(0x07.toByte()); put(sid.toByte())
            putInt(body.size); putInt(cid); put(body)
        }.array()
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Button(this).apply {
            text = "START SUBARU SERVER"
            setOnClickListener { startForegroundService(Intent(this@MainActivity, AhaEmulatorService::class.java)) }
        })
    }
}
