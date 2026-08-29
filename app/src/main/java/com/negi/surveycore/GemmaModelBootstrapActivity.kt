package com.negi.surveycore

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.negi.surveycore.internal.InternalHfTokenProvider
import com.negi.surveycore.ui.theme.SurveyCoreTheme
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads Gemma 3n E2B once on first launch.
 *
 * Nemotron is bundled inside the APK. Gemma is downloaded into private app
 * storage and all later SurveyCore sessions can run fully offline.
 */
class GemmaModelBootstrapActivity : ComponentActivity() {

    private val activityScope =
        CoroutineScope(
            Job() +
                Dispatchers.Main
        )

    private var statusText by
        mutableStateOf(
            "Preparing AI model..."
        )

    private var errorText by
        mutableStateOf<String?>(
            null
        )

    private var downloadedBytes by
        mutableLongStateOf(
            0L
        )

    private var downloadRunning by
        mutableStateOf(
            false
        )

    private var downloadJob: Job? =
        null

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState
        )

        val modelFile =
            File(
                filesDir,
                MODEL_RELATIVE_PATH,
            )

        if (
            isModelReady(
                modelFile
            )
        ) {
            openSurvey()
            return
        }

        val partialFile =
            partialFileFor(
                modelFile
            )

        downloadedBytes =
            partialFile
                .takeIf {
                    it.isFile
                }
                ?.length()
                ?: 0L

        setContent {
            SurveyCoreTheme {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(
                                24.dp
                            ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            16.dp
                        ),
                ) {
                    Text(
                        text =
                            "SurveyCore setup",
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                    )

                    Text(
                        text =
                            "Nemotron 1120ms is included in the APK. " +
                                "Gemma 3n E2B is downloaded once, then " +
                                "SurveyCore runs fully offline.",
                    )

                    Text(
                        text =
                            "Gemma: " +
                                formatBytes(
                                    EXPECTED_MODEL_SIZE
                                ) +
                                "\nRecommended free storage: at least 4.5 GB",
                    )

                    LinearProgressIndicator(
                        progress = {
                            progressFraction()
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                    )

                    Text(
                        text =
                            "$statusText\n" +
                                formatBytes(
                                    downloadedBytes
                                ) +
                                " / " +
                                formatBytes(
                                    EXPECTED_MODEL_SIZE
                                ) +
                                " (" +
                                progressPercent() +
                                "%)",
                    )

                    errorText?.let {
                            message ->

                        Text(
                            text =
                                message,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error,
                        )

                        Button(
                            modifier =
                                Modifier
                                    .fillMaxWidth(),
                            enabled =
                                !downloadRunning,
                            onClick = {
                                startDownload()
                            },
                        ) {
                            Text(
                                "Retry download"
                            )
                        }
                    }
                }
            }
        }

        startDownload()
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        activityScope.cancel()

        window.clearFlags(
            WindowManager
                .LayoutParams
                .FLAG_KEEP_SCREEN_ON
        )

        super.onDestroy()
    }

    private fun startDownload() {
        if (downloadRunning) {
            return
        }

        val modelFile =
            File(
                filesDir,
                MODEL_RELATIVE_PATH,
            )

        if (
            isModelReady(
                modelFile
            )
        ) {
            openSurvey()
            return
        }

        val partialFile =
            partialFileFor(
                modelFile
            )

        modelFile
            .parentFile
            ?.mkdirs()

        val existingBytes =
            partialFile
                .takeIf {
                    it.isFile
                }
                ?.length()
                ?: 0L

        val remainingBytes =
            (
                EXPECTED_MODEL_SIZE -
                    existingBytes
                )
                .coerceAtLeast(
                    0L
                )

        val availableBytes =
            StatFs(
                filesDir.absolutePath
            ).availableBytes

        if (
            availableBytes <
            remainingBytes +
                FREE_SPACE_MARGIN_BYTES
        ) {
            errorText =
                "Not enough free storage. Available: " +
                    formatBytes(
                        availableBytes
                    ) +
                    ". Need about " +
                    formatBytes(
                        remainingBytes +
                            FREE_SPACE_MARGIN_BYTES
                    ) +
                    "."
            return
        }

        window.addFlags(
            WindowManager
                .LayoutParams
                .FLAG_KEEP_SCREEN_ON
        )

        downloadRunning =
            true

        errorText =
            null

        statusText =
            if (
                existingBytes >
                0L
            ) {
                "Resuming Gemma download..."
            } else {
                "Downloading Gemma..."
            }

        downloadJob =
            activityScope.launch {
                var token: String? =
                    null

                try {
                    token =
                        withContext(
                            Dispatchers.Default
                        ) {
                            InternalHfTokenProvider
                                .decrypt(
                                    applicationContext
                                )
                        }

                    withContext(
                        Dispatchers.IO
                    ) {
                        downloadModel(
                            token =
                                token,
                            destination =
                                modelFile,
                            partial =
                                partialFile,
                        )
                    }

                    statusText =
                        "Gemma ready. Starting SurveyCore..."

                    openSurvey()
                } catch (
                    cancellation:
                    CancellationException
                ) {
                    throw cancellation
                } catch (
                    throwable: Throwable
                ) {
                    downloadRunning =
                        false

                    statusText =
                        "Gemma download stopped."

                    errorText =
                        throwable.message
                            ?: throwable::class.java.simpleName
                } finally {
                    token =
                        null

                    window.clearFlags(
                        WindowManager
                            .LayoutParams
                            .FLAG_KEEP_SCREEN_ON
                    )
                }
            }
    }

    private suspend fun downloadModel(
        token: String,
        destination: File,
        partial: File,
    ) {
        if (
            partial.isFile &&
            partial.length() ==
            EXPECTED_MODEL_SIZE
        ) {
            finalizeDownload(
                partial =
                    partial,
                destination =
                    destination,
            )

            return
        }

        if (
            partial.isFile &&
            partial.length() >
            EXPECTED_MODEL_SIZE
        ) {
            partial.delete()
        }

        var existingBytes =
            partial
                .takeIf {
                    it.isFile
                }
                ?.length()
                ?: 0L

        downloadedBytes =
            existingBytes

        val connection =
            openDownloadConnection(
                token =
                    token,
                rangeStart =
                    existingBytes,
            )

        try {
            val responseCode =
                connection
                    .responseCode

            when (
                responseCode
            ) {
                HttpURLConnection
                    .HTTP_OK -> {
                    if (
                        existingBytes >
                        0L
                    ) {
                        existingBytes =
                            0L

                        partial.delete()

                        runOnUiThread {
                            downloadedBytes =
                                0L
                        }
                    }
                }

                HttpURLConnection
                    .HTTP_PARTIAL -> {
                    val contentRange =
                        connection
                            .getHeaderField(
                                "Content-Range"
                            )
                            .orEmpty()

                    check(
                        existingBytes >
                            0L &&
                            contentRange
                                .startsWith(
                                    "bytes $existingBytes-"
                                )
                    ) {
                        "Unexpected resume response from Hugging Face."
                    }
                }

                HttpURLConnection
                    .HTTP_UNAUTHORIZED -> {
                    error(
                        "Embedded Hugging Face token was rejected."
                    )
                }

                HttpURLConnection
                    .HTTP_FORBIDDEN -> {
                    error(
                        "The Hugging Face account does not have access " +
                            "to the Gemma repository."
                    )
                }

                else -> {
                    error(
                        "Gemma download failed: HTTP $responseCode"
                    )
                }
            }

            RandomAccessFile(
                partial,
                "rw",
            ).use {
                    output ->

                if (
                    responseCode ==
                    HttpURLConnection
                        .HTTP_PARTIAL
                ) {
                    output.seek(
                        existingBytes
                    )
                } else {
                    output.setLength(
                        0L
                    )

                    existingBytes =
                        0L
                }

                connection
                    .inputStream
                    .buffered(
                        DOWNLOAD_BUFFER_BYTES
                    )
                    .use {
                            input ->

                        val buffer =
                            ByteArray(
                                DOWNLOAD_BUFFER_BYTES
                            )

                        var total =
                            existingBytes

                        var lastUiUpdate =
                            0L

                        while (
                            true
                        ) {
                            currentCoroutineContext()
                                .ensureActive()

                            val count =
                                input.read(
                                    buffer
                                )

                            if (
                                count <
                                0
                            ) {
                                break
                            }

                            if (
                                count ==
                                0
                            ) {
                                continue
                            }

                            output.write(
                                buffer,
                                0,
                                count,
                            )

                            total +=
                                count

                            val now =
                                System.nanoTime()

                            if (
                                now -
                                lastUiUpdate >=
                                UI_UPDATE_INTERVAL_NANOS
                            ) {
                                lastUiUpdate =
                                    now

                                runOnUiThread {
                                    downloadedBytes =
                                        total
                                }
                            }
                        }

                        output.fd.sync()

                        runOnUiThread {
                            downloadedBytes =
                                total
                        }
                    }
            }
        } finally {
            connection.disconnect()
        }

        check(
            partial.length() ==
                EXPECTED_MODEL_SIZE
        ) {
            "Downloaded Gemma file has the wrong size. " +
                "Expected " +
                formatBytes(
                    EXPECTED_MODEL_SIZE
                ) +
                ", got " +
                formatBytes(
                    partial.length()
                ) +
                ". The partial file was kept so retry can resume."
        }

        finalizeDownload(
            partial =
                partial,
            destination =
                destination,
        )
    }

    private fun openDownloadConnection(
        token: String,
        rangeStart: Long,
    ): HttpURLConnection {
        var currentUrl =
            URL(
                MODEL_DOWNLOAD_URL
            )

        repeat(
            MAX_REDIRECTS
        ) {
            val connection =
                currentUrl
                    .openConnection()
                    as HttpURLConnection

            connection
                .instanceFollowRedirects =
                false

            connection
                .requestMethod =
                "GET"

            connection
                .connectTimeout =
                CONNECT_TIMEOUT_MS

            connection
                .readTimeout =
                READ_TIMEOUT_MS

            connection
                .setRequestProperty(
                    "User-Agent",
                    USER_AGENT,
                )

            connection
                .setRequestProperty(
                    "Accept-Encoding",
                    "identity",
                )

            if (
                isHuggingFaceHost(
                    currentUrl.host
                )
            ) {
                connection
                    .setRequestProperty(
                        "Authorization",
                        "Bearer $token",
                    )
            }

            if (
                rangeStart >
                0L
            ) {
                connection
                    .setRequestProperty(
                        "Range",
                        "bytes=$rangeStart-",
                    )
            }

            connection.connect()

            when (
                connection
                    .responseCode
            ) {
                HttpURLConnection
                    .HTTP_MOVED_PERM,
                HttpURLConnection
                    .HTTP_MOVED_TEMP,
                HttpURLConnection
                    .HTTP_SEE_OTHER,
                HTTP_TEMP_REDIRECT,
                HTTP_PERM_REDIRECT,
                -> {
                    val location =
                        connection
                            .getHeaderField(
                                "Location"
                            )
                            ?: error(
                                "Hugging Face redirect is missing Location."
                            )

                    val nextUrl =
                        URL(
                            currentUrl,
                            location,
                        )

                    connection.disconnect()

                    currentUrl =
                        nextUrl
                }

                else -> {
                    return connection
                }
            }
        }

        error(
            "Too many redirects while downloading Gemma."
        )
    }

    private fun finalizeDownload(
        partial: File,
        destination: File,
    ) {
        check(
            partial.length() ==
                EXPECTED_MODEL_SIZE
        ) {
            "Gemma model size validation failed."
        }

        if (
            destination.exists() &&
            !destination.delete()
        ) {
            error(
                "Could not replace the existing Gemma model."
            )
        }

        if (
            !partial.renameTo(
                destination
            )
        ) {
            partial.copyTo(
                target =
                    destination,
                overwrite =
                    true,
            )

            partial.delete()
        }

        check(
            isModelReady(
                destination
            )
        ) {
            "Gemma model finalization failed."
        }
    }

    private fun partialFileFor(
        modelFile: File,
    ): File =
        File(
            modelFile.parentFile,
            "${modelFile.name}.part",
        )

    private fun isModelReady(
        file: File,
    ): Boolean =
        file.isFile &&
            file.length() ==
            EXPECTED_MODEL_SIZE

    private fun isHuggingFaceHost(
        host: String,
    ): Boolean =
        host ==
            "huggingface.co" ||
            host.endsWith(
                ".huggingface.co"
            )

    private fun openSurvey() {
        startActivity(
            Intent(
                this,
                MainActivity::class.java,
            )
        )

        finish()
    }

    private fun progressFraction():
        Float =
        (
            downloadedBytes
                .coerceIn(
                    0L,
                    EXPECTED_MODEL_SIZE,
                )
                .toDouble() /
                EXPECTED_MODEL_SIZE
                    .toDouble()
            )
            .toFloat()

    private fun progressPercent():
        Long =
        (
            downloadedBytes
                .coerceIn(
                    0L,
                    EXPECTED_MODEL_SIZE,
                ) *
                100L
            ) /
            EXPECTED_MODEL_SIZE

    private fun formatBytes(
        bytes: Long,
    ): String {
        val gib =
            bytes
                .toDouble() /
                (
                    1024.0 *
                        1024.0 *
                        1024.0
                    )

        return "%.2f GB"
            .format(
                gib
            )
    }

    private companion object {

        const val MODEL_RELATIVE_PATH =
            "models/gemma-3n-E2B-it-int4.litertlm"

        const val EXPECTED_MODEL_SIZE =
            3_655_827_456L

        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/" +
                "google/gemma-3n-E2B-it-litert-lm/" +
                "resolve/main/" +
                "gemma-3n-E2B-it-int4.litertlm" +
                "?download=true"

        const val USER_AGENT =
            "SurveyCore/1.0 (Android)"

        const val FREE_SPACE_MARGIN_BYTES =
            512L *
                1024L *
                1024L

        const val DOWNLOAD_BUFFER_BYTES =
            1024 *
                1024

        const val CONNECT_TIMEOUT_MS =
            30_000

        const val READ_TIMEOUT_MS =
            120_000

        const val MAX_REDIRECTS =
            10

        const val UI_UPDATE_INTERVAL_NANOS =
            250_000_000L

        const val HTTP_TEMP_REDIRECT =
            307

        const val HTTP_PERM_REDIRECT =
            308
    }
}
