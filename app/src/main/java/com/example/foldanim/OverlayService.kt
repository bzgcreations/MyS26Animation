package com.example.foldanim

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.lifecycle.ViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.ViewTreeSavedStateRegistryOwner

class OverlayService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner, SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var sensorManager: SensorManager
    
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.blurBehindRadius = 60
        }

        composeView = ComposeView(this).apply {
            setContent {
                OverlayContent(tiltProgress)
            }
        }

        ViewTreeLifecycleOwner.set(composeView, this)
        ViewTreeSavedStateRegistryOwner.set(composeView, this)
        ViewTreeViewModelStoreOwner.set(composeView, this)

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
        if (::composeView.isInitialized) {
            windowManager.removeView(composeView)
        }
    }
}

@Composable
fun OverlayContent(tiltProgress: Float) {
    var swipeProgress by remember { mutableFloatStateOf(0.5f) }
    val activeProgress = (swipeProgress + (tiltProgress - 0.5f)).coerceIn(0f, 1f)

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = dragAmount / size.width
                    swipeProgress = (swipeProgress + delta).coerceIn(0f, 1f)
                }
            }
    ) {
        val width = size.width
        val height = size.height
        val splitX = width * activeProgress

        drawRect(
            color = Color(0xAA1E1E2C), 
            topLeft = Offset(0f, 0f),
            size = Size(splitX, height)
        )

        drawRect(
            color = Color(0xAA2D3250),
            topLeft = Offset(splitX, 0f),
            size = Size(width - splitX, height)
        )

        drawRect(
            color = Color.Black.copy(alpha = 0.6f),
            topLeft = Offset(splitX - 10f, 0f),
            size = Size(20f, height)
        )
    }
}