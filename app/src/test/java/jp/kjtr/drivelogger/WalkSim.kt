package jp.kjtr.drivelogger

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 徒歩（手持ち・ポケット）の合成データ（0.8.0 の原因調査・単体テスト用）。
 *
 * - 真の軌跡: 1.35 m/s で、ゆるく曲がる道（向きが 0.4°/s で回る）を歩く。
 * - 端末の向き: ズボンのポケットに縦（画面が前）→ 歩くたびに太ももと一緒に前後に ±25°（ストライド 0.9 Hz）振れる。
 *   [pocketShiftAt] でポケットの中で 60° 傾き直し、[handAt] からは手に持って画面を見る（画面が上・少し手前）。
 * - 加速度センサー（重力を含む・端末の座標）: 重力＋歩行の周期的な加速度（上下 2.0・前後 1.2 m/s²、1.8 Hz、左右 0.6 m/s²、0.9 Hz）＋雑音 0.15。
 * - ジャイロ（端末の座標）: 端末の向きの変化から。雑音 0.02 rad/s。
 * - GPS 1 Hz: 位置の雑音＝ゆっくり動くずれ（σ 3 m、τ 20 s）＋白色 1.5 m、精度 5〜8 m、ドップラーの速さの雑音 0.25 m/s、方位の雑音 25°。
 *
 * 時刻はすべて ms（0 から）。
 */
