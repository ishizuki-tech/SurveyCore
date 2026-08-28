#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unistd.h>
#include <utility>
#include <vector>

#include "llama.h"

// ------------------------------------------------------------
// Java exception helpers
// ------------------------------------------------------------

/**
 * Throws java.lang.IllegalStateException with a native diagnostic message.
 */
static jstring throw_illegal_state(
        JNIEnv* env,
        const std::string& message) {

    jclass exception_class =
            env->FindClass(
                    "java/lang/IllegalStateException"
            );

    if (exception_class != nullptr) {
        env->ThrowNew(
                exception_class,
                message.c_str()
        );
    }

    return nullptr;
}

/**
 * Throws java.lang.IllegalArgumentException with a native diagnostic message.
 */
static jstring throw_illegal_argument(
        JNIEnv* env,
        const std::string& message) {

    jclass exception_class =
            env->FindClass(
                    "java/lang/IllegalArgumentException"
            );

    if (exception_class != nullptr) {
        env->ThrowNew(
                exception_class,
                message.c_str()
        );
    }

    return nullptr;
}

// ------------------------------------------------------------
// Global llama.cpp backend lifecycle
// ------------------------------------------------------------
//
// llama_backend_init() and llama_backend_free() manage process-wide
// llama.cpp infrastructure.
//
// Persistent model instances and temporary smoke tests may coexist, so
// backend initialization is reference-counted instead of being started and
// stopped independently by every JNI method.
//

static std::mutex g_backend_mutex;
static uint32_t g_backend_ref_count = 0;

/**
 * Acquires one process-wide llama.cpp backend reference.
 */
static void retain_backend() {

    std::lock_guard<std::mutex> lock(
            g_backend_mutex
    );

    if (g_backend_ref_count == 0) {
        llama_backend_init();
    }

    g_backend_ref_count +=
            1;
}

/**
 * Releases one process-wide llama.cpp backend reference.
 */
static void release_backend() {

    std::lock_guard<std::mutex> lock(
            g_backend_mutex
    );

    if (g_backend_ref_count == 0) {
        return;
    }

    g_backend_ref_count -=
            1;

    if (g_backend_ref_count == 0) {
        llama_backend_free();
    }
}

// ------------------------------------------------------------
// Generic helpers
// ------------------------------------------------------------

/**
 * Determines a conservative CPU thread count for mobile inference.
 *
 * Two logical processors are reserved when possible so Android and the
 * application UI retain CPU headroom.
 */
static int32_t determine_thread_count() {

    const long processor_count =
            sysconf(
                    _SC_NPROCESSORS_ONLN
            );

    if (processor_count <= 0) {
        return 2;
    }

    const int32_t available =
            static_cast<int32_t>(
                    processor_count
            );

    return std::max<int32_t>(
            2,
            std::min<int32_t>(
                    4,
                    available - 2
            )
    );
}

/**
 * Converts one llama token into its UTF-8 text representation.
 */
static bool token_to_text(
        const llama_vocab* vocab,
        llama_token token,
        std::string& output) {

    char stack_buffer[256];

    int32_t length =
            llama_token_to_piece(
                    vocab,
                    token,
                    stack_buffer,
                    sizeof(stack_buffer),
                    0,
                    true
            );

    if (length >= 0) {
        output.assign(
                stack_buffer,
                static_cast<size_t>(
                        length
                )
        );

        return true;
    }

    /*
     * A negative result means the supplied buffer was too small. Its
     * absolute value is the required output capacity.
     */
    const int32_t required =
            -length;

    std::string dynamic_buffer(
            static_cast<size_t>(
                    required
            ),
            '\0'
    );

    length =
            llama_token_to_piece(
                    vocab,
                    token,
                    dynamic_buffer.data(),
                    required,
                    0,
                    true
            );

    if (length < 0) {
        return false;
    }

    dynamic_buffer.resize(
            static_cast<size_t>(
                    length
            )
    );

    output =
            std::move(
                    dynamic_buffer
            );

    return true;
}

/**
 * Escapes line-control characters so diagnostic output remains readable as
 * a single logcat line.
 */
