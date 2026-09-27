package com.example.esp32rover

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val DEVICE_NAME = "ESP32_ROVER"
    private val SEND_INTERVAL_MS = 150L // must stay well under the ESP32's 500ms watchdog timeout

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connected = false

    // Latest joystick/gamepad state, sent on a timer rather than on every
    // single touch/motion event - keeps Bluetooth traffic light and steady.
    @Volatile private var lastX = 0
    @Volatile private var lastY = 0

    private val sendHandler = Handler(Looper.getMainLooper())
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val seekSpeed = findViewById<SeekBar>(R.id.seekSpeed)
        val joystick = findViewById<JoystickView>(R.id.joystick)

        joystick.onMoveListener = { x, y ->
            lastX = x
            lastY = y
        }

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) sendLine("S$progress")
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnConnect.setOnClickListener {
            if (!connected) connectToRover() else disconnectFromRover()
        }

        startSendLoop()
    }

    // ---------------- Bluetooth connection ----------------

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true // older Android versions rely on the install-time BLUETOOTH permission
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1
            )
        }
    }

    private fun connectToRover() {
        if (!hasBtPermission()) {
            requestBtPermission()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            tvStatus.text = "Bluetooth not enabled"
            return
        }

        // ESP32_ROVER must already be paired in Android Bluetooth settings first
        val device: BluetoothDevice? = adapter.bondedDevices.find { it.name == DEVICE_NAME }
        if (device == null) {
            tvStatus.text = "Pair '$DEVICE_NAME' in Bluetooth settings first"
            return
        }

        thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                socket.connect()
                bluetoothSocket = socket
                outputStream = socket.outputStream
                connected = true
                runOnUiThread { tvStatus.text = "Connected to $DEVICE_NAME" }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Connection failed: ${e.message}" }
            }
        }
    }

    private fun disconnectFromRover() {
        connected = false
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }
        tvStatus.text = "Disconnected"
    }

    private fun sendLine(line: String) {
        if (!connected) return
        try {
            outputStream?.write("$line\n".toByteArray())
        } catch (e: Exception) {
            connected = false
            runOnUiThread { tvStatus.text = "Connection lost" }
        }
    }

    // Periodically streams the latest joystick/gamepad X,Y - this is what
    // gives smooth continuous control instead of one-shot commands.
    private fun startSendLoop() {
        sendHandler.postDelayed(object : Runnable {
            override fun run() {
                if (connected) sendLine("$lastX,$lastY")
                sendHandler.postDelayed(this, SEND_INTERVAL_MS)
            }
        }, SEND_INTERVAL_MS)
    }

    // ---------------- Physical gamepad support ----------------
    // A paired physical Bluetooth/USB gamepad delivers analog stick movement
    // through this callback, not the touchscreen. It's fed into the same
    // lastX/lastY the virtual joystick uses, so the ESP32 firmware needs no
    // changes at all to support either input source.

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)   // -1.0 to 1.0
            val y = event.getAxisValue(MotionEvent.AXIS_Y)   // -1.0 to 1.0, down = positive

            lastX = (x * 255).toInt().coerceIn(-255, 255)
            lastY = (-y * 255).toInt().coerceIn(-255, 255)   // inverted so up = forward
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Example: use a controller face button as an emergency stop
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            lastX = 0
            lastY = 0
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromRover()
    }
}
