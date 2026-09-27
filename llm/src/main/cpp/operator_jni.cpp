// operator patch set P2 (FOUNDATION §4.2, ADR-0005/0006).
//
// This is deliberately independent of ai_chat.cpp's process-global objects.  OperatorNative owns
// the handles returned here and serializes calls per context.  A model must outlive its contexts.
//
// Kotlin contract (all are instance methods on `object dev.operator.llm.OperatorNative`):
//   initialize(String): Unit
//   systemInfo(): String
//   loadModel(String, IntArray, Boolean, Int): Long
//   freeModel(Long): Unit
//   newContext(Long, Int, Int, Int, Int, Int, Int, Boolean): Long
//   freeContext(Long): Unit
//   tokenize(Long, String, Boolean, Boolean): IntArray
//   detokenize(Long, IntArray, Boolean, Boolean): String
//   generate(Long, Int, IntArray, Int, String?, Int, Float, Int, Float, Float, Int, Boolean): IntArray
//   labelLogits(Long, Int, Int, Int, IntArray, IntArray): FloatArray
//   embedLast(Long, Int, IntArray, Int): FloatArray
//   clearSequence(Long, Int, Int): Boolean
//   copySequence(Long, Int, Int, Int, Int): Unit
//   saveState(Long, Int, Int): ByteArray
//   restoreState(Long, Int, Int, ByteArray): Unit
//   saveStateFile(Long, Int, String, IntArray): Long
//   restoreStateFile(Long, Int, String, Int): IntArray
//   abort(Long): Unit
//   attachThreadpool(Long, Int, Int, IntArray): Unit
//   stats(Long): DoubleArray
//   bench(Long, Int, Int): DoubleArray
//
// Sequence handles are llama sequence ids scoped to a context.  generate/embed reuse [0, prefix),
// labelLogits copies [0, sourceLength) to a disposable branch id, and state flags are llama's
// LLAMA_STATE_SEQ_FLAGS_* values.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"
#include "llama.h"

namespace {

constexpr const char * kIllegalArgument = "java/lang/IllegalArgumentException";
constexpr const char * kIllegalState = "java/lang/IllegalStateException";
constexpr const char * kRuntimeException = "java/lang/RuntimeException";

struct ModelHandle {
    llama_model * model = nullptr;
    double load_ms = 0;
    std::atomic<int> contexts{0};
};

struct ContextHandle {
    llama_context * ctx = nullptr;
    ModelHandle * model = nullptr;
    std::atomic<bool> abort{false};
    std::mutex mutex;

    ggml_threadpool_t pool = nullptr;
    ggml_threadpool_t pool_batch = nullptr;
    decltype(ggml_threadpool_free) * pool_free = nullptr;
};

std::mutex g_handles_mutex;
std::unordered_set<ModelHandle *> g_models;
std::unordered_set<ContextHandle *> g_contexts;
std::once_flag g_backend_once;

void throw_java(JNIEnv * env, const char * type, const std::string & message) {
    if (!env->ExceptionCheck()) {
        if (jclass cls = env->FindClass(type)) {
            env->ThrowNew(cls, message.c_str());
        }
    }
}

std::string jstring_value(JNIEnv * env, jstring value) {
    if (!value) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<llama_token> token_array(JNIEnv * env, jintArray values) {
    if (!values) {
        return {};
    }
    const jsize size = env->GetArrayLength(values);
    std::vector<llama_token> result(static_cast<size_t>(size));
    static_assert(sizeof(llama_token) == sizeof(jint));
    env->GetIntArrayRegion(values, 0, size, reinterpret_cast<jint *>(result.data()));
    return result;
}

jintArray to_jint_array(JNIEnv * env, const std::vector<llama_token> & values) {
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result && !values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()),
                               reinterpret_cast<const jint *>(values.data()));
    }
    return result;
}

jfloatArray to_jfloat_array(JNIEnv * env, const float * values, size_t size) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(size));
    if (result && size != 0) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(size), values);
    }
    return result;
}