static std::string escape_for_log(
        const std::string& text) {

    std::string escaped;

    escaped.reserve(
            text.size()
    );

    for (const char character : text) {
        switch (character) {
            case '\n':
                escaped += "\\n";
                break;

            case '\r':
                escaped += "\\r";
                break;

            case '\t':
                escaped += "\\t";
                break;

            default:
                escaped += character;
                break;
        }
    }

    return escaped;
}

/**
 * Copies a required Java string into a native std::string.
 *
 * Returns false when JNI could not provide the string. In that case a Java
 * exception may already be pending.
 */
static bool required_java_string(
        JNIEnv* env,
        jstring value,
        std::string& output) {

    if (value == nullptr) {
        return false;
    }

    const char* characters =
            env->GetStringUTFChars(
                    value,
                    nullptr
            );

    if (characters == nullptr) {
        return false;
    }

    output.assign(
            characters
    );

    env->ReleaseStringUTFChars(
            value,
            characters
    );

    return true;
}

/**
 * Copies an optional Java string into a native std::string.
 *
 * A null Java reference becomes an empty native string.
 */
static bool optional_java_string(
        JNIEnv* env,
        jstring value,
        std::string& output) {

    output.clear();

    if (value == nullptr) {
        return true;
    }

    return required_java_string(
            env,
            value,
            output
    );
}

// ------------------------------------------------------------
// Chat-template formatting
// ------------------------------------------------------------

/**
 * Applies the chat template embedded in the GGUF model.
 *
 * The system message is omitted when system_instruction is empty.
 */
static bool build_chat_prompt(
        llama_model* model,
        const std::string& system_instruction,
        const std::string& user_prompt,
        std::string& formatted_prompt,
        std::string& error) {

    const char* chat_template =
            llama_model_chat_template(
                    model,
                    nullptr
            );

    if (chat_template == nullptr) {
        error =
                "Model does not provide a chat template.";

        return false;
    }

    std::vector<llama_chat_message> messages;

    if (!system_instruction.empty()) {
        messages.push_back(
                {
                        "system",
                        system_instruction.c_str()
                }
        );
    }

    messages.push_back(
            {
                    "user",
                    user_prompt.c_str()
            }
    );

    int32_t formatted_size =
            llama_chat_apply_template(
                    chat_template,
                    messages.data(),
                    messages.size(),
                    true,
                    nullptr,
                    0
            );

    if (formatted_size < 0) {
        error =
                "Failed to determine formatted chat prompt size.";

        return false;
    }

    std::vector<char> formatted_buffer(
            static_cast<size_t>(
                    formatted_size
            ) + 1
    );

    formatted_size =
            llama_chat_apply_template(
                    chat_template,
                    messages.data(),
                    messages.size(),
                    true,
                    formatted_buffer.data(),
                    formatted_buffer.size()
            );

    if (formatted_size < 0) {
        error =
                "Failed to apply model chat template.";

        return false;
    }

    formatted_prompt.assign(
            formatted_buffer.data(),
            static_cast<size_t>(
                    formatted_size
            )
    );

    return true;
}

// ------------------------------------------------------------
// Tokenization
// ------------------------------------------------------------

/**
 * Tokenizes one complete formatted prompt.
 */
static bool tokenize_prompt(
        const llama_vocab* vocab,
        const std::string& formatted_prompt,
        std::vector<llama_token>& tokens,
        std::string& error) {

    const int32_t sizing_result =
            llama_tokenize(
                    vocab,
                    formatted_prompt.c_str(),
                    formatted_prompt.size(),
                    nullptr,
                    0,
                    true,
                    true
            );

    if (sizing_result >= 0) {
        error =
                "Unexpected tokenizer sizing result.";

        return false;
    }

    const int32_t required_tokens =
            -sizing_result;

    tokens.resize(
            static_cast<size_t>(
                    required_tokens
            )
    );

    const int32_t actual_tokens =
            llama_tokenize(
                    vocab,
                    formatted_prompt.c_str(),
                    formatted_prompt.size(),
                    tokens.data(),
                    tokens.size(),
                    true,
                    true
            );

    if (actual_tokens < 0) {
        error =
                "Failed to tokenize formatted prompt.";

        return false;
    }

    tokens.resize(
            static_cast<size_t>(
                    actual_tokens
            )
    );

    return true;
}

