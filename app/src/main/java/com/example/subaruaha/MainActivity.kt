package com.example.subaruaha

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.subaruaha.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Persistent settings loading
        val prefs = getSharedPreferences("aha_prefs", Context.MODE_PRIVATE)
        val savedMac = prefs.getString("mac_address", "F8:E8:77:8E:9C:9B")
        val isServerMode = prefs.getBoolean("is_server_mode", false)

        binding.etMacAddress.setText(savedMac)

        if (isServerMode) {
            binding.rbServerMode.isChecked = true
            binding.layoutClientSettings.visibility = View.GONE
        } else {
            binding.rbClientMode.isChecked = true
            binding.layoutClientSettings.visibility = View.VISIBLE
        }

        // Toggle settings panel based on Client/Server mode
        binding.rgRfcommMode.setOnCheckedChangeListener { _, checkedId ->
            val isServer = checkedId == R.id.rb_server_mode
            binding.layoutClientSettings.visibility = if (isServer) View.GONE else View.VISIBLE
            prefs.edit().putBoolean("is_server_mode", isServer).apply()
        }

        binding.btnToggleService.setOnClickListener {
            if (ServerState.isRunning) {
                stopService(Intent(this, AhaEmulatorService::class.java))
            } else {
                val isServer = binding.rbServerMode.isChecked
                val mac = binding.etMacAddress.text.toString().trim()
                
                if (!isServer && mac.isEmpty()) {
                    Toast.makeText(this, "Please select or type a target MAC address", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (!isServer) {
                    prefs.edit().putString("mac_address", mac).apply()
                }

                val intent = Intent(this, AhaEmulatorService::class.java).apply {
                    putExtra("is_server_mode", isServer)
                    putExtra("mac_address", mac)
                }
                startForegroundService(intent)
            }
        }

        binding.btnRefreshDevices.setOnClickListener {
            checkPermissionsAndLoad()
        }

        binding.btnClearLogs.setOnClickListener {
            ServerState.clearLogs()
        }

        checkPermissionsAndLoad()
    }

    override fun onStart() {
        super.onStart()
        checkBatteryOptimizationStatus()
        
        ServerState.onUpdateListener = {
            runOnUiThread {
                updateUi()
            }
        }
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        ServerState.onUpdateListener = null
    }

    private fun updateUi() {
        binding.tvStatusText.text = "Service: ${ServerState.connectionState}"
        binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, ServerState.statusColorResId))

        if (ServerState.isRunning) {
            binding.btnToggleService.text = "STOP SERVER"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.state_inactive))
            binding.rgRfcommMode.setEnabled(false)
            binding.rbClientMode.setEnabled(false)
            binding.rbServerMode.setEnabled(false)
        } else {
            binding.btnToggleService.text = "START SERVER"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.rgRfcommMode.setEnabled(true)
            binding.rbClientMode.setEnabled(true)
            binding.rbServerMode.setEnabled(true)
        }

        val sb = java.lang.StringBuilder()
        synchronized(ServerState.logs) {
            for (line in ServerState.logs) {
                sb.append(line).append("\n")
            }
        }
        binding.tvConsoleLogs.text = sb.toString()
        binding.scrollConsole.post { binding.scrollConsole.fullScroll(View.FOCUS_DOWN) }
    }

    private fun checkBatteryOptimizationStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoring) {
            binding.tvBatteryStatus.text = "Battery Optimization: Exempted (runs unrestricted)."
            binding.btnOptimizeExemption.visibility = View.GONE
        } else {
            binding.tvBatteryStatus.text = "Android may restrict background execution unless optimized power consumption is exempted."
            binding.btnOptimizeExemption.visibility = View.VISIBLE
            binding.btnOptimizeExemption.setOnClickListener {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Exemption direct request failed. Please check Android power settings manually.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPermissionsAndLoad() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        } else {
            loadPairedDevices()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            loadPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                ServerState.log("No Bluetooth adapter detected")
                return
            }
            val pairedDevices = adapter.bondedDevices
            val deviceList = mutableListOf<String>()
            val macList = mutableListOf<String>()

            deviceList.add("Select paired device...")
            macList.add("")

            for (device in pairedDevices) {
                deviceList.add("${device.name} (${device.address})")
                macList.add(device.address)
            }

            val arrayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, deviceList)
            arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerPairedDevices.adapter = arrayAdapter

            binding.spinnerPairedDevices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val mac = macList[position]
                    if (mac.isNotEmpty()) {
                        binding.etMacAddress.setText(mac)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            ServerState.log("Failed to query paired devices: ${e.message}")
        }
    }
}
