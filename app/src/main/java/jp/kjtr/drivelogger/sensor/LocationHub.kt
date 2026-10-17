package jp.kjtr.drivelogger.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 端末の位置。kijunten_meguri の LocationHub をもとに、使う側（走行画面・記録サービス）ごとに [acquire] / [release] する形にした。
 * 誰かが使っている間だけ測位する。
 *
 * 測位の方式（0.8.0〜 設定「測位の方式」、[setUseFused]）:
 * - 端末の GPS（既定）: LocationManager の GPS とネットワーク（GPS が 10 秒来なければネットワーク位置も使う）。
 * - Google の融合測位: Google Play 開発者サービスの FusedLocationProviderClient（PRIORITY_HIGH_ACCURACY）。GNSS・Wi‑Fi・
 *   携帯の基地局・センサーを Google の仕組みで組み合わせた位置。Play 開発者サービスが無い・使えないときは自動で端末の GPS に戻す（[fusedActive] = false）。
 */
class LocationHub(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var wantFused = false
    /** いま Google の融合測位で測っている（測位を始めたときに決まる）。 */
    private var usingFused = false
    private val _fusedActive = MutableStateFlow(false)
    /** Google の融合測位で測っている（設定で選んでいても、使えなければ false）。 */
    val fusedActive: StateFlow<Boolean> = _fusedActive
    private val fusedClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) onLocationChanged(l)
        }
    }

    /** Google Play 開発者サービスの位置 API が使えるか。 */
    fun fusedAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** 測位の方式を変える（true = Google の融合測位）。測っていればやり直す。 */
    @Synchronized
    fun setUseFused(on: Boolean) {
        if (wantFused == on) return
        wantFused = on
        if (running) {
            stop()
            start()
        }
    }

    private val _last = MutableStateFlow<Location?>(null)
    /** 最後に採用した位置（表示用）。 */
    val last: StateFlow<Location?> = _last

    private val _updates = MutableSharedFlow<Location>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 届いた位置を 1 件ずつ（記録用。取りこぼさないようバッファ付き）。 */
    val updates: SharedFlow<Location> = _updates

    private val _locationEnabled = MutableStateFlow(true)
    /** 端末の位置情報（GPS かネットワーク）が ON。測位中だけ更新する。 */
    val locationEnabled: StateFlow<Boolean> = _locationEnabled

    /** 最後に精度 [GOOD_ACCURACY_M] 以内の位置が届いた時刻（elapsedRealtime ms。無ければ 0）。 */
    @Volatile var lastGoodFixElapsedMs = 0L
        private set

    private val owners = HashSet<String>()
    private var running = false
    private var paused = false

    private val _paused = MutableStateFlow(false)
    /** 停車中で測位を止めている（[setPaused]）。 */
    val pausedState: StateFlow<Boolean> = _paused

    /**
     * 測位を止める / 再開する（停車中の電池の節約。0.3.0〜）。止めている間は誰が [acquire] しても測位しない。
     */
    @Synchronized
    fun setPaused(on: Boolean) {
        if (paused == on) return
        paused = on
        _paused.value = on
        if (on) stop() else if (owners.isNotEmpty()) start()
    }
    private var intervalMs = 1000L
    private var lastGpsAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun acquire(owner: String) {
        owners.add(owner)
        start()
    }

    @Synchronized
    fun release(owner: String) {
        owners.remove(owner)
        if (owners.isEmpty()) stop()
    }

    /** 取得間隔を変える（動いていれば測位をやり直す）。 */
    @Synchronized
    fun setInterval(ms: Long) {
        if (ms == intervalMs) return
        intervalMs = ms
        if (running) {
            stop()
            start()
        }
    }

    /** 権限を得た直後など。 */
    @Synchronized
    fun restart() {
        stop()
        if (owners.isNotEmpty()) start()
    }

    private fun start() {
        if (running || paused || !hasPermission()) return
        if (wantFused && fusedAvailable() && startFused()) return
        usingFused = false
        _fusedActive.value = false
        startManager()
    }

    /** Google の融合測位で始める。始められなければ false（端末の GPS で測る）。 */
    private fun startFused(): Boolean {
        return try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs)
                .setWaitForAccurateLocation(false)
                .build()
            fusedClient.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper())
                .addOnFailureListener { e ->
                    // 途中で使えなくなった（Play 開発者サービスの更新中など）→ 端末の GPS に戻す
                    android.util.Log.w("DL-Location", "fused location failed → device GPS: ${e.message}")
                    synchronized(this) {
                        if (running && usingFused) {
                            runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
                            usingFused = false
                            _fusedActive.value = false
                            running = false
                            startManager()
                        }
                    }
                }
            if (_last.value == null) {
                fusedClient.lastLocation.addOnSuccessListener { l -> if (l != null && _last.value == null) _last.value = l }
            }
            usingFused = true
            _fusedActive.value = true
            running = true
            refreshEnabled()
            true
        } catch (_: SecurityException) {
            false
        } catch (e: RuntimeException) {
            android.util.Log.w("DL-Location", "fused location unavailable: ${e.message}")
            false
        }
    }

    private fun startManager() {
        try {
            // 無効なプロバイダにも登録しておく（後で有効にされたら届き始める）
            val all = runCatching { manager.allProviders }.getOrDefault(emptyList())
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
            if (_last.value == null) {
                providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.elapsedRealtimeNanos }?.let { _last.value = it }
            }
            for (p in providers) manager.requestLocationUpdates(p, intervalMs, 0f, this, Looper.getMainLooper())
            running = true
            refreshEnabled()
        } catch (_: SecurityException) {
            // 権限が途中で外された
        } catch (_: IllegalArgumentException) {
            // プロバイダが無い端末
        }
    }

    private fun stop() {
        try {
            manager.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        if (usingFused) runCatching { fusedClient.removeLocationUpdates(fusedCallback) }
        usingFused = false
        running = false
    }

    override fun onLocationChanged(location: Location) {
        val nowMs = location.elapsedRealtimeNanos / 1_000_000
        // 融合測位の位置（provider = "fused"）は GPS と同じ扱い
        val isGps = location.provider == LocationManager.GPS_PROVIDER || location.provider == FUSED_PROVIDER
        // GPS が 10 秒以内に来ていれば、ネットワーク位置（精度が粗い）は使わない
        if (!isGps && nowMs - lastGpsAt < 10_000) return
        if (isGps) lastGpsAt = nowMs
        if (location.hasAccuracy() && location.accuracy <= GOOD_ACCURACY_M) lastGoodFixElapsedMs = SystemClock.elapsedRealtime()
        _last.value = location
        _updates.tryEmit(location)
    }

    @Deprecated("Deprecated in API 29 but required on older APIs")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) = refreshEnabled()
    override fun onProviderDisabled(provider: String) = refreshEnabled()

    /**
     * その場で 1 回だけ測位する（駐車中の写真・車までの距離。0.4.0〜）。止めている（[setPaused]）間でも、この呼び出しの間だけ測る。
     * 精度 [wantAccuracyM] 以内の位置が来たらすぐ、来なければ [timeoutMs] 待って、それまでの一番よい位置（精度 [maxAccuracyM] 以内）。
     * 取れなければ null。記録用の [updates] には流さない。
     */
    suspend fun oneShot(timeoutMs: Long = 8_000, wantAccuracyM: Float = GOOD_ACCURACY_M, maxAccuracyM: Float = 100f): Location? {
        if (!hasPermission()) return null
        val all = runCatching { manager.allProviders }.getOrDefault(emptyList())
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
        if (providers.isEmpty()) return null
        var best: Location? = null
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // 呼ぶ前の古い位置は使わない
                if (location.elapsedRealtimeNanos < startedNs - 2_000_000_000L) return
                val acc = if (location.hasAccuracy()) location.accuracy else 0f
                val b = best
                if (b == null || acc < (if (b.hasAccuracy()) b.accuracy else 0f)) best = location
                if (acc <= wantAccuracyM) done.complete(Unit)
            }

            @Deprecated("Deprecated in API 29 but required on older APIs")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            for (p in providers) manager.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        try {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            runCatching { manager.removeUpdates(listener) }
        }
        return best?.takeIf { !it.hasAccuracy() || it.accuracy <= maxAccuracyM }
    }

    /** 位置情報の ON / OFF を読み直す（設定の切り替えは onProviderEnabled / Disabled で届く）。 */
    fun refreshEnabled() {
        _locationEnabled.value = runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(true)
    }

    companion object {
        /** 「使える位置」とみなす精度（記録の RideTracker と同じ 30 m）。 */
        const val GOOD_ACCURACY_M = 30f
        /** Google の融合測位の位置の provider 名。 */
        const val FUSED_PROVIDER = "fused"
    }
}