// ------------------------------------------------------------
// Sampling
// ------------------------------------------------------------

/**
 * Creates a sampler suitable for the current GenerationRequest.
 *
 * temperature <= 0:
 *     deterministic greedy sampling
 *
 * temperature > 0:
 *     temperature transform followed by distribution sampling
 */
static llama_sampler* create_sampler(
        float temperature) {

    llama_sampler* sampler =
            llama_sampler_chain_init(
                    llama_sampler_chain_default_params()
            );

    if (sampler == nullptr) {
        return nullptr;
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_greedy()
        );

        return sampler;
    }

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_temp(
                    temperature
            )
    );

    llama_sampler_chain_add(
            sampler,
            llama_sampler_init_dist(
                    LLAMA_DEFAULT_SEED
            )
    );

    return sampler;
}

// ------------------------------------------------------------
// Generation implementation
// ------------------------------------------------------------

struct GenerationMetrics {

    int32_t prompt_tokens =
            0;

    int32_t generated_tokens =
            0;

    int32_t continuation_decode_tokens =
            0;

    double prompt_seconds =
            0.0;

    double continuation_decode_seconds =
            0.0;

    double generation_seconds =
            0.0;
};

/**
 * Executes one independent generation request against an existing model and
 * context.
 *
 * The context memory is cleared before each request. SurveyCore therefore
 * does not implicitly carry KV-cache history from one AI request into the
 * next.
 */
static bool generate_text(
        llama_model* model,
        llama_context* context,
        const llama_vocab* vocab,
        int32_t context_size,
        const std::string& system_instruction,
        const std::string& user_prompt,
        int32_t max_output_tokens,
        float temperature,
        std::string& output,
        GenerationMetrics& metrics,
        std::string& error) {

    if (model == nullptr ||
        context == nullptr ||
        vocab == nullptr) {

        error =
                "Native llama.cpp instance is incomplete.";

        return false;
    }

    if (user_prompt.empty()) {
        error =
                "Prompt must not be empty.";

        return false;
    }

    if (max_output_tokens <= 0) {
        error =
                "maxOutputTokens must be greater than zero.";

        return false;
    }

    if (!std::isfinite(temperature)) {
        error =
                "Temperature must be finite.";

        return false;
    }

    /*
     * Every GenerationRequest is independent.
     *
     * Keep the allocated KV buffers but remove previous request metadata and
     * positions so the next decode begins from an empty context.
     */
    llama_memory_t memory =
            llama_get_memory(
                    context
            );

    if (memory == nullptr) {
        error =
                "llama_context does not expose inference memory.";

        return false;
    }

    llama_memory_clear(
            memory,
            false
    );

    std::string formatted_prompt;

    if (!build_chat_prompt(
            model,
            system_instruction,
            user_prompt,
            formatted_prompt,
            error
    )) {
        return false;
    }

    std::vector<llama_token> prompt_tokens;

    if (!tokenize_prompt(
            vocab,
            formatted_prompt,
            prompt_tokens,
            error
    )) {
        return false;
    }

    if (prompt_tokens.empty()) {
        error =
                "Formatted prompt produced no tokens.";

        return false;
    }

    if (
            static_cast<int64_t>(
                    prompt_tokens.size()
            ) +
            static_cast<int64_t>(
                    max_output_tokens
            ) >
            static_cast<int64_t>(
                    context_size
            )
            ) {
        std::ostringstream message;

        message
                << "Prompt and requested output exceed context size"
                << "; promptTokens="
                << prompt_tokens.size()
                << "; maxOutputTokens="
                << max_output_tokens
                << "; contextSize="
                << context_size;

        error =
                message.str();

        return false;
    }

    llama_sampler* sampler =
            create_sampler(
                    temperature
            );

    if (sampler == nullptr) {
        error =
                "Failed to create llama sampler.";

        return false;
    }

    /*
     * ------------------------------------------------------------
     * Prompt prefill
     * ------------------------------------------------------------
     */

    llama_batch batch =
            llama_batch_get_one(
                    prompt_tokens.data(),
                    static_cast<int32_t>(
                            prompt_tokens.size()
                    )
            );

    const int64_t prompt_start =
            llama_time_us();

    const int32_t prompt_decode_result =
            llama_decode(
                    context,
                    batch
            );

    const int64_t prompt_end =
            llama_time_us();

    if (prompt_decode_result != 0) {
        llama_sampler_free(
                sampler
        );

        error =
                "Initial prompt llama_decode() failed with code " +
                std::to_string(
                        prompt_decode_result
                );

        return false;
    }

    metrics.prompt_tokens =
            static_cast<int32_t>(
                    prompt_tokens.size()
            );

    metrics.prompt_seconds =
            static_cast<double>(
                    prompt_end -
                    prompt_start
            ) /
            1'000'000.0;

    /*
     * ------------------------------------------------------------
     * Continuation generation
     * ------------------------------------------------------------
     */

    output.clear();

    const int64_t generation_start =
            llama_time_us();

    llama_token token =
            llama_sampler_sample(
                    sampler,
                    context,
                    -1
            );

    while (
            metrics.generated_tokens <
            max_output_tokens
            ) {
        if (
                llama_vocab_is_eog(
                        vocab,
                        token
                )
                ) {
            break;
        }

        std::string piece;

        if (!token_to_text(
                vocab,
                token,
                piece
        )) {
            llama_sampler_free(
                    sampler
            );

            error =
                    "Failed to convert generated token to text.";

            return false;
        }

        output +=
                piece;

        metrics.generated_tokens +=
                1;

        if (
                metrics.generated_tokens >=
                max_output_tokens
                ) {
            break;
        }

        batch =
                llama_batch_get_one(
                        &token,
                        1
                );

        const int64_t token_decode_start =
                llama_time_us();

        const int32_t decode_result =
                llama_decode(
                        context,
                        batch
                );

        const int64_t token_decode_end =
                llama_time_us();

        if (decode_result != 0) {
            llama_sampler_free(
                    sampler
            );

            error =
                    "Continuation llama_decode() failed with code " +
                    std::to_string(
                            decode_result
                    );

            return false;
        }

        metrics.continuation_decode_seconds +=
                static_cast<double>(
                        token_decode_end -
                        token_decode_start
                ) /
                1'000'000.0;

        metrics.continuation_decode_tokens +=
                1;

        token =
                llama_sampler_sample(
                        sampler,
                        context,
                        -1
                );
    }

    const int64_t generation_end =
            llama_time_us();

    metrics.generation_seconds =
            static_cast<double>(
                    generation_end -
                    generation_start
            ) /
            1'000'000.0;

    llama_sampler_free(
            sampler
    );

    return true;
}

