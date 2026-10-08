package com.wardrobapp.server

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Cutting a garment out of its background, on the server, for the browser.
 *
 * The phone does this with ML Kit's subject segmentation, which is Android's
 * and arrives through Play Services; a server in Home Assistant has neither.
 * So it runs a model of its own: silueta, a smaller U²-Net, through ONNX
 * Runtime's Java binding, which ships the native library for both of the
 * architectures the Home Assistant app is built for. U²-Net is Apache-2.0; the
 * model is downloaded when the server is built (see build.gradle.kts), not
 * kept in the repository.
 *
 * Chosen against three others on photos of clothes laid on beds and floors and
 * hanging from hooks, which is what people photograph a wardrobe as:
 *  - u2netp, the 4.5 MB U²-Net, found the same garments three times as fast,
 *    but its edges were soft where silueta's were clean: a plaid shirt and a
 *    skirt's tulle came out with a half-transparent fringe of the floor they
 *    lay on. (Both kept a crack in the floorboards that ran into a pair of
 *    shoes. Nothing tried was right every time.);
 *  - IS-Net, four times silueta's size, cut the print out of a printed shirt
 *    and threw the shirt away;
 *  - BiRefNet's small variant was no better on these and took nine seconds a
 *    photo on four fast cores -- a minute, on a Raspberry Pi.
 * Silueta takes about a second on four desktop cores, and a few on a Pi 4.
 *
 * In the server rather than the browser, which could run the same model as
 * WebAssembly: then every phone and laptop that opened the page would download
 * 44 MB to use it, and a phone's browser might not have the memory to.
 *
 * Memory is the cost to watch, on a machine whose first job is Home
 * Assistant. A loaded model with ONNX Runtime's defaults held over half a
 * gigabyte after one photo, most of it an arena it keeps for the next; without
 * the arena it is under three hundred megabytes while working, at a small cost
 * in speed. And it is not kept loaded: [IDLE_SECONDS] after the last cut-out
 * it is closed, so a wardrobe that cuts out a photo a week holds the memory
 * for minutes a week. A bulk add cutting out photo after photo keeps it open
 * between them.
 *
 * One cut-out at a time, across every profile: a second request waits for the
 * first rather than running beside it, since two would take as long as one
 * after the other and hold twice the memory.
 *
 * All of it on one thread of its own, which owns the model: loading it,
 * running it and closing it. Partly because owning it makes the one-at-a-time
 * and the idle close need no lock, but mostly for the thread's stack. ONNX
 * Runtime's first load overflowed the 1 MB stack of the request thread it ran
 * on, which kills the whole server outright -- no exception, no crash report,
 * the process gone -- in the installed server, though never under the tests,
 * whose threads ran it on as little as 256 KB. A thread asked for with
 * [STACK_BYTES] of stack does not depend on whichever thread the request
 * happened to arrive on, and costs address space rather than memory: pages of
 * stack nothing touches are never given any.
 */
