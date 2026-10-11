package com.wardrobapp.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import com.wardrobapp.data.StyleAnchors
import com.wardrobapp.data.centreSquare
import com.wardrobapp.data.clipInput
import com.wardrobapp.domain.normalized
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The model and its anchors, as the build puts them in the APK's assets. */
private const val MODEL_ASSET = "style-image-vitb32-int8.onnx"
private const val ANCHORS_ASSET = "style-anchors.json"

/** How long the model stays loaded after the last photo; the server's two minutes. */
private const val IDLE_SECONDS = 120L

/** The server's stack for its models, for the same reason: ONNX Runtime's graph walk recurses deep. */
private const val STACK_BYTES = 16L * 1024 * 1024

private const val TAG = "StyleEncoder"

/**
 * The style model, on the phone: a stored photo to a unit vector.
 *
 * The same model the Home Assistant app runs -- the image half of CLIP
 * ViT-B/32 at 8 bits, in the APK's assets -- run the way the server runs it
 * (StyleEncoder there) and for the same reasons: one photo at a time on a
 * thread of its own, without ONNX Runtime's arena, loaded on first use and
 * let go two minutes after the last. Everything about what the model is shown
 * is :data's, shared with the server, so the two embed a photo alike: the
 * square cut out of it (centreSquare) and the arithmetic that turns pixels
 * into the model's input (clipInput). Only the decoding and the scaling are
 * Android's own.
 *
 * The model is read straight out of the APK, memory-mapped -- the build stores
 * it uncompressed for that -- so loading it costs neither a copy on disk nor
 * 87 MB of Java heap, which a phone with a small heap would not have.
 *
 * A phone where the model will not load -- too little memory, a missing
 * native library, a Robolectric run with no native code at all -- says so in
 * the log once and then answers null for every photo, and the app goes on
 * without the style term, as it did before the model was here.
 */
class AndroidStyleEncoder(
    private val context: Context,
    /** The stored photo by its name, or null when there is no such file. */
    private val photoFile: (String) -> File?,
) : AutoCloseable {

    // All of these only ever touched on [worker]'s one thread.
    private var session: Loaded? = null
    private var closing: ScheduledFuture<*>? = null
    private var unavailable = false

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(null, task, "style-encoder", STACK_BYTES).apply { isDaemon = true }
    }

    /**
     * The anchors the model's vectors are read against, or null when the APK
     * has none. Small, so read once and kept.
     */
    val anchors: StyleAnchors? by lazy {
        try {
            StyleAnchors.parse(context.assets.open(ANCHORS_ASSET).bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "No style anchors in this build: ${e.message}")
            null
        }
    }

    /**
     * The photo stored as [photo] as a unit vector, or null when there is no
     * such photo, no decoder reads it, or the model cannot run here. Blocking,
     * for a fraction of a second on a recent phone, on the encoder's thread.
     */
    fun embed(photo: String): FloatArray? = onWorker {
        if (unavailable) return@onWorker null
        val file = photoFile(photo) ?: return@onWorker null
        val anchors = anchors ?: return@onWorker null
        closing?.cancel(false)
        try {
            val loaded = session ?: load().also { session = it }
            val pixels = squareOf(file, loaded.size) ?: run {
                Log.w(TAG, "Could not read $photo")
                return@onWorker null
            }
            normalized(loaded.embed(clipInput(pixels, loaded.size, anchors)))
        } catch (e: Throwable) {
            // A model that will not load will not load next time either; a
            // photo that broke it is one photo. Only the first is remembered.
            if (session == null) {
                unavailable = true
                Log.w(TAG, "The style model cannot run on this phone: $e")
            } else {
                Log.w(TAG, "Could not embed $photo: $e")
            }
            null
        } finally {
            closing = worker.schedule(::closeIdle, IDLE_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * The photo in [file] as the model's [size] by [size] square of ARGB
     * pixels: decoded at the smallest power-of-two reduction that still
     * covers the square, scaled so its shorter side is [size], and the middle
     * cut out -- the square the server cuts.
     */
    private fun squareOf(file: File, size: Int): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val (scaledSize, square) = centreSquare(decoded.width, decoded.height, size)
        val scaled = decoded.scale(scaledSize.width, scaledSize.height)
        if (scaled !== decoded) decoded.recycle()
        return try {
            IntArray(size * size).also { scaled.getPixels(it, 0, size, square.left, square.top, size, size) }
        } finally {
            scaled.recycle()
        }
    }

    private fun load(): Loaded {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        val model: ByteBuffer = context.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
        return Loaded(environment, options, environment.createSession(model, options))
    }

    private fun closeIdle() {
        session?.close()
        session = null
    }

    override fun close() {
        if (!worker.isShutdown) onWorker(::closeIdle)
        worker.shutdownNow()
    }

    private fun <T> onWorker(work: () -> T): T = try {
        worker.submit(Callable(work)).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** A loaded model, and what closing it means. */
    private class Loaded(
        private val environment: OrtEnvironment,
        private val options: OrtSession.SessionOptions,
        private val session: OrtSession,
    ) : AutoCloseable {
        private val inputName = session.inputNames.first()

        val size: Int = (session.inputInfo.getValue(inputName).info as TensorInfo).shape.last().toInt()

        fun embed(input: FloatArray): FloatArray {
            val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val buffer = (result.get(0) as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }

        override fun close() {
            session.close()
            options.close()
        }
    }
}