// ------------------------------------------------------------
// Persistent native instance
// ------------------------------------------------------------

/**
 * Owns one persistent llama.cpp model and inference context.
 *
 * The mutex serializes generation because a llama_context is mutable and
 * must not be decoded by multiple requests simultaneously.
 */
struct NativeLlamaContext {

    llama_model* model =
            nullptr;

    llama_context* context =
            nullptr;

    const llama_vocab* vocab =
            nullptr;

    int32_t context_size =
            0;

    int32_t thread_count =
            0;

    std::mutex mutex;

    NativeLlamaContext(
            llama_model* model_value,
            llama_context* context_value,
            const llama_vocab* vocab_value,
            int32_t context_size_value,
            int32_t thread_count_value)
            :
            model(
                    model_value
            ),
            context(
                    context_value
            ),
            vocab(
                    vocab_value
            ),
            context_size(
                    context_size_value
            ),
            thread_count(
                    thread_count_value
            ) {
    }

    NativeLlamaContext(
            const NativeLlamaContext&) =
    delete;

    NativeLlamaContext& operator=(
            const NativeLlamaContext&) =
    delete;

    ~NativeLlamaContext() {

        if (context != nullptr) {
            llama_free(
                    context
            );

            context =
                    nullptr;
        }

        if (model != nullptr) {
            llama_model_free(
                    model
            );

            model =
                    nullptr;
        }

        vocab =
                nullptr;

        release_backend();
    }
};

// ------------------------------------------------------------
// Persistent-handle registry
// ------------------------------------------------------------
//
// Kotlin receives an opaque integer handle rather than a native pointer.
//
// The registry stores shared_ptr instances so close() cannot free native
// resources while an already-running generate() still has a reference.
//

