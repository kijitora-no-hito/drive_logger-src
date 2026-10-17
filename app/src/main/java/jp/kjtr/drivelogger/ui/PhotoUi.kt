package jp.kjtr.drivelogger.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMapComposable
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import jp.kjtr.drivelogger.R
import jp.kjtr.drivelogger.data.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 縮小版の画像（無ければ null）。 */
@Composable
fun rememberThumb(path: String?): ImageBitmap? = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }

/** 地図の写真のマーカー（縮小版つき）。位置の無い写真は出さない。 */
@Composable
@GoogleMapComposable
fun PhotoMarkers(photos: List<PhotoEntity>, onClick: (PhotoEntity) -> Unit) {
    for (p in photos) {
        val lat = p.lat ?: continue
        val lon = p.lon ?: continue
        val state = remember(p.id, lat, lon) { MarkerState(LatLng(lat, lon)) }
        val thumb = rememberThumb(p.thumbPath)
        MarkerComposable(p.id, p.thumbPath ?: "", state = state, anchor = Offset(0.5f, 1f), zIndex = 5f, onClick = { onClick(p); true }) {
            Box(Modifier.size(46.dp).background(Color.White, RoundedCornerShape(6.dp)).padding(3.dp)) {
                if (thumb != null) {
                    Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF9E9E9E), RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/**
 * 写真の拡大表示（左右に送る）。［削除］で「アプリから外す」（紐づけだけ）か「ライブラリからも削除」（アプリで撮った写真）を選ぶ。
 */
@Composable
fun PhotoViewer(vm: MainViewModel, photos: List<PhotoEntity>, start: PhotoEntity, onDismiss: () -> Unit) {
    if (photos.isEmpty()) {
        onDismiss(); return
    }
    val pager = rememberPagerState(initialPage = photos.indexOfFirst { it.id == start.id }.coerceAtLeast(0)) { photos.size }
    var deleting by remember { mutableStateOf<PhotoEntity?>(null) }
    var pendingLibraryDelete by remember { mutableStateOf<PhotoEntity?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pendingLibraryDelete
        pendingLibraryDelete = null
        if (r.resultCode == android.app.Activity.RESULT_OK && p != null) vm.unlinkPhoto(p)
    }
    val failedText = stringResource(R.string.photo_delete_failed)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val p = photos[page]
                val bmp by produceState<ImageBitmap?>(null, p.id) {
                    value = withContext(Dispatchers.IO) { vm.photos.loadPhoto(p, 1600)?.asImageBitmap() }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else CircularProgressIndicator()
                }
            }
            val cur = photos.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).background(Color(0x88000000)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    if (cur != null) {
                        Text(Format.dateTime(cur.takenAt) + Format.clock(cur.takenAt).takeLast(3), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${pager.currentPage + 1} / ${photos.size}  " + stringResource(
                                when (cur.locationSource) {
                                    PhotoEntity.LOC_GPS -> R.string.photo_loc_gps
                                    PhotoEntity.LOC_EXIF -> R.string.photo_loc_exif
                                    PhotoEntity.LOC_TRACK -> R.string.photo_loc_track
                                    else -> R.string.photo_loc_none
                                },
                            ) + photoModeLabel(cur),
                            color = Color.White, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { deleting = cur }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = Color.White) }
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.photo_delete_title)) },
            text = { Text(stringResource(if (p.ownCapture) R.string.photo_delete_text_own else R.string.photo_delete_text_picked)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(onClick = { deleting = null; vm.unlinkPhoto(p) }) { Text(stringResource(R.string.photo_unlink)) }
                    if (p.ownCapture) {
                        TextButton(onClick = {
                            deleting = null
                            runCatching { vm.photos.deleteFromLibrary(Uri.parse(p.uri)) }
                                .onSuccess { sender ->
                                    if (sender == null) vm.unlinkPhoto(p)
                                    else {
                                        pendingLibraryDelete = p
                                        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                    }
                                }
                                .onFailure { vm.toast(failedText) }
                        }) { Text(stringResource(R.string.photo_delete_library), color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}

/** どう撮ったか（拡大表示の 2 行目の後ろ）。 */
@Composable
private fun photoModeLabel(p: PhotoEntity): String {
    val mode = when (p.captureMode) {
        PhotoEntity.MODE_STILL -> stringResource(R.string.photo_mode_still)
        PhotoEntity.MODE_FRAME -> stringResource(R.string.photo_mode_frame)
        PhotoEntity.MODE_CAMERA -> stringResource(R.string.photo_mode_camera)
        null -> stringResource(R.string.photo_mode_picked)
        else -> return ""
    }
    return "  ・ " + mode + if (p.burnIn) stringResource(R.string.photo_mode_burned) else ""
}

/** 写真の一覧の小さな列（トリップの詳細）。 */
@Composable
fun PhotoStrip(photos: List<PhotoEntity>, onClick: (PhotoEntity) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(photos.size) { i ->
            val p = photos[i]
            val t = rememberThumb(p.thumbPath)
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFBDBDBD))
                    .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp))
                    .clickable { onClick(p) },
            ) {
                if (t != null) Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
