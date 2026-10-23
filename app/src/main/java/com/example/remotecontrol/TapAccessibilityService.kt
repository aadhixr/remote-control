package com.example.remotecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android does not allow a normal app to inject touch events
 * (`android.permission.INJECT_EVENTS` is signature-level), so remote taps are
 * dispatched as accessibility gestures. The user must enable this service
 * manually under Settings -> Accessibility; [MainActivity] deep-links there.
 */
class TapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Dispatches a tap at (x, y) in screen coordinates; callback fires with the result. */
    fun tap(x: Float, y: Float, onResult: (Boolean) -> Unit) {
        mainHandler.post {
            val delivered = AtomicBoolean(false)
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (delivered.compareAndSet(false, true)) onResult(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (delivered.compareAndSet(false, true)) onResult(false)
                    }
                },
                null
            )
            if (!dispatched && delivered.compareAndSet(false, true)) {
                onResult(false)
            }
        }
    }
}