static std::mutex g_instance_registry_mutex;

static std::unordered_map<
        jlong,
        std::shared_ptr<NativeLlamaContext>
> g_instance_registry;

static std::atomic<jlong> g_next_instance_handle{
        1
};

/**
 * Returns a strong reference to an existing native instance.
 */
static std::shared_ptr<NativeLlamaContext> find_instance(
        jlong handle) {

    if (handle <= 0) {
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(
            g_instance_registry_mutex
    );

    const auto iterator =
            g_instance_registry.find(
                    handle
            );

    if (
            iterator ==
            g_instance_registry.end()
            ) {
        return nullptr;
    }

    return iterator->second;
}

/**
 * Registers a new native instance and returns its opaque handle.
 */
static jlong register_instance(
        std::shared_ptr<NativeLlamaContext> instance) {

    const jlong handle =
            g_next_instance_handle.fetch_add(
                    1,
                    std::memory_order_relaxed
            );

    std::lock_guard<std::mutex> lock(
            g_instance_registry_mutex
    );

    g_instance_registry.emplace(
            handle,
            std::move(
                    instance
            )
    );

    return handle;
}

// ------------------------------------------------------------
// JNI: runtime information
// ------------------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_nativeInfo(
        JNIEnv* env,
        jobject /* thiz */) {

    retain_backend();

    const bool supports_mmap =
            llama_supports_mmap();

    const bool supports_mlock =
            llama_supports_mlock();

    const bool supports_gpu_offload =
            llama_supports_gpu_offload();

    std::ostringstream message;

    message
            << "SurveyCore llama.cpp runtime is ready"
            << "; version="
            << llama_version()
            << "; mmap="
            << (supports_mmap ? "true" : "false")
            << "; mlock="
            << (supports_mlock ? "true" : "false")
            << "; gpuOffload="
            << (supports_gpu_offload ? "true" : "false");

    release_backend();

    return env->NewStringUTF(
            message.str().c_str()
    );
}

// ------------------------------------------------------------
// JNI: temporary model smoke test
// ------------------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_smokeTestModel(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jint context_size) {

    if (j_model_path == nullptr) {
        return throw_illegal_argument(
                env,
                "Model path must not be null."
        );
    }

    if (context_size <= 0) {
        return throw_illegal_argument(
                env,
                "Context size must be greater than zero."
        );
    }

    std::string model_path;

    if (!required_java_string(
            env,
            j_model_path,
            model_path
    )) {
        return nullptr;
    }

    retain_backend();

    llama_model_params model_params =
            llama_model_default_params();

    model_params.n_gpu_layers =
            0;

    llama_model* model =
            llama_model_load_from_file(
                    model_path.c_str(),
                    model_params
            );

    if (model == nullptr) {
        release_backend();

        return throw_illegal_state(
                env,
                "llama_model_load_from_file() failed for: " +
                model_path
        );
    }

    const llama_vocab* vocab =
            llama_model_get_vocab(
                    model
            );

    if (vocab == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        return throw_illegal_state(
                env,
                "Model vocabulary is unavailable."
        );
    }

    llama_context_params context_params =
            llama_context_default_params();

    context_params.n_ctx =
            static_cast<uint32_t>(
                    context_size
            );

    context_params.n_batch =
            static_cast<uint32_t>(
                    context_size
            );

    context_params.n_ubatch =
            std::min<uint32_t>(
                    512,
                    context_params.n_batch
            );

    const int32_t thread_count =
            determine_thread_count();

    context_params.n_threads =
            thread_count;

    context_params.n_threads_batch =
            thread_count;

    llama_context* context =
            llama_init_from_model(
                    model,
                    context_params
            );

    if (context == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        return throw_illegal_state(
                env,
                "llama_init_from_model() failed."
        );
    }

    char description[256] = {};

    llama_model_desc(
            model,
            description,
            sizeof(description)
    );

    const uint64_t model_size =
            llama_model_size(
                    model
            );

    const uint64_t parameter_count =
            llama_model_n_params(
                    model
            );

    const uint32_t training_context =
            llama_model_n_ctx_train(
                    model
            );

    const uint32_t runtime_context =
            llama_n_ctx(
                    context
            );

    std::ostringstream result;

    result
            << "GGUF MODEL READY"
            << "; model="
            << description
            << "; sizeBytes="
            << model_size
            << "; parameters="
            << parameter_count
            << "; trainingContext="
            << training_context
            << "; runtimeContext="
            << runtime_context
            << "; threads="
            << thread_count;

    llama_free(
            context
    );

    llama_model_free(
            model
    );

    release_backend();

    return env->NewStringUTF(
            result.str().c_str()
    );
}

