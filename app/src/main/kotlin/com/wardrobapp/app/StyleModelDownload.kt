package com.wardrobapp.app

import android.content.Context
import android.util.Log
import com.wardrobapp.data.DownloadCheck
import com.wardrobapp.data.acceptDownload
import com.wardrobapp.presentation.StyleModelState.Status
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the model is kept on the phone, under noBackupFilesDir. */
private const val MODEL_DIRECTORY = "models"
private const val MODEL_FILE = "style-image-vitb32-int8.onnx"

/**
 * The style model, fetched when somebody asks for it and kept until they
 * remove it.
 *
 * It used to be in the APK, and made the APK 229 MB -- more than any
 * installed copy of the app would download as an update, so nobody could
 * update at all. Now it is 85 MB that only the people who want the feature
 * spend, after a card has told them what it is and what it costs
 * (StyleModelCard). A phone paired with Home Assistant does not need it:
 * Home Assistant runs the same model and the sync brings its results here.
 *
 * The file comes from the release the build pins (BuildConfig.STYLE_MODEL_URL)
 * through the same host-checked door as the app's own updates, and is used
 * only once its size and SHA-256 are the ones pinned (acceptDownload in
 * :data). It is kept in noBackupFilesDir: a backup of the wardrobe should
 * not carry 85 MB anyone can download again, and a phone restored from one
 * offers the download afresh.
 *
 * The download runs in the app's scope, so leaving the screen that started
 * it does not stop it; Cancel does. It is never started by anything but a
 * tap.
 */
class StyleModelDownload(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Told when the model has arrived, so the wardrobe can be embedded straight away. */
    private val onReady: () -> Unit,
    /** Told before the model is deleted, so whatever has it open lets go first. */
    private val beforeRemove: () -> Unit,
) {
    private val directory = File(context.noBackupFilesDir, MODEL_DIRECTORY)
    private val target = File(directory, MODEL_FILE)
    private val partial = File(directory, "$MODEL_FILE.part")

    /** The size the card states, before anybody taps anything. */
    val bytes: Long = BuildConfig.STYLE_MODEL_BYTES

    private val _status = MutableStateFlow<Status>(if (target.isFile) Status.Ready else Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null

    /** The model on this phone, or null when it is not here. */
    fun file(): File? = target.takeIf { it.isFile }

    /** Start fetching the model, unless it is here already or on its way. */
    fun start() {
        if (job?.isActive == true || target.isFile) return
        _status.value = Status.Downloading(null)
        job = scope.launch(Dispatchers.IO) {
            try {
                download()
                _status.value = Status.Ready
                onReady()
            } catch (e: CancellationException) {
                partial.delete()
                _status.value = Status.Absent
                throw e
            } catch (e: IOException) {
                Log.w("StyleModel", "Downloading the style model failed: $e")
                partial.delete()
                _status.value = Status.Failed(e.message ?: context.getString(R.string.error_style_model_network))
            }
        }
    }

    /** Stop a download on its way; what had arrived is deleted. */
    fun cancel() {
        job?.cancel()
    }

    /** Delete the model, giving its space back. The vectors it made stay, and stay right. */
    fun remove() {
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            beforeRemove()
            target.delete()
            partial.delete()
            _status.value = Status.Absent
        }
    }

    private suspend fun download() {
        directory.mkdirs()
        val connection = requestTrusted(context, BuildConfig.STYLE_MODEL_URL)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(context.getString(R.string.error_update_refused, connection.responseCode))
            }
            val expected = BuildConfig.STYLE_MODEL_BYTES
            var written = 0L
            var shownPercent = -1
            try {
                connection.inputStream.use { source ->
                    partial.outputStream().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            written += read
                            // A file longer than the one pinned is not it; no
                            // reason to fill the phone finding out.
                            if (written > expected) throw NotTheModel(context.getString(R.string.error_style_model_corrupt))
                            sink.write(buffer, 0, read)
                            // Whole percents only: a flow updated every 64 KB
                            // would recompose the card 1,400 times for nothing.
                            val percent = (written * 100 / expected).toInt()
                            if (percent != shownPercent) {
                                shownPercent = percent
                                _status.value = Status.Downloading(percent / 100f)
                            }
                        }
                    }
                }
            } catch (e: NotTheModel) {
                throw e
            } catch (e: IOException) {
                // A dropped connection reads as an exception from deep in the
                // stack; what somebody can do about it is try again.
                throw IOException(context.getString(R.string.error_style_model_network), e)
            }
            when (acceptDownload(partial, expected, BuildConfig.STYLE_MODEL_SHA256, target)) {
                DownloadCheck.ACCEPTED -> Unit
                DownloadCheck.WRONG_SIZE -> throw IOException(context.getString(R.string.error_style_model_network))
                DownloadCheck.WRONG_DIGEST -> throw NotTheModel(context.getString(R.string.error_style_model_corrupt))
                DownloadCheck.NOT_MOVED -> throw IOException(context.getString(R.string.error_style_model_network))
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** What arrived is not the model pinned: said as such, not as a dropped connection. */
private class NotTheModel(message: String) : IOException(message)
