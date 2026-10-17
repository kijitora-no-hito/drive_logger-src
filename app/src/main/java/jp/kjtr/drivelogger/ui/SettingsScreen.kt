package jp.kjtr.drivelogger.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.kjtr.drivelogger.BuildConfig
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.dashcam.BurnInConfig
import jp.kjtr.drivelogger.dashcam.BurnInPosition
import jp.kjtr.drivelogger.dashcam.BurnInRenderer
import jp.kjtr.drivelogger.dashcam.BurnInSize
import jp.kjtr.drivelogger.dashcam.BurnInValues
import jp.kjtr.drivelogger.dashcam.DashcamEngine
import jp.kjtr.drivelogger.dashcam.ImpactSensitivity
import jp.kjtr.drivelogger.dashcam.VideoQuality
import jp.kjtr.drivelogger.dashcam.estimateSegmentBytes
import jp.kjtr.drivelogger.data.MapKind
import jp.kjtr.drivelogger.data.SettingsStore
import jp.kjtr.drivelogger.data.StartChoice
import jp.kjtr.drivelogger.dashcam.RecordMode
import jp.kjtr.drivelogger.record.RecorderStatus
import jp.kjtr.drivelogger.service.DriveController
import jp.kjtr.drivelogger.gauge.GReading
import jp.kjtr.drivelogger.gauge.GaugeMath
import jp.kjtr.drivelogger.gauge.MeterStyle
import jp.kjtr.drivelogger.map.GsiLayer
import jp.kjtr.drivelogger.map.MapTrack
import android.widget.Toast
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val cam by vm.dashcam.state.collectAsStateWithLifecycle()
    val driving by vm.session.state.collectAsStateWithLifecycle()
    val caps by produceState(DashcamEngine.Companion.Capabilities(setOf(VideoQuality.HD, VideoQuality.FHD), false)) {
        if (RecorderStatus.hasCamera(context)) value = DashcamEngine.capabilities(context).let { if (it.qualities.isEmpty()) value else it }
    }
    LaunchedEffect(Unit) { vm.dashcam.refreshUsage(DriveController.dashcamConfig(s)) }
    val videoLocked = cam.active
    var dialog by remember { mutableStateOf<String?>(null) }
    var audioWarn by remember { mutableStateOf(false) }
    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.updateSettings { it.copy(audio = true) } }
    val backupOut = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportBackup(uri) }
    val backupIn = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importBackup(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Section(stringResource(R.string.set_drive))
            ValueRow(stringResource(R.string.set_start_choice), startChoiceLabel(s.startChoice)) { dialog = "start" }
            // 乗り物（0.8.0〜）: 衝撃の感度・融合・軌跡のなめらかさを合わせる
            ValueRow(stringResource(R.string.set_vehicle), vehicleLabel(s.vehicle)) { dialog = "vehicle" }
            Text(stringResource(R.string.set_vehicle_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.set_auto_pause), stringResource(R.string.set_auto_pause_desc), s.autoPause, enabled = driving == null) { v -> vm.updateSettings { it.copy(autoPause = v) } }
            SwitchRow(stringResource(R.string.set_screen_on), null, s.keepScreenOn) { v -> vm.updateSettings { it.copy(keepScreenOn = v) } }
            SwitchRow(stringResource(R.string.set_restrict), stringResource(R.string.set_restrict_desc), s.restrictWhileMoving) { v -> vm.updateSettings { it.copy(restrictWhileMoving = v) } }
            ValueRow(stringResource(R.string.set_map_kind), mapKindLabel(s.mapKind)) { dialog = "map" }

            Section(stringResource(R.string.set_positioning))
            // 測位の方式（0.8.0〜）。Google の融合測位を選んでも、Play 開発者サービスが無ければ端末の GPS
            val fusedOk = remember { vm.app.locationHub.fusedAvailable() }
            ValueRow(stringResource(R.string.set_positioning_mode), positioningLabel(s.positioning)) { dialog = "positioning" }
            Text(
                stringResource(if (s.positioning == jp.kjtr.drivelogger.data.Positioning.GOOGLE && !fusedOk) R.string.set_positioning_unavailable else R.string.set_positioning_desc),
                style = MaterialTheme.typography.bodySmall,
                color = if (s.positioning == jp.kjtr.drivelogger.data.Positioning.GOOGLE && !fusedOk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                stringResource(R.string.set_fusion), stringResource(R.string.set_fusion_desc), s.fusion,
                // 車のときだけ（自転車・徒歩は使わない）
                enabled = (s.positioning == jp.kjtr.drivelogger.data.Positioning.DEVICE || !fusedOk) && s.vehicle.imuFusion,
            ) { v -> vm.updateSettings { it.copy(fusion = v) } }
            SwitchRow(stringResource(R.string.set_stop_pause), stringResource(R.string.set_stop_pause_desc), s.stopPause) { v -> vm.updateSettings { it.copy(stopPause = v) } }
            SwitchRow(stringResource(R.string.set_track_smoothing), stringResource(R.string.set_track_smoothing_desc), s.trackSmoothing) { v -> vm.updateSettings { it.copy(trackSmoothing = v) } }
            ValueRow(stringResource(R.string.set_gpx_source), gpxSourceLabel(s.gpxSource)) { dialog = "gpx" }
            SwitchRow(stringResource(R.string.set_gpx_photos), stringResource(R.string.set_gpx_photos_desc), s.gpxPhotos) { v -> vm.updateSettings { it.copy(gpxPhotos = v) } }

            Section(stringResource(R.string.set_photo))
            SwitchRow(stringResource(R.string.set_photo_exif_location), stringResource(R.string.set_photo_exif_location_desc), s.photoExifLocation) { v ->
                vm.updateSettings { it.copy(photoExifLocation = v) }
            }
            SwitchRow(stringResource(R.string.set_photo_burn), stringResource(R.string.set_photo_burn_desc), s.photoBurnIn) { v -> vm.updateSettings { it.copy(photoBurnIn = v) } }
            Text(stringResource(R.string.set_photo_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.set_park))
            SwitchRow(stringResource(R.string.set_park_walk), stringResource(R.string.set_park_walk_desc), s.parkWalkTrack) { v -> vm.updateSettings { it.copy(parkWalkTrack = v) } }
            Text(stringResource(R.string.set_park_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.set_meter))
            ValueRow(stringResource(R.string.set_hud_style), styleLabel(s.hudStyle)) { dialog = "hudStyle" }
            if (s.hudStyle == MeterStyle.GAUGES) {
                SwitchRow(stringResource(R.string.set_hud_compass), null, s.hudCompass) { v -> vm.updateSettings { it.copy(hudCompass = v) } }
                SwitchRow(stringResource(R.string.set_hud_g), stringResource(R.string.set_hud_g_desc), s.hudG) { v -> vm.updateSettings { it.copy(hudG = v) } }
            }
            ValueRow(stringResource(R.string.set_gauge_max), stringResource(R.string.gauge_max_value, s.gaugeMaxKmh), enabled = !videoLocked) { dialog = "gaugeMax" }
            Text(stringResource(R.string.set_meter_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.set_video))
            if (videoLocked) Text(stringResource(R.string.set_video_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.set_video_usage, Format.bytes(cam.usedBytes), Format.megabytes(s.storageLimitMb), Format.bytes(cam.protectedBytes), Format.bytes(cam.freeBytes)),
                style = MaterialTheme.typography.bodySmall,
            )
            // 長時間録画（0.7.0〜）はループの上限の外
            val longBytes by produceState(0L, cam.active) { value = vm.clips.longBytes() }
            if (longBytes > 0) Text(stringResource(R.string.set_long_usage, Format.bytes(longBytes)), style = MaterialTheme.typography.bodySmall)
            ValueRow(
                stringResource(R.string.set_record_mode),
                stringResource(if (s.recordMode == RecordMode.LONG) R.string.record_mode_long else R.string.record_mode_loop),
            ) { dialog = "recordMode" }
            Text(
                (if (s.recordMode == RecordMode.LONG) stringResource(R.string.record_mode_long_desc, s.longSegmentMinutes) else stringResource(R.string.record_mode_loop_desc, s.segmentMinutes)) +
                    "\n" + stringResource(R.string.set_record_mode_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ValueRow(stringResource(R.string.set_long_segment), stringResource(R.string.minutes, s.longSegmentMinutes), enabled = !videoLocked) { dialog = "longSegment" }
            ValueRow(stringResource(R.string.set_segment), stringResource(R.string.minutes, s.segmentMinutes), enabled = !videoLocked) { dialog = "segment" }
            ValueRow(stringResource(R.string.set_limit), Format.megabytes(s.storageLimitMb) + "  " + stringResource(R.string.set_limit_hours, hoursFor(s.storageLimitMb, s)), enabled = !videoLocked) { dialog = "limit" }
            ValueRow(stringResource(R.string.set_protected_limit), Format.megabytes(s.protectedLimitMb), enabled = !videoLocked) { dialog = "plimit" }
            ValueRow(stringResource(R.string.set_quality), s.quality.label, enabled = !videoLocked) { dialog = "quality" }
            ValueRow(
                stringResource(R.string.set_video_orientation),
                stringResource(if (s.videoOrientation == jp.kjtr.drivelogger.data.VideoOrientation.LANDSCAPE) R.string.orient_landscape else R.string.orient_device),
                enabled = !videoLocked,
            ) { dialog = "orient" }
            ValueRow(stringResource(R.string.set_fps), "${s.fps} fps" + if (!caps.fps60) "  " + stringResource(R.string.set_fps_no60) else "", enabled = !videoLocked && caps.fps60) { dialog = "fps" }
            SwitchRow(stringResource(R.string.set_audio), stringResource(R.string.set_audio_desc), s.audio, enabled = !videoLocked) { v ->
                if (v) audioWarn = true else vm.updateSettings { it.copy(audio = false) }
            }
            Text(stringResource(R.string.set_heat_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.set_burn))
            ValueRow(
                stringResource(R.string.set_overlay_mode),
                stringResource(if (s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.LATER) R.string.overlay_later else R.string.overlay_burn),
                enabled = !videoLocked,
            ) { dialog = "overlay" }
            Text(
                stringResource(if (s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.LATER) R.string.overlay_later_desc else R.string.overlay_burn_desc),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                stringResource(R.string.set_burn_on), stringResource(if (s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.LATER) R.string.set_burn_desc_later else R.string.set_burn_desc),
                s.burnIn, enabled = !videoLocked,
            ) { v -> vm.updateSettings { it.copy(burnIn = v) } }
            if (s.overlayMode == jp.kjtr.drivelogger.data.OverlayMode.LATER) {
                SwitchRow(stringResource(R.string.set_gallery_overlay), stringResource(R.string.set_gallery_overlay_desc), s.galleryOverlay) { v -> vm.updateSettings { it.copy(galleryOverlay = v) } }
            }
            if (s.burnIn) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip(stringResource(R.string.burn_date), s.burnDate, !videoLocked) { v -> vm.updateSettings { it.copy(burnDate = v) } }
                    Chip(stringResource(R.string.burn_speed), s.burnSpeed, !videoLocked) { v -> vm.updateSettings { it.copy(burnSpeed = v) } }
                    Chip(stringResource(R.string.burn_latlon), s.burnLatLon, !videoLocked) { v -> vm.updateSettings { it.copy(burnLatLon = v) } }
                    Chip(stringResource(R.string.burn_heading), s.burnHeading, !videoLocked) { v -> vm.updateSettings { it.copy(burnHeading = v) } }
                    Chip(stringResource(R.string.burn_g), s.burnG, !videoLocked) { v -> vm.updateSettings { it.copy(burnG = v) } }
                    Chip(stringResource(R.string.burn_app), s.burnAppName, !videoLocked) { v -> vm.updateSettings { it.copy(burnAppName = v) } }
                }
                ValueRow(stringResource(R.string.set_burn_position), positionLabel(s.burnPosition), enabled = !videoLocked) { dialog = "pos" }
                ValueRow(stringResource(R.string.set_burn_size), sizeLabel(s.burnSize), enabled = !videoLocked) { dialog = "size" }
                ValueRow(stringResource(R.string.set_burn_style), styleLabel(s.burnStyle), enabled = !videoLocked) { dialog = "burnStyle" }
                if (s.burnStyle == MeterStyle.GAUGES) {
                    Text(stringResource(R.string.set_burn_style_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SwitchRow(stringResource(R.string.set_burn_map), stringResource(R.string.set_burn_map_desc), s.burnMap, enabled = !videoLocked) { v -> vm.updateSettings { it.copy(burnMap = v) } }
                if (s.burnMap) {
                    ValueRow(stringResource(R.string.set_burn_map_layer), layerLabel(s.burnMapLayer), enabled = !videoLocked) { dialog = "mapLayer" }
                    ValueRow(stringResource(R.string.set_burn_map_zoom), zoomLabel(s.burnMapZoom), enabled = !videoLocked) { dialog = "mapZoom" }
                    ValueRow(stringResource(R.string.set_burn_map_orientation), orientationLabel(s.burnMapHeadingUp), enabled = !videoLocked) { dialog = "mapOrientation" }
                    ValueRow(stringResource(R.string.set_burn_map_size), sizeLabel(s.burnMapSize), enabled = !videoLocked) { dialog = "mapSize" }
                    ValueRow(stringResource(R.string.set_burn_map_position), positionLabel(s.burnMapPosition), enabled = !videoLocked) { dialog = "mapPos" }
                    ValueRow(stringResource(R.string.set_burn_map_track), trackLabel(s.burnMapTrack), enabled = !videoLocked) { dialog = "mapTrack" }
                    Text(stringResource(R.string.set_burn_map_offline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.set_burn_sample), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                BurnInPreview(vm, DriveController.burnConfig(s), Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp))
                Text(stringResource(R.string.set_burn_map_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TileCacheRow(vm)

            Section(stringResource(R.string.set_impact))
            ValueRow(stringResource(R.string.set_impact_sens), impactLabel(s.impactSensitivity)) { dialog = "impact" }
            Text(stringResource(R.string.set_impact_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(stringResource(R.string.set_background))
            BackgroundRecordingSection()

            Section(stringResource(R.string.set_update))
            UpdateSettingsSection(vm) { title, desc, checked, onChange -> SwitchRow(title, desc, checked, onChange = onChange) }

            Section(stringResource(R.string.set_backup))
            Text(stringResource(R.string.set_backup_desc), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupOut.launch("drivelogger_backup_" + Format.fileStamp(System.currentTimeMillis()) + ".zip") }) { Text(stringResource(R.string.set_backup_export)) }
                OutlinedButton(onClick = { backupIn.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.set_backup_import)) }
            }

            Section(stringResource(R.string.set_about))
            Text(stringResource(R.string.about_text, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.safety_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        }
    }

    when (dialog) {
        "start" -> ChoiceDialog(stringResource(R.string.set_start_choice), StartChoice.entries.toList(), s.startChoice, { startChoiceLabel(it) }, { v -> vm.updateSettings { it.copy(startChoice = v) } }) { dialog = null }
        "gpx" -> ChoiceDialog(
            stringResource(R.string.set_gpx_source), jp.kjtr.drivelogger.data.GpxSource.entries.toList(), s.gpxSource,
            { gpxSourceLabel(it) }, { v -> vm.updateSettings { it.copy(gpxSource = v) } },
        ) { dialog = null }
        "vehicle" -> ChoiceDialog(
            stringResource(R.string.set_vehicle), jp.kjtr.drivelogger.record.Vehicle.entries.toList(), s.vehicle,
            { vehicleLabel(it) }, { v -> vm.updateSettings { it.copy(vehicle = v) } },
        ) { dialog = null }
        "positioning" -> ChoiceDialog(
            stringResource(R.string.set_positioning_mode), jp.kjtr.drivelogger.data.Positioning.entries.toList(), s.positioning,
            { positioningLabel(it) }, { v -> vm.updateSettings { it.copy(positioning = v) } },
        ) { dialog = null }
        "map" -> ChoiceDialog(stringResource(R.string.set_map_kind), MapKind.entries.toList(), s.mapKind, { mapKindLabel(it) }, { v -> vm.updateSettings { it.copy(mapKind = v) } }) { dialog = null }
        "recordMode" -> ChoiceDialog(
            stringResource(R.string.set_record_mode), RecordMode.entries.toList(), s.recordMode,
            { stringResource(if (it == RecordMode.LONG) R.string.record_mode_long else R.string.record_mode_loop) },
            { v -> vm.updateSettings { it.copy(recordMode = v) } },
        ) { dialog = null }
        "longSegment" -> ChoiceDialog(
            stringResource(R.string.set_long_segment),
            (if (BuildConfig.DEBUG) SettingsStore.DEBUG_LONG_SEGMENT_CHOICES else emptyList()) + SettingsStore.LONG_SEGMENT_CHOICES,
            s.longSegmentMinutes, { stringResource(R.string.minutes, it) }, { v -> vm.updateSettings { it.copy(longSegmentMinutes = v) } },
        ) { dialog = null }
        "segment" -> ChoiceDialog(stringResource(R.string.set_segment), SettingsStore.SEGMENT_CHOICES, s.segmentMinutes, { stringResource(R.string.minutes, it) }, { v -> vm.updateSettings { it.copy(segmentMinutes = v) } }) { dialog = null }
        "limit" -> ChoiceDialog(
            stringResource(R.string.set_limit),
            (if (BuildConfig.DEBUG) SettingsStore.DEBUG_STORAGE_CHOICES_MB else emptyList()) + SettingsStore.STORAGE_CHOICES_MB,
            s.storageLimitMb, { Format.megabytes(it) + "  " + stringResource(R.string.set_limit_hours, hoursFor(it, s)) },
            { v -> vm.updateSettings { it.copy(storageLimitMb = v) } },
        ) { dialog = null }
        "plimit" -> ChoiceDialog(
            stringResource(R.string.set_protected_limit),
            (if (BuildConfig.DEBUG) SettingsStore.DEBUG_PROTECTED_CHOICES_MB else emptyList()) + SettingsStore.PROTECTED_CHOICES_MB,
            s.protectedLimitMb, { Format.megabytes(it) }, { v -> vm.updateSettings { it.copy(protectedLimitMb = v) } },
        ) { dialog = null }
        "quality" -> ChoiceDialog(stringResource(R.string.set_quality), VideoQuality.entries.filter { it in caps.qualities || it == s.quality }, s.quality, { it.label }, { v -> vm.updateSettings { it.copy(quality = v) } }) { dialog = null }
        "orient" -> ChoiceDialog(
            stringResource(R.string.set_video_orientation), jp.kjtr.drivelogger.data.VideoOrientation.entries.toList(), s.videoOrientation,
            { stringResource(if (it == jp.kjtr.drivelogger.data.VideoOrientation.LANDSCAPE) R.string.orient_landscape else R.string.orient_device) },
            { v -> vm.updateSettings { it.copy(videoOrientation = v) } },
        ) { dialog = null }
        "overlay" -> ChoiceDialog(
            stringResource(R.string.set_overlay_mode), jp.kjtr.drivelogger.data.OverlayMode.entries.toList(), s.overlayMode,
            { stringResource(if (it == jp.kjtr.drivelogger.data.OverlayMode.LATER) R.string.overlay_later else R.string.overlay_burn) },
            { v -> vm.updateSettings { it.copy(overlayMode = v) } },
        ) { dialog = null }
        "fps" -> ChoiceDialog(stringResource(R.string.set_fps), listOf(30, 60), s.fps, { "$it fps" }, { v -> vm.updateSettings { it.copy(fps = v) } }) { dialog = null }
        "pos" -> ChoiceDialog(stringResource(R.string.set_burn_position), BurnInPosition.entries.toList(), s.burnPosition, { positionLabel(it) }, { v -> vm.updateSettings { it.copy(burnPosition = v) } }) { dialog = null }
        "size" -> ChoiceDialog(stringResource(R.string.set_burn_size), BurnInSize.entries.toList(), s.burnSize, { sizeLabel(it) }, { v -> vm.updateSettings { it.copy(burnSize = v) } }) { dialog = null }
        "hudStyle" -> ChoiceDialog(stringResource(R.string.set_hud_style), MeterStyle.entries.toList(), s.hudStyle, { styleLabel(it) }, { v -> vm.updateSettings { it.copy(hudStyle = v) } }) { dialog = null }
        "burnStyle" -> ChoiceDialog(stringResource(R.string.set_burn_style), MeterStyle.entries.toList(), s.burnStyle, { styleLabel(it) }, { v -> vm.updateSettings { it.copy(burnStyle = v) } }) { dialog = null }
        "gaugeMax" -> ChoiceDialog(stringResource(R.string.set_gauge_max), GaugeMath.MAX_CHOICES, s.gaugeMaxKmh, { stringResource(R.string.gauge_max_value, it) }, { v -> vm.updateSettings { it.copy(gaugeMaxKmh = v) } }) { dialog = null }
        "mapLayer" -> ChoiceDialog(stringResource(R.string.set_burn_map_layer), GsiLayer.entries.toList(), s.burnMapLayer, { layerLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapLayer = v) } }) { dialog = null }
        "mapZoom" -> ChoiceDialog(stringResource(R.string.set_burn_map_zoom), SettingsStore.MAP_ZOOM_CHOICES.toList(), s.burnMapZoom, { zoomLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapZoom = v) } }) { dialog = null }
        "mapOrientation" -> ChoiceDialog(stringResource(R.string.set_burn_map_orientation), listOf(true, false), s.burnMapHeadingUp, { orientationLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapHeadingUp = v) } }) { dialog = null }
        "mapSize" -> ChoiceDialog(stringResource(R.string.set_burn_map_size), BurnInSize.entries.toList(), s.burnMapSize, { sizeLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapSize = v) } }) { dialog = null }
        "mapPos" -> ChoiceDialog(stringResource(R.string.set_burn_map_position), BurnInPosition.entries.toList(), s.burnMapPosition, { positionLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapPosition = v) } }) { dialog = null }
        "mapTrack" -> ChoiceDialog(stringResource(R.string.set_burn_map_track), MapTrack.entries.toList(), s.burnMapTrack, { trackLabel(it) }, { v -> vm.updateSettings { it.copy(burnMapTrack = v) } }) { dialog = null }
        "impact" -> ChoiceDialog(stringResource(R.string.set_impact_sens), ImpactSensitivity.entries.toList(), s.impactSensitivity, { impactLabel(it) }, { v -> vm.updateSettings { it.copy(impactSensitivity = v) } }) { dialog = null }
    }
    if (audioWarn) {
        AlertDialog(
            onDismissRequest = { audioWarn = false },
            title = { Text(stringResource(R.string.audio_warn_title)) },
            text = { Text(stringResource(R.string.audio_warn_text)) },
            confirmButton = {
                TextButton(onClick = {
                    audioWarn = false
                    if (RecorderStatus.hasAudio(context)) vm.updateSettings { it.copy(audio = true) } else audioPerm.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.audio_warn_ok)) }
            },
            dismissButton = { TextButton(onClick = { audioWarn = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 上限まで録れるおおよその時間（時間）。 */
private fun hoursFor(limitMb: Int, s: jp.kjtr.drivelogger.data.Settings): String {
    val perMin = estimateSegmentBytes(s.quality, s.fps, 60_000L, s.audio).coerceAtLeast(1)
    val minutes = limitMb.toLong() * 1024 * 1024 / perMin
    return if (minutes >= 120) "${minutes / 60} 時間" else "$minutes 分"
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.material3.FilterChip(selected = on, onClick = { onChange(!on) }, enabled = enabled, label = { Text(label) })
}

/** 焼き込みの見本（録画と同じ BurnInRenderer で描く。地図は現在地（無ければ東京駅）のまわりの地理院タイル）。 */
@Composable
fun BurnInPreview(vm: MainViewModel, config: BurnInConfig, modifier: Modifier) {
    val tiles = vm.app.tiles
    val renderer = remember { BurnInRenderer(DashcamEngine.APP_NAME, tiles) }
    val tileVersion by tiles.version.collectAsStateWithLifecycle()
    val last by vm.locationHub.last.collectAsStateWithLifecycle()
    val line by vm.session.line.collectAsStateWithLifecycle()
    val values = remember(last) {
        BurnInValues(
            speedKmh = 62.0, lat = last?.latitude ?: 35.681236, lon = last?.longitude ?: 139.767125, headingDeg = 45.0, g = 0.12,
            vehicleG = GReading(longG = -0.18, latG = 0.12, totalG = 0.22, calibrated = true),
        )
    }
    Canvas(modifier) {
        // タイルが届いたら描き直す
        tileVersion.hashCode()
        drawRect(Brush.verticalGradient(listOf(Color(0xFF7FA7C9), Color(0xFF5B6B5A), Color(0xFF3A3A3A))))
        drawIntoCanvas { c ->
            renderer.drawUpright(c.nativeCanvas, size.width.toInt(), size.height.toInt(), System.currentTimeMillis(), values, config, line)
        }
    }
}

/** 地図のキャッシュの大きさと［キャッシュを削除］。 */
@Composable
private fun TileCacheRow(vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var bytes by remember { mutableStateOf<Long?>(null) }
    val tileVersion by vm.app.tiles.version.collectAsStateWithLifecycle()
    LaunchedEffect(tileVersion / 20) { bytes = vm.app.tiles.usageBytes() }
    val b = bytes ?: return
    if (b <= 0L && !vm.settings.value.burnMap) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.set_tile_cache, Format.bytes(b)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            scope.launch {
                vm.app.tiles.clear()
                bytes = 0L
                Toast.makeText(context, R.string.msg_tile_cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }) { Text(stringResource(R.string.set_tile_cache_clear)) }
    }
}
@Composable
private fun startChoiceLabel(c: StartChoice) = stringResource(
    when (c) {
        StartChoice.LAST -> R.string.start_choice_last
        StartChoice.GPS_ONLY -> R.string.start_choice_gps
        StartChoice.WITH_VIDEO -> R.string.start_choice_video
    },
)

@Composable
private fun gpxSourceLabel(g: jp.kjtr.drivelogger.data.GpxSource) = stringResource(
    when (g) {
        jp.kjtr.drivelogger.data.GpxSource.SMOOTHED -> R.string.gpx_smoothed
        jp.kjtr.drivelogger.data.GpxSource.RECORDED -> R.string.gpx_fused
        jp.kjtr.drivelogger.data.GpxSource.RAW -> R.string.gpx_raw
    },
)

@Composable
fun vehicleLabel(v: jp.kjtr.drivelogger.record.Vehicle) = stringResource(
    when (v) {
        jp.kjtr.drivelogger.record.Vehicle.CAR -> R.string.vehicle_car
        jp.kjtr.drivelogger.record.Vehicle.BICYCLE -> R.string.vehicle_bicycle
        jp.kjtr.drivelogger.record.Vehicle.WALK -> R.string.vehicle_walk
    },
)

@Composable
private fun positioningLabel(p: jp.kjtr.drivelogger.data.Positioning) = stringResource(
    if (p == jp.kjtr.drivelogger.data.Positioning.GOOGLE) R.string.positioning_google else R.string.positioning_device,
)

@Composable
private fun mapKindLabel(k: MapKind) = stringResource(
    when (k) {
        MapKind.NORMAL -> R.string.map_normal
        MapKind.SATELLITE -> R.string.map_satellite
        MapKind.HYBRID -> R.string.map_hybrid
        MapKind.TERRAIN -> R.string.map_terrain
    },
)

@Composable
private fun positionLabel(p: BurnInPosition) = stringResource(
    when (p) {
        BurnInPosition.TOP_LEFT -> R.string.pos_top_left
        BurnInPosition.TOP_RIGHT -> R.string.pos_top_right
        BurnInPosition.BOTTOM_LEFT -> R.string.pos_bottom_left
        BurnInPosition.BOTTOM_RIGHT -> R.string.pos_bottom_right
    },
)

@Composable
private fun sizeLabel(s: BurnInSize) = stringResource(
    when (s) {
        BurnInSize.SMALL -> R.string.size_small
        BurnInSize.MEDIUM -> R.string.size_medium
        BurnInSize.LARGE -> R.string.size_large
    },
)

@Composable
private fun styleLabel(m: MeterStyle) = stringResource(if (m == MeterStyle.TEXT) R.string.style_text else R.string.style_gauges)

@Composable
private fun layerLabel(l: GsiLayer) = stringResource(
    when (l) {
        GsiLayer.STD -> R.string.gsi_std
        GsiLayer.PALE -> R.string.gsi_pale
        GsiLayer.PHOTO -> R.string.gsi_photo
    },
)

@Composable
private fun zoomLabel(z: Int) = stringResource(
    R.string.map_zoom_value, z,
    stringResource(
        when (z) {
            13 -> R.string.zoom_13
            14 -> R.string.zoom_14
            16 -> R.string.zoom_16
            17 -> R.string.zoom_17
            else -> R.string.zoom_15
        },
    ),
)

@Composable
private fun orientationLabel(headingUp: Boolean) = stringResource(if (headingUp) R.string.map_heading_up else R.string.map_north_up)

@Composable
private fun trackLabel(t: MapTrack) = stringResource(
    when (t) {
        MapTrack.NONE -> R.string.track_none
        MapTrack.RECENT_1KM -> R.string.track_1km
        MapTrack.RECENT_5KM -> R.string.track_5km
        MapTrack.ALL -> R.string.track_all
    },
)

@Composable
private fun impactLabel(i: ImpactSensitivity) = when (i) {
    ImpactSensitivity.OFF -> stringResource(R.string.impact_off)
    ImpactSensitivity.LOW -> stringResource(R.string.impact_low, i.thresholdG ?: 0.0)
    ImpactSensitivity.MEDIUM -> stringResource(R.string.impact_medium, i.thresholdG ?: 0.0)
    ImpactSensitivity.HIGH -> stringResource(R.string.impact_high, i.thresholdG ?: 0.0)
}
