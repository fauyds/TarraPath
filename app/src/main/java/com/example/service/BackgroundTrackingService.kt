package com.example.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.TerraPathApplication
import com.example.domain.GeoGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AndroidBackgroundServiceController(
    private val context: Context
) : BackgroundServiceController {
    private val appContext = context.applicationContext

    override fun startLocationService() {
        BackgroundTrackingService.start(appContext)
    }

    override fun stopLocationService() {
        BackgroundTrackingService.stop(appContext)
    }
}

class BackgroundTrackingService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationObserverJob: Job? = null

    private val coordinator: TrackingCoordinator
        get() = (application as TerraPathApplication).container.trackingCoordinator

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        if (!hasAnyLocationPermission()) {
            stopSelf()
            return
        }
        coordinator.updatePermissions(
            hasLocation = hasAnyLocationPermission(),
            hasActivityRecognition = hasActivityRecognitionPermission()
        )
        if (promoteToForeground()) {
            coordinator.onBackgroundServiceCreated()
            observeCoordinatorForNotifications()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_DISABLE_BACKGROUND -> {
                coordinator.setBackgroundTrackingEnabled(false)
                if (!coordinator.activeRecording.value.isRecording &&
                    coordinator.walkingRouteState.value.target == null
                ) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_RECORDING -> {
                coordinator.stopRouteRecordingAndPrompt()
            }
            ACTION_STOP_WALKING -> {
                coordinator.stopWalkingNavigation()
            }
        }

        if (!hasAnyLocationPermission()) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // Sync permissions in case service restarted via START_STICKY after process kill
        coordinator.updatePermissions(
            hasLocation = hasAnyLocationPermission(),
            hasActivityRecognition = hasActivityRecognitionPermission()
        )

        if (!promoteToForeground()) {
            return START_NOT_STICKY
        }

        coordinator.onBackgroundServiceCreated()
        updateNotificationNow()

        return if (coordinator.shouldRunBackgroundService()) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!coordinator.shouldRunBackgroundService()) {
            stopForegroundAndSelf()
        }
    }

    override fun onDestroy() {
        notificationObserverJob?.cancel()
        serviceScope.cancel()
        coordinator.onBackgroundServiceDestroyed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeCoordinatorForNotifications() {
        notificationObserverJob?.cancel()
        notificationObserverJob = serviceScope.launch {
            launch {
                combine(
                    coordinator.activeRecording,
                    coordinator.walkingRouteState,
                    coordinator.locationTelemetry,
                    coordinator.totalChunkCount
                ) { rec, walk, telemetry, chunks ->
                    NotificationSnapshot(rec, walk, telemetry, chunks)
                }.collect { snapshot ->
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(snapshot))
                }
            }
            // Keep elapsed recording duration fresh every second even if GPS is steady
            launch {
                while (isActive) {
                    delay(2000L)
                    if (coordinator.activeRecording.value.isRecording) {
                        updateNotificationNow()
                    }
                }
            }
        }
    }

    private fun updateNotificationNow() {
        val snapshot = NotificationSnapshot(
            recording = coordinator.activeRecording.value,
            walking = coordinator.walkingRouteState.value,
            telemetry = coordinator.locationTelemetry.value,
            chunksUnlocked = coordinator.totalChunkCount.value
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun promoteToForeground(): Boolean {
        val snapshot = NotificationSnapshot(
            recording = coordinator.activeRecording.value,
            walking = coordinator.walkingRouteState.value,
            telemetry = coordinator.locationTelemetry.value,
            chunksUnlocked = coordinator.totalChunkCount.value
        )
        val notification = buildNotification(snapshot)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        } catch (e: SecurityException) {
            stopSelf()
            false
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                stopSelf()
                false
            } else {
                stopSelf()
                false
            }
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(snapshot: NotificationSnapshot): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val recording = snapshot.recording
        val walking = snapshot.walking
        val telemetry = snapshot.telemetry

        val title: String
        val body: String
        var actionLabel: String? = null
        var actionIntent: PendingIntent? = null

        when {
            recording.isRecording -> {
                title = "Recording Route"
                body = "${GeoGrid.formatDistance(recording.distanceMeters)} • ${GeoGrid.formatDurationExact(recording.elapsedMillis)} • ${snapshot.chunksUnlocked} sectors"
                actionLabel = "Stop Route"
                actionIntent = serviceActionPendingIntent(ACTION_STOP_RECORDING, REQ_ACTION_STOP_REC)
            }
            walking.target != null -> {
                title = "Walking to ${walking.target.label}"
                val etaMin = ((walking.estimatedWalkSeconds + 30L) / 60L).coerceAtLeast(1L)
                body = if (telemetry.hasFix) {
                    "${GeoGrid.formatDistance(walking.remainingDistanceMeters)} • ~$etaMin min • ${snapshot.chunksUnlocked} sectors"
                } else {
                    "Acquiring GPS signal… • ${snapshot.chunksUnlocked} sectors"
                }
                actionLabel = "Stop Walk"
                actionIntent = serviceActionPendingIntent(ACTION_STOP_WALKING, REQ_ACTION_STOP_WALK)
            }
            else -> {
                title = "Exploring in Background"
                val speedText = if (telemetry.hasFix) {
                    String.format("%.1f km/h", telemetry.speedKmh)
                } else {
                    "GPS ready"
                }
                body = "${snapshot.chunksUnlocked} sectors unlocked • $speedText"
                actionLabel = "Pause Background"
                actionIntent = serviceActionPendingIntent(ACTION_DISABLE_BACKGROUND, REQ_ACTION_PAUSE_BG)
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terrapath)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (actionLabel != null && actionIntent != null) {
            builder.addAction(0, actionLabel, actionIntent)
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundTrackingService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.tracking_channel_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun hasAnyLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private data class NotificationSnapshot(
        val recording: com.example.data.model.ActiveRecordingState,
        val walking: com.example.data.model.WalkingRouteState,
        val telemetry: com.example.data.model.LocationTelemetry,
        val chunksUnlocked: Int
    )

    companion object {
        const val CHANNEL_ID = "terrapath_tracking_channel"
        const val NOTIFICATION_ID = 4107

        const val ACTION_START = "com.example.terrapath.action.START_TRACKING"
        const val ACTION_STOP_SERVICE = "com.example.terrapath.action.STOP_SERVICE"
        const val ACTION_DISABLE_BACKGROUND = "com.example.terrapath.action.DISABLE_BACKGROUND"
        const val ACTION_STOP_RECORDING = "com.example.terrapath.action.STOP_RECORDING"
        const val ACTION_STOP_WALKING = "com.example.terrapath.action.STOP_WALKING"

        private const val REQ_OPEN_APP = 100
        private const val REQ_ACTION_STOP_REC = 101
        private const val REQ_ACTION_STOP_WALK = 102
        private const val REQ_ACTION_PAUSE_BG = 103

        fun start(context: Context) {
            val appContext = context.applicationContext
            val fine = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return

            val intent = Intent(appContext, BackgroundTrackingService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (_: Exception) {
                // Safely ignore ForegroundServiceStartNotAllowedException if called while transitioning
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, BackgroundTrackingService::class.java)
            try {
                appContext.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
