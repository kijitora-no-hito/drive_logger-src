package jp.kjtr.drivelogger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ドライブ（0.6.0〜 GPS ログ・動画ログの「入れ物」。DESIGN.md §4.6）。開いている（[STATUS_OPEN]）間は、その中で GPS ログ・動画ログを
 * いくつでも記録できる。［ドライブを終了］で [STATUS_FINISHED]（閉じた）、［このドライブを再開］で開き直せる。同時に開けるのは 1 本まで。
 * [distanceM] などの統計は全 GPS ログの合計（ログとログの間は数えない）。点は [TripPointEntity] に逐次保存する。
 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ドライブを作った（始めた）時刻。 */
    val startedAt: Long,
    /** 最後の記録（ログ・駐車・写真）の時刻。開いている間は null のことがある。 */
    val endedAt: Long? = null,
    /** [STATUS_OPEN] / [STATUS_FINISHED]。 */
    val status: Int,
    val autoPause: Boolean,
    /** 一度でも録画したか（動画ログがあるか）。 */
    val recordedVideo: Boolean = false,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    /** 止まっていた（5 km/h 未満の）時間 [ms]。自動一時停止 OFF でも数える。 */
    val stillMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val pointCount: Int = 0,
    /** 最後に変わった時刻（0.6.0〜。記録の追加・終了・再開）。 */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /** 乗り物（0.8.0〜。0 = 車 / 1 = 自転車 / 2 = 徒歩＝[jp.kjtr.drivelogger.record.Vehicle.ordinal]）。軌跡のなめらかさをこれに合わせる。 */
    @ColumnInfo(defaultValue = "0") val vehicle: Int = 0,
) {
    val isOpen: Boolean get() = status != STATUS_FINISHED
    val vehicleKind: jp.kjtr.drivelogger.record.Vehicle get() = jp.kjtr.drivelogger.record.Vehicle.of(vehicle)

    companion object {
        /** 開いている（記録を追加できる）。0.5.0 までは「記録中」。 */
        const val STATUS_OPEN = 0
        /** 閉じた（［ドライブを終了］）。 */
        const val STATUS_FINISHED = 2
    }
}

/**
 * ドライブの中の記録 1 本（0.6.0〜。DESIGN.md §4.6）。
 * - [KIND_GPS]: GPS ログ（［GPS 記録 開始］〜［停止］。点は [TripPointEntity.logId]）。統計はこのログの点だけで計算したもの。
 * - [KIND_VIDEO]: 動画ログ（［録画 開始］〜［停止］。中身はループ録画のファイル群＝[ClipEntity.logId]）。
 * [endedAt] = null は記録中（落ちたときは、次に開いたときに閉じる）。
 */
@Entity(
    tableName = "drive_log",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class DriveLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val kind: Int,
    val startedAt: Long,
    val endedAt: Long? = null,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val stillMs: Long = 0,
    val maxSpeedMps: Double = 0.0,
    val pointCount: Int = 0,
    /** 動画ログの録画の方式（0.7.0〜。0 = ループ / 1 = 長時間＝[jp.kjtr.drivelogger.dashcam.RecordMode.code]）。GPS ログと移行した動画ログは 0。 */
    @ColumnInfo(defaultValue = "0") val recordMode: Int = 0,
) {
    val isGps: Boolean get() = kind == KIND_GPS
    val isVideo: Boolean get() = kind == KIND_VIDEO
    /** 長時間録画の動画ログ。 */
    val isLong: Boolean get() = isVideo && recordMode == jp.kjtr.drivelogger.dashcam.RecordMode.LONG.code

    companion object {
        const val KIND_GPS = 0
        const val KIND_VIDEO = 1
    }
}

