package my.cheysoff.feature_pairing.qr

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import my.cheysoff.core_pairing.qr.QrCodes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "QrScanner"

/**
 * A live camera viewfinder that reports every QR code it reads.
 *
 * ## What is and is not tested
 *
 * Nothing in this file is covered by a unit test, and it deliberately holds as little logic as
 * possible for that reason. CameraX needs a real camera HAL, a real `LifecycleOwner` and a real
 * `SurfaceView`; there is no unit-test seam for it and this project has no instrumented-test
 * infrastructure beyond `ExampleInstrumentedTest`. Everything that *can* be decided without a
 * camera — reading a QR out of a luminance plane, parsing the payload, the protocol — lives in
 * [QrCodes] and `my.cheysoff.core_pairing.protocol`, which are tested thoroughly. What is left
 * here is wiring: bind two use cases, copy a plane, call a function.
 *
 * ## Rotation
 *
 * The luminance plane is handed to the decoder in the sensor's own orientation, with no rotation
 * applied. That is correct rather than lazy: QR decoding starts by locating the three finder
 * patterns, which works at any in-plane rotation, so rotating the buffer would cost a full-frame
 * copy per frame to change nothing.
 *
 * @param onCode invoked on the analyser's background executor for every QR symbol read, including
 *   the same one repeatedly while it stays in shot. The caller is expected to be idempotent and to
 *   hop to its own thread — the pairing sessions are explicitly not thread-safe.
 */
@Composable
fun QrScannerView(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // rememberUpdatedState so a recomposition that swaps the callback does not force the camera to
    // be unbound and rebound -- rebinding blanks the viewfinder for a few hundred milliseconds,
    // which reads as the camera failing.
    val currentOnCode by rememberUpdatedState(onCode)

    val previewView = remember(context) {
        PreviewView(context).apply {
            // COMPATIBLE (a TextureView) rather than the default PERFORMANCE (a SurfaceView).
            // A SurfaceView punches a hole through the window, and this viewfinder is drawn inside
            // a rounded, clipped Compose card that sits over other content -- exactly the case the
            // CameraX documentation names as needing COMPATIBLE.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val executor = Executors.newSingleThreadExecutor()
        val binding = bindCamera(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            executor = executor,
            onCode = { currentOnCode(it) },
        )
        onDispose {
            binding?.unbindAll()
            // Shut the analyser thread down explicitly. Leaving it alive would keep a thread per
            // visit to this screen, each holding the last frame it was handed.
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun bindCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    executor: ExecutorService,
    onCode: (String) -> Unit,
): ProcessCameraProvider? {
    val future = ProcessCameraProvider.getInstance(context)
    val provider = try {
        // `getInstance` resolves almost immediately once the process has a camera provider, and
        // this runs from a DisposableEffect on the main thread. `get()` is used rather than a
        // listener because the alternative -- binding from a callback -- can land after the effect
        // has already been disposed, leaving a bound camera nobody unbinds.
        future.get()
    } catch (e: Exception) {
        // No camera, no permission, or a camera service that failed to start. Reported as an
        // absent viewfinder rather than a crash; the manifest declares the camera feature as
        // not required precisely so this state is reachable.
        Log.w(TAG, "camera provider unavailable", e)
        return null
    }

    val preview = Preview.Builder().build().also {
        it.setSurfaceProvider(previewView.surfaceProvider)
    }

    val analysis = ImageAnalysis.Builder()
        // 1280x720 is plenty to resolve a version-13 symbol filling most of the frame, and it is
        // small enough that a full-frame decode keeps up. Asking for the sensor's native size
        // would hand the decoder several megapixels per frame for no extra readability.
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    )
                )
                .build()
        )
        // Drop frames rather than queue them. A decode takes longer than a frame interval, and a
        // queue would mean decoding an ever-staler picture of where the phone used to be pointing.
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()

    analysis.setAnalyzer(executor) { image -> analyseFrame(image, onCode) }

    return try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        provider
    } catch (e: Exception) {
        Log.w(TAG, "could not bind the camera", e)
        provider.unbindAll()
        null
    }
}

/**
 * Copy one frame's luminance plane and hand it to the decoder.
 *
 * `close()` is in a `finally`: CameraX has a small fixed pool of image buffers and a single leaked
 * `ImageProxy` stalls the analyser permanently, with no error anywhere.
 */
private fun analyseFrame(image: ImageProxy, onCode: (String) -> Unit) {
    try {
        val plane = image.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val text = QrCodes.decodeLuminance(
            yPlane = bytes,
            width = image.width,
            height = image.height,
            rowStride = plane.rowStride,
        )
        // Never logged. A decoded pairing code is either a public ephemeral key or a sealed ARK,
        // and neither belongs in logcat, which is readable by `adb` from any developer machine the
        // phone is plugged into.
        if (text != null) onCode(text)
    } catch (e: Exception) {
        Log.w(TAG, "frame analysis failed", e)
    } finally {
        image.close()
    }
}