class WalkSim(
    seed: Long = 11,
    private val speed: Double = 1.35,
    private val pocketShiftAt: Double = 120.0,
    private val handAt: Double = 240.0,
    /** 車の走行（ホルダーに固定・徒歩の揺れなし）にする。速さ [speed] のまま。 */
    private val car: Boolean = false,
) {
    private val rnd = Random(seed)

    class Imu(val tMs: Long, val ax: Double, val ay: Double, val az: Double, val wx: Double, val wy: Double, val wz: Double)
    class Gps(val tMs: Long, val x: Double, val y: Double, val accuracy: Float, val speed: Float, val bearing: Float?)
    class Truth(val tMs: Long, val x: Double, val y: Double, val heading: Double)

    val imu = ArrayList<Imu>()
    val gps = ArrayList<Gps>()
    val truth = ArrayList<Truth>()

    private fun heading(t: Double) = 0.3 + 0.4 * PI / 180 * t

    private fun rot(v: DoubleArray, k: DoubleArray, a: Double): DoubleArray {
        // Rodrigues
        val c = cos(a)
        val s = sin(a)
        val kd = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
        val cx = k[1] * v[2] - k[2] * v[1]
        val cy = k[2] * v[0] - k[0] * v[2]
        val cz = k[0] * v[1] - k[1] * v[0]
        return doubleArrayOf(
            v[0] * c + cx * s + k[0] * kd * (1 - c),
            v[1] * c + cy * s + k[1] * kd * (1 - c),
            v[2] * c + cz * s + k[2] * kd * (1 - c),
        )
    }

    /** 端末の x・y・z 軸（世界の東・北・上の座標）。 */
    private fun axes(t: Double): Array<DoubleArray> {
        val h = heading(t)
        val f = doubleArrayOf(sin(h), cos(h), 0.0)
        val r = doubleArrayOf(cos(h), -sin(h), 0.0)
        val u = doubleArrayOf(0.0, 0.0, 1.0)
        if (car) {
            // ホルダー: 画面が後ろ（運転者）向き・縦。x = 右、y = 上、z = 後ろ
            return arrayOf(r, u, doubleArrayOf(-f[0], -f[1], 0.0))
        }
        if (t >= handAt) {
            // 手に持って画面を見る: 画面が上から 30° 手前に起きる。x = 右、y = 前（30° 上）、z = 上（30° 後ろ）
            val tilt = 30 * PI / 180 + 0.08 * sin(2 * PI * 1.8 * t)
            val y = rot(f, r, tilt)
            val z = rot(u, r, tilt)
            return arrayOf(r, y, z)
        }
        // ポケット: y = 下、z = 前、x = 右。太ももと一緒に右の軸まわりに振れる
        val swing = 25 * PI / 180 * sin(2 * PI * 0.9 * t)
        val shift = if (t >= pocketShiftAt) 60 * PI / 180 * ((t - pocketShiftAt) / 3.0).coerceAtMost(1.0) else 0.0
        var x = r
        var y = doubleArrayOf(0.0, 0.0, -1.0)
        var z = f
        y = rot(y, r, swing)
        z = rot(z, r, swing)
        // ポケットの中で前の軸まわりに傾く
        x = rot(x, f, shift)
        y = rot(y, f, shift)
        z = rot(z, f, shift)
        return arrayOf(x, y, z)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun run(seconds: Int, gapsMs: List<LongRange> = emptyList()): WalkSim {
        val dt = 0.02
        var x = 0.0
        var y = 0.0
        var ex = 0.0
        var ey = 0.0
        val tau = 20.0
        val n = seconds * 50
        var prevAxes = axes(0.0)
        for (i in 0..n) {
            val t = i * dt
            val tMs = i * 20L
            val h = heading(t)
            val f = doubleArrayOf(sin(h), cos(h), 0.0)
            val r = doubleArrayOf(cos(h), -sin(h), 0.0)
            // 世界の加速度（歩行の周期的な揺れ）＋重力（上向き 9.81 を感じる）
            val walk = if (car) 0.0 else 1.0
            val au = walk * 2.0 * sin(2 * PI * 1.8 * t) + if (car) 0.3 * rnd.nextGaussian() else 0.0
            val af = walk * 1.2 * sin(2 * PI * 1.8 * t + 1.0)
            val ar = walk * 0.6 * sin(2 * PI * 0.9 * t) + speed * (0.4 * PI / 180) // 曲がる分の向心加速度
            val aw = doubleArrayOf(af * f[0] + ar * r[0], af * f[1] + ar * r[1], au + 9.81)
            val ax = axes(t)
            val acc = DoubleArray(3) { dot(ax[it], aw) + rnd.nextGaussian() * 0.15 }
            // ジャイロ: ω = ½ Σ eᵢ × ėᵢ（世界）→ 端末の座標へ
            val om = DoubleArray(3)
            for (k in 0..2) {
                val e = ax[k]
                val de = DoubleArray(3) { (e[it] - prevAxes[k][it]) / dt }
                om[0] += 0.5 * (e[1] * de[2] - e[2] * de[1])
                om[1] += 0.5 * (e[2] * de[0] - e[0] * de[2])
                om[2] += 0.5 * (e[0] * de[1] - e[1] * de[0])
            }
            prevAxes = ax
            val gyro = DoubleArray(3) { dot(ax[it], om) + rnd.nextGaussian() * 0.02 }
            imu.add(Imu(tMs, acc[0], acc[1], acc[2], gyro[0], gyro[1], gyro[2]))
            if (tMs % 1000 == 0L) {
                truth.add(Truth(tMs, x, y, h))
                // ゆっくり動くずれ（AR(1)、σ 3 m）
                val a = exp(-1.0 / tau)
                val q = 3.0 * sqrt(1 - a * a)
                ex = a * ex + q * rnd.nextGaussian()
                ey = a * ey + q * rnd.nextGaussian()
                if (gapsMs.none { tMs in it }) {
                    val gx = x + ex + rnd.nextGaussian() * 1.5
                    val gy = y + ey + rnd.nextGaussian() * 1.5
                    val sp = (speed + rnd.nextGaussian() * 0.25).coerceAtLeast(0.0)
                    val br = ((h * 180 / PI) + rnd.nextGaussian() * (if (car) 3.0 else 25.0) + 720) % 360
                    gps.add(Gps(tMs, gx, gy, (5 + rnd.nextDouble() * 3).toFloat(), sp.toFloat(), if (sp > 0.5) br.toFloat() else null))
                }
            }
            x += speed * f[0] * dt
            y += speed * f[1] * dt
        }
        return this
    }

    fun truthAt(tMs: Long): Truth {
        val i = (tMs / 1000).toInt().coerceIn(0, truth.size - 1)
        val a = truth[i]
        val b = truth.getOrNull(i + 1) ?: return a
        val u = (tMs - a.tMs) / 1000.0
        return Truth(tMs, a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.heading)
    }
}