// ------------------------------------------------------------
// JNI: temporary generation smoke test
// ------------------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_smokeTestGenerate(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jstring j_prompt,
        jint context_size,
        jint max_output_tokens) {

    if (
            j_model_path == nullptr ||
            j_prompt == nullptr
            ) {
        return throw_illegal_argument(
                env,
                "Model path and prompt must not be null."
        );
    }

    if (context_size <= 0) {
        return throw_illegal_argument(
                env,
                "Context size must be greater than zero."
        );
    }

    if (max_output_tokens <= 0) {
        return throw_illegal_argument(
                env,
                "maxOutputTokens must be greater than zero."
        );
    }

    std::string model_path;
    std::string user_prompt;

    if (!required_java_string(
            env,
            j_model_path,
            model_path
    )) {
        return nullptr;
    }

    if (!required_java_string(
            env,
            j_prompt,
            user_prompt
    )) {
        return nullptr;
    }

    if (user_prompt.empty()) {
        return throw_illegal_argument(
                env,
                "Prompt must not be empty."
        );
    }

    retain_backend();

    const int64_t total_start =
            llama_time_us();

    const int64_t load_start =
            llama_time_us();

    llama_model_params model_params =
            llama_model_default_params();

    model_params.n_gpu_layers =
            0;

    llama_model* model =
            llama_model_load_from_file(
                    model_path.c_str(),
                    model_params
            );

    const int64_t load_end =
            llama_time_us();

    if (model == nullptr) {
        release_backend();

        return throw_illegal_state(
                env,
                "Failed to load GGUF model: " +
                model_path
        );
    }

    const llama_vocab* vocab =
            llama_model_get_vocab(
                    model
            );

    if (vocab == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        return throw_illegal_state(
                env,
                "Model vocabulary is unavailable."
        );
    }

    llama_context_params context_params =
            llama_context_default_params();

    context_params.n_ctx =
            static_cast<uint32_t>(
                    context_size
            );

    context_params.n_batch =
            static_cast<uint32_t>(
                    context_size
            );

    context_params.n_ubatch =
            std::min<uint32_t>(
                    512,
                    context_params.n_batch
            );

    const int32_t thread_count =
            determine_thread_count();

    context_params.n_threads =
            thread_count;

    context_params.n_threads_batch =
            thread_count;

    const int64_t context_start =
            llama_time_us();

    llama_context* context =
            llama_init_from_model(
                    model,
                    context_params
            );

    const int64_t context_end =
            llama_time_us();

    if (context == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        return throw_illegal_state(
                env,
                "Failed to create llama_context."
        );
    }

    std::string generated_text;
    std::string generation_error;
    GenerationMetrics metrics;

    const bool generation_ok =
            generate_text(
                    model,
                    context,
                    vocab,
                    context_size,
                    "",
                    user_prompt,
                    max_output_tokens,
                    0.0f,
                    generated_text,
                    metrics,
                    generation_error
            );

    const int64_t total_end =
            llama_time_us();

    if (!generation_ok) {
        llama_free(
                context
        );

        llama_model_free(
                model
        );

        release_backend();

        return throw_illegal_state(
                env,
                generation_error
        );
    }

    const double load_seconds =
            static_cast<double>(
                    load_end -
                    load_start
            ) /
            1'000'000.0;

    const double context_seconds =
            static_cast<double>(
                    context_end -
                    context_start
            ) /
            1'000'000.0;

    const double total_seconds =
            static_cast<double>(
                    total_end -
                    total_start
            ) /
            1'000'000.0;

    const double prompt_tokens_per_second =
            metrics.prompt_seconds > 0.0
            ? static_cast<double>(
                      metrics.prompt_tokens
              ) /
              metrics.prompt_seconds
            : 0.0;

    const double generation_tokens_per_second =
            metrics.continuation_decode_seconds > 0.0
            ? static_cast<double>(
                      metrics.continuation_decode_tokens
              ) /
              metrics.continuation_decode_seconds
            : 0.0;

    const double generation_wall_tokens_per_second =
            metrics.generation_seconds > 0.0
            ? static_cast<double>(
                      metrics.generated_tokens
              ) /
              metrics.generation_seconds
            : 0.0;

    std::ostringstream result;

    result
            << std::fixed
            << std::setprecision(3)
            << "GGUF GENERATION READY"
            << "; output='"
            << escape_for_log(
                    generated_text
            )
            << "'"
            << "; loadSeconds="
            << load_seconds
            << "; contextInitSeconds="
            << context_seconds
            << "; promptTokens="
            << metrics.prompt_tokens
            << "; promptSeconds="
            << metrics.prompt_seconds
            << "; promptTokensPerSecond="
            << prompt_tokens_per_second
            << "; generatedTokens="
            << metrics.generated_tokens
            << "; continuationDecodeTokens="
            << metrics.continuation_decode_tokens
            << "; generationSeconds="
            << metrics.generation_seconds
            << "; generationTokensPerSecond="
            << generation_tokens_per_second
            << "; generationWallTokensPerSecond="
            << generation_wall_tokens_per_second
            << "; totalSeconds="
            << total_seconds
            << "; threads="
            << thread_count;

    llama_free(
            context
    );

    llama_model_free(
            model
    );

    release_backend();

    return env->NewStringUTF(
            result.str().c_str()
    );
}

