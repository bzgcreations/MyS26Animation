package com.example.foldanim

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

class OverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner, SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var sensorManager: SensorManager
    
    private var mediaProjection: MediaProjection? = null
    private var tiltProgress by mutableFloatStateOf(0.5f)
    
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        try {
            val channel = NotificationChannel("cast", "3D Screen Cast", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            
            val notif = Notification.Builder(this, "cast")
                .setContentTitle("3D Perspective Active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
                
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notif)
            }

            val code = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            
            // Safely get the Intent data across all Android versions
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("DATA")
            }

            if (code == Activity.RESULT_OK && data != null) {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                mediaProjection = mgr.getMediaProjection(code, data)
                showOverlay()
            } else {
                stopSelf() // Closes safely if permissions failed
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE, 
            PixelFormat.TRANSLUCENT
        )

        composeView = ComposeView(this).apply {
            setContent {
                LiveScreenPerspective(mediaProjection, tiltProgress)
            }
        }

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)

        windowManager.addView(composeView, params)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val xTilt = it.values[0]
            tiltProgress = ((xTilt + 5f) / 10f).coerceIn(0f, 1f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        mediaProjection?.stop()
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }
}

@Composable
fun LiveScreenPerspective(mediaProjection: MediaProjection?, tiltProgress: Float) {
    val context = LocalContext.current
    
    // Calculate the bending angle (-45 to 45 degrees)
    val tiltAngle = (tiltProgress - 0.5f) * -90f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = tiltAngle
                cameraDistance = 14f * density // Creates the deep 3D perspective
            }
    ) {
        if (mediaProjection != null) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            var virtualDisplay: VirtualDisplay? = null
                            var surface: Surface? = null

                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                try {
                                    surface = Surface(st)
                                    val metrics = ctx.resources.displayMetrics
                                    
                                    virtualDisplay = mediaProjection.createVirtualDisplay(
                                        "ScreenCapture",
                                        metrics.widthPixels, 
                                        metrics.heightPixels, 
                                        metrics.densityDpi,
                                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                        surface, null, null
                                    )
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                            
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                virtualDisplay?.release()
                                surface?.release()
                                return true
                            }
                            
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // The dynamic glass shadow/light gradient that shifts based on rotation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.White.copy(alpha = if (tiltAngle < 0) 0.3f else 0.0f),
                        1.0f to Color.Black.copy(alpha = if (tiltAngle > 0) 0.6f else 0.0f)
                    )
                )
        )
    }
}