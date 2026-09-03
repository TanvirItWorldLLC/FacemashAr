package com.facemesh.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.vision.facemesh.FaceLandmarker
import com.google.mediapipe.tasks.vision.facemesh.FaceLandmarkerOptions
import com.google.mediapipe.tasks.vision.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.core.ImageProcessor
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.BitmapImageBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FaceMeshAR"
        private const val REQUEST_CAMERA_PERMISSION = 100
        private const val MAX_FACES = 1
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: FaceMeshOverlayView
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var faceLandmarker: FaceLandmarker? = null
    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var currentRotation = 0
    private var isProcessing = false
    private val orientationListener: OrientationEventListener by lazy {
        OrientationEventListener(this) { orientation ->
            val rotation = when {
                orientation in 45..135 -> 90
                orientation in 135..225 -> 180
                orientation in 225..315 -> 270
                else -> 0
            }
            if (rotation != currentRotation) {
                currentRotation = rotation
                updatePreviewRotation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)

        initFaceLandmarker()
        requestCameraPermission()
    }

    private fun initFaceLandmarker() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val options = FaceLandmarkerOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath("face_landmarker.task")
                            .setDelegate(BaseOptions.Delegate.GPU)
                            .build()
                    )
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(MAX_FACES)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener { result, image, timestampMs ->
                        runOnUiThread {
                            overlayView.updateFaceMesh(result?.faceLandmarks ?: emptyList())
                            isProcessing = false
                        }
                    }
                    .build()

                faceLandmarker = FaceLandmarker.createFromOptions(this@MainActivity, options)
                Log.d(TAG, "FaceLandmarker initialized with GPU delegate")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FaceLandmarker", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to load face mesh model", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture?.addListener({
            try {
                val cameraProvider = cameraProviderFuture?.get()
                bindCamera(cameraProvider!!)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetResolution(Size(1280, 720))
            .setTargetRotation(previewView.display.rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, FaceMeshAnalyzer()) }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)
            orientationListener.enable()
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private inner class FaceMeshAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (isProcessing || faceLandmarker == null) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                return
            }

            val buffer = mediaImage.planes[0].buffer
            val width = mediaImage.width
            val height = mediaImage.height

            // Convert YUV to Bitmap for MediaPipe
            val yuvBuffer = ByteBuffer.allocateDirect(width * height * 3 / 2)
            buffer.rewind()
            yuvBuffer.put(buffer)
            buffer.rewind()
            yuvBuffer.rewind()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val yuvBytes = yuvBuffer.array()
            decodeYUV420SP(bitmap, yuvBytes, width, height)

            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestamp = imageProxy.imageInfo.timestamp

            isProcessing = true
            faceLandmarker?.detectAsync(mpImage, timestamp)

            imageProxy.close()
        }

        private fun decodeYUV420SP(bitmap: Bitmap, yuv420sp: ByteArray, width: Int, height: Int) {
            val frameSize = width * height
            var yIndex = 0
            var uvIndex = frameSize

            val pixels = IntArray(frameSize)
            var pixelIndex = 0

            for (j in 0 until height) {
                for (i in 0 until width) {
                    val y = (yuv420sp[yIndex] + 256).toInt() % 256
                    yIndex++

                    val uvOffset = (j / 2) * width + (i / 2) * 2
                    val v = (yuv420sp[uvOffset] + 256).toInt() % 256
                    val u = (yuv420sp[uvOffset + 1] + 256).toInt() % 256

                    val r = (y + 1.402f * (v - 128)).coerceIn(0, 255).toInt()
                    val g = (y - 0.344f * (u - 128) - 0.714f * (v - 128)).coerceIn(0, 255).toInt()
                    val b = (y + 1.772f * (u - 128)).coerceIn(0, 255).toInt()

                    pixels[pixelIndex++] = Color.rgb(r, g, b)
                }
            }

            bitmap.pixels = pixels
        }
    }

    private fun updatePreviewRotation() {
        previewView.implementationMode = when (currentRotation) {
            90 -> PreviewView.ImplementationMode.COMPATIBLE
            180 -> PreviewView.ImplementationMode.COMPATIBLE
            270 -> PreviewView.ImplementationMode.COMPATIBLE
            else -> PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        faceLandmarker?.close()
    }
}

class FaceMeshOverlayView(context: android.content.Context, attrs: android.util.AttributeSet) : TextureView(context, attrs) {

    private val paint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var faceLandmarks: List<List<NormalizedLandmark>> = emptyList()
    private val canvasLock = Any()

    fun updateFaceMesh(landmarks: List<List<NormalizedLandmark>>) {
        synchronized(canvasLock) {
            faceLandmarks = landmarks
            postInvalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()

        synchronized(canvasLock) {
            for (face in faceLandmarks) {
                drawFaceMesh(canvas, face, width, height)
            }
        }
    }

    private fun drawFaceMesh(canvas: Canvas, landmarks: List<NormalizedLandmark>, width: Float, height: Float) {
        // Draw key facial features: eyes, eyebrows, nose, mouth, face oval
        val connections = getFaceMeshConnections()

        for ((start, end) in connections) {
            val p1 = landmarks[start]
            val p2 = landmarks[end]
            canvas.drawLine(
                p1.x * width,
                p1.y * height,
                p2.x * width,
                p2.y * height,
                paint
            )
        }

        // Draw key points
        val keyPoints = listOf(
            // Left eye
            33, 133, 160, 159, 158, 153, 145, 144,
            // Right eye
            362, 263, 387, 386, 385, 380, 374, 373,
            // Nose tip
            1, 2, 98, 327,
            // Mouth
            61, 291, 0, 17, 84, 181, 78, 308,
            // Face oval
            10, 338, 297, 332, 284, 251, 389, 356,
            454, 323, 361, 288, 397, 365, 379, 378
        )

        for (idx in keyPoints) {
            if (idx < landmarks.size) {
                val p = landmarks[idx]
                canvas.drawCircle(p.x * width, p.y * height, 3f, pointPaint)
            }
        }
    }

    private fun getFaceMeshConnections(): List<Pair<Int, Int>> {
        // Simplified face mesh connections for key features
        return listOf(
            // Left eye
            33 to 133, 133 to 160, 160 to 159, 159 to 158, 158 to 153, 153 to 145, 145 to 144, 144 to 33,
            // Right eye
            362 to 263, 263 to 387, 387 to 386, 386 to 385, 385 to 380, 380 to 374, 374 to 373, 373 to 362,
            // Left eyebrow
            70 to 63, 63 to 105, 105 to 66, 66 to 107,
            // Right eyebrow
            336 to 296, 296 to 334, 334 to 293, 293 to 300,
            // Nose
            1 to 2, 2 to 98, 98 to 327, 327 to 1,
            // Mouth outer
            61 to 146, 146 to 91, 91 to 181, 181 to 84, 84 to 17, 17 to 314, 314 to 405, 405 to 321, 321 to 375, 375 to 291, 291 to 61,
            // Mouth inner
            78 to 95, 95 to 88, 88 to 178, 178 to 87, 87 to 14, 14 to 317, 317 to 402, 402 to 318, 318 to 324, 324 to 308, 308 to 78,
            // Face oval
            10 to 338, 338 to 297, 297 to 332, 332 to 284, 284 to 251, 251 to 389, 389 to 356, 356 to 454,
            454 to 323, 323 to 361, 361 to 288, 288 to 397, 397 to 365, 365 to 379, 379 to 378, 378 to 10
        )
    }
}