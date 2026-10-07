# sensors-haptics — read a sensor (step counter, accelerometer, light) and give haptic feedback

- Register in a `DisposableEffect` and unregister in `onDispose`, or the sensor keeps the CPU awake.
- Step counter needs ACTIVITY_RECOGNITION (runtime permission) and reports steps since boot.
- Haptics: `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` — no permission.

```kotlin
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
fun skillLightLevel(): Float {
    val context = LocalContext.current
    var lux by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0] }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sm.unregisterListener(listener) }
    }
    return lux
}
```