// ------------------------------------------------------------
// JNI: persistent create()
// ------------------------------------------------------------

extern "C"
JNIEXPORT jlong JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_create(
        JNIEnv* env,
        jobject /* thiz */,
        jstring j_model_path,
        jint context_size) {

    if (j_model_path == nullptr) {
        throw_illegal_argument(
                env,
                "Model path must not be null."
        );

        return 0;
    }

    if (context_size <= 0) {
        throw_illegal_argument(
                env,
                "Context size must be greater than zero."
        );

        return 0;
    }

    std::string model_path;

    if (!required_java_string(
            env,
            j_model_path,
            model_path
    )) {
        return 0;
    }

    if (model_path.empty()) {
        throw_illegal_argument(
                env,
                "Model path must not be empty."
        );

        return 0;
    }

    /*
     * This backend reference becomes owned by NativeLlamaContext after
     * successful construction.
     */
    retain_backend();

    llama_model_params model_params =
            llama_model_default_params();

    /*
     * SurveyCore currently uses the CPU backend.
     */
    model_params.n_gpu_layers =
            0;

    llama_model* model =
            llama_model_load_from_file(
                    model_path.c_str(),
                    model_params
            );

    if (model == nullptr) {
        release_backend();

        throw_illegal_state(
                env,
                "Failed to load GGUF model: " +
                model_path
        );

        return 0;
    }

    const llama_vocab* vocab =
            llama_model_get_vocab(
                    model
            );

    if (vocab == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        throw_illegal_state(
                env,
                "Model vocabulary is unavailable."
        );

        return 0;
    }

    llama_context_params context_params =
            llama_context_default_params();

    context_params.n_ctx =
            static_cast<uint32_t>(
                    context_size
            );

    /*
     * Allow one request to prefill up to the configured context capacity.
     * llama.cpp will internally split work into smaller micro-batches.
     */
    context_params.n_batch =
            static_cast<uint32_t>(
                    context_size
            );

    context_params.n_ubatch =
            std::min<uint32_t>(
                    512,
                    context_params.n_batch
            );

    const int32_t thread_count =
            determine_thread_count();

    context_params.n_threads =
            thread_count;

    context_params.n_threads_batch =
            thread_count;

    llama_context* context =
            llama_init_from_model(
                    model,
                    context_params
            );

    if (context == nullptr) {
        llama_model_free(
                model
        );

        release_backend();

        throw_illegal_state(
                env,
                "Failed to create persistent llama_context."
        );

        return 0;
    }

    std::shared_ptr<NativeLlamaContext> instance;

    try {
        instance =
                std::make_shared<NativeLlamaContext>(
                        model,
                        context,
                        vocab,
                        context_size,
                        thread_count
                );
    } catch (...) {
        llama_free(
                context
        );

        llama_model_free(
                model
        );

        release_backend();

        throw_illegal_state(
                env,
                "Failed to allocate persistent native llama.cpp instance."
        );

        return 0;
    }

    const jlong handle =
            register_instance(
                    std::move(
                            instance
                    )
            );

    if (handle <= 0) {
        throw_illegal_state(
                env,
                "Failed to register persistent native llama.cpp instance."
        );

        return 0;
    }

    return handle;
}

