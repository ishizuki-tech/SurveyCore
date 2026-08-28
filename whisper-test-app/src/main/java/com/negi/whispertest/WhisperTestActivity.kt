package com.negi.whispertest

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.negi.surveycore.asr.Pcm16WavDecoder
import com.negi.surveycore.asr.TranscriptionResult
import com.negi.surveycore.asr.whispercpp.WhisperCppBackend
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone app for testing whisper.cpp without SurveyCore or an SLM runtime.
 */
class WhisperTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var nativeView: TextView
    private lateinit var wavButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var recordButton: Button
    private lateinit var liveButton: Button
    private lateinit var sherpaButton: Button

    private var backend: WhisperCppBackend? = null
    private var livePartialBackend: WhisperCppBackend? = null
    private var recorder: MicrophoneRecorder? = null
    private var recordingJob: Job? = null
    private var liveController: LiveTranscriptionController? = null
    private var sherpaController: SherpaStreamingController? = null
    private var sherpaPreparedProfile: SherpaProfile? = null
    private var sherpaPreloadJob: Job? = null
    private var whisperInitializationStarted = false
    private var pendingMicrophoneAction: MicrophoneAction? = null
    private var backendReady = false
    private var livePartialBackendReady = false
    private var sherpaModelReady = false
    private var activeSherpaProfile: SherpaProfile? = null

    private val partialTranscriptMerger = LivePartialTranscriptMerger()
    private val finalLiveTranscript = StringBuilder()
    private var currentPartialTranscript: String = ""
    private var activeLiveUtteranceId: Long? = null
    private var liveStateLabel: String = "IDLE"
    private var liveRms: Float = 0.0f
    private var liveThreshold: Float = 0.0f
    private var liveInferenceLine: String = ""

    private val sherpaFinalTranscript = StringBuilder()
    private var sherpaPartialTranscript: String = ""
    private var sherpaRms: Float = 0.0f
    private var sherpaSpeechThreshold: Float = 0.0f
    private var sherpaFirstPartialLatencyMs: Long? = null
    private var sherpaHadError: Boolean = false
    private var sherpaAudioChunkMs: Int = 0
    private var sherpaThreadCount: Int = 0
    private var sherpaEndpointTrailingSilenceSeconds: Float = 0.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContentView(buildContentView())
        initializeSherpaStreamingAvailability()
    }

    override fun onDestroy() {
        sherpaPreloadJob?.cancel()
        sherpaPreloadJob = null
        sherpaController?.close()
        sherpaController = null
        liveController?.close()
        liveController = null
        recorder?.requestStop()
        recorder = null
        recordingJob?.cancel()
        recordingJob = null
        livePartialBackend?.close()
        livePartialBackend = null
        backend?.close()
        backend = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != MICROPHONE_PERMISSION_REQUEST) {
            return
        }

        val action = pendingMicrophoneAction
        pendingMicrophoneAction = null

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                MicrophoneAction.BATCH -> startRecording()
                MicrophoneAction.LIVE -> startLiveTranscription()
                MicrophoneAction.SHERPA -> startSherpaStreaming(SherpaProfile.NEMOTRON_1120)
                null -> Unit
            }
        } else {
            setStatus("Microphone permission denied.")
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.decorView.setBackgroundColor(COLOR_PAGE_BACKGROUND)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)

            val appearance =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

            window.insetsController?.setSystemBarsAppearance(
                appearance,
                appearance,
            )
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun buildContentView(): ScrollView {
        val pagePadding = dp(20)
        val sectionGap = dp(18)
        val itemGap = dp(10)

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pagePadding, dp(22), pagePadding, dp(32))
                setBackgroundColor(COLOR_PAGE_BACKGROUND)
            }

        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }

        val titleView =
            TextView(this).apply {
                text = "ASR Playground"
                textSize = 30f
                setTextColor(COLOR_TEXT_PRIMARY)
                setTypeface(typeface, Typeface.BOLD)
            }

        val subtitleView =
            TextView(this).apply {
                text = "Offline speech recognition lab"
                textSize = 15f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), 0, dp(12))
            }

        val capabilityRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

        capabilityRow.addView(makeChip("OFFLINE"))
        capabilityRow.addView(makeChip("ENGLISH").withStartMargin(dp(8)))
        capabilityRow.addView(makeChip("16 kHz").withStartMargin(dp(8)))

        header.addView(titleView)
        header.addView(subtitleView)
        header.addView(capabilityRow)

        statusView =
            TextView(this).apply {
                text = "Preparing ASR runtimes..."
                textSize = 16f
                setTextColor(COLOR_TEXT_PRIMARY)
                setLineSpacing(0f, 1.12f)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background =
                    roundedBackground(
                        color = COLOR_STATUS_BACKGROUND,
                        radiusDp = 18,
                        strokeColor = COLOR_STATUS_BORDER,
                    )
            }

        wavButton =
            Button(this).apply {
                text = "Run bundled WAV test"
                isEnabled = false
                setOnClickListener { runBundledWavTest() }
                styleActionButton(primary = false)
            }

        benchmarkButton =
            Button(this).apply {
                text = "Run 5× benchmark"
                isEnabled = false
                setOnClickListener { runBundledWavBenchmark() }
                styleActionButton(primary = false)
            }

        recordButton =
            Button(this).apply {
                text = "Start microphone"
                isEnabled = false
                setOnClickListener {
                    if (recordingJob == null) {
                        ensureMicrophonePermissionAndStart()
                    } else {
                        stopRecording()
                    }
                }
                styleActionButton(primary = false)
            }

        liveButton =
            Button(this).apply {
                text = "Start Whisper live transcription"
                isEnabled = false
                setOnClickListener {
                    if (liveController == null) {
                        ensureLiveMicrophonePermissionAndStart()
                    } else {
                        stopLiveTranscription()
                    }
                }
                styleActionButton(primary = false)
            }

        sherpaButton =
            Button(this).apply {
                text = SherpaProfile.NEMOTRON_1120.startButtonText
                isEnabled = false
                setOnClickListener {
                    if (activeSherpaProfile == null) {
                        ensureSherpaMicrophonePermissionAndStart()
                    } else {
                        stopSherpaStreaming()
                    }
                }
                styleActionButton(primary = true)
            }

        transcriptView =
            TextView(this).apply {
                text = "Transcript will appear here."
                textSize = 20f
                setTextColor(COLOR_TEXT_PRIMARY)
                setLineSpacing(0f, 1.15f)
                minHeight = dp(140)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background =
                    roundedBackground(
                        color = COLOR_CARD_BACKGROUND,
                        radiusDp = 18,
                        strokeColor = COLOR_CARD_BORDER,
                    )
            }

        nativeView =
            TextView(this).apply {
                text = "Runtime information will appear here."
                textSize = 12f
                setTextColor(COLOR_TEXT_TERTIARY)
                setTypeface(Typeface.MONOSPACE)
                setLineSpacing(0f, 1.08f)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background =
                    roundedBackground(
                        color = COLOR_RUNTIME_BACKGROUND,
                        radiusDp = 14,
                    )
            }

        container.addView(header, matchWidthParams())

        addVerticalSpace(container, sectionGap)
        container.addView(makeSectionLabel("STATUS"), matchWidthParams())
        addVerticalSpace(container, dp(8))
        container.addView(statusView, matchWidthParams())

        addVerticalSpace(container, sectionGap)
        container.addView(makeSectionLabel("STREAMING"), matchWidthParams())
        addVerticalSpace(container, dp(8))
        container.addView(sherpaButton, matchWidthParams())

        addVerticalSpace(container, sectionGap)
        container.addView(makeSectionLabel("WHISPER TOOLS"), matchWidthParams())
        addVerticalSpace(container, dp(8))
        container.addView(recordButton, matchWidthParams())
        addVerticalSpace(container, itemGap)
        container.addView(liveButton, matchWidthParams())
        addVerticalSpace(container, itemGap)
        container.addView(wavButton, matchWidthParams())
        addVerticalSpace(container, itemGap)
        container.addView(benchmarkButton, matchWidthParams())

        addVerticalSpace(container, sectionGap)
        container.addView(makeSectionLabel("TRANSCRIPT"), matchWidthParams())
        addVerticalSpace(container, dp(8))
        container.addView(transcriptView, matchWidthParams())

        addVerticalSpace(container, sectionGap)
        container.addView(makeSectionLabel("RUNTIME & MODELS"), matchWidthParams())
        addVerticalSpace(container, dp(8))
        container.addView(nativeView, matchWidthParams())

        return ScrollView(this).apply {
            setBackgroundColor(COLOR_PAGE_BACKGROUND)
            isFillViewport = true
            clipToPadding = false
            addView(container)

            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val systemBars =
                        insets.getInsets(WindowInsets.Type.systemBars())
                    topInset = systemBars.top
                    bottomInset = systemBars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    topInset = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottomInset = insets.systemWindowInsetBottom
                }

                view.setPadding(
                    0,
                    topInset,
                    0,
                    bottomInset,
                )
                insets
            }
        }
    }

    private fun makeSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(COLOR_TEXT_TERTIARY)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.08f
        }

    private fun makeChip(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(COLOR_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background =
                roundedBackground(
                    color = COLOR_CHIP_BACKGROUND,
                    radiusDp = 999,
                )
        }

    private fun View.withStartMargin(margin: Int): View =
        apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = margin
                }
        }

    private fun Button.styleActionButton(primary: Boolean) {
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(56)
        setPadding(dp(16), dp(10), dp(16), dp(10))

        if (primary) {
            backgroundTintList =
                ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        COLOR_BUTTON_DISABLED,
                        COLOR_ACCENT,
                    ),
                )
            setTextColor(Color.WHITE)
        } else {
            backgroundTintList =
                ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_enabled),
                        intArrayOf(),
                    ),
                    intArrayOf(
                        COLOR_BUTTON_DISABLED,
                        COLOR_BUTTON_SECONDARY,
                    ),
                )
            setTextColor(COLOR_TEXT_PRIMARY)
        }
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun matchWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun addVerticalSpace(
        container: LinearLayout,
        height: Int,
    ) {
        container.addView(
            View(this),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height,
            ),
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun initializeSherpaStreamingAvailability() {
        sherpaModelReady = isSherpaModelReady(SherpaProfile.NEMOTRON_1120)

        resetSherpaButtonLabel()
        setSherpaButtonEnabled(true)
        renderRuntimeInfo()

        if (sherpaModelReady) {
            preloadPreferredSherpaProfile()
        } else {
            initializeBackend()
        }
    }

    private fun preloadPreferredSherpaProfile() {
        val profile = SherpaProfile.NEMOTRON_1120
        if (!sherpaModelReady) {
            return
        }

        sherpaPreloadJob?.cancel()

        val controller = createSherpaController(profile)
        sherpaController?.close()
        sherpaController = controller
        sherpaPreparedProfile = profile

        sherpaAudioChunkMs = controller.audioChunkMs
        sherpaThreadCount = controller.threadCount
        sherpaEndpointTrailingSilenceSeconds =
            controller.endpointTrailingSilenceSeconds

        sherpaButton.text = "Preparing Nemotron 1120ms..."
        sherpaButton.isEnabled = false
        setStatus(
            "PREPARING NEMOTRON\n" +
                "model: ${profile.displayName}\n" +
                "${controller.audioChunkMs} ms stateful chunks | CPU | " +
                "${controller.threadCount} threads\n" +
                "warming decoder before first microphone session"
        )

        sherpaPreloadJob =
            scope.launch {
                try {
                    val preloadStartNs = System.nanoTime()

                    withContext(Dispatchers.IO) {
                        controller.preload()
                    }

                    val preloadSeconds = elapsedSeconds(preloadStartNs)

                    if (
                        sherpaController === controller &&
                        activeSherpaProfile == null
                    ) {
                        sherpaButton.text = profile.startButtonText
                        sherpaButton.isEnabled = true
                        renderRuntimeInfo()
                        setStatus(
                            "NEMOTRON READY\n" +
                                "model: ${profile.displayName}\n" +
                                "${controller.audioChunkMs} ms stateful chunks | CPU | " +
                                "${controller.threadCount} threads\n" +
                                "preload + warm-up: ${formatSeconds(preloadSeconds)} sec\n" +
                                "initializing Whisper tools in background..."
                        )
                        Log.d(
                            LOG_TAG,
                            "Nemotron preload + warm-up ready in " +
                                "${formatSeconds(preloadSeconds)} sec; " +
                                "model=${profile.displayName}; threads=${controller.threadCount}",
                        )
                    }
                } catch (throwable: Throwable) {
                    Log.e(LOG_TAG, "Nemotron preload failed.", throwable)

                    if (sherpaController === controller) {
                        controller.close()
                        sherpaController = null
                        sherpaPreparedProfile = null
                        sherpaButton.text = "Nemotron preload failed"
                        sherpaButton.isEnabled = false
                    }
                } finally {
                    sherpaPreloadJob = null
                    initializeBackend()
                }
            }
    }

    private fun createSherpaController(
        profile: SherpaProfile,
    ): SherpaStreamingController =
        SherpaStreamingController(
            context = this,
            modelDir = File(filesDir, profile.modelRelativePath),
            modelSpec = profile.modelSpec,
        )

    private fun isSherpaModelReady(profile: SherpaProfile): Boolean {
        val modelDir = File(filesDir, profile.modelRelativePath)
        return profile.modelSpec.requiredFiles.all { name ->
            File(modelDir, name).let { file ->
                file.isFile && file.length() > 0L
            }
        }
    }

    private fun setSherpaButtonEnabled(enabled: Boolean) {
        sherpaButton.isEnabled =
            enabled &&
                sherpaModelReady &&
                (
                    sherpaPreloadJob == null ||
                        sherpaPreparedProfile != SherpaProfile.NEMOTRON_1120
                )
    }

    private fun resetSherpaButtonLabel() {
        sherpaButton.text =
            if (sherpaModelReady) {
                SherpaProfile.NEMOTRON_1120.startButtonText
            } else {
                "Nemotron 1120ms model not installed"
            }
    }

    private fun initializeBackend() {
        if (whisperInitializationStarted) {
            return
        }
        whisperInitializationStarted = true

        scope.launch {
            val finalModelFile = File(filesDir, FINAL_MODEL_RELATIVE_PATH)
            val partialModelFile = File(filesDir, LIVE_PARTIAL_MODEL_RELATIVE_PATH)

            if (!finalModelFile.isFile) {
                setStatus(
                    "FINAL MODEL NOT FOUND\n${finalModelFile.absolutePath}\n\n" +
                        "Install the model with:\n" +
                        "./scripts/install_whisper_base_en_test_app.sh"
                )
                return@launch
            }

            try {
                val finalRuntimeBackend =
                    WhisperCppBackend(
                        modelPath = finalModelFile.absolutePath,
                        threadCount = THREAD_COUNT,
                    )

                backend = finalRuntimeBackend

                val finalInitializeStart = System.nanoTime()
                withContext(Dispatchers.IO) {
                    finalRuntimeBackend.initialize()
                }
                val finalInitializeSeconds = elapsedSeconds(finalInitializeStart)

                backendReady = true
                wavButton.isEnabled = true
                benchmarkButton.isEnabled = true
                recordButton.isEnabled = true

                if (partialModelFile.isFile) {
                    val partialRuntimeBackend =
                        WhisperCppBackend(
                            modelPath = partialModelFile.absolutePath,
                            threadCount = THREAD_COUNT,
                        )

                    livePartialBackend = partialRuntimeBackend

                    val partialInitializeStart = System.nanoTime()
                    withContext(Dispatchers.IO) {
                        partialRuntimeBackend.initialize()
                    }
                    val partialInitializeSeconds = elapsedSeconds(partialInitializeStart)

                    livePartialBackendReady = true
                    liveButton.isEnabled = true

                    setStatus(
                        "READY\n" +
                            "live partial: ${partialModelFile.name} " +
                            "(${formatSeconds(partialInitializeSeconds)} sec init)\n" +
                            "final/batch: ${finalModelFile.name} " +
                            "(${formatSeconds(finalInitializeSeconds)} sec init)"
                    )
                } else {
                    livePartialBackendReady = false
                    liveButton.isEnabled = false

                    setStatus(
                        "READY FOR BATCH / FINAL\n" +
                            "model: ${finalModelFile.name}\n" +
                            "initialize: ${formatSeconds(finalInitializeSeconds)} sec\n\n" +
                            "LIVE PARTIAL MODEL NOT FOUND\n" +
                            "${partialModelFile.absolutePath}\n" +
                            "Install with:\n" +
                            "./scripts/install_whisper_tiny_en_test_app.sh"
                    )
                }

                renderRuntimeInfo()
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Whisper initialization failed.", throwable)
                showError("Initialization error", throwable)
            }
        }
    }

    private fun runBundledWavTest() {
        if (!backendReady || recordingJob != null || liveController != null || activeSherpaProfile != null) {
            return
        }

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        liveButton.isEnabled = false
        setSherpaButtonEnabled(false)
        transcriptView.text = "Running bundled WAV..."
        setStatus("TRANSCRIBING BUNDLED WAV")

        scope.launch {
            try {
                val decoded =
                    withContext(Dispatchers.IO) {
                        assets.open(BUNDLED_WAV_ASSET).use { input ->
                            Pcm16WavDecoder.decode(input)
                        }
                    }

                val result =
                    requireNotNull(backend).transcribe(
                        samples = decoded.samples,
                        sampleRateHz = decoded.sampleRateHz,
                    )

                showResult("BUNDLED WAV READY", result)
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Bundled WAV test failed.", throwable)
                showError("Bundled WAV error", throwable)
            } finally {
                wavButton.isEnabled = backendReady
                benchmarkButton.isEnabled = backendReady
                recordButton.isEnabled = backendReady
                liveButton.isEnabled = backendReady && livePartialBackendReady
                setSherpaButtonEnabled(true)
            }
        }
    }

    private fun runBundledWavBenchmark() {
        if (!backendReady || recordingJob != null || liveController != null || activeSherpaProfile != null) {
            return
        }

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        liveButton.isEnabled = false
        setSherpaButtonEnabled(false)
        transcriptView.text = "Benchmark running..."

        scope.launch {
            try {
                val decoded =
                    withContext(Dispatchers.IO) {
                        assets.open(BUNDLED_WAV_ASSET).use { input ->
                            Pcm16WavDecoder.decode(input)
                        }
                    }

                val results =
                    ArrayList<TranscriptionResult>(BENCHMARK_RUN_COUNT)

                repeat(BENCHMARK_RUN_COUNT) { index ->
                    setStatus(
                        "BENCHMARK RUN ${index + 1}/$BENCHMARK_RUN_COUNT\n" +
                            "audio: ${formatSeconds(decoded.samples.size.toDouble() / decoded.sampleRateHz)} sec\n" +
                            "threads: $THREAD_COUNT"
                    )

                    val result =
                        requireNotNull(backend).transcribe(
                            samples = decoded.samples,
                            sampleRateHz = decoded.sampleRateHz,
                        )

                    results += result
                }

                showBenchmarkResult(results)
            } catch (throwable: Throwable) {
                Log.e(LOG_TAG, "Bundled WAV benchmark failed.", throwable)
                showError("Benchmark error", throwable)
            } finally {
                wavButton.isEnabled = backendReady
                benchmarkButton.isEnabled = backendReady
                recordButton.isEnabled = backendReady
                liveButton.isEnabled = backendReady && livePartialBackendReady
                setSherpaButtonEnabled(true)
            }
        }
    }

    private fun showBenchmarkResult(results: List<TranscriptionResult>) {
        check(results.isNotEmpty()) {
            "Benchmark produced no results."
        }

        val inferenceTimesMs = results.map { it.inferenceDurationMs }
        val averageInferenceMs = inferenceTimesMs.average()
        val minimumInferenceMs = inferenceTimesMs.min()
        val maximumInferenceMs = inferenceTimesMs.max()
        val averageRtf = results.map { it.realtimeFactor }.average()
        val referenceTranscript = results.first().text
        val transcriptStable = results.all { it.text == referenceTranscript }

        val runLines =
            results.mapIndexed { index, result ->
                "${index + 1}: ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec" +
                    "  RTF ${formatRtf(result.realtimeFactor)}"
            }

        transcriptView.text =
            buildString {
                append(referenceTranscript.ifBlank { "(No speech recognized)" })
                append("\n\nTranscript stable: ")
                append(if (transcriptStable) "YES" else "NO")
            }

        setStatus(
            buildString {
                append("BUNDLED WAV BENCHMARK READY\n")
                append("audio: ${formatSeconds(results.first().audioDurationMs / 1000.0)} sec\n")
                append("runs: ${results.size}\n")
                append("threads: $THREAD_COUNT\n\n")
                append(runLines.joinToString("\n"))
                append("\n\n")
                append("average: ${formatSeconds(averageInferenceMs / 1000.0)} sec\n")
                append("min: ${formatSeconds(minimumInferenceMs / 1000.0)} sec\n")
                append("max: ${formatSeconds(maximumInferenceMs / 1000.0)} sec\n")
                append("average RTF: ${formatRtf(averageRtf)}")
            }
        )
    }

    private fun ensureMicrophonePermissionAndStart() {
        ensureMicrophonePermission(MicrophoneAction.BATCH)
    }

    private fun ensureLiveMicrophonePermissionAndStart() {
        ensureMicrophonePermission(MicrophoneAction.LIVE)
    }

    private fun ensureSherpaMicrophonePermissionAndStart() {
        ensureMicrophonePermission(MicrophoneAction.SHERPA)
    }

    private fun ensureMicrophonePermission(action: MicrophoneAction) {
        when (action) {
            MicrophoneAction.SHERPA -> {
                if (!sherpaModelReady) return
            }

            else -> {
                if (!backendReady) return
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                MicrophoneAction.BATCH -> startRecording()
                MicrophoneAction.LIVE -> startLiveTranscription()
                MicrophoneAction.SHERPA -> startSherpaStreaming(SherpaProfile.NEMOTRON_1120)
            }
            return
        }

        pendingMicrophoneAction = action
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            MICROPHONE_PERMISSION_REQUEST,
        )
    }

    private fun startRecording() {
        if (recordingJob != null || liveController != null || activeSherpaProfile != null || !backendReady) {
            return
        }

        val microphoneRecorder =
            MicrophoneRecorder(
                sampleRateHz = REQUIRED_SAMPLE_RATE_HZ,
            )

        recorder = microphoneRecorder
        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        liveButton.isEnabled = false
        setSherpaButtonEnabled(false)
        recordButton.text = "Stop and transcribe"
        transcriptView.text = "Listening..."
        setStatus("RECORDING\n16 kHz mono PCM16\nMaximum $MAX_RECORDING_SECONDS sec")

        val job =
            scope.launch {
                try {
                    val recording =
                        withContext(Dispatchers.IO) {
                            microphoneRecorder.recordBlocking(
                                maxDurationSeconds = MAX_RECORDING_SECONDS,
                            )
                        }

                    recorder = null
                    recordButton.text = "Start microphone"
                    recordButton.isEnabled = false

                    val result =
                        requireNotNull(backend).transcribe(
                            samples = recording.samples,
                            sampleRateHz = recording.sampleRateHz,
                        )

                    showResult("MICROPHONE READY", result)
                } catch (throwable: Throwable) {
                    Log.e(LOG_TAG, "Microphone test failed.", throwable)
                    showError("Microphone error", throwable)
                } finally {
                    recorder = null
                    recordingJob = null
                    recordButton.text = "Start microphone"
                    recordButton.isEnabled = backendReady
                    wavButton.isEnabled = backendReady
                    benchmarkButton.isEnabled = backendReady
                    liveButton.isEnabled = backendReady && livePartialBackendReady
                    setSherpaButtonEnabled(true)
                }
            }

        recordingJob = job
    }

    private fun startLiveTranscription() {
        if (
            liveController != null ||
            activeSherpaProfile != null ||
            recordingJob != null ||
            !backendReady ||
            !livePartialBackendReady
        ) {
            return
        }

        val finalRuntimeBackend =
            checkNotNull(backend) {
                "Final Whisper backend is unavailable."
            }

        val partialRuntimeBackend =
            checkNotNull(livePartialBackend) {
                "Live partial Whisper backend is unavailable."
            }

        finalLiveTranscript.clear()
        partialTranscriptMerger.reset()
        currentPartialTranscript = ""
        activeLiveUtteranceId = null
        liveStateLabel = "LIVE LISTENING"
        liveRms = 0.0f
        liveThreshold = 0.0f
        liveInferenceLine = ""
        transcriptView.text = "Listening for speech..."

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        setSherpaButtonEnabled(false)
        liveButton.text = "Stop Whisper live transcription"
        liveButton.isEnabled = true

        val controller =
            LiveTranscriptionController(
                partialBackend = partialRuntimeBackend,
                finalBackend = finalRuntimeBackend,
                scope = scope,
                listener =
                    object : LiveTranscriptionController.Listener {
                        override fun onListening() {
                            runOnUiThread {
                                liveStateLabel = "LIVE LISTENING"
                                renderLiveStatus()
                            }
                        }

                        override fun onVoiceLevel(
                            rms: Float,
                            threshold: Float,
                            speechActive: Boolean,
                        ) {
                            runOnUiThread {
                                liveRms = rms
                                liveThreshold = threshold
                                if (!speechActive && liveStateLabel != "FINAL READY") {
                                    liveStateLabel = "LIVE LISTENING"
                                }
                                renderLiveStatus()
                            }
                        }

                        override fun onSpeechStarted(utteranceId: Long) {
                            runOnUiThread {
                                activeLiveUtteranceId = utteranceId
                                partialTranscriptMerger.startUtterance(utteranceId)
                                currentPartialTranscript = ""
                                liveStateLabel = "SPEECH DETECTED #$utteranceId"
                                liveInferenceLine = "capturing 16 kHz mono PCM16"
                                renderLiveTranscript()
                                renderLiveStatus()
                            }
                        }

                        override fun onSpeechEnded(utteranceId: Long) {
                            runOnUiThread {
                                liveStateLabel = "FINALIZING #$utteranceId"
                                renderLiveStatus()
                            }
                        }

                        override fun onPartial(
                            utteranceId: Long,
                            result: TranscriptionResult,
                        ) {
                            runOnUiThread {
                                if (activeLiveUtteranceId == utteranceId) {
                                    currentPartialTranscript =
                                        partialTranscriptMerger.mergePartial(
                                            utteranceId = utteranceId,
                                            incomingText = result.text,
                                        )
                                    liveStateLabel = "PARTIAL #$utteranceId"
                                    liveInferenceLine =
                                        "audio ${formatSeconds(result.audioDurationMs / 1000.0)} sec | " +
                                            "inference ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec | " +
                                            "RTF ${formatRtf(result.realtimeFactor)}"
                                    renderLiveTranscript()
                                    renderLiveStatus()
                                } else {
                                    Log.d(
                                        LOG_TAG,
                                        "Ignoring stale partial for utterance=$utteranceId; " +
                                            "active=$activeLiveUtteranceId",
                                    )
                                }
                            }
                        }

                        override fun onFinal(
                            utteranceId: Long,
                            result: TranscriptionResult,
                        ) {
                            runOnUiThread {
                                val text =
                                    partialTranscriptMerger.finalizeUtterance(
                                        utteranceId = utteranceId,
                                        finalText = result.text,
                                    )
                                if (text.isNotEmpty()) {
                                    if (finalLiveTranscript.isNotEmpty()) {
                                        finalLiveTranscript.append('\n')
                                    }
                                    finalLiveTranscript.append(text)
                                }

                                if (activeLiveUtteranceId == utteranceId) {
                                    currentPartialTranscript = ""
                                    activeLiveUtteranceId = null
                                }

                                liveStateLabel = "FINAL READY #$utteranceId — LISTENING"
                                liveInferenceLine =
                                    "audio ${formatSeconds(result.audioDurationMs / 1000.0)} sec | " +
                                        "inference ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec | " +
                                        "RTF ${formatRtf(result.realtimeFactor)}"
                                renderLiveTranscript()
                                renderLiveStatus()

                                Log.d(
                                    LOG_TAG,
                                    "liveFinal utterance=$utteranceId; " +
                                        "audioMs=${result.audioDurationMs}; " +
                                        "inferenceMs=${result.inferenceDurationMs}; " +
                                        "rtf=${formatRtf(result.realtimeFactor)}; " +
                                        "text='${result.text.replace("\n", "\\n")}'",
                                )
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            Log.e(LOG_TAG, "Live transcription error.", throwable)
                            runOnUiThread {
                                liveStateLabel = "LIVE ERROR"
                                liveInferenceLine =
                                    "${throwable::class.java.simpleName}: " +
                                        (throwable.message ?: "Unknown error")
                                renderLiveStatus()
                            }
                        }

                        override fun onStopped() {
                            runOnUiThread {
                                liveController = null
                                activeLiveUtteranceId = null
                                partialTranscriptMerger.reset()
                                liveStateLabel = "LIVE STOPPED"
                                liveInferenceLine = ""
                                liveButton.text = "Start Whisper live transcription"
                                liveButton.isEnabled = backendReady && livePartialBackendReady
                                setSherpaButtonEnabled(true)
                                wavButton.isEnabled = backendReady
                                benchmarkButton.isEnabled = backendReady
                                recordButton.isEnabled = backendReady
                                renderLiveTranscript()
                                renderLiveStatus()
                            }
                        }
                    },
            )

        liveController = controller
        renderLiveStatus()
        controller.start()
    }

    private fun startSherpaStreaming(profile: SherpaProfile) {
        if (
            activeSherpaProfile != null ||
            liveController != null ||
            recordingJob != null ||
            !isSherpaModelReady(profile)
        ) {
            return
        }

        sherpaFinalTranscript.clear()
        sherpaPartialTranscript = ""
        sherpaRms = 0.0f
        sherpaSpeechThreshold = 0.0f
        sherpaFirstPartialLatencyMs = null
        sherpaHadError = false
        activeSherpaProfile = profile

        wavButton.isEnabled = false
        benchmarkButton.isEnabled = false
        recordButton.isEnabled = false
        liveButton.isEnabled = false
        setSherpaButtonEnabled(false)
        sherpaButton.apply {
            text = profile.stopButtonText
            isEnabled = true
        }
        transcriptView.text = "Listening..."
        renderSherpaStatus("NEMOTRON STARTING", profile)

        val controller =
            if (
                sherpaController != null &&
                sherpaPreparedProfile == profile
            ) {
                checkNotNull(sherpaController)
            } else {
                sherpaPreloadJob?.cancel()
                sherpaPreloadJob = null
                sherpaController?.close()

                createSherpaController(profile).also { created ->
                    sherpaController = created
                    sherpaPreparedProfile = profile
                }
            }

        controller.setListener(
            object : SherpaStreamingController.Listener {
                        override fun onStarted() {
                            runOnUiThread {
                                renderSherpaStatus("NEMOTRON STREAMING", profile)
                            }
                        }

                        override fun onAudioLevel(
                            rms: Float,
                            speechThreshold: Float,
                        ) {
                            runOnUiThread {
                                sherpaRms = rms
                                sherpaSpeechThreshold = speechThreshold
                                renderSherpaStatus("NEMOTRON STREAMING", profile)
                            }
                        }

                        override fun onPartial(
                            text: String,
                            firstPartialLatencyMs: Long?,
                        ) {
                            runOnUiThread {
                                sherpaPartialTranscript = normalizeSherpaDisplayText(text)
                                if (firstPartialLatencyMs != null) {
                                    sherpaFirstPartialLatencyMs = firstPartialLatencyMs
                                }
                                renderSherpaTranscript()
                                renderSherpaStatus("NEMOTRON STREAMING", profile)
                            }
                        }

                        override fun onFinal(text: String) {
                            runOnUiThread {
                                if (text.isNotBlank()) {
                                    if (sherpaFinalTranscript.isNotEmpty()) {
                                        sherpaFinalTranscript.append('\n')
                                    }
                                    sherpaFinalTranscript.append(normalizeSherpaDisplayText(text))
                                }

                                sherpaPartialTranscript = ""
                                sherpaFirstPartialLatencyMs = null
                                renderSherpaTranscript()
                                renderSherpaStatus("NEMOTRON ENDPOINT — LISTENING", profile)
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            Log.e(LOG_TAG, "Sherpa streaming error.", throwable)
                            runOnUiThread {
                                sherpaHadError = true
                                showError("Sherpa streaming error", throwable)
                            }
                        }

                        override fun onStopped() {
                            runOnUiThread {
                                activeSherpaProfile = null
                                sherpaPartialTranscript = ""
                                resetSherpaButtonLabel()
                                setSherpaButtonEnabled(true)
                                wavButton.isEnabled = backendReady
                                benchmarkButton.isEnabled = backendReady
                                recordButton.isEnabled = backendReady
                                liveButton.isEnabled = backendReady && livePartialBackendReady
                                if (!sherpaHadError) {
                                    renderSherpaStatus("NEMOTRON STOPPED", profile)
                                }
                            }
                        }
                    },
        )

        sherpaAudioChunkMs = controller.audioChunkMs
        sherpaThreadCount = controller.threadCount
        sherpaEndpointTrailingSilenceSeconds =
            controller.endpointTrailingSilenceSeconds
        sherpaController = controller

        try {
            controller.start()
        } catch (throwable: Throwable) {
            controller.close()
            sherpaController = null
            sherpaPreparedProfile = null
            activeSherpaProfile = null
            Log.e(LOG_TAG, "Failed to start Sherpa streaming.", throwable)
            showError("Sherpa start error", throwable)
            resetSherpaButtonLabel()
            setSherpaButtonEnabled(true)
            wavButton.isEnabled = backendReady
            benchmarkButton.isEnabled = backendReady
            recordButton.isEnabled = backendReady
            liveButton.isEnabled = backendReady && livePartialBackendReady
        }
    }

    private fun stopSherpaStreaming() {
        val controller = sherpaController ?: return
        val profile = activeSherpaProfile ?: return

        setSherpaButtonEnabled(false)
        sherpaButton.apply {
            isEnabled = false
            text = "Stopping ${profile.shortName}..."
        }
        renderSherpaStatus("NEMOTRON STOPPING", profile)
        controller.requestStop()
    }

    private fun normalizeSherpaDisplayText(text: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) {
            return normalized
        }

        val letters = normalized.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() }) {
            return normalized
        }

        val lower = normalized.lowercase(Locale.US)
        return lower.replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.US)
            } else {
                character.toString()
            }
        }
    }

    private fun renderSherpaTranscript() {
        transcriptView.text =
            buildString {
                if (sherpaFinalTranscript.isNotEmpty()) {
                    append(sherpaFinalTranscript)
                }

                if (sherpaPartialTranscript.isNotBlank()) {
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append(sherpaPartialTranscript)
                    append(" ▌")
                }

                if (isEmpty()) {
                    append("Listening for English speech...")
                }
            }
    }

    private fun renderSherpaStatus(
        state: String,
        profile: SherpaProfile,
    ) {
        setStatus(
            buildString {
                append(state)
                append("\nmodel: ")
                append(profile.displayName)
                append("\n")
                append(sherpaAudioChunkMs)
                append(" ms stateful chunks | CPU | ")
                append(sherpaThreadCount)
                append(" threads")
                append("\nRMS: ")
                append("%.4f".format(sherpaRms))
                append(" | speech threshold: ")
                append("%.4f".format(sherpaSpeechThreshold))

                sherpaFirstPartialLatencyMs?.let { latencyMs ->
                    append("\nfirst partial after speech: ")
                    append(latencyMs)
                    append(" ms")
                }

                append("\nendpoint: ~")
                append("%.1f".format(sherpaEndpointTrailingSilenceSeconds))
                append(" sec trailing silence")
            }
        )
    }

    private fun stopLiveTranscription() {
        val controller = liveController ?: return

        liveButton.isEnabled = false
        liveButton.text = "Stopping live transcription..."
        liveStateLabel = "STOPPING LIVE"
        renderLiveStatus()
        controller.requestStop()
    }

    private fun renderLiveTranscript() {
        transcriptView.text =
            buildString {
                if (finalLiveTranscript.isNotEmpty()) {
                    append(finalLiveTranscript)
                }

                if (currentPartialTranscript.isNotBlank()) {
                    if (isNotEmpty()) {
                        append('\n')
                    }
                    append(currentPartialTranscript)
                    append(" ▌")
                }

                if (isEmpty()) {
                    append("Listening for speech...")
                }
            }
    }

    private fun renderLiveStatus() {
        setStatus(
            buildString {
                append(liveStateLabel)
                append("\nRMS: ")
                append("%.4f".format(liveRms))
                append(" | threshold: ")
                append("%.4f".format(liveThreshold))

                if (liveInferenceLine.isNotBlank()) {
                    append('\n')
                    append(liveInferenceLine)
                }

                append("\nVAD: auto start | final after 700 ms silence")
                append("\nlive partial: tiny.en | final: base.en")
                append("\npartial: <=2.8 sec rolling window | ~1.2 sec steps")
            }
        )
    }

    private fun stopRecording() {
        recordButton.isEnabled = false
        setStatus("Stopping microphone...")
        recorder?.requestStop()
    }

    private fun showResult(
        label: String,
        result: TranscriptionResult,
    ) {
        transcriptView.text = result.text.ifBlank { "(No speech recognized)" }
        setStatus(
            "$label\n" +
                "audio: ${formatSeconds(result.audioDurationMs / 1000.0)} sec\n" +
                "inference: ${formatSeconds(result.inferenceDurationMs / 1000.0)} sec\n" +
                "RTF: ${formatRtf(result.realtimeFactor)}"
        )
    }

    private fun showError(
        label: String,
        throwable: Throwable,
    ) {
        transcriptView.text = "ASR ERROR"
        setStatus(
            "$label\n" +
                "${throwable::class.java.simpleName}\n" +
                (throwable.message ?: "Unknown error")
        )
    }

    private fun renderRuntimeInfo() {
        val sherpaProfile = SherpaProfile.NEMOTRON_1120

        nativeView.text =
            buildString {
                append("SHERPA-ONNX\n")
                append("model: ")
                append(sherpaProfile.displayName)
                append('\n')
                append("installed: ")
                append(if (sherpaModelReady) "YES" else "NO")
                append('\n')
                append("encoder: ")
                append(sherpaProfile.modelSpec.encoderFile)
                append('\n')
                append("decoder: ")
                append(sherpaProfile.modelSpec.decoderFile)
                append('\n')
                append("joiner: ")
                append(sherpaProfile.modelSpec.joinerFile)

                if (sherpaThreadCount > 0) {
                    append("\nCPU: ")
                    append(sherpaThreadCount)
                    append(" threads")
                }

                if (sherpaAudioChunkMs > 0) {
                    append(" | chunk: ")
                    append(sherpaAudioChunkMs)
                    append(" ms")
                }

                if (sherpaEndpointTrailingSilenceSeconds > 0.0f) {
                    append("\nendpoint: ")
                    append("%.1f".format(sherpaEndpointTrailingSilenceSeconds))
                    append(" sec trailing silence")
                }

                append("\n\nWHISPER.CPP\n")
                append("live model: ")
                append(File(filesDir, LIVE_PARTIAL_MODEL_RELATIVE_PATH).name)
                append(
                    if (File(filesDir, LIVE_PARTIAL_MODEL_RELATIVE_PATH).isFile) {
                        " [installed]"
                    } else {
                        " [missing]"
                    }
                )
                append("\nfinal model: ")
                append(File(filesDir, FINAL_MODEL_RELATIVE_PATH).name)
                append(
                    if (File(filesDir, FINAL_MODEL_RELATIVE_PATH).isFile) {
                        " [installed]"
                    } else {
                        " [missing]"
                    }
                )
                append("\nCPU: ")
                append(THREAD_COUNT)
                append(" threads")

                if (backendReady || livePartialBackendReady) {
                    append("\n\n")
                    append(WhisperCppBackend.nativeInfo())
                }
            }
    }

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun elapsedSeconds(startNanoseconds: Long): Double =
        (System.nanoTime() - startNanoseconds) / 1_000_000_000.0

    private fun formatSeconds(seconds: Double): String = "%.3f".format(seconds)

    private fun formatRtf(value: Double): String = "%.3f".format(value)

    private enum class MicrophoneAction {
        BATCH,
        LIVE,
        SHERPA,
    }

    private enum class SherpaProfile(
        val shortName: String,
        val displayName: String,
        val startButtonText: String,
        val stopButtonText: String,
        val modelRelativePath: String,
        val modelSpec: SherpaStreamingController.ModelSpec,
    ) {
        NEMOTRON_1120(
            shortName = "Nemotron 1120ms",
            displayName = "Nemotron Speech Streaming English 0.6B 1120ms INT8",
            startButtonText = "Start Nemotron 1120ms Streaming",
            stopButtonText = "Stop Nemotron 1120ms Streaming",
            modelRelativePath =
                "models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25",
            modelSpec =
                SherpaStreamingController.ModelSpec(
                    encoderFile = "encoder.int8.onnx",
                    decoderFile = "decoder.int8.onnx",
                    joinerFile = "joiner.int8.onnx",
                    modelType = "",
                ),
        )
    }

    private companion object {
        val COLOR_PAGE_BACKGROUND = Color.rgb(246, 247, 251)
        val COLOR_CARD_BACKGROUND = Color.WHITE
        val COLOR_CARD_BORDER = Color.rgb(229, 231, 235)
        val COLOR_STATUS_BACKGROUND = Color.rgb(243, 240, 255)
        val COLOR_STATUS_BORDER = Color.rgb(221, 214, 254)
        val COLOR_RUNTIME_BACKGROUND = Color.rgb(239, 241, 245)
        val COLOR_CHIP_BACKGROUND = Color.rgb(237, 233, 254)
        val COLOR_BUTTON_SECONDARY = Color.rgb(235, 237, 242)
        val COLOR_BUTTON_DISABLED = Color.rgb(220, 222, 228)
        val COLOR_TEXT_PRIMARY = Color.rgb(31, 35, 43)
        val COLOR_TEXT_SECONDARY = Color.rgb(90, 96, 108)
        val COLOR_TEXT_TERTIARY = Color.rgb(118, 124, 137)
        val COLOR_ACCENT = Color.rgb(103, 80, 164)

        const val LOG_TAG = "WhisperTest"
        const val FINAL_MODEL_RELATIVE_PATH = "models/ggml-base.en.bin"
        const val LIVE_PARTIAL_MODEL_RELATIVE_PATH = "models/ggml-tiny.en.bin"
        const val BUNDLED_WAV_ASSET = "samples/hello_survey.wav"
        const val THREAD_COUNT = 4
        const val BENCHMARK_RUN_COUNT = 5
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val MAX_RECORDING_SECONDS = 30
        const val MICROPHONE_PERMISSION_REQUEST = 1001
    }
}
