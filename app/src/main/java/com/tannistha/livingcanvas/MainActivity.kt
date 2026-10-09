package com.tannistha.livingcanvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

private const val GRID_COLUMNS = 64
private const val GRID_ROWS = 40
private const val MAX_IMAGE_SIZE = 1600
private val FULL_TURN = (2.0 * Math.PI).toFloat()

private data class LoadedPainting(
    val bitmap: Bitmap,
    val isDemo: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LivingCanvasScreen()
            }
        }
    }
}

@Composable
fun LivingCanvasScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedUri by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var strength by rememberSaveable { mutableStateOf(1f) }
    var speed by rememberSaveable { mutableStateOf(1f) }
    var playing by rememberSaveable { mutableStateOf(true) }

    var loaded by remember { mutableStateOf<LoadedPainting?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Keep animation state separate from the controls.
    val phase = remember { mutableStateOf(0f) }
    val currentSpeed = rememberUpdatedState(speed)

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri.toString()
        }
    }

    // Load off the UI thread. Cancels if the selection changes.
    LaunchedEffect(selectedUri) {
        loading = true
        error = null

        try {
            val selection = selectedUri

            val result = withContext(Dispatchers.IO) {
                if (selection == null) {
                    val options = BitmapFactory.Options().apply {
                        inScaled = false
                    }

                    val bitmap = requireNotNull(
                        BitmapFactory.decodeResource(
                            context.resources,
                            R.drawable.starry_night,
                            options
                        )
                    ) {
                        "Could not load the demo image."
                    }

                    LoadedPainting(bitmap, isDemo = true)
                } else {
                    LoadedPainting(
                        bitmap = loadPhoto(
                            context,
                            Uri.parse(selection)
                        ),
                        isDemo = false
                    )
                }
            }

            loaded = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = "Could not open that image. Try a JPG or PNG."
        } finally {
            loading = false
        }
    }

    // Pause when requested, or when the app is in the background.
    LaunchedEffect(playing, lifecycleOwner) {
        if (!playing) return@LaunchedEffect

        lifecycleOwner.lifecycle.repeatOnLifecycle(
            Lifecycle.State.STARTED
        ) {
            var previousFrame = withFrameNanos { it }

            while (isActive) {
                withFrameNanos { now ->
                    val elapsed =
                        (now - previousFrame) / 1_000_000_000f
                    previousFrame = now

                    // Avoid jumping after a long interruption.
                    if (elapsed > 0f && elapsed <= 0.1f) {
                        phase.value = (
                                phase.value +
                                        elapsed *
                                        (FULL_TURN / 12f) *
                                        currentSpeed.value
                                ) % FULL_TURN
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101528))
            .systemBarsPadding()
    ) {
        Text(
            text = "Living Canvas",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        val currentPainting = loaded

        if (currentPainting != null) {
            AnimatedPainting(
                painting = currentPainting.bitmap,
                isDemo = currentPainting.isDemo,
                phase = phase,
                strength = strength,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment =
                androidx.compose.ui.Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text("Choose a photo to start")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (loading) {
                Text("Loading image…")
            }

            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !loading,
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts
                                    .PickVisualMedia.ImageOnly
                            )
                        )
                    }
                ) {
                    Text("Choose photo")
                }

                OutlinedButton(
                    enabled = loaded != null,
                    onClick = { playing = !playing }
                ) {
                    Text(if (playing) "Pause" else "Play")
                }

                TextButton(
                    enabled = !loading && selectedUri != null,
                    onClick = { selectedUri = null }
                ) {
                    Text("Demo")
                }
            }

            Text(
                text = "Motion strength: ${
                    (strength * 100).roundToInt()
                }%"
            )

            Slider(
                value = strength,
                onValueChange = { strength = it },
                valueRange = 0f..1.5f
            )

            Text(
                text = "Speed: ${
                    String.format(Locale.US, "%.1f", speed)
                }×"
            )

            Slider(
                value = speed,
                onValueChange = { speed = it },
                valueRange = 0.25f..2f
            )
        }
    }
}