// ------------------------------------------------------------
// JNI: persistent generate()
// ------------------------------------------------------------

extern "C"
JNIEXPORT jstring JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_generate(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jstring j_system_instruction,
        jstring j_prompt,
        jint max_output_tokens,
        jfloat temperature) {

    if (handle <= 0) {
        return throw_illegal_argument(
                env,
                "Native llama.cpp handle must be greater than zero."
        );
    }

    if (j_prompt == nullptr) {
        return throw_illegal_argument(
                env,
                "Prompt must not be null."
        );
    }

    if (max_output_tokens <= 0) {
        return throw_illegal_argument(
                env,
                "maxOutputTokens must be greater than zero."
        );
    }

    if (!std::isfinite(
            static_cast<float>(
                    temperature
            )
    )) {
        return throw_illegal_argument(
                env,
                "Temperature must be finite."
        );
    }

    std::string system_instruction;
    std::string user_prompt;

    if (!optional_java_string(
            env,
            j_system_instruction,
            system_instruction
    )) {
        return nullptr;
    }

    if (!required_java_string(
            env,
            j_prompt,
            user_prompt
    )) {
        return nullptr;
    }

    if (user_prompt.empty()) {
        return throw_illegal_argument(
                env,
                "Prompt must not be empty."
        );
    }

    const std::shared_ptr<NativeLlamaContext> instance =
            find_instance(
                    handle
            );

    if (instance == nullptr) {
        return throw_illegal_state(
                env,
                "Native llama.cpp handle is invalid or has already been closed."
        );
    }

    /*
     * llama_context is mutable. Serialize all inference through this
     * particular persistent instance.
     */
    std::lock_guard<std::mutex> lock(
            instance->mutex
    );

    std::string generated_text;
    std::string generation_error;
    GenerationMetrics metrics;

    const bool generation_ok =
            generate_text(
                    instance->model,
                    instance->context,
                    instance->vocab,
                    instance->context_size,
                    system_instruction,
                    user_prompt,
                    max_output_tokens,
                    static_cast<float>(
                            temperature
                    ),
                    generated_text,
                    metrics,
                    generation_error
            );

    if (!generation_ok) {
        return throw_illegal_state(
                env,
                generation_error
        );
    }

    return env->NewStringUTF(
            generated_text.c_str()
    );
}

// ------------------------------------------------------------
// JNI: persistent close()
// ------------------------------------------------------------

extern "C"
JNIEXPORT void JNICALL
Java_com_negi_surveycore_ai_backend_llamacpp_LlamaCppNative_close(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {

    if (handle <= 0) {
        return;
    }

    std::shared_ptr<NativeLlamaContext> instance;

    /*
     * Remove the handle first so no new generate() call can acquire this
     * instance.
     */
    {
        std::lock_guard<std::mutex> registry_lock(
                g_instance_registry_mutex
        );

        const auto iterator =
                g_instance_registry.find(
                        handle
                );

        if (
                iterator ==
                g_instance_registry.end()
                ) {
            /*
             * close() is intentionally idempotent.
             */
            return;
        }

        instance =
                iterator->second;

        g_instance_registry.erase(
                iterator
        );
    }

    /*
     * Wait for an already-running generate() call to leave the context
     * before releasing our registry reference.
     */
    {
        std::lock_guard<std::mutex> instance_lock(
                instance->mutex
        );
    }

    /*
     * When this is the final shared_ptr reference, NativeLlamaContext's
     * destructor frees the context, model, and process-wide backend
     * reference.
     */
    instance.reset();
}