jdoubleArray to_jdouble_array(JNIEnv * env, const std::vector<double> & values) {
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(values.size()));
    if (result && !values.empty()) {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

ModelHandle * get_model(JNIEnv * env, jlong handle) {
    auto * value = reinterpret_cast<ModelHandle *>(handle);
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    if (!value || g_models.count(value) == 0) {
        throw_java(env, kIllegalState, "invalid or closed model handle");
        return nullptr;
    }
    return value;
}

ContextHandle * get_context(JNIEnv * env, jlong handle) {
    auto * value = reinterpret_cast<ContextHandle *>(handle);
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    if (!value || g_contexts.count(value) == 0) {
        throw_java(env, kIllegalState, "invalid or closed context handle");
        return nullptr;
    }
    return value;
}

bool abort_callback(void * data) {
    return static_cast<ContextHandle *>(data)->abort.load(std::memory_order_relaxed);
}

bool valid_seq(ContextHandle * handle, jint seq) {
    return seq >= 0 && static_cast<uint32_t>(seq) < llama_n_seq_max(handle->ctx);
}

// Decode tokens at explicit positions.  Only the final row is retained.
bool decode(ContextHandle * handle, const llama_token * tokens, size_t count,
            llama_pos start, llama_seq_id seq) {
    const size_t capacity = std::max<size_t>(1, llama_n_batch(handle->ctx));
    size_t offset = 0;
    while (offset < count) {
        const int32_t n = static_cast<int32_t>(std::min(capacity, count - offset));
        llama_batch batch = llama_batch_init(n, 0, 1);
        batch.n_tokens = n;
        for (int32_t i = 0; i < n; ++i) {
            batch.token[i] = tokens[offset + static_cast<size_t>(i)];
            batch.pos[i] = start + static_cast<llama_pos>(offset) + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = seq;
            batch.logits[i] = offset + static_cast<size_t>(i) + 1 == count;
        }
        const int rc = llama_decode(handle->ctx, batch);
        llama_batch_free(batch);
        if (rc != 0) {
            return false;
        }
        offset += static_cast<size_t>(n);
    }
    return true;
}

bool prepare_sequence(JNIEnv * env, ContextHandle * handle, jint seq,
                      const std::vector<llama_token> & tokens, jint prefix) {
    if (!valid_seq(handle, seq) || prefix < 0 ||
        static_cast<size_t>(prefix) > tokens.size()) {
        throw_java(env, kIllegalArgument, "invalid sequence id or prefix length");
        return false;
    }
    const llama_pos cached_max = llama_memory_seq_pos_max(llama_get_memory(handle->ctx), seq);
    if (prefix > 0 && cached_max < prefix - 1) {
        throw_java(env, kIllegalState, "requested prefix is not present in the sequence");
        return false;
    }
    if (!llama_memory_seq_rm(llama_get_memory(handle->ctx), seq, prefix, -1)) {
        throw_java(env, kIllegalState, "model memory cannot roll back to the requested prefix");
        return false;
    }
    if (static_cast<size_t>(prefix) == tokens.size()) {
        throw_java(env, kIllegalArgument, "at least one uncached token is required");
        return false;
    }
    if (!decode(handle, tokens.data() + prefix, tokens.size() - static_cast<size_t>(prefix),
                prefix, seq)) {
        throw_java(env, kRuntimeException,
                   handle->abort.load() ? "decode aborted" : "llama_decode failed");
        return false;
    }
    return true;
}

bool validate_tensor_types(const std::string & path, const std::unordered_set<int> & allowed,
                           std::string * error) {
    gguf_init_params params{/*.no_alloc =*/ true, /*.ctx =*/ nullptr};
    gguf_context * gguf = gguf_init_from_file(path.c_str(), params);
    if (!gguf) {
        *error = "cannot read GGUF metadata";
        return false;
    }
    bool valid = true;
    const int64_t count = gguf_get_n_tensors(gguf);
    for (int64_t i = 0; i < count; ++i) {
        const int type = static_cast<int>(gguf_get_tensor_type(gguf, i));
        if (allowed.count(type) == 0) {
            *error = std::string("tensor type not allowed: ") + ggml_type_name(
                    static_cast<ggml_type>(type)) + " (" + std::to_string(type) + ")";
            valid = false;
            break;
        }
    }
    gguf_free(gguf);
    return valid;
}

void release_pools(ContextHandle * handle) {
    if (!handle->pool_free) {
        return;
    }
    handle->pool_free(handle->pool);
    handle->pool_free(handle->pool_batch);
    handle->pool = nullptr;
    handle->pool_batch = nullptr;
    handle->pool_free = nullptr;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_initialize(
        JNIEnv * env, jobject, jstring native_lib_dir) {
    const std::string path = jstring_value(env, native_lib_dir);
    if (env->ExceptionCheck()) {
        return;
    }
    std::call_once(g_backend_once, [&path] {
        if (!path.empty()) {
            ggml_backend_load_all_from_path(path.c_str());
        }
        llama_backend_init();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_operator_llm_OperatorNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_operator_llm_OperatorNative_loadModel(
        JNIEnv * env, jobject, jstring model_path, jintArray allowed_types,
        jboolean use_extra_bufts, jint backend) {
    const std::string path = jstring_value(env, model_path);
    if (path.empty() || !allowed_types || env->GetArrayLength(allowed_types) == 0) {
        throw_java(env, kIllegalArgument, "path and allowedTensorTypes are required");
        return 0;
    }
    if (backend != 0 && backend != 1) {
        throw_java(env, kIllegalArgument, "backend must be 0 (CPU) or 1 (OpenCL)");
        return 0;
    }

    const jsize n_allowed = env->GetArrayLength(allowed_types);
    std::vector<jint> types(static_cast<size_t>(n_allowed));
    env->GetIntArrayRegion(allowed_types, 0, n_allowed, types.data());
    std::unordered_set<int> allowed(types.begin(), types.end());
    std::string validation_error;
    if (!validate_tensor_types(path, allowed, &validation_error)) {
        throw_java(env, kIllegalArgument, validation_error);
        return 0;
    }

    llama_model_params params = llama_model_default_params();
    params.use_extra_bufts = use_extra_bufts;
    ggml_backend_dev_t selected_device = nullptr;
    if (backend == 0) {
        selected_device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        params.n_gpu_layers = 0;
    } else {
        // Do not use dev_by_type(GPU): another dynamically loaded GPU backend could win and an
        // absent OpenCL backend must be reported instead of silently loading on CPU.
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            ggml_backend_dev_t candidate = ggml_backend_dev_get(i);
            ggml_backend_reg_t registry = ggml_backend_dev_backend_reg(candidate);
            if (registry && std::strcmp(ggml_backend_reg_name(registry), "OpenCL") == 0) {
                selected_device = candidate;
                break;
            }
        }
        params.n_gpu_layers = -1;
    }
    if (!selected_device) {
        throw_java(env, kIllegalState,
                   backend == 0 ? "CPU backend unavailable" : "OpenCL backend unavailable");
        return 0;
    }
    // llama copies this NULL-terminated selection into the model during load.
    ggml_backend_dev_t devices[] = {selected_device, nullptr};
    params.devices = devices;
    const auto started = std::chrono::steady_clock::now();
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (!model) {
        throw_java(env, kRuntimeException, "llama_model_load_from_file failed");
        return 0;
    }
    auto * result = new ModelHandle;
    result->model = model;
    result->load_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        g_models.insert(result);
    }
    return reinterpret_cast<jlong>(result);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_freeModel(JNIEnv * env, jobject, jlong model_handle) {
    ModelHandle * handle = get_model(env, model_handle);
    if (!handle) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    if (handle->contexts.load() != 0) {
        throw_java(env, kIllegalState, "model still has live contexts");
        return;
    }
    g_models.erase(handle);
    llama_model_free(handle->model);
    delete handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_operator_llm_OperatorNative_newContext(
        JNIEnv * env, jobject, jlong model_handle, jint n_ctx, jint n_batch,
        jint n_seq_max, jint n_threads, jint type_k, jint type_v, jboolean embeddings) {
    ModelHandle * model = get_model(env, model_handle);
    if (!model) {
        return 0;
    }
    if (n_ctx <= 0 || n_batch <= 0 || n_seq_max <= 0 || n_threads <= 0 ||
        type_k < 0 || type_k >= GGML_TYPE_COUNT || type_v < 0 || type_v >= GGML_TYPE_COUNT) {
        throw_java(env, kIllegalArgument, "invalid context parameters");
        return 0;
    }

    auto * result = new ContextHandle;
    result->model = model;
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(n_ctx);
    params.n_batch = static_cast<uint32_t>(n_batch);
    params.n_ubatch = static_cast<uint32_t>(n_batch);
    params.n_seq_max = static_cast<uint32_t>(n_seq_max);
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.type_k = static_cast<ggml_type>(type_k);
    params.type_v = static_cast<ggml_type>(type_v);
    params.embeddings = embeddings;
    params.pooling_type = LLAMA_POOLING_TYPE_NONE;
    params.kv_unified = n_seq_max > 1;
    params.abort_callback = abort_callback;
    params.abort_callback_data = result;
    result->ctx = llama_init_from_model(model->model, params);
    if (!result->ctx) {
        delete result;
        throw_java(env, kRuntimeException, "llama_init_from_model failed");
        return 0;
    }
    model->contexts.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        g_contexts.insert(result);
    }
    return reinterpret_cast<jlong>(result);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_freeContext(
        JNIEnv * env, jobject, jlong context_handle) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return;
    }
    std::lock_guard<std::mutex> handles_lock(g_handles_mutex);
    std::lock_guard<std::mutex> context_lock(handle->mutex);
    g_contexts.erase(handle);
    llama_detach_threadpool(handle->ctx);
    llama_free(handle->ctx);
    release_pools(handle);
    handle->model->contexts.fetch_sub(1);
    delete handle;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_operator_llm_OperatorNative_tokenize(
        JNIEnv * env, jobject, jlong model_handle, jstring text,
        jboolean add_special, jboolean parse_special) {
    ModelHandle * handle = get_model(env, model_handle);
    if (!handle) {
        return nullptr;
    }
    const std::string value = jstring_value(env, text);
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    int32_t capacity = std::max<int32_t>(32, static_cast<int32_t>(value.size()) + 8);
    std::vector<llama_token> tokens(static_cast<size_t>(capacity));
    int32_t count = llama_tokenize(vocab, value.data(), static_cast<int32_t>(value.size()),
                                   tokens.data(), capacity, add_special, parse_special);
    if (count < 0 && count != INT32_MIN) {
        capacity = -count;
        tokens.resize(static_cast<size_t>(capacity));
        count = llama_tokenize(vocab, value.data(), static_cast<int32_t>(value.size()),
                               tokens.data(), capacity, add_special, parse_special);
    }
    if (count < 0) {
        throw_java(env, kRuntimeException, "llama_tokenize failed");
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(count));
    return to_jint_array(env, tokens);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_operator_llm_OperatorNative_detokenize(
        JNIEnv * env, jobject, jlong model_handle, jintArray input,
        jboolean remove_special, jboolean unparse_special) {
    ModelHandle * handle = get_model(env, model_handle);
    if (!handle) {
        return nullptr;
    }
    const std::vector<llama_token> tokens = token_array(env, input);
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    int32_t capacity = std::max<int32_t>(64, static_cast<int32_t>(tokens.size()) * 8);
    std::vector<char> text(static_cast<size_t>(capacity));
    int32_t count = llama_detokenize(vocab, tokens.data(), static_cast<int32_t>(tokens.size()),
                                     text.data(), capacity, remove_special, unparse_special);
    if (count < 0 && count != INT32_MIN) {
        capacity = -count;
        text.resize(static_cast<size_t>(capacity));
        count = llama_detokenize(vocab, tokens.data(), static_cast<int32_t>(tokens.size()),
                                 text.data(), capacity, remove_special, unparse_special);
    }
    if (count < 0) {
        throw_java(env, kRuntimeException, "llama_detokenize failed");
        return nullptr;
    }
    return env->NewStringUTF(std::string(text.data(), static_cast<size_t>(count)).c_str());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_operator_llm_OperatorNative_generate(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jintArray prompt,
        jint prefix_length, jstring gbnf, jint max_tokens, jfloat temperature,
        jint top_k, jfloat top_p, jfloat min_p, jint seed, jboolean grammar_first) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    if (max_tokens < 0 || top_p < 0.0f || top_p > 1.0f ||
        min_p < 0.0f || min_p > 1.0f) {
        throw_java(env, kIllegalArgument, "invalid generation parameters");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->abort.store(false);
    const std::vector<llama_token> tokens = token_array(env, prompt);
    if (!prepare_sequence(env, handle, seq, tokens, prefix_length)) {
        return nullptr;
    }

    const std::string grammar = jstring_value(env, gbnf);
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        // Grammar-constrained calls are greedy by design (FOUNDATION §4.6). Applying the grammar
        // before greedy selection and trying greedy first followed by a constrained fallback select
        // the same highest-logit valid token, so grammarFirst only affects cost, not semantics here.
        (void) grammar_first;
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(
                llama_model_get_vocab(handle->model->model), grammar.c_str(), "root");
        if (!grammar_sampler) {
            llama_sampler_free(sampler);
            throw_java(env, kIllegalArgument, "invalid GBNF grammar");
            return nullptr;
        }
        llama_sampler_chain_add(sampler, grammar_sampler);
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    std::vector<llama_token> output;
    output.reserve(static_cast<size_t>(max_tokens));
    llama_pos position = static_cast<llama_pos>(tokens.size());
    const llama_vocab * vocab = llama_model_get_vocab(handle->model->model);
    for (jint i = 0; i < max_tokens; ++i) {
        const llama_token token = llama_sampler_sample(sampler, handle->ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        output.push_back(token);
        if (!decode(handle, &token, 1, position++, seq)) {
            llama_sampler_free(sampler);
            throw_java(env, kRuntimeException,
                       handle->abort.load() ? "generation aborted" : "llama_decode failed");
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    return to_jint_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_operator_llm_OperatorNative_labelLogits(
        JNIEnv * env, jobject, jlong context_handle, jint source_seq, jint branch_seq,
        jint source_length, jintArray suffix_array, jintArray labels_array) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->abort.store(false);
    const std::vector<llama_token> suffix = token_array(env, suffix_array);
    const std::vector<llama_token> labels = token_array(env, labels_array);
    if (!valid_seq(handle, source_seq) || !valid_seq(handle, branch_seq) ||
        source_seq == branch_seq || source_length < 0 || suffix.empty() || labels.empty()) {
        throw_java(env, kIllegalArgument, "invalid branch, suffix, or labels");
        return nullptr;
    }
    llama_memory_t memory = llama_get_memory(handle->ctx);
    if (source_length > 0 &&
        llama_memory_seq_pos_max(memory, source_seq) < source_length - 1) {
        throw_java(env, kIllegalState, "source prefix is not present in the sequence");
        return nullptr;
    }
    llama_memory_seq_rm(memory, branch_seq, -1, -1);
    llama_memory_seq_cp(memory, source_seq, branch_seq, 0, source_length);
    if (!decode(handle, suffix.data(), suffix.size(), source_length, branch_seq)) {
        llama_memory_seq_rm(memory, branch_seq, -1, -1);
        throw_java(env, kRuntimeException,
                   handle->abort.load() ? "label decode aborted" : "label decode failed");
        return nullptr;
    }
    const int32_t vocab_size = llama_vocab_n_tokens(
            llama_model_get_vocab(handle->model->model));
    std::vector<float> result;
    result.reserve(labels.size());
    llama_pos label_position = source_length + static_cast<llama_pos>(suffix.size());
    for (size_t i = 0; i < labels.size(); ++i) {
        const llama_token token = labels[i];
        if (token < 0 || token >= vocab_size) {
            llama_memory_seq_rm(memory, branch_seq, -1, -1);
            throw_java(env, kIllegalArgument, "label token is outside vocabulary");
            return nullptr;
        }
        float * logits = llama_get_logits_ith(handle->ctx, -1);
        if (!logits) {
            llama_memory_seq_rm(memory, branch_seq, -1, -1);
            throw_java(env, kRuntimeException, "label logits are unavailable");
            return nullptr;
        }
        result.push_back(logits[token]);
        // A multi-token label is scored autoregressively. There is no need to decode the final
        // label token because the disposable branch is removed immediately afterwards.
        if (i + 1 < labels.size() &&
            !decode(handle, &token, 1, label_position++, branch_seq)) {
            llama_memory_seq_rm(memory, branch_seq, -1, -1);
            throw_java(env, kRuntimeException,
                       handle->abort.load() ? "label decode aborted" : "label decode failed");
            return nullptr;
        }
    }
    llama_memory_seq_rm(memory, branch_seq, -1, -1);
    return to_jfloat_array(env, result.data(), result.size());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_operator_llm_OperatorNative_embedLast(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jintArray input,
        jint prefix_length) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->abort.store(false);
    const std::vector<llama_token> tokens = token_array(env, input);
    if (!prepare_sequence(env, handle, seq, tokens, prefix_length)) {
        return nullptr;
    }
    float * embedding = llama_get_embeddings_ith(handle->ctx, -1);
    if (!embedding) {
        throw_java(env, kIllegalState, "context was not created with embeddings enabled");
        return nullptr;
    }
    return to_jfloat_array(env, embedding,
                           static_cast<size_t>(llama_model_n_embd(handle->model->model)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_operator_llm_OperatorNative_clearSequence(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jint from) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return false;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (!valid_seq(handle, seq) || from < 0) {
        throw_java(env, kIllegalArgument, "invalid sequence or position");
        return false;
    }
    return llama_memory_seq_rm(llama_get_memory(handle->ctx), seq, from, -1);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_copySequence(
        JNIEnv * env, jobject, jlong context_handle, jint source_seq, jint destination_seq,
        jint from, jint to) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (!valid_seq(handle, source_seq) || !valid_seq(handle, destination_seq) ||
        source_seq == destination_seq || from < 0 || (to >= 0 && to < from)) {
        throw_java(env, kIllegalArgument, "invalid sequence copy range");
        return;
    }
    llama_memory_seq_rm(llama_get_memory(handle->ctx), destination_seq, -1, -1);
    llama_memory_seq_cp(llama_get_memory(handle->ctx), source_seq, destination_seq, from, to);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_operator_llm_OperatorNative_saveState(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jint flags) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (!valid_seq(handle, seq) || flags < 0) {
        throw_java(env, kIllegalArgument, "invalid sequence or state flags");
        return nullptr;
    }
    const size_t size = llama_state_seq_get_size_ext(handle->ctx, seq, flags);
    if (size > static_cast<size_t>(INT32_MAX)) {
        throw_java(env, kIllegalState, "state is too large for a JVM byte array");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(size));
    if (!result) {
        return nullptr;
    }
    jbyte * bytes = env->GetByteArrayElements(result, nullptr);
    const size_t written = llama_state_seq_get_data_ext(
            handle->ctx, reinterpret_cast<uint8_t *>(bytes), size, seq, flags);
    env->ReleaseByteArrayElements(result, bytes, 0);
    if (written != size) {
        throw_java(env, kRuntimeException, "failed to save sequence state");
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_restoreState(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jint flags, jbyteArray state) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return;
    }
    if (!state) {
        throw_java(env, kIllegalArgument, "state is required");
        return;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (!valid_seq(handle, seq) || flags < 0) {
        throw_java(env, kIllegalArgument, "invalid sequence or state flags");
        return;
    }
    const jsize size = env->GetArrayLength(state);
    jbyte * bytes = env->GetByteArrayElements(state, nullptr);
    const size_t read = llama_state_seq_set_data_ext(
            handle->ctx, reinterpret_cast<const uint8_t *>(bytes),
            static_cast<size_t>(size), seq, flags);
    env->ReleaseByteArrayElements(state, bytes, JNI_ABORT);
    if (read != static_cast<size_t>(size)) {
        throw_java(env, kRuntimeException, "failed to restore sequence state");
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_operator_llm_OperatorNative_saveStateFile(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jstring path,
        jintArray state_tokens) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    const std::string file = jstring_value(env, path);
    const std::vector<llama_token> tokens = token_array(env, state_tokens);
    if (!valid_seq(handle, seq) || file.empty()) {
        throw_java(env, kIllegalArgument, "invalid sequence or path");
        return 0;
    }
    const size_t written = llama_state_seq_save_file(
            handle->ctx, file.c_str(), seq, tokens.data(), tokens.size());
    if (written == 0) {
        throw_java(env, kRuntimeException, "failed to save sequence state file");
    }
    return static_cast<jlong>(written);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_operator_llm_OperatorNative_restoreStateFile(
        JNIEnv * env, jobject, jlong context_handle, jint seq, jstring path, jint max_tokens) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    const std::string file = jstring_value(env, path);
    if (!valid_seq(handle, seq) || file.empty() || max_tokens < 0) {
        throw_java(env, kIllegalArgument, "invalid sequence, path, or token capacity");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(max_tokens));
    size_t count = 0;
    const size_t read = llama_state_seq_load_file(
            handle->ctx, file.c_str(), seq, tokens.data(), tokens.size(), &count);
    if (read == 0 || count > tokens.size()) {
        throw_java(env, kRuntimeException, "failed to restore sequence state file");
        return nullptr;
    }
    tokens.resize(count);
    return to_jint_array(env, tokens);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_abort(
        JNIEnv * env, jobject, jlong context_handle) {
    ContextHandle * handle = get_context(env, context_handle);
    if (handle) {
        handle->abort.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_operator_llm_OperatorNative_attachThreadpool(
        JNIEnv * env, jobject, jlong context_handle, jint decode_threads,
        jint batch_threads, jintArray cpu_ids) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return;
    }
    if (decode_threads <= 0 || batch_threads <= 0) {
        throw_java(env, kIllegalArgument, "thread counts must be positive");
        return;
    }
    std::vector<jint> cpus;
    if (cpu_ids) {
        cpus.resize(static_cast<size_t>(env->GetArrayLength(cpu_ids)));
        env->GetIntArrayRegion(cpu_ids, 0, static_cast<jsize>(cpus.size()), cpus.data());
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    auto * device = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    auto * registry = device ? ggml_backend_dev_backend_reg(device) : nullptr;
    auto * create = registry ? reinterpret_cast<decltype(ggml_threadpool_new) *>(
            ggml_backend_reg_get_proc_address(registry, "ggml_threadpool_new")) : nullptr;
    auto * destroy = registry ? reinterpret_cast<decltype(ggml_threadpool_free) *>(
            ggml_backend_reg_get_proc_address(registry, "ggml_threadpool_free")) : nullptr;
    if (!create || !destroy) {
        throw_java(env, kIllegalState, "loaded CPU backend has no threadpool API");
        return;
    }
    llama_detach_threadpool(handle->ctx);
    release_pools(handle);

    auto make_params = [&cpus](jint threads) {
        ggml_threadpool_params params = ggml_threadpool_params_default(threads);
        if (!cpus.empty()) {
            std::fill(std::begin(params.cpumask), std::end(params.cpumask), false);
            for (jint cpu : cpus) {
                if (cpu >= 0 && cpu < GGML_MAX_N_THREADS) {
                    params.cpumask[cpu] = true;
                }
            }
            params.strict_cpu = true;
        }
        return params;
    };
    ggml_threadpool_params decode_params = make_params(decode_threads);
    ggml_threadpool_params batch_params = make_params(batch_threads);
    handle->pool = create(&decode_params);
    if (decode_threads != batch_threads) {
        handle->pool_batch = create(&batch_params);
    }
    if (!handle->pool || (decode_threads != batch_threads && !handle->pool_batch)) {
        destroy(handle->pool);
        destroy(handle->pool_batch);
        handle->pool = nullptr;
        handle->pool_batch = nullptr;
        throw_java(env, kRuntimeException, "failed to create ggml threadpool");
        return;
    }
    handle->pool_free = destroy;
    llama_attach_threadpool(handle->ctx, handle->pool, handle->pool_batch);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_operator_llm_OperatorNative_stats(
        JNIEnv * env, jobject, jlong context_handle) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    const llama_perf_context_data perf = llama_perf_context(handle->ctx);
    return to_jdouble_array(env, {
            static_cast<double>(perf.n_p_eval),
            static_cast<double>(perf.n_eval),
            perf.t_p_eval_ms,
            perf.t_eval_ms,
            handle->model->load_ms,
            static_cast<double>(perf.n_reused),
    });
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_dev_operator_llm_OperatorNative_bench(
        JNIEnv * env, jobject, jlong context_handle, jint pp, jint tg) {
    ContextHandle * handle = get_context(env, context_handle);
    if (!handle) {
        return nullptr;
    }
    if (pp <= 0 || tg < 0 || static_cast<uint32_t>(pp + tg) > llama_n_ctx_seq(handle->ctx)) {
        throw_java(env, kIllegalArgument, "benchmark does not fit the context");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(handle->mutex);
    handle->abort.store(false);
    llama_memory_clear(llama_get_memory(handle->ctx), false);
    llama_perf_context_reset(handle->ctx);
    std::vector<llama_token> prompt(static_cast<size_t>(pp), 0);
    const auto pp_start = std::chrono::steady_clock::now();
    if (!decode(handle, prompt.data(), prompt.size(), 0, 0)) {
        throw_java(env, kRuntimeException, "benchmark prefill failed");
        return nullptr;
    }
    const double pp_seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - pp_start).count();

    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_init_greedy(), llama_sampler_free);
    const auto tg_start = std::chrono::steady_clock::now();
    for (jint i = 0; i < tg; ++i) {
        const llama_token token = llama_sampler_sample(sampler.get(), handle->ctx, -1);
        if (!decode(handle, &token, 1, pp + i, 0)) {
            throw_java(env, kRuntimeException, "benchmark generation failed");
            return nullptr;
        }
    }
    const double tg_seconds = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - tg_start).count();
    return to_jdouble_array(env, {
            pp_seconds == 0 ? 0 : pp / pp_seconds,
            tg == 0 || tg_seconds == 0 ? 0 : tg / tg_seconds,
            pp_seconds * 1000,
            tg_seconds * 1000,
    });
}
