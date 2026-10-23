package com.example.ridefilterpro

import android.accessibilityservice.AccessibilityService
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.regex.Pattern

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ride_filter_prefs", Context.MODE_PRIVATE)

    var isServiceActive: Boolean
        get() = prefs.getBoolean("is_active", false)
        set(value) = prefs.edit().putBoolean("is_active", value).apply()

    var minFare: Float
        get() = prefs.getFloat("min_fare", 30.0f)
        set(value) = prefs.edit().putFloat("min_fare", value).apply()

    var minRatePerKm: Float
        get() = prefs.getFloat("min_rate_per_km", 10.0f)
        set(value) = prefs.edit().putFloat("min_rate_per_km", value).apply()

    var maxPickupDistance: Float
        get() = prefs.getFloat("max_pickup_distance", 2.0f)
        set(value) = prefs.edit().putFloat("max_pickup_distance", value).apply()

    var petrolPrice: Float
        get() = prefs.getFloat("petrol_price", 114.0f)
        set(value) = prefs.edit().putFloat("petrol_price", value).apply()

    var bikeMileage: Float
        get() = prefs.getFloat("bike_mileage", 45.0f)
        set(value) = prefs.edit().putFloat("bike_mileage", value).apply()
}

data class RideDetails(val fare: Float, val tripDistanceKm: Float, val pickupDistanceKm: Float)
data class FilterResult(val passes: Boolean, val reason: String, val fuelCost: Float, val netEarnings: Float)

class RideFilterEngine(private val settings: SettingsManager) {
    fun evaluateRide(ride: RideDetails): FilterResult {
        val totalDistance = ride.tripDistanceKm + ride.pickupDistanceKm
        val fuelLiters = if (settings.bikeMileage > 0) totalDistance / settings.bikeMileage else 0f
        val fuelCost = fuelLiters * settings.petrolPrice
        val netEarnings = ride.fare - fuelCost

        if (ride.fare < settings.minFare) {
            return FilterResult(false, "Fare (₹${ride.fare}) < Min", fuelCost, netEarnings)
        }
        if (ride.pickupDistanceKm > settings.maxPickupDistance) {
            return FilterResult(false, "Pickup (${ride.pickupDistanceKm}km) > Max", fuelCost, netEarnings)
        }
        val ratePerKm = if (ride.tripDistanceKm > 0) ride.fare / ride.tripDistanceKm else 0f
        if (ratePerKm < settings.minRatePerKm) {
            return FilterResult(false, "Rate (₹$ratePerKm/km) < Min Rate", fuelCost, netEarnings)
        }
        return FilterResult(true, "Qualified! Net: ₹$netEarnings", fuelCost, netEarnings)
    }
}

