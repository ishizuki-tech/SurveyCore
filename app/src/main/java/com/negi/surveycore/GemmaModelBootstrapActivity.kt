package com.negi.surveycore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run bootstrap for the gated Gemma 3n E2B LiteRT-LM model.
 *
 * Nemotron is bundled in the APK. Gemma is downloaded once into private app
 * storage, then SurveyCore runs fully offline on subsequent launches.
 *
 * The Hugging Face token is used only in memory for the download and is never
 * persisted by SurveyCore.
 */
class GemmaModelBootstrapActivity : ComponentActivity() {

    private val activityScope =
        CoroutineScope(
            Job() + Dispatchers.Main
        )

    private var accessToken by
        mutableStateOf("")

    private var statusText by
        mutableStateOf("Checking Gemma model...")

    private var errorText by
        mutableStateOf<String?>(null)

    private var downloadedBytes by
        mutableLongStateOf(0L)

    private var downloadRunning by
        mutableStateOf(false)

    private var downloadJob: Job? = null

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        val modelFile =
            File(
                filesDir,
                MODEL_RELATIVE_PATH,
            )

        if (isModelReady(modelFile)) {
            openSurvey()
            return
        }

        val partialFile =
            File(
                modelFile.parentFile,
                "${modelFile.name}.part",
            )

        downloadedBytes =
            partialFile
                .takeIf { it.isFile }
                ?.length()
                ?: 0L

        statusText =
            if (downloadedBytes > 0L) {
                "Partial download found. Download can resume."
            } else {
                "Gemma 3n E2B must be downloaded once."
            }

        setContent {
            SurveyCoreTheme {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "SurveyCore setup",
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                    )

                    Text(
                        text =
                            "Nemotron 1120ms is already included in the APK. " +
                                "Gemma 3n E2B is downloaded once from the official " +
                                "Google repository on Hugging Face. After that, " +
                                "SurveyCore can run fully offline.",
                    )

                    Text(
                        text =
                            "Download size: ${formatBytes(EXPECTED_MODEL_SIZE)}\n" +
                                "Recommended free storage: at least 4.5 GB\n" +
                                "Recommended device memory: 8 GB or more",
                    )

                    Text(
                        text =
                            "Before downloading, accept the Gemma license on " +
                                "Hugging Face and use a read token that has access " +
                                "to google/gemma-3n-E2B-it-litert-lm.",
                    )

                    Button(
                        modifier =
                            Modifier.fillMaxWidth(),
                        onClick = {
                            openExternalUrl(
                                GEMMA_ACCESS_URL
                            )
                        },
                        enabled =
                            !downloadRunning,
                    ) {
                        Text(
                            "Open Gemma access page"
                        )
                    }

                    Button(
                        modifier =
                            Modifier.fillMaxWidth(),
                        onClick = {
                            openExternalUrl(
                                HF_TOKEN_URL
                            )
                        },
                        enabled =
                            !downloadRunning,
                    ) {
                        Text(
                            "Open Hugging Face tokens"
                        )
                    }

                    OutlinedTextField(
                        value =
                            accessToken,
                        onValueChange = {
                            accessToken =
                                it.trim()
                            errorText =
                                null
                        },
                        modifier =
                            Modifier.fillMaxWidth(),
                        enabled =
                            !downloadRunning,
                        singleLine =
                            true,
                        label = {
                            Text(
                                "Hugging Face read token"
                            )
                        },
                        visualTransformation =
                            PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    KeyboardType.Password,
                            ),
                    )

                    val percent =
                        (
                            downloadedBytes
                                .coerceIn(
                                    0L,
                                    EXPECTED_MODEL_SIZE,
                                ) *
                                100L
                            ) /
                            EXPECTED_MODEL_SIZE

                    Text(
                        text =
                            "$statusText\n" +
                                "${formatBytes(downloadedBytes)} / " +
                                "${formatBytes(EXPECTED_MODEL_SIZE)} " +
                                "($percent%)",
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
                    }

