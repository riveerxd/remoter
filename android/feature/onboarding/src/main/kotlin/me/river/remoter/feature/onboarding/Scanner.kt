package me.river.remoter.feature.onboarding

import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.river.remoter.core.design.Space
import me.river.remoter.core.design.components.Chip
import kotlin.math.abs
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import me.river.remoter.core.design.EaseOut
import me.river.remoter.core.design.Remoter
import me.river.remoter.core.net.Pairing
import java.util.concurrent.Executors

// position as fractions of the analysed frame, so the frame can snap to it
data class Found(val text: String, val left: Float, val top: Float, val size: Float)

// ZXing core only, no Google services. a terminal draws the code light on dark and QRCodeReader
// ignores ALSO_INVERTED (only MultiFormatReader honours it), hence the inverted second pass
internal fun decodeLuma(data: ByteArray, rowStride: Int, width: Int, height: Int): com.google.zxing.Result? {
    val reader = QRCodeReader()
    val src = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
    for (s in listOf(src, src.invert())) {
        runCatching { reader.decode(BinaryBitmap(HybridBinarizer(s)), QR_HINTS) }.getOrNull()?.let { return it }
        reader.reset()
    }
    return null
}

private val QR_HINTS = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

class QrAnalyzer(private val onFound: (Found) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val buf = plane.buffer
            val data = ByteArray(buf.remaining()).also(buf::get)
            val result = decodeLuma(data, plane.rowStride, it.width, it.height) ?: return
            // anything but a link we'd accept keeps scanning quietly
            if (Pairing.Link.parse(result.text) == null) return
            val xs = result.resultPoints.map { p -> p.x / it.width }
            val ys = result.resultPoints.map { p -> p.y / it.height }
            val s = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
            onFound(Found(result.text, xs.min(), ys.min(), s))
        }
    }
}

// the ultrawide only shows below 1x on a logical camera that exposes it
internal fun zoomStops(min: Float, max: Float): List<Float> =
    buildList {
        if (min < 0.95f) add(min)
        add(1f)
        listOf(2f, 3f).filter { it <= max + 0.01f }.forEach(::add)
    }

internal fun zoomLabel(z: Float): String =
    if (z >= 1f && z == z.toInt().toFloat()) "${z.toInt()}\u00d7" else "%.1f\u00d7".format(java.util.Locale.ROOT, z)

// an S25 defaulted to the wide end of the zoom range and a terminal QR came out mush, so zoom starts at 1x
@Composable
fun ScannerView(onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    var found by remember { mutableStateOf<Found?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { p -> p.surfaceProvider = surfaceProvider }
                        // the default 640x480 gives a dense pairing code about two pixels a module
                        // at arm's length, too few to decode off a screen
                        val resolution = ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                            .build()
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolution)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor, QrAnalyzer { f ->
                            post {
                                if (found == null) {
                                    found = f
                                    postDelayed({ onLink(f.text) }, 350)
                                }
                            }
                        })
                        provider.unbindAll()
                        val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        cam.cameraControl.setZoomRatio(zoom)
                        camera = cam
                    }, ContextCompat.getMainExecutor(ctx))
                    setOnTouchListener { v, e ->
                        if (e.action == MotionEvent.ACTION_UP) {
                            val point = meteringPointFactory.createPoint(e.x, e.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                            v.performClick()
                        }
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(Unit) { onDispose { executor.shutdown() } }
        val w = maxWidth
        val h = maxHeight
        val idle = w * 0.68f
        val f = found
        // the analysis frame is landscape, mapped roughly onto the rotated preview
        val size by animateDpAsState(if (f == null) idle else (w * f.size).coerceIn(120.dp, w), tween(250, easing = EaseOut), label = "size")
        val x by animateDpAsState(if (f == null) (w - idle) / 2 else w * (1f - f.top - f.size), tween(250, easing = EaseOut), label = "x")
        val y by animateDpAsState(if (f == null) (h - idle) / 2 else h * f.left, tween(250, easing = EaseOut), label = "y")
        val color by animateColorAsState(if (f == null) androidx.compose.ui.graphics.Color.White else Remoter.colors.volt, tween(200), label = "c")
        Box(Modifier.offset(x, y).size(size).border(3.dp, color, RoundedCornerShape(24.dp)))
        val range = camera?.cameraInfo?.zoomState?.value
        if (range != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s8),
                horizontalArrangement = Arrangement.spacedBy(Space.s8),
            ) {
                zoomStops(range.minZoomRatio, range.maxZoomRatio).forEach { z ->
                    Chip(zoomLabel(z), selected = abs(z - zoom) < 0.01f, onClick = {
                        zoom = z
                        camera?.cameraControl?.setZoomRatio(z)
                    }, modifier = Modifier.semantics { contentDescription = "Zoom ${zoomLabel(z)}" })
                }
            }
        }
    }
}