@Composable
private fun AnimatedPainting(
    painting: Bitmap,
    isDemo: Boolean,
    phase: State<Float>,
    strength: Float,
    modifier: Modifier = Modifier
) {
    val paint = remember {
        Paint(Paint.FILTER_BITMAP_FLAG)
    }

    val vertices = remember {
        FloatArray(
            (GRID_COLUMNS + 1) * (GRID_ROWS + 1) * 2
        )
    }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) {
            return@Canvas
        }

        val time = phase.value

        val scale = minOf(
            size.width / painting.width,
            size.height / painting.height
        )

        val imageWidth = painting.width * scale
        val imageHeight = painting.height * scale
        val left = (size.width - imageWidth) / 2f
        val top = (size.height - imageHeight) / 2f
        val aspect = imageWidth / imageHeight

        var index = 0

        for (row in 0..GRID_ROWS) {
            val v = row.toFloat() / GRID_ROWS

            for (column in 0..GRID_COLUMNS) {
                val u = column.toFloat() / GRID_COLUMNS

                val edgeDistance = minOf(
                    u, 1f - u, v, 1f - v
                )
                val edgeMask =
                    smoothStep(0f, 0.08f, edgeDistance)

                // Protect the tree only for the bundled painting.
                val subjectMask = if (isDemo) {
                    val treeWidth = 0.025f + v * 0.055f

                    val treeHorizontal = 1f - smoothStep(
                        treeWidth,
                        treeWidth + 0.045f,
                        abs(u - 0.26f)
                    )

                    val treeVertical =
                        smoothStep(0.08f, 0.22f, v)

                    val skyMask =
                        1f - smoothStep(0.78f, 0.98f, v)

                    (1f - treeHorizontal * treeVertical) *
                            skyMask
                } else {
                    1f
                }

                val motionMask =
                    edgeMask * subjectMask * strength

                var moveX =
                    0.010f * sin(v * 10f - time) +
                            0.004f * sin(
                        u * 13f + v * 8f - 2f * time
                    )

                var moveY =
                    0.006f * sin(u * 9f - time) +
                            0.003f * cos(
                        u * 12f - v * 7f + 2f * time
                    )

                // Imported photos get a centred main swirl.
                val mainX = if (isDemo) 0.55f else 0.5f
                val mainY = if (isDemo) 0.46f else 0.5f

                val dx1 = (u - mainX) * aspect
                val dy1 = v - mainY

                val weight1 = exp(
                    -(dx1 * dx1 + dy1 * dy1) / 0.10f
                )
                val rotation1 =
                    0.18f * sin(time) * weight1

                moveX += (
                        dx1 * cos(rotation1) -
                                dy1 * sin(rotation1) - dx1
                        ) / aspect

                moveY +=
                    dx1 * sin(rotation1) +
                            dy1 * cos(rotation1) - dy1

                // Keep the second painted-sky swirl for the demo.
                if (isDemo) {
                    val dx2 = (u - 0.77f) * aspect
                    val dy2 = v - 0.66f

                    val weight2 = exp(
                        -(dx2 * dx2 + dy2 * dy2) / 0.035f
                    )

                    val rotation2 =
                        -0.15f * sin(time + 1.2f) * weight2

                    moveX += (
                            dx2 * cos(rotation2) -
                                    dy2 * sin(rotation2) - dx2
                            ) / aspect

                    moveY +=
                        dx2 * sin(rotation2) +
                                dy2 * cos(rotation2) - dy2
                }

                vertices[index++] =
                    left + (u + moveX * motionMask) * imageWidth

                vertices[index++] =
                    top + (v + moveY * motionMask) * imageHeight
            }
        }

        drawContext.canvas.nativeCanvas.drawBitmapMesh(
            painting,
            GRID_COLUMNS,
            GRID_ROWS,
            vertices,
            0,
            null,
            0,
            paint
        )
    }
}

private fun smoothStep(
    start: Float,
    end: Float,
    value: Float
): Float {
    val t = ((value - start) / (end - start))
        .coerceIn(0f, 1f)

    return t * t * (3f - 2f * t)
}

// Called on Dispatchers.IO, not on the UI thread.
private fun loadPhoto(
    context: Context,
    uri: Uri
): Bitmap {
    val resolver = context.contentResolver

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(resolver, uri)

        return ImageDecoder.decodeBitmap(source) {
                decoder, info, _ ->

            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE

            val longest = maxOf(
                info.size.width,
                info.size.height
            )

            if (longest > MAX_IMAGE_SIZE) {
                val scale = MAX_IMAGE_SIZE.toFloat() / longest

                decoder.setTargetSize(
                    (info.size.width * scale)
                        .roundToInt().coerceAtLeast(1),
                    (info.size.height * scale)
                        .roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    // Android 8 / 8.1 fallback: inspect dimensions first.
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }

    resolver.openInputStream(uri).use { stream ->
        requireNotNull(stream) { "Cannot open image." }
        BitmapFactory.decodeStream(stream, null, bounds)
    }

    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
        "Unsupported image."
    }

    var sample = 1
    while (
        maxOf(bounds.outWidth, bounds.outHeight) / sample >
        MAX_IMAGE_SIZE
    ) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    val bitmap = resolver.openInputStream(uri).use { stream ->
        requireNotNull(stream) { "Cannot open image." }
        requireNotNull(
            BitmapFactory.decodeStream(stream, null, options)
        ) {
            "Cannot decode image."
        }
    }

    // Respect camera-photo orientation where metadata is available.
    val orientation = try {
        resolver.openInputStream(uri).use { stream ->
            if (stream == null) {
                ExifInterface.ORIENTATION_NORMAL
            } else {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }
    } catch (_: java.io.IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    val matrix = Matrix()

    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
            matrix.setScale(-1f, 1f)

        ExifInterface.ORIENTATION_ROTATE_180 ->
            matrix.setRotate(180f)

        ExifInterface.ORIENTATION_FLIP_VERTICAL ->
            matrix.setScale(1f, -1f)

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 ->
            matrix.setRotate(90f)

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 ->
            matrix.setRotate(-90f)
    }

    if (matrix.isIdentity) return bitmap

    val corrected = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )

    if (corrected !== bitmap) bitmap.recycle()
    return corrected
}