class BackgroundRemover(
    private val idleSeconds: Long = IDLE_SECONDS,
    private val openSegmenter: () -> Segmenter,
) : AutoCloseable {

    // Both only ever touched on [worker]'s one thread.
    private var segmenter: Segmenter? = null
    private var closing: ScheduledFuture<*>? = null

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(null, task, "background-remover", STACK_BYTES).apply { isDaemon = true }
    }

    /**
     * [photo], a stored photo's bytes, with everything but the garment made
     * transparent: a PNG the same size. Blocking, for seconds, while the
     * remover's own thread does the work; callers run it off any thread that
     * matters.
     */
    fun cutOut(photo: ByteArray): ByteArray = onWorker {
        closing?.cancel(false)
        try {
            val model = segmenter ?: openSegmenter().also { segmenter = it }
            cutOut(photo, model)
        } finally {
            closing = worker.schedule(::closeIdle, idleSeconds, TimeUnit.SECONDS)
        }
    }

    /** Whether the model is loaded now; for the test that it is let go. */
    internal val loaded: Boolean get() = onWorker { segmenter != null }

    private fun closeIdle() {
        segmenter?.close()
        segmenter = null
    }

    override fun close() {
        if (!worker.isShutdown) onWorker(::closeIdle)
        worker.shutdownNow()
    }

    /** [work] on the remover's thread, waited for; what it threw, thrown here. */
    private fun <T> onWorker(work: () -> T): T = try {
        worker.submit(Callable(work)).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    companion object {
        /** How long the model stays loaded after a cut-out, in case another follows. */
        const val IDLE_SECONDS = 120L

        /**
         * The remover's thread's stack. Four megabytes was enough where one
         * was not; sixteen leaves room for a model that recurses deeper, for
         * nothing but address space.
         */
        const val STACK_BYTES = 16L * 1024 * 1024

        /**
         * The model at [model], or null to say the server cannot remove
         * backgrounds: no model was configured, as on a development server, or
         * the file is not there.
         */
        fun at(model: File?): BackgroundRemover? =
            model?.takeIf { it.isFile }?.let { file -> BackgroundRemover { OnnxSegmenter(file) } }

        /**
         * The work itself, apart from loading and unloading: decode, ask
         * [model] where the garment is, and paint everything else transparent.
         *
         * As rembg prepares an image for this family of models, since that is
         * what the published weights were exported for and what the comparison
         * above ran: scaled to the model's square, divided by its brightest
         * value rather than by 255, then ImageNet's mean and deviation. The
         * answer is stretched to run from nothing to everything and scaled back
         * up to the photo, smoothly, so the edge is soft the way the phone's is
         * rather than a staircase of the model's pixels.
         */
        internal fun cutOut(photo: ByteArray, model: Segmenter): ByteArray {
            // Measured before it is decoded. The 20 MB the store allows is a
            // bound on the file, not on the picture: a flat-colour PNG of
            // twenty thousand pixels a side compresses to under a megabyte
            // and decodes to over a gigabyte, before the ARGB copy and the
            // alpha plane below double it, and the server runs in a quarter
            // of a Raspberry Pi's memory. The browser scales a photo to the
            // phone's size before uploading it, so a photo this large did not
            // come through the form; it is refused rather than attempted.
            val (width, height) = dimensionsOf(photo)
                ?: throw IllegalArgumentException("That photo could not be read.")
            if (width > MAX_SIDE || height > MAX_SIDE) {
                throw PhotoRejected.TooLarge("That photo is $width by $height pixels; up to $MAX_SIDE a side can be cut out.")
            }
            val image = ImageIO.read(ByteArrayInputStream(photo))
                ?: throw IllegalArgumentException("That photo could not be read.")

            val size = model.size
            val input = modelInput(image, size)
            val mask = stretched(model.segment(input))
            require(mask.size == size * size) { "The model answered ${mask.size} values for a $size by $size photo." }
            val alpha = scaledBilinear(mask, size, size, width, height)

            val cutout = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val a = (alpha[y * width + x] * 255f + 0.5f).toInt().coerceIn(0, 255)
                    cutout.setRGB(x, y, (a shl 24) or (image.getRGB(x, y) and 0xFFFFFF))
                }
            }
            return ByteArrayOutputStream().use { out ->
                ImageIO.write(cutout, "png", out)
                out.toByteArray()
            }
        }

        /**
         * The most a photo may measure on either side to be cut out. Twice
         * what the phone stores and change, and well above any screenshot;
         * at this size the planes below come to a few hundred megabytes,
         * which the smallest Pi the app runs on can spare once.
         */
        const val MAX_SIDE = 4096

        /** [photo]'s width and height from its header alone, or null if no reader knows it. */
        internal fun dimensionsOf(photo: ByteArray): Pair<Int, Int>? =
            ImageIO.createImageInputStream(ByteArrayInputStream(photo))?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.setInput(input)
                    reader.getWidth(0) to reader.getHeight(0)
                } catch (_: Exception) {
                    null
                } finally {
                    reader.dispose()
                }
            }

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DEVIATION = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** [image] as the model reads it: three planes of [size] by [size], normalised. */
        internal fun modelInput(image: BufferedImage, size: Int): FloatArray {
            // Averaged rather than sampled: a photo several times the model's
            // size, sampled, is the weave of the fabric aliased into a pattern
            // that is not there.
            val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                drawImage(image.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null)
                dispose()
            }
            val pixels = scaled.getRGB(0, 0, size, size, null, 0, size)
            var brightest = 0
            for (pixel in pixels) {
                brightest = maxOf(brightest, pixel shr 16 and 0xFF, pixel shr 8 and 0xFF, pixel and 0xFF)
            }
            val scale = 1f / maxOf(brightest, 1)
            val plane = size * size
            val input = FloatArray(3 * plane)
            for ((index, pixel) in pixels.withIndex()) {
                for (channel in 0..2) {
                    val value = pixel shr (16 - 8 * channel) and 0xFF
                    input[channel * plane + index] = (value * scale - MEAN[channel]) / DEVIATION[channel]
                }
            }
            return input
        }

        /** [mask] stretched to run from 0 to 1; all zeros if it says the same everywhere. */
        internal fun stretched(mask: FloatArray): FloatArray {
            val low = mask.min()
            val range = mask.max() - low
            return if (range <= 0f) FloatArray(mask.size) else FloatArray(mask.size) { (mask[it] - low) / range }
        }

        /** [source], [sourceWidth] by [sourceHeight], scaled to [width] by [height] by bilinear interpolation. */
        internal fun scaledBilinear(source: FloatArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): FloatArray {
            val result = FloatArray(width * height)
            // Pixel centres onto pixel centres, so the mask does not drift by
            // half a pixel of the model's towards the top left.
            val xScale = sourceWidth.toFloat() / width
            val yScale = sourceHeight.toFloat() / height
            for (y in 0 until height) {
                val sy = ((y + 0.5f) * yScale - 0.5f).coerceIn(0f, sourceHeight - 1f)
                val y0 = sy.toInt()
                val y1 = minOf(y0 + 1, sourceHeight - 1)
                val fy = sy - y0
                for (x in 0 until width) {
                    val sx = ((x + 0.5f) * xScale - 0.5f).coerceIn(0f, sourceWidth - 1f)
                    val x0 = sx.toInt()
                    val x1 = minOf(x0 + 1, sourceWidth - 1)
                    val fx = sx - x0
                    val top = source[y0 * sourceWidth + x0] * (1 - fx) + source[y0 * sourceWidth + x1] * fx
                    val bottom = source[y1 * sourceWidth + x0] * (1 - fx) + source[y1 * sourceWidth + x1] * fx
                    result[y * width + x] = top * (1 - fy) + bottom * fy
                }
            }
            return result
        }
    }
}

/** A model that says where the subject of a photo is. An interface so the tests need no model. */
interface Segmenter : AutoCloseable {
    /** The side of the square the model reads. */
    val size: Int

    /**
     * [input] -- three planes of [size] by [size], as BackgroundRemover
     * prepares them -- to how likely each pixel is to be the subject, [size]
     * by [size], on whatever scale the model answers in.
     */
    fun segment(input: FloatArray): FloatArray
}

/** A U²-Net-shaped model in an ONNX file, run on the CPU. */
class OnnxSegmenter(model: File) : Segmenter {

    private val environment = OrtEnvironment.getEnvironment()

    // Without the arena and the memory-pattern planner: see BackgroundRemover.
    private val options = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }

    private val session = environment.createSession(model.path, options)

    private val inputName = session.inputNames.first()

    override val size: Int = (session.inputInfo.getValue(inputName).info as TensorInfo).shape.last().toInt()

    override fun segment(input: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, size.toLong(), size.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // The first output is the finished mask; U²-Net's others are
                // its intermediate guesses.
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
