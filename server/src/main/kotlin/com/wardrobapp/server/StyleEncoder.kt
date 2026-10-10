package com.wardrobapp.server

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.wardrobapp.domain.Fit
import com.wardrobapp.domain.Formality
import com.wardrobapp.domain.GarmentAttributes
import com.wardrobapp.domain.Pattern
import com.wardrobapp.domain.Weight
import com.wardrobapp.domain.normalized
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Seeing what a garment looks like: a photo to a vector, and a vector to the
 * attributes it suggests.
 *
 * The image half of CLIP (OpenAI's ViT-B/32, 8-bit, exported by
 * scripts/export-style-model.py), run the way BackgroundRemover runs its
 * model and for the same reasons, set out there: on a thread of its own with
 * a deep stack, without ONNX Runtime's arena, closed when idle, one photo at
 * a time. A photo becomes a unit vector that lands near another garment's
 * when the two look alike -- cut, fabric, formality, colour -- which is what
 * StyleTaste in :domain scores outfits by, and what lets a photo of a look
 * from outside the wardrobe say anything at all.
 *
 * The attributes come from [StyleAnchors]: the text half's embedding of
 * sentences like "a photo of formal evening wear", written by the same
 * export. A garment is formal if its vector sits nearer that anchor than
 * the casual one; the text model itself never runs here, which saves a
 * tokenizer and a second model. CLIP's zero-shot reading of clothes is good
 * at formality and pattern and only fair at fit and weight, so an attribute
 * is written only when the nearest anchor wins clearly, and never over one
 * somebody set.
 */
class StyleEncoder(
    val anchors: StyleAnchors,
    private val idleSeconds: Long = IDLE_SECONDS,
    private val openEncoder: () -> ImageEncoder,
) : AutoCloseable {

    // Both only ever touched on [worker]'s one thread.
    private var encoder: ImageEncoder? = null
    private var closing: ScheduledFuture<*>? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(null, task, "style-encoder", BackgroundRemover.STACK_BYTES).apply { isDaemon = true }
    }

    /** The model this encodes with, which the stored vectors are keyed by. */
    val model: String get() = anchors.model

    /**
     * [photo], a stored photo's bytes, as a unit vector. Blocking, for a
     * second or two, on the encoder's own thread. Throws for a photo no
     * reader can decode.
     */
    fun embed(photo: ByteArray): FloatArray = onWorker {
        closing?.cancel(false)
        try {
            val image = ImageIO.read(ByteArrayInputStream(photo))
                ?: throw IllegalArgumentException("That photo could not be read.")
            val model = encoder ?: openEncoder().also { encoder = it }
            val raw = model.embed(modelInput(image, model.size, anchors))
            normalized(raw) ?: throw IllegalStateException("The model answered a zero vector.")
        } finally {
            closing = worker.schedule(::closeIdle, idleSeconds, TimeUnit.SECONDS)
        }
    }

    /** Whether the model is loaded now; for the test that it is let go. */
    internal val loaded: Boolean get() = onWorker { encoder != null }

    private fun closeIdle() {
        encoder?.close()
        encoder = null
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

    companion object {
        const val IDLE_SECONDS = 120L

        /**
         * The encoder over [model] with [anchors], or null to say the server
         * has no style model: nothing configured, or a file not there.
         */
        fun at(model: File?, anchors: File?): StyleEncoder? {
            if (model == null || anchors == null || !model.isFile || !anchors.isFile) return null
            val loaded = StyleAnchors.load(anchors)
            return StyleEncoder(loaded) { OnnxImageEncoder(model) }
        }

        /**
         * [image] as CLIP reads it: the shorter side scaled to [size], the
         * middle [size] by [size] cut out, and each channel normalised with the
         * mean and deviation the model was trained with (carried in the
         * anchors file, so the two cannot drift apart). Area-averaged on the
         * way down, as the cut-out model's input is, so a weave is not
         * aliased into a pattern.
         */
        internal fun modelInput(image: BufferedImage, size: Int, anchors: StyleAnchors): FloatArray {
            val scale = size.toDouble() / minOf(image.width, image.height)
            val width = maxOf(size, Math.round(image.width * scale).toInt())
            val height = maxOf(size, Math.round(image.height * scale).toInt())
            val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
                dispose()
            }
            val left = (width - size) / 2
            val top = (height - size) / 2
            val pixels = scaled.getRGB(left, top, size, size, null, 0, size)

            val plane = size * size
            val input = FloatArray(3 * plane)
            for ((index, pixel) in pixels.withIndex()) {
                for (channel in 0..2) {
                    val value = (pixel shr (16 - 8 * channel) and 0xFF) / 255f
                    input[channel * plane + index] = (value - anchors.mean[channel]) / anchors.deviation[channel]
                }
            }
            return input
        }
    }
}

