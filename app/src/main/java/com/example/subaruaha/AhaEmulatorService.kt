package com.example.subaruaha

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// Extension function to guarantee exactly N bytes are read from socket stream
fun InputStream.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
    var totalRead = 0
    while (totalRead < length) {
        val read = this.read(buffer, offset + totalRead, length - totalRead)
        if (read == -1) throw java.io.IOException("End of stream reached")
        totalRead += read
    }
}

class AhaEmulatorService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rpcHandler = AhaRpcHandler()
    
    // Standard SPP UUID
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Active sockets
    private var clientSocket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isServerMode = intent?.getBooleanExtra("is_server_mode", false) ?: false
        val mac = intent?.getStringExtra("mac_address") ?: ""

        startForeground(1, createNotification())
        
        ServerState.isServerMode = isServerMode
        if (isServerMode) {
            startServerEngine()
        } else {
            startClientEngine(mac)
        }
        return START_STICKY
    }

    // --- CLIENT MODE ENGINE ---
    @SuppressLint("MissingPermission")
    private fun startClientEngine(mac: String) {
        scope.launch {
            var retryDelay = 2000L
            val maxRetryDelay = 60000L
            
            while (isActive) {
                try {
                    ServerState.updateState(true, "Connecting...", R.color.state_connecting)
                    ServerState.log("Client Mode: Locating device $mac")
                    
                    val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw Exception("Bluetooth adapter unavailable")
                    val device = adapter.getRemoteDevice(mac)
                    
                    ServerState.log("Opening RFCOMM socket on channel 1...")
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                    val socketInstance = method.invoke(device, 1) as BluetoothSocket
                    clientSocket = socketInstance
                    
                    ServerState.log("Connecting...")
                    socketInstance.connect()
                    
                    // Reset backoff delay upon successful connection
                    retryDelay = 2000L
                    ServerState.updateState(true, "Connected (Client)", R.color.state_active)
                    ServerState.log("Connected to Subaru Headunit!")
                    
                    runProtocolSession(socketInstance.inputStream, socketInstance.outputStream)
                    
                } catch (e: Exception) {
                    if (!isActive) break
                    ServerState.log("Connection failed: ${e.message}")
                    ServerState.updateState(true, "Reconnecting...", R.color.state_connecting)
                    
                    ServerState.log("Retrying connection in ${retryDelay / 1000}s...")
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(maxRetryDelay)
                } finally {
                    try {
                        clientSocket?.close()
                    } catch (ex: Exception) {}
                    clientSocket = null
                }
            }
        }
    }

    // --- SERVER MODE ENGINE ---
    @SuppressLint("MissingPermission")
    private fun startServerEngine() {
        scope.launch {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                ServerState.updateState(false, "BT Error", R.color.state_inactive)
                ServerState.log("Server Mode: Bluetooth adapter unavailable")
                stopSelf()
                return@launch
            }

            try {
                ServerState.updateState(true, "Listening...", R.color.state_connecting)
                ServerState.log("Server Mode: Creating RFCOMM Server (UUID: $SPP_UUID)")
                
                val serverSocketInstance = adapter.listenUsingRfcommWithServiceRecord("Subaru Aha Emulator", SPP_UUID)
                serverSocket = serverSocketInstance
                
                while (isActive) {
                    ServerState.log("Server Mode: Awaiting connection from car headunit...")
                    val socketInstance = serverSocketInstance.accept() // Blocks until client links
                    
                    ServerState.updateState(true, "Connected (Server)", R.color.state_active)
                    ServerState.log("Server Mode: Car headunit linked: ${socketInstance.remoteDevice.name}")
                    
                    clientSocket = socketInstance
                    try {
                        runProtocolSession(socketInstance.inputStream, socketInstance.outputStream)
                    } catch (e: Exception) {
                        ServerState.log("Session terminated: ${e.message}")
                    } finally {
                        try {
                            socketInstance.close()
                        } catch (ex: Exception) {}
                        clientSocket = null
                        ServerState.updateState(true, "Listening...", R.color.state_connecting)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    ServerState.log("Server Engine Error: ${e.message}")
                }
            } finally {
                try {
                    serverSocket?.close()
                } catch (ex: Exception) {}
                serverSocket = null
                ServerState.updateState(false, "Disconnected", R.color.state_inactive)
                stopSelf()
            }
        }
    }

    // --- PROTOCOL TRANSACTION HANDLER ---
    private suspend fun runProtocolSession(inputStream: InputStream, outputStream: OutputStream) = coroutineScope {
        // Start periodic ping heartbeat transmitter
        val pingJob = launch {
            while (isActive) {
                delay(4000)
                try {
                    outputStream.write(rpcHandler.pack(0xFF, 0, 0, null))
                    ServerState.log("Heartbeat ping dispatched")
                } catch (e: Exception) {
                    ServerState.log("Heartbeat dispatch failure: ${e.message}")
                    break
                }
            }
        }

        val headerBytes = ByteArray(12)
        try {
            while (isActive) {
                inputStream.readFully(headerBytes)
                val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                buf.get() // Skip version/flags
                val type = buf.get().toInt() and 0xFF
                buf.get() // Skip service type
                val sid = buf.get().toInt()
                val pSize = buf.getInt()
                val cid = buf.getInt()
                
                var payloadStr = ""
                if (pSize > 0) {
                    val payload = ByteArray(pSize)
                    inputStream.readFully(payload)
                    payloadStr = String(payload)
                }

                ServerState.log("Recv packet: Type=$type, Sid=$sid, Cid=$cid, Size=$pSize")
                if (payloadStr.isNotEmpty()) {
                    ServerState.log("Recv JSON: $payloadStr")
                }

                val response = rpcHandler.handle(type, sid, cid, payloadStr)
                if (response != null) {
                    outputStream.write(response)
                    ServerState.log("Response packet written to stream")
                }
            }
        } finally {
            pingJob.cancel()
        }
    }

    private fun createNotification(): Notification {
        val chan = NotificationChannel("aha", "Subaru Emulator", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        return NotificationCompat.Builder(this, "aha")
            .setContentTitle("Aha Emulation Active")
            .setContentText("Subaru Aha RFCOMM Emulator running in background")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try {
            clientSocket?.close()
        } catch (e: Exception) {}
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        
        ServerState.updateState(false, "Stopped", R.color.state_inactive)
        ServerState.log("Aha Emulator Service stopped")
    }

    override fun onBind(i: Intent?): IBinder? = null
}
