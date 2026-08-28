#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <new>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "whisper.h"

namespace {

constexpr const char * LOG_TAG =
        "WhisperCppNative";

struct WhisperInstance {
    whisper_context * context =
            nullptr;

    std::mutex mutex;
};

void log_info(
        const std::string & message
) {
    __android_log_print(
            ANDROID_LOG_INFO,
            LOG_TAG,
            "%s",
            message.c_str()
    );
}

void log_error(
        const std::string & message
) {
    __android_log_print(
            ANDROID_LOG_ERROR,
            LOG_TAG,
            "%s",
            message.c_str()
    );
}

void throw_java(
        JNIEnv * env,
        const char * class_name,
        const std::string & message
) {
    jclass exception_class =
            env->FindClass(
                    class_name
            );

    if (
            exception_class != nullptr
    ) {
        env->ThrowNew(
                exception_class,
                message.c_str()
        );
    }
}

WhisperInstance * require_instance(
        JNIEnv * env,
        jlong handle
) {
    auto * instance =
            reinterpret_cast<WhisperInstance *>(
                    handle
            );

    if (
            instance == nullptr ||
            instance->context == nullptr
    ) {
        throw_java(
                env,
                "java/lang/IllegalStateException",
                "Invalid whisper.cpp native handle."
        );

        return nullptr;
    }

    return instance;
}

std::string collect_transcript(
        whisper_context * context
) {
    const int segment_count =
            whisper_full_n_segments(
                    context
            );

    std::string transcript;

    for (
            int index = 0;
            index < segment_count;
            ++index
    ) {
        const char * segment_text =
                whisper_full_get_segment_text(
                        context,
                        index
                );

        if (
                segment_text != nullptr
        ) {
            transcript +=
                    segment_text;
        }
    }

    return transcript;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_asr_whispercpp_WhisperCppNative_nativeInfo(
        JNIEnv * env,
        jobject /* thiz */
) {
    std::ostringstream output;

    output
            << "whisper.cpp "
            << whisper_version()
            << "\n"
            << whisper_print_system_info();

    return env->NewStringUTF(
            output.str().c_str()
    );
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_negi_surveycore_asr_whispercpp_WhisperCppNative_create(
        JNIEnv * env,
        jobject /* thiz */,
        jstring model_path
) {
    if (
            model_path == nullptr
    ) {
        throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "Whisper model path is null."
        );

        return 0L;
    }

    const char * model_path_chars =
            env->GetStringUTFChars(
                    model_path,
                    nullptr
            );

    if (
            model_path_chars == nullptr
    ) {
        return 0L;
    }

    const std::string model_path_string =
            model_path_chars;

    env->ReleaseStringUTFChars(
            model_path,
            model_path_chars
    );

    whisper_context_params context_params =
            whisper_context_default_params();

    // Start with the portable CPU path. Android GPU backends can be evaluated
    // independently after the English-only CPU baseline is stable.
    context_params.use_gpu =
            false;

    context_params.flash_attn =
            false;

    whisper_context * context =
            whisper_init_from_file_with_params(
                    model_path_string.c_str(),
                    context_params
            );

    if (
            context == nullptr
    ) {
        const std::string message =
                "Failed to load whisper.cpp model: " +
                model_path_string;

        log_error(
                message
        );

        throw_java(
                env,
                "java/lang/IllegalStateException",
                message
        );

        return 0L;
    }

    auto * instance =
            new (
                    std::nothrow
            ) WhisperInstance();

    if (
            instance == nullptr
    ) {
        whisper_free(
                context
        );

        throw_java(
                env,
                "java/lang/OutOfMemoryError",
                "Failed to allocate WhisperInstance."
        );

        return 0L;
    }

    instance->context =
            context;

    log_info(
            "Whisper model loaded: " +
            model_path_string
    );

    return reinterpret_cast<jlong>(
            instance
    );
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_asr_whispercpp_WhisperCppNative_transcribe(
        JNIEnv * env,
        jobject /* thiz */,
        jlong handle,
        jfloatArray samples,
        jint sample_rate_hz,
        jint thread_count
) {
    WhisperInstance * instance =
            require_instance(
                    env,
                    handle
            );

    if (
            instance == nullptr
    ) {
        return nullptr;
    }

    if (
            samples == nullptr
    ) {
        throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "PCM sample buffer is null."
        );

        return nullptr;
    }

    if (
            sample_rate_hz != WHISPER_SAMPLE_RATE
    ) {
        std::ostringstream message;

        message
                << "whisper.cpp requires "
                << WHISPER_SAMPLE_RATE
                << " Hz PCM; received "
                << sample_rate_hz
                << " Hz.";

        throw_java(
                env,
                "java/lang/IllegalArgumentException",
                message.str()
        );

        return nullptr;
    }

    const jsize sample_count =
            env->GetArrayLength(
                    samples
            );

    if (
            sample_count <= 0
    ) {
        throw_java(
                env,
                "java/lang/IllegalArgumentException",
                "PCM sample buffer is empty."
        );

        return nullptr;
    }

    std::vector<float> pcm(
            static_cast<size_t>(
                    sample_count
            )
    );

    env->GetFloatArrayRegion(
            samples,
            0,
            sample_count,
            pcm.data()
    );

    if (
            env->ExceptionCheck()
    ) {
        return nullptr;
    }

    whisper_full_params params =
            whisper_full_default_params(
                    WHISPER_SAMPLING_GREEDY
            );

    params.n_threads =
            std::max(
                    1,
                    static_cast<int>(
                            thread_count
                    )
            );

    params.language =
            "en";

    params.detect_language =
            false;

    params.translate =
            false;

    params.no_context =
            true;

    params.no_timestamps =
            true;

    params.single_segment =
            false;

    params.print_special =
            false;

    params.print_progress =
            false;

    params.print_realtime =
            false;

    params.print_timestamps =
            false;

    params.token_timestamps =
            false;

    params.temperature =
            0.0f;

    // One greedy candidate keeps the first Android baseline deterministic and
    // avoids the default best-of-5 decode cost.
    params.greedy.best_of =
            1;

    std::lock_guard<std::mutex> lock(
            instance->mutex
    );

    whisper_reset_timings(
            instance->context
    );

    const int result =
            whisper_full(
                    instance->context,
                    params,
                    pcm.data(),
                    sample_count
            );

    if (
            result != 0
    ) {
        std::ostringstream message;

        message
                << "whisper_full failed with code "
                << result
                << ".";

        log_error(
                message.str()
        );

        throw_java(
                env,
                "java/lang/IllegalStateException",
                message.str()
        );

        return nullptr;
    }

    const std::string transcript =
            collect_transcript(
                    instance->context
            );

    return env->NewStringUTF(
            transcript.c_str()
    );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_negi_surveycore_asr_whispercpp_WhisperCppNative_close(
        JNIEnv * env,
        jobject /* thiz */,
        jlong handle
) {
    auto * instance =
            reinterpret_cast<WhisperInstance *>(
                    handle
            );

    if (
            instance == nullptr
    ) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(
                instance->mutex
        );

        if (
                instance->context != nullptr
        ) {
            whisper_free(
                    instance->context
            );

            instance->context =
                    nullptr;
        }
    }

    delete instance;
}