/** A model that turns a prepared picture into a vector. An interface so the tests need no model. */
interface ImageEncoder : AutoCloseable {
    /** The side of the square the model reads. */
    val size: Int

    /** [input], three planes of [size] by [size] as StyleEncoder prepares them, to the raw embedding. */
    fun embed(input: FloatArray): FloatArray
}

/** The exported CLIP image tower in an ONNX file, run on the CPU. */
class OnnxImageEncoder(model: File) : ImageEncoder {

    private val environment = OrtEnvironment.getEnvironment()

    private val options = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }

    private val session = environment.createSession(model.path, options)

    private val inputName = session.inputNames.first()

    override val size: Int = (session.inputInfo.getValue(inputName).info as ai.onnxruntime.TensorInfo).shape.last().toInt()

    override fun embed(input: FloatArray): FloatArray {
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

/**
 * The anchors the export wrote: for each attribute, a unit vector per value,
 * plus how the model wants its pictures prepared.
 */
class StyleAnchors(
    val model: String,
    val imageSize: Int,
    val mean: FloatArray,
    val deviation: FloatArray,
    val dimensions: Int,
    val formality: Map<Formality, FloatArray>,
    val pattern: Map<Pattern, FloatArray>,
    val fit: Map<Fit, FloatArray>,
    val weight: Map<Weight, FloatArray>,
    val statement: Map<Boolean, FloatArray>,
) {
    /**
     * What [vector] suggests, attribute by attribute: the nearest anchor,
     * where it beats the runner-up by [LEAST_MARGIN] of cosine; unset where
     * the model cannot tell. Cosines between CLIP's text and image embeddings
     * sit in a narrow band -- a few hundredths separate a clear call from a
     * toss-up -- which is what the margin is sized to.
     */
    fun attributesFor(vector: FloatArray): GarmentAttributes = GarmentAttributes(
        formality = nearest(formality, vector),
        pattern = nearest(pattern, vector),
        fit = nearest(fit, vector),
        weight = nearest(weight, vector),
        statement = nearest(statement, vector),
    )

    private fun <T> nearest(anchors: Map<T, FloatArray>, vector: FloatArray): T? {
        if (anchors.size < 2) return null
        val ranked = anchors.entries.map { (value, anchor) -> value to cosine(anchor, vector) }.sortedByDescending { it.second }
        val (best, bestScore) = ranked[0]
        return if (bestScore - ranked[1].second >= LEAST_MARGIN) best else null
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var total = 0f
        for (i in a.indices) total += a[i] * b[i]
        return total
    }

    @Serializable
    private class Document(
        val model: String,
        val imageSize: Int,
        val mean: List<Float>,
        val deviation: List<Float>,
        val dimensions: Int,
        val anchors: Map<String, Map<String, List<Float>>>,
    )

    companion object {
        /** By how much the nearest anchor must beat the next for the attribute to be written. */
        const val LEAST_MARGIN = 0.01f

        fun load(file: File): StyleAnchors = parse(file.readText())

        fun parse(json: String): StyleAnchors {
            val document = Json { ignoreUnknownKeys = true }.decodeFromString(Document.serializer(), json)
            fun <T> table(name: String, values: List<T>, tag: (T) -> String): Map<T, FloatArray> {
                val anchors = document.anchors[name].orEmpty()
                return values.mapNotNull { value -> anchors[tag(value)]?.let { value to it.toFloatArray() } }.toMap()
            }
            return StyleAnchors(
                model = document.model,
                imageSize = document.imageSize,
                mean = document.mean.toFloatArray(),
                deviation = document.deviation.toFloatArray(),
                dimensions = document.dimensions,
                formality = table("formality", Formality.entries) { it.tag },
                pattern = table("pattern", Pattern.entries) { it.tag },
                fit = table("fit", Fit.entries) { it.tag },
                weight = table("weight", Weight.entries) { it.tag },
                statement = table("statement", listOf(true, false)) { if (it) "yes" else "no" },
            )
        }
    }
}
