package de.velospot.feature.map.presentation.sheets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.velospot.R
import de.velospot.core.photo.BikePhotoCrop
import de.velospot.core.photo.BikePhotoScaling
import de.velospot.core.photo.NormalizedCropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A full-screen framing/crop step for a freshly-picked bike photo. The picked image
 * is shown inside a fixed crop window whose aspect ratio matches the Sharepic photo
 * box ([BikePhotoCrop.FRAME_ASPECT_WIDTH] : [BikePhotoCrop.FRAME_ASPECT_HEIGHT]); the
 * rider **pans and pinch-zooms** the image to choose what part is shown. Confirm
 * hands back the chosen [NormalizedCropRect] (0..1 in source-image space) which
 * storage applies to the saved JPEG; cancel returns without changing the photo.
 *
 * Implemented purely with Compose gestures ([detectTransformGestures]) and the
 * platform BitmapFactory/Canvas — no third-party crop library — consistent with the
 * project's no-Coil approach. All the fiddly display-scale ⇄ crop maths lives in the
 * pure, JVM-tested [BikePhotoCrop].
 */
@Composable
internal fun BikePhotoCropDialog(
    photoUri: String,
    onCancel: () -> Unit,
    onConfirm: (NormalizedCropRect) -> Unit
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, photoUri) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadOrientedBitmap(context, Uri.parse(photoUri)) }
                .getOrNull()?.asImageBitmap()
        }
    }

    // Display scale (px per image px) and image-centre offset (screen px).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }

    val img = image

    // Once the image and the frame size are known, start at the cover scale, centred.
    LaunchedEffect(img, frameSize) {
        if (img != null && frameSize.width > 0 && frameSize.height > 0) {
            scale = BikePhotoCrop.coverScale(
                img.width, img.height,
                frameSize.width.toFloat(), frameSize.height.toFloat()
            )
            offset = Offset.Zero
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(R.string.bike_photo_crop_title),
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.bike_photo_crop_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(BikePhotoCrop.FRAME_ASPECT_WIDTH / BikePhotoCrop.FRAME_ASPECT_HEIGHT)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .onSizeChanged { frameSize = it }
                        .pointerInput(img, frameSize) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val bmp = img ?: return@detectTransformGestures
                                if (frameSize.width == 0 || frameSize.height == 0) return@detectTransformGestures
                                val fw = frameSize.width.toFloat()
                                val fh = frameSize.height.toFloat()
                                val cover = BikePhotoCrop.coverScale(bmp.width, bmp.height, fw, fh)
                                val newScale = (scale * zoom).coerceIn(cover, cover * BikePhotoCrop.MAX_ZOOM)
                                val (cx, cy) = BikePhotoCrop.clampOffset(
                                    bmp.width, bmp.height, fw, fh, newScale,
                                    offset.x + pan.x, offset.y + pan.y
                                )
                                scale = newScale
                                offset = Offset(cx, cy)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (img != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dispW = img.width * scale
                            val dispH = img.height * scale
                            val left = size.width / 2f - dispW / 2f + offset.x
                            val top = size.height / 2f - dispH / 2f + offset.y
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(dispW.roundToInt(), dispH.roundToInt())
                            )
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.bike_photo_crop_cancel_cd),
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bike_photo_crop_cancel))
                    }
                    Button(
                        onClick = {
                            val bmp = img
                            val crop = if (bmp != null && frameSize.width > 0 && frameSize.height > 0) {
                                BikePhotoCrop.normalizedCrop(
                                    bmp.width, bmp.height,
                                    frameSize.width.toFloat(), frameSize.height.toFloat(),
                                    scale, offset.x, offset.y
                                )
                            } else {
                                NormalizedCropRect.FULL
                            }
                            onConfirm(crop)
                        },
                        enabled = img != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.bike_photo_crop_confirm_cd),
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bike_photo_crop_confirm))
                    }
                }
            }
        }
    }
}

/**
 * Decodes [uri] into an EXIF-oriented, display-sized bitmap for the crop UI. Mirrors
 * storage's orientation handling so what the rider frames is exactly what is stored
 * (the normalized crop is relative to this oriented image).
 */
private fun loadOrientedBitmap(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val opts = BitmapFactory.Options().apply {
        inSampleSize = BikePhotoScaling.sampleSize(bounds.outWidth, bounds.outHeight, maxDim)
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null

    val rotation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (rotation == 0f) return decoded
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