class RideAccessibilityService : AccessibilityService() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var filterEngine: RideFilterEngine

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsManager = SettingsManager(applicationContext)
        filterEngine = RideFilterEngine(settingsManager)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!settingsManager.isServiceActive) return
        val rootNode = rootInActiveWindow ?: return
        try {
            val collectedText = mutableListOf<String>()
            extractNodeTexts(rootNode, collectedText)
            val fullText = collectedText.joinToString(" ")
            val rideDetails = parseRideInfo(fullText) ?: return

            val result = filterEngine.evaluateRide(rideDetails)
            if (result.passes) {
                performAccept(rootNode)
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun extractNodeTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.let { list.add(it.toString()) }
        node.contentDescription?.let { list.add(it.toString()) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            extractNodeTexts(child, list)
            child.recycle()
        }
    }

    private fun parseRideInfo(rawText: String): RideDetails? {
        val farePattern = Pattern.compile("(?:₹|rs\\.?|inr)\\s*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE)
        val kmPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*km", Pattern.CASE_INSENSITIVE)

        val fareMatcher = farePattern.matcher(rawText)
        val fare = if (fareMatcher.find()) fareMatcher.group(1)?.toFloatOrNull() ?: return null else return null

        val kmMatcher = kmPattern.matcher(rawText)
        val distances = mutableListOf<Float>()
        while (kmMatcher.find()) {
            kmMatcher.group(1)?.toFloatOrNull()?.let { distances.add(it) }
        }
        if (distances.isEmpty()) return null

        val pickup = distances.firstOrNull() ?: 0f
        val trip = if (distances.size > 1) distances[1] else distances[0]
        return RideDetails(fare, trip, pickup)
    }

    private fun performAccept(root: AccessibilityNodeInfo): Boolean {
        val buttons = root.findAccessibilityNodeInfosByText("Accept")
        for (btn in buttons) {
            if (btn.isClickable && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var p = btn.parent
            while (p != null) {
                if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                p = p.parent
            }
        }
        return false
    }

    override fun onInterrupt() {}
}

class FloatingWidgetService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: ImageView? = null
    private lateinit var settings: SettingsManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        floatingView = ImageView(this).apply {
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (settings.isServiceActive) Color.parseColor("#00C853") else Color.parseColor("#1976D2"))
            }
            background = shape
            setImageResource(android.R.drawable.ic_dialog_info)
            setPadding(30, 30, 30, 30)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            150, 150, layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager?.addView(floatingView, params)

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var isClick = false

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
                        params.x = initX + dx
                        params.y = initY + dy
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) {
                            settings.isServiceActive = !settings.isServiceActive
                            val shape = floatingView?.background as? GradientDrawable
                            shape?.setColor(if (settings.isServiceActive) Color.parseColor("#00C853") else Color.parseColor("#1976D2"))
                            val msg = if (settings.isServiceActive) "Filter Active" else "Filter Paused"
                            Toast.makeText(this@FloatingWidgetService, msg, Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) windowManager?.removeView(floatingView)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var tvStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOverlayToggle: Button
    private var isOverlayRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)

        tvStatus = findViewById(R.id.tvStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnOverlayToggle = findViewById(R.id.btnOverlayToggle)
        val btnPermission = findViewById<Button>(R.id.btnPermission)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnSimulateAccept = findViewById<Button>(R.id.btnSimulateAccept)

        val etMinFare = findViewById<EditText>(R.id.etMinFare)
        val etMinRate = findViewById<EditText>(R.id.etMinRate)
        val etMaxPickup = findViewById<EditText>(R.id.etMaxPickup)
        val etPetrolPrice = findViewById<EditText>(R.id.etPetrolPrice)
        val etBikeMileage = findViewById<EditText>(R.id.etBikeMileage)

        etMinFare.setText(settingsManager.minFare.toString())
        etMinRate.setText(settingsManager.minRatePerKm.toString())
        etMaxPickup.setText(settingsManager.maxPickupDistance.toString())
        etPetrolPrice.setText(settingsManager.petrolPrice.toString())
        etBikeMileage.setText(settingsManager.bikeMileage.toString())

        updateStatusUI()

        btnSave.setOnClickListener {
            settingsManager.minFare = etMinFare.text.toString().toFloatOrNull() ?: 30f
            settingsManager.minRatePerKm = etMinRate.text.toString().toFloatOrNull() ?: 10f
            settingsManager.maxPickupDistance = etMaxPickup.text.toString().toFloatOrNull() ?: 2f
            settingsManager.petrolPrice = etPetrolPrice.text.toString().toFloatOrNull() ?: 114f
            settingsManager.bikeMileage = etBikeMileage.text.toString().toFloatOrNull() ?: 45f
            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
        }

        btnToggle.setOnClickListener {
            settingsManager.isServiceActive = !settingsManager.isServiceActive
            updateStatusUI()
        }

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlayToggle.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                val overlayIntent = Intent(this, FloatingWidgetService::class.java)
                if (isOverlayRunning) {
                    stopService(overlayIntent)
                    isOverlayRunning = false
                    btnOverlayToggle.text = "Floating Icon"
                } else {
                    startService(overlayIntent)
                    isOverlayRunning = true
                    btnOverlayToggle.text = "Hide Icon"
                }
            }
        }

        btnSimulateAccept.setOnClickListener {
            Toast.makeText(this, "Ride Accepted!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUI()
    }

    private fun updateStatusUI() {
        if (settingsManager.isServiceActive) {
            tvStatus.text = "Status: Filter Running (Active)"
            tvStatus.setBackgroundColor(Color.parseColor("#D4EDDA"))
            btnToggle.text = "Stop Filter"
        } else {
            tvStatus.text = "Status: Idle (Stopped)"
            tvStatus.setBackgroundColor(Color.parseColor("#EEEEEE"))
            btnToggle.text = "Start Filter"
        }
    }
}
