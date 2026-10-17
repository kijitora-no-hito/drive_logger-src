package jp.kjtr.drivelogger.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import jp.kjtr.drivelogger.dashcam.ImpactDetector
import jp.kjtr.drivelogger.gauge.GReading
import jp.kjtr.drivelogger.gauge.VehicleGEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * 車の動きのセンサー 1 回分（融合と停車の判定に渡す）。[elapsedMs] は SystemClock.elapsedRealtime。
 * [forwardAccel] = 車の前後の加速度 [m/s²]（向きの補正ができるまで null）、[yawRate] = 右回りが正の回転 [rad/s]（ジャイロが無ければ null）。
 */
data class MotionSample(
    val elapsedMs: Long,
    val forwardAccel: Double?,
    val yawRate: Double?,
    /** 重力を除いた加速度の大きさ [m/s²]。 */
    val linearAccel: Double,
    /** ジャイロの大きさ [rad/s]（無ければ null）。 */
    val gyroMagnitude: Double?,
    /** 重力を含む加速度の大きさ [m/s²]（端末の向きによらない。歩行の判定、0.8.0〜）。 */
    val accelNorm: Double = 0.0,
)

/**
 * 加速度センサー（重力を含む TYPE_ACCELEROMETER、約 50 Hz）を [ImpactDetector] に流す。走行サービスが走行中ずっと動かす。
 * 同じ値を [VehicleGEstimator]（車の向きにそろえた G。メーター用）にも流す。イベントはメインスレッドに届く。
 * 0.3.0〜: ジャイロ（あれば）も読み、[onMotion] に前後の加速度・ヨーレート・揺れの大きさを渡す（GPS との融合・停車の判定）。
 */
class ImpactMonitor(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val detector = ImpactDetector(null)
    private val estimator = VehicleGEstimator()
    private var listener: ((ImpactDetector.Impact) -> Unit)? = null
    private var lastPublish = 0L
    private var lastYaw: Double? = null
    private var lastGyroMag: Double? = null
    private var lastGyroAt = 0L
    /** ジャイロのずれ（上まわり）。停車中に少しずつ求める。 */
    private var yawBias = 0.0

    /** 車の動き（加速度のたび。メインスレッド）。 */
    var onMotion: ((MotionSample) -> Unit)? = null

    /** 停車中（ジャイロのずれを求める）。 */
    @Volatile var stationary = false

    private val _vehicleG = MutableStateFlow(GReading())
    /** 車の向きにそろえた G（10 回 / 秒まで。走行中だけ。止まっていれば空）。 */
    val vehicleG: StateFlow<GReading> = _vehicleG

    val available: Boolean get() = sensor != null
    val hasGyro: Boolean get() = gyro != null

    /** 直近の衝撃の大きさ [G]（重力を除く）。 */
    val currentG: Double get() = detector.currentG

    /** [minDurationMs] / [vibrationK] = 衝撃とみなす続く時間・振動に合わせて足す倍率（乗り物ごと。0.8.0〜）。 */
    fun start(
        thresholdG: Double?,
        minDurationMs: Long = ImpactDetector.DEFAULT_MIN_DURATION_MS,
        vibrationK: Double = ImpactDetector.DEFAULT_VIBRATION_K,
        onImpact: (ImpactDetector.Impact) -> Unit,
    ) {
        val running = listener != null
        sm.unregisterListener(this)
        detector.reset()
        detector.thresholdG = thresholdG
        detector.minDurationMs = minDurationMs
        detector.vibrationK = vibrationK
        // 感度を変えただけ（走行中）なら G の補正は続ける
        if (!running) {
            estimator.reset()
            yawBias = 0.0
        }
        listener = onImpact
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun setThreshold(thresholdG: Double?) {
        detector.thresholdG = thresholdG
    }

    fun stop() {
        sm.unregisterListener(this)
        listener = null
        estimator.reset()
        _vehicleG.value = GReading()
        lastYaw = null
        lastGyroMag = null
    }

    /** GPS の点（走行サービスから。G の前後・左右の補正に使う）。 */
    fun onGps(timeMs: Long, speedMps: Double?, courseDeg: Double?) {
        if (listener == null) return
        estimator.onGps(timeMs, speedMps, courseDeg)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
            Sensor.TYPE_ACCELEROMETER -> onAccel(event)
        }
    }

    private fun onGyro(event: SensorEvent) {
        val wx = event.values[0].toDouble()
        val wy = event.values[1].toDouble()
        val wz = event.values[2].toDouble()
        lastGyroMag = sqrt(wx * wx + wy * wy + wz * wz)
        lastGyroAt = SystemClock.elapsedRealtime()
        val up = estimator.up() ?: return
        // 上まわりの回転（左回りが正）。進行方向（北から右回り）の変化はその逆
        val wUp = wx * up[0] + wy * up[1] + wz * up[2]
        if (stationary) yawBias += 0.02 * (wUp - yawBias)
        lastYaw = -(wUp - yawBias)
    }

    private fun onAccel(event: SensorEvent) {
        val t = event.timestamp / 1_000_000
        val ax = event.values[0].toDouble()
        val ay = event.values[1].toDouble()
        val az = event.values[2].toDouble()
        val hit = detector.feed(t, ax, ay, az)
        // GPS の時刻（壁時計）とそろえるため、補正には壁時計を使う
        val now = System.currentTimeMillis()
        estimator.onAccel(now, ax, ay, az)
        val reading = estimator.reading
        if (now - lastPublish >= 100) {
            lastPublish = now
            _vehicleG.value = reading
        }
        onMotion?.let { cb ->
            val el = SystemClock.elapsedRealtime()
            val gyroFresh = el - lastGyroAt < 500
            cb(
                MotionSample(
                    elapsedMs = el,
                    forwardAccel = if (reading.calibrated) reading.longG?.let { it * VehicleGEstimator.G } else null,
                    yawRate = if (gyroFresh) lastYaw else null,
                    linearAccel = estimator.linearMagnitude(ax, ay, az),
                    gyroMagnitude = if (gyroFresh) lastGyroMag else null,
                    accelNorm = sqrt(ax * ax + ay * ay + az * az),
                ),
            )
        }
        if (hit != null) listener?.invoke(hit.copy(timeMs = now))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