                    Button(
                        modifier =
                            Modifier.fillMaxWidth(),
                        enabled =
                            !downloadRunning &&
                                accessToken.isNotBlank(),
                        onClick = {
                            startDownload()
                        },
                    ) {
                        Text(
                            if (downloadedBytes > 0L) {
                                "Resume Gemma download"
                            } else {
                                "Download Gemma"
                            }
                        )
                    }

                    Text(
                        text =
                            "The Hugging Face token is not saved by SurveyCore.",
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        downloadJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startDownload() {
        if (downloadRunning) {
            return
        }

        val token =
            accessToken.trim()

        if (token.isEmpty()) {
            errorText =
                "Enter a Hugging Face read token."
            return
        }

        val modelFile =
            File(
                filesDir,
                MODEL_RELATIVE_PATH,
            )

        val partialFile =
            File(
                modelFile.parentFile,
                "${modelFile.name}.part",
            )

        modelFile
            .parentFile
            ?.mkdirs()

        val existingBytes =
            partialFile
                .takeIf { it.isFile }
                ?.length()
                ?: 0L

        val remainingBytes =
            (
                EXPECTED_MODEL_SIZE -
                    existingBytes
                )
                .coerceAtLeast(0L)

        val availableBytes =
            StatFs(
                filesDir.absolutePath
            ).availableBytes

        if (
            availableBytes <
            remainingBytes + FREE_SPACE_MARGIN_BYTES
        ) {
            errorText =
                "Not enough free storage. Available: " +
                    formatBytes(availableBytes) +
                    ". Need about " +
                    formatBytes(
                        remainingBytes +
                            FREE_SPACE_MARGIN_BYTES
                    ) +
                    "."
            return
        }

        downloadRunning =
            true
        errorText =
            null
        statusText =
            if (existingBytes > 0L) {
                "Resuming Gemma download..."
            } else {
                "Downloading Gemma..."
            }

        downloadJob =
            activityScope.launch {
                try {
                    withContext(
                        Dispatchers.IO
                    ) {
                        downloadModel(
                            token = token,
                            destination = modelFile,
                            partial = partialFile,
                        )
                    }

                    accessToken =
                        ""
                    downloadRunning =
                        false
                    statusText =
                        "Gemma ready. Starting SurveyCore..."

                    openSurvey()
                } catch (
                    cancellation: CancellationException
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
                }
            }
    }

    private suspend fun downloadModel(
        token: String,
        destination: File,
        partial: File,
    ) {
        destination
            .parentFile
            ?.mkdirs()

        if (
            partial.isFile &&
            partial.length() ==
            EXPECTED_MODEL_SIZE
        ) {
            finalizeDownload(
                partial = partial,
                destination = destination,
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
                .takeIf { it.isFile }
                ?.length()
                ?: 0L

        val connection =
            openDownloadConnection(
                token = token,
                rangeStart = existingBytes,
            )

        try {
            val responseCode =
                connection.responseCode

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    if (existingBytes > 0L) {
                        existingBytes =
                            0L
                        partial.delete()
                    }
                }

                HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange =
                        connection
                            .getHeaderField(
                                "Content-Range"
                            )
                            .orEmpty()

                    if (
                        existingBytes <= 0L ||
                        !contentRange.startsWith(
                            "bytes $existingBytes-"
                        )
                    ) {
                        throw IllegalStateException(
                            "Unexpected resume response from Hugging Face."
                        )
                    }
                }

                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    throw IllegalStateException(
                        "Hugging Face rejected the token. " +
                            "Check that it is a valid read token."
                    )
                }

                HttpURLConnection.HTTP_FORBIDDEN -> {
                    throw IllegalStateException(
                        "Access to Gemma is still gated for this account. " +
                            "Accept the Gemma license on Hugging Face first."
                    )
                }

                else -> {
                    throw IllegalStateException(
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
                    HttpURLConnection.HTTP_PARTIAL
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

                        while (true) {
                            kotlinx.coroutines
                                .currentCoroutineContext()
                                .ensureActive()

                            val count =
                                input.read(
                                    buffer
                                )

                            if (count < 0) {
                                break
                            }

                            if (count == 0) {
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

        if (
            partial.length() !=
            EXPECTED_MODEL_SIZE
        ) {
            throw IllegalStateException(
                "Downloaded Gemma file has the wrong size. " +
                    "Expected ${formatBytes(EXPECTED_MODEL_SIZE)}, " +
                    "got ${formatBytes(partial.length())}. " +
                    "The partial file was kept so the next attempt can resume."
            )
        }

        finalizeDownload(
            partial = partial,
            destination = destination,
        )
    }

    private fun openDownloadConnection(
        token: String,
        rangeStart: Long,
    ): HttpURLConnection {
        var currentUrl =
            URL(MODEL_DOWNLOAD_URL)

        repeat(
            MAX_REDIRECTS
        ) {
            val connection =
                currentUrl
                    .openConnection()
                    as HttpURLConnection

            connection.instanceFollowRedirects =
                false
            connection.requestMethod =
                "GET"
            connection.connectTimeout =
                CONNECT_TIMEOUT_MS
            connection.readTimeout =
                READ_TIMEOUT_MS
            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT,
            )
            connection.setRequestProperty(
                "Accept-Encoding",
                "identity",
            )

            if (
                currentUrl.host ==
                    "huggingface.co" ||
                currentUrl.host
                    .endsWith(
                        ".huggingface.co"
                    )
            ) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token",
                )
            }

            if (rangeStart > 0L) {
                connection.setRequestProperty(
                    "Range",
                    "bytes=$rangeStart-",
                )
            }

            connection.connect()

            when (
                connection.responseCode
            ) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                HTTP_TEMP_REDIRECT,
                HTTP_PERM_REDIRECT,
                -> {
                    val location =
                        connection.getHeaderField(
                            "Location"
                        )
                            ?: throw IllegalStateException(
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

        throw IllegalStateException(
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
            throw IllegalStateException(
                "Could not replace the existing Gemma model."
            )
        }

        if (
            !partial.renameTo(
                destination
            )
        ) {
            partial.copyTo(
                target = destination,
                overwrite = true,
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

    private fun isModelReady(
        file: File,
    ): Boolean =
        file.isFile &&
            file.length() ==
            EXPECTED_MODEL_SIZE

    private fun openSurvey() {
        startActivity(
            Intent(
                this,
                MainActivity::class.java,
            )
        )
        finish()
    }

    private fun openExternalUrl(
        url: String,
    ) {
        startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url),
            )
        )
    }

    private fun formatBytes(
        bytes: Long,
    ): String {
        val gib =
            bytes.toDouble() /
                (1024.0 * 1024.0 * 1024.0)

        return "%.2f GB".format(
            gib
        )
    }

    private companion object {
        const val MODEL_RELATIVE_PATH =
            "models/gemma-3n-E2B-it-int4.litertlm"

        const val MODEL_REPOSITORY =
            "google/gemma-3n-E2B-it-litert-lm"

        const val MODEL_REVISION =
            "ba9ca88da013b537b6ed38108be609b8db1c3a16"

        const val MODEL_FILE_NAME =
            "gemma-3n-E2B-it-int4.litertlm"

        const val EXPECTED_MODEL_SIZE =
            3_655_827_456L

        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/" +
                MODEL_REPOSITORY +
                "/resolve/" +
                MODEL_REVISION +
                "/" +
                MODEL_FILE_NAME +
                "?download=true"

        const val GEMMA_ACCESS_URL =
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm"

        const val HF_TOKEN_URL =
            "https://huggingface.co/settings/tokens"

        const val USER_AGENT =
            "SurveyCore/1.0 (Android)"

        const val FREE_SPACE_MARGIN_BYTES =
            512L * 1024L * 1024L

        const val DOWNLOAD_BUFFER_BYTES =
            1024 * 1024

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