@Entity(
    tableName = "trip_point",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("time"), Index("logId")],
)
data class TripPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    /** 区間の番号（跳びで測り直した・落ちて再開したところで切る）。 */
    val segment: Int,
    /** UTC [ms]（Location.time）。動画の再生と同期するときの基準。 */
    val time: Long,
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val accuracy: Float?,
    /** 端末が出した速度（再計算用）[m/s]。 */
    val deviceSpeed: Float?,
    /** 平滑化した速度 [m/s]。 */
    val speed: Float?,
    /** 進行方向 [度]（端末の方位、無ければ前の点からの方位）。 */
    val bearing: Float?,
    /**
     * 元の GPS の値（0.3.0〜。GPS と加速度・ジャイロの融合が ON のとき、[lat]/[lon]/[speed] は融合後で、ここに測位そのものを残す）。
     * null = 元の GPS が無い点（推測航法・停車中の点）か、融合が OFF・0.2.0 以前の点（[lat]/[lon] が元の GPS）。
     */
    val rawLat: Double? = null,
    val rawLon: Double? = null,
    val rawAccuracy: Float? = null,
    val rawSpeed: Float? = null,
    /** 点の出どころ（[SOURCE_GPS] / [SOURCE_FUSED] / [SOURCE_DEAD_RECKONING] / [SOURCE_STOPPED]）。 */
    @ColumnInfo(defaultValue = "0") val source: Int = SOURCE_GPS,
    /** この点の GPS ログ（[DriveLogEntity.KIND_GPS]、0.6.0〜。移行した点も入る）。 */
    val logId: Long? = null,
    /** 高度の精度（Location.verticalAccuracyMeters、0.8.0〜。無ければ null）[m]。獲得標高で精度の悪い高度を使わない。 */
    val altAccuracy: Float? = null,
    /** 測位の出どころ（0.8.0〜。[PROVIDER_GPS] / [PROVIDER_NETWORK] / [PROVIDER_FUSED]、それより前の点と推測・停車中の点は [PROVIDER_UNKNOWN]）。 */
    @ColumnInfo(defaultValue = "0") val provider: Int = PROVIDER_UNKNOWN,
) {
    companion object {
        const val PROVIDER_UNKNOWN = 0
        /** 端末の GPS（LocationManager の gps）。 */
        const val PROVIDER_GPS = 1
        /** 端末のネットワーク位置（高度は使わない）。 */
        const val PROVIDER_NETWORK = 2
        /** Google の融合測位（FusedLocationProviderClient）。 */
        const val PROVIDER_FUSED = 3

        fun providerOf(name: String?): Int = when (name) {
            "gps" -> PROVIDER_GPS
            "network" -> PROVIDER_NETWORK
            "fused" -> PROVIDER_FUSED
            else -> PROVIDER_UNKNOWN
        }

        /** GPS のまま（融合 OFF・0.2.0 以前）。 */
        const val SOURCE_GPS = 0
        /** GPS の測位を加速度・ジャイロと融合した。 */
        const val SOURCE_FUSED = 1
        /** GPS が来ない間の推測航法（加速度・ジャイロだけ。長く続くと打ち切る）。 */
        const val SOURCE_DEAD_RECKONING = 2
        /** 停車中（測位を止めている。停車を始めた位置のまま時刻だけ進む）。 */
        const val SOURCE_STOPPED = 3
    }
}

/**
 * 走行中の出来事（保護・衝撃・GPS 不通・熱で録画を止めた など）。続いた出来事は [endedAt] に終わりの時刻。
 * [lat]/[lon] はその時の位置（分からなければ null）、[value] は衝撃の大きさ [G] など。
 */
@Entity(
    tableName = "trip_event",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class TripEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val type: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val value: Double? = null,
) {
    companion object {
        /** 衝撃を検知した（[value] = G）。録画中なら動画も保護した。記録だけのときは出来事として残すだけ。 */
        const val TYPE_IMPACT = "impact"
        /** ［保護］ボタンで保護した。 */
        const val TYPE_PROTECT = "protect"
        const val TYPE_LOCATION_OFF = "location_off"
        const val TYPE_NO_FIX = "no_fix"
        /** アプリが落ちて、サービスが GPS 記録だけ自動で再開した。 */
        const val TYPE_RESTARTED = "restarted"
        /** アプリが落ちて、次に開いたときに続けた。 */
        const val TYPE_INTERRUPTED = "interrupted"
        /** 端末が熱くなったので録画を止めた（GPS は続けた）。 */
        const val TYPE_THERMAL = "thermal"
        /** カメラを他のアプリに取られた・開けなかった。 */
        const val TYPE_CAMERA_LOST = "camera_lost"
        /** 空き容量が足りないので録画を止めた。 */
        const val TYPE_STORAGE_FULL = "storage_full"
        /**
         * 録画していた時間帯（［録画 ON］〜［録画 OFF］。動画のファイルが容量の管理で消えても残る）。
         * トリップの詳細の地図で、この時間帯の軌跡を「録画した区間」として描く。
         */
        const val TYPE_VIDEO = "video"
    }
}

/**
 * 録画したファイル 1 本（ループ録画の 1 区切り）。本体はアプリ専用の外部領域 `Movies/dashcam/[fileName]`。
 * 保護（[protected]）したものは容量の管理で消さない。トリップを消しても動画は残る（[tripId] は null になる）。
 */
