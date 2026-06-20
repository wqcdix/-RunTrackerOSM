package com.example.runtracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView

    private lateinit var tvStatus: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAverageSpeed: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvElevationGain: TextView
    private lateinit var tvCoordinates: TextView

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val trackPoints = mutableListOf<GeoPoint>()
    private var trackPolyline: Polyline? = null
    private var currentMarker: Marker? = null
    private var startMarkerAdded = false
    private var isRunning = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val locationGranted =
                result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (locationGranted) {
                startRun()
            } else {
                Toast.makeText(
                    this,
                    "Для записи пробежки нужен доступ к геолокации",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackingService.ACTION_LOCATION_UPDATE) return

            val latitude = intent.getDoubleExtra(
                TrackingService.EXTRA_LATITUDE,
                Double.NaN
            )

            val longitude = intent.getDoubleExtra(
                TrackingService.EXTRA_LONGITUDE,
                Double.NaN
            )

            if (latitude.isNaN() || longitude.isNaN()) return

            val distance = intent.getFloatExtra(
                TrackingService.EXTRA_DISTANCE,
                0f
            )

            val speed = intent.getFloatExtra(
                TrackingService.EXTRA_SPEED,
                0f
            )

            val averageSpeed = intent.getFloatExtra(
                TrackingService.EXTRA_AVERAGE_SPEED,
                0f
            )

            val altitude = intent.getDoubleExtra(
                TrackingService.EXTRA_ALTITUDE,
                Double.NaN
            )

            val elevationGain = intent.getDoubleExtra(
                TrackingService.EXTRA_ELEVATION_GAIN,
                0.0
            )

            val point = GeoPoint(latitude, longitude)

            updateMap(point)
            updateStats(
                latitude = latitude,
                longitude = longitude,
                distanceMeters = distance,
                speedMetersPerSecond = speed,
                averageSpeedMetersPerSecond = averageSpeed,
                altitude = altitude,
                elevationGain = elevationGain
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Настройка osmdroid. API-ключ не нужен.
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        tvStatus = findViewById(R.id.tvStatus)
        tvDistance = findViewById(R.id.tvDistance)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvAverageSpeed = findViewById(R.id.tvAverageSpeed)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvElevationGain = findViewById(R.id.tvElevationGain)
        tvCoordinates = findViewById(R.id.tvCoordinates)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        setupMap()

        btnStart.setOnClickListener {
            if (isRunning) {
                Toast.makeText(this, "Пробежка уже записывается", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (hasLocationPermission()) {
                startRun()
            } else {
                requestPermissions()
            }
        }

        btnStop.setOnClickListener {
            stopRun()
        }
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            IntentFilter(TrackingService.ACTION_LOCATION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(locationReceiver)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun setupMap() {
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.minZoomLevel = 4.0
        mapView.maxZoomLevel = 20.0

        val startPoint = GeoPoint(55.751244, 37.618423)
        mapView.controller.setZoom(13.0)
        mapView.controller.setCenter(startPoint)
    }

    private fun startRun() {
        isRunning = true
        resetRunOnMap()
        tvStatus.text = "Статус: запись пробежки"

        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Запись пробежки начата", Toast.LENGTH_SHORT).show()
    }

    private fun stopRun() {
        if (!isRunning) {
            Toast.makeText(this, "Запись ещё не запущена", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = false

        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_STOP
        }
        startService(intent)

        tvStatus.text = "Статус: пробежка остановлена"

        val lastPoint = trackPoints.lastOrNull()
        if (lastPoint != null) {
            val finishMarker = Marker(mapView).apply {
                position = lastPoint
                title = "Финиш пробежки"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(finishMarker)
            mapView.invalidate()
        }

        Toast.makeText(this, "Запись пробежки остановлена", Toast.LENGTH_SHORT).show()
    }

    private fun resetRunOnMap() {
        trackPoints.clear()
        mapView.overlays.clear()
        trackPolyline = null
        currentMarker = null
        startMarkerAdded = false

        tvDistance.text = "Дистанция: 0.00 км"
        tvSpeed.text = "Текущая скорость: 0.0 км/ч"
        tvAverageSpeed.text = "Средняя скорость: 0.0 км/ч"
        tvAltitude.text = "Высота: нет данных"
        tvElevationGain.text = "Набор высоты: 0 м"
        tvCoordinates.text = "Координаты: нет данных"

        mapView.invalidate()
    }

    private fun updateMap(point: GeoPoint) {
        if (!startMarkerAdded) {
            val startMarker = Marker(mapView).apply {
                position = point
                title = "Старт пробежки"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(startMarker)
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(point)
            startMarkerAdded = true
        } else {
            mapView.controller.animateTo(point)
        }

        if (currentMarker == null) {
            currentMarker = Marker(mapView).apply {
                position = point
                title = "Текущее местоположение"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(currentMarker)
        } else {
            currentMarker?.position = point
        }

        trackPoints.add(point)

        if (trackPolyline == null) {
            trackPolyline = Polyline().apply {
                setColor(Color.BLUE)
                setWidth(10f)
                setPoints(trackPoints)
            }
            mapView.overlays.add(trackPolyline)
        } else {
            trackPolyline?.setPoints(trackPoints)
        }

        mapView.invalidate()
    }

    private fun updateStats(
        latitude: Double,
        longitude: Double,
        distanceMeters: Float,
        speedMetersPerSecond: Float,
        averageSpeedMetersPerSecond: Float,
        altitude: Double,
        elevationGain: Double
    ) {
        val distanceKm = distanceMeters / 1000f
        val speedKmH = speedMetersPerSecond * 3.6f
        val averageSpeedKmH = averageSpeedMetersPerSecond * 3.6f

        tvDistance.text = "Дистанция: %.2f км".format(distanceKm)
        tvSpeed.text = "Текущая скорость: %.1f км/ч".format(speedKmH)
        tvAverageSpeed.text = "Средняя скорость: %.1f км/ч".format(averageSpeedKmH)

        tvAltitude.text =
            if (altitude.isNaN()) {
                "Высота: нет данных"
            } else {
                "Высота: %.0f м".format(altitude)
            }

        tvElevationGain.text = "Набор высоты: %.0f м".format(elevationGain)
        tvCoordinates.text = "Координаты: %.6f, %.6f".format(latitude, longitude)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }
}
