package com.example.runtracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.example.runtracker.ACTION_START"
        const val ACTION_STOP = "com.example.runtracker.ACTION_STOP"
        const val ACTION_LOCATION_UPDATE = "com.example.runtracker.ACTION_LOCATION_UPDATE"

        const val EXTRA_LATITUDE = "extra_latitude"
        const val EXTRA_LONGITUDE = "extra_longitude"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_AVERAGE_SPEED = "extra_average_speed"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_ELEVATION_GAIN = "extra_elevation_gain"

        private const val CHANNEL_ID = "running_tracker_channel"
        private const val NOTIFICATION_ID = 101
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var lastLocation: Location? = null
    private var totalDistanceMeters = 0f
    private var elevationGainMeters = 0.0
    private var startTimeMillis = 0L
    private var isTracking = false

    private val locationRequest: LocationRequest by lazy {
        LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            3000L
        )
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(2f)
            .build()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                handleNewLocation(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_STOP -> stopTracking()
        }

        return START_STICKY
    }

    private fun startTracking() {
        if (isTracking) return

        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        isTracking = true
        lastLocation = null
        totalDistanceMeters = 0f
        elevationGainMeters = 0.0
        startTimeMillis = System.currentTimeMillis()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Идёт запись пробежки"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            stopTracking()
        }
    }

    private fun stopTracking() {
        if (!isTracking) {
            stopSelf()
            return
        }

        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)

        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun handleNewLocation(location: Location) {
        if (!isTracking) return

        // Отсекаем слишком неточные GPS-точки.
        if (location.hasAccuracy() && location.accuracy > 80f) {
            return
        }

        val previousLocation = lastLocation
        var currentSpeedMetersPerSecond = 0f

        if (previousLocation != null) {
            val segmentDistance = previousLocation.distanceTo(location)
            val timeDeltaSeconds = (location.time - previousLocation.time) / 1000.0

            // Отсекаем GPS-скачки и считаем только реалистичные сегменты.
            if (segmentDistance in 0.5f..200f && timeDeltaSeconds > 0) {
                totalDistanceMeters += segmentDistance

                currentSpeedMetersPerSecond =
                    if (location.hasSpeed()) {
                        location.speed
                    } else {
                        (segmentDistance / timeDeltaSeconds).toFloat()
                    }

                if (previousLocation.hasAltitude() && location.hasAltitude()) {
                    val altitudeDelta = location.altitude - previousLocation.altitude

                    // Мелкий шум высоты не учитываем.
                    if (altitudeDelta > 1.0) {
                        elevationGainMeters += altitudeDelta
                    }
                }
            }
        } else {
            currentSpeedMetersPerSecond =
                if (location.hasSpeed()) location.speed else 0f
        }

        lastLocation = location

        val elapsedSeconds =
            ((System.currentTimeMillis() - startTimeMillis) / 1000.0).coerceAtLeast(1.0)

        val averageSpeedMetersPerSecond = totalDistanceMeters / elapsedSeconds

        sendLocationBroadcast(
            location = location,
            speed = currentSpeedMetersPerSecond,
            averageSpeed = averageSpeedMetersPerSecond.toFloat()
        )

        updateNotification(
            distanceMeters = totalDistanceMeters,
            averageSpeedMetersPerSecond = averageSpeedMetersPerSecond.toFloat()
        )
    }

    private fun sendLocationBroadcast(
        location: Location,
        speed: Float,
        averageSpeed: Float
    ) {
        val intent = Intent(ACTION_LOCATION_UPDATE).apply {
            setPackage(packageName)

            putExtra(EXTRA_LATITUDE, location.latitude)
            putExtra(EXTRA_LONGITUDE, location.longitude)
            putExtra(EXTRA_DISTANCE, totalDistanceMeters)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_AVERAGE_SPEED, averageSpeed)
            putExtra(
                EXTRA_ALTITUDE,
                if (location.hasAltitude()) location.altitude else Double.NaN
            )
            putExtra(EXTRA_ELEVATION_GAIN, elevationGainMeters)
        }

        sendBroadcast(intent)
    }

    private fun updateNotification(
        distanceMeters: Float,
        averageSpeedMetersPerSecond: Float
    ) {
        val distanceKm = distanceMeters / 1000f
        val averageSpeedKmH = averageSpeedMetersPerSecond * 3.6f

        val text = "Дистанция: %.2f км, средняя скорость: %.1f км/ч"
            .format(distanceKm, averageSpeedKmH)

        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(text)
            )
        } catch (_: SecurityException) {
        }
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Трекер пробежки")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Запись пробежки",
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