@Entity(
    tableName = "clip",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("tripId"), Index(value = ["fileName"], unique = true), Index("startedAt"), Index("logId")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val tripId: Long?,
    /** 録画を始めた時刻（UTC ms。VideoRecordEvent.Start を受けた時刻）。 */
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val protected: Boolean = false,
    /** [REASON_IMPACT] / [REASON_MANUAL]。 */
    val protectReason: String? = null,
    val protectedAt: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val hasAudio: Boolean = false,
    val burnIn: Boolean = false,
    /** [STATUS_RECORDING] / [STATUS_DONE] / [STATUS_FAILED]。 */
    val status: Int = STATUS_RECORDING,
    /**
     * 焼き込みの配置（0.3.0〜。`BurnLayout` の JSON。速度・緯度経度・地図の矩形）。SNS 用の書き出しで覆う場所。
     * null = 焼き込み無し、または 0.2.0 以前の動画（今の設定から推定する）。
     */
    val burnLayout: String? = null,
    /**
     * 表示を「あとから重ねる」方式で録った（0.4.0〜。表示なしのきれいな映像。書き出し・再生のときに GPS 記録から描いて重ねる）。
     * false = 焼き込み方式（[burnIn]）か、0.3.0 以前の動画。焼き込んでいない動画はどれも、あとから重ねられる。
     */
    @ColumnInfo(defaultValue = "0") val laterOverlay: Boolean = false,
    /** この動画の動画ログ（[DriveLogEntity.KIND_VIDEO]、0.6.0〜）。ドライブに属さない動画は null。 */
    val logId: Long? = null,
    /**
     * 録画の方式（0.7.0〜。0 = ループ / 1 = 長時間＝[jp.kjtr.drivelogger.dashcam.RecordMode.code]）。
     * 長時間録画のファイルはループの容量の上限・自動削除の対象外（ドライブを消して [logId] が外れても、この印で守る）。
     */
    @ColumnInfo(defaultValue = "0") val recordMode: Int = 0,
) {
    /** 長時間録画のファイル。 */
    val isLong: Boolean get() = recordMode == jp.kjtr.drivelogger.dashcam.RecordMode.LONG.code

    companion object {
        const val STATUS_RECORDING = 0
        const val STATUS_DONE = 1
        const val STATUS_FAILED = 2
        const val REASON_IMPACT = "impact"
        const val REASON_MANUAL = "manual"
    }
}

/**
 * 写真（版 3・0.4.0〜。自転車ルートロガーと同じ作り）。本体は端末のフォトライブラリ（MediaStore の Pictures/ドライブロガー）か、
 * Photo Picker で選んだもの（[uri]）。アプリには参照と撮影時刻・位置・縮小版（[thumbPath]、アプリの files/photo_thumbs/）を持つ。
 * トリップを消すと紐づけも消える（写真の本体は残る）。記録していないときに撮った写真は [tripId] = null。
 */
@Entity(
    tableName = "photo",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId"), Index("takenAt")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 撮ったときのトリップ（記録していなければ null）。 */
    val tripId: Long?,
    val uri: String,
    /** 撮影時刻（UTC ms）。 */
    val takenAt: Long,
    val lat: Double?,
    val lon: Double?,
    /** 位置の出どころ: [LOC_GPS]（その時の測位）/ [LOC_EXIF] / [LOC_TRACK]（軌跡から時刻で補間）/ null（不明）。 */
    val locationSource: String?,
    val thumbPath: String?,
    /** アプリで撮った（ライブラリから消せる）なら true。取り込んだものは false。 */
    val ownCapture: Boolean,
    val createdAt: Long,
    /**
     * どう撮ったか: [MODE_CAMERA]（アプリ内カメラ画面）/ [MODE_STILL]（録画中に ImageCapture）/
     * [MODE_FRAME]（録画中にプレビューのフレームから）/ null（Photo Picker で取り込んだ）。
     */
    val captureMode: String? = null,
    /** 焼き込み（日時・速度など）を写真にも入れた。 */
    val burnIn: Boolean = false,
    /** 駐車中（[ParkEntity]）に撮った写真ならその id（タイムラインで駐車の区間の中に並べる）。 */
    val parkId: Long? = null,
) {
    companion object {
        const val LOC_GPS = "gps"
        const val LOC_EXIF = "exif"
        const val LOC_TRACK = "track"
        const val MODE_CAMERA = "camera"
        const val MODE_STILL = "still"
        const val MODE_FRAME = "frame"
    }
}

/**
 * 駐車・寄り道の区間（版 3・0.4.0〜）。ドライブの途中で［駐車する］〜［ドライブを再開］（または終了）。
 * この間は走行の GPS 記録を止める（走行距離・走行時間に入らない）。[lat]/[lon] は駐車した位置（最後の走行の点）。
 * 設定「駐車中の徒歩の軌跡も記録」が ON なら、徒歩の点は [WalkPointEntity]・距離は [walkDistanceM]。
 */
@Entity(
    tableName = "park",
    foreignKeys = [ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class ParkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val startedAt: Long,
    /** 再開・終了した時刻（駐車中は null）。 */
    val endedAt: Long? = null,
    val lat: Double?,
    val lon: Double?,
    /** 徒歩の距離 [m]（徒歩の軌跡を記録したときだけ。それ以外は 0）。 */
    val walkDistanceM: Double = 0.0,
)

/** 駐車中の徒歩の点（設定で ON のときだけ。低頻度）。ドライブの走行の点（trip_point）とは別にする。 */
@Entity(
    tableName = "walk_point",
    foreignKeys = [
        ForeignKey(entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ParkEntity::class, parentColumns = ["id"], childColumns = ["parkId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tripId"), Index("parkId")],
)
data class WalkPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val parkId: Long,
    val time: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float?,
)
