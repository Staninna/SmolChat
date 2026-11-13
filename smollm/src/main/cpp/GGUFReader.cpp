#include "gguf.h"
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_GGUFReader_getGGUFContextNativeHandle(JNIEnv* env, jobject thiz, jstring modelPath) {
    jboolean         isCopy        = true;
    const char*      modelPathCStr = env->GetStringUTFChars(modelPath, &isCopy);
    gguf_init_params initParams    = { .no_alloc = true, .ctx = nullptr };
    gguf_context*    ggufContext   = gguf_init_from_file(modelPathCStr, initParams);
    env->ReleaseStringUTFChars(modelPath, modelPathCStr);
    return reinterpret_cast<jlong>(ggufContext);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_GGUFReader_getContextSize(JNIEnv* env, jobject thiz, jlong nativeHandle) {
    gguf_context* ggufContext       = reinterpret_cast<gguf_context*>(nativeHandle);
    int64_t       architectureKeyId = gguf_find_key(ggufContext, "general.architecture");
    if (architectureKeyId == -1)
        return -1;
    std::string architecture       = gguf_get_val_str(ggufContext, architectureKeyId);
    std::string contextLengthKey   = architecture + ".context_length";
    int64_t     contextLengthKeyId = gguf_find_key(ggufContext, contextLengthKey.c_str());
    if (contextLengthKeyId == -1)
        return -1;
    uint32_t contextLength = gguf_get_val_u32(ggufContext, contextLengthKeyId);
    return contextLength;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_GGUFReader_getChatTemplate(JNIEnv* env, jobject thiz, jlong nativeHandle) {
    gguf_context* ggufContext       = reinterpret_cast<gguf_context*>(nativeHandle);
    int64_t       chatTemplateKeyId = gguf_find_key(ggufContext, "tokenizer.chat_template");
    std::string   chatTemplate;
    if (chatTemplateKeyId == -1) {
        chatTemplate = "";
    } else {
        chatTemplate = gguf_get_val_str(ggufContext, chatTemplateKeyId);
    }
    return env->NewStringUTF(chatTemplate.c_str());
}
// Returns the string value for `key`, or an empty string if the key is missing or not a string.
extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_GGUFReader_getString(JNIEnv* env, jobject thiz, jlong nativeHandle, jstring key) {
    auto*       ggufContext = reinterpret_cast<gguf_context*>(nativeHandle);
    const char* keyCStr     = env->GetStringUTFChars(key, nullptr);
    int64_t     keyId       = gguf_find_key(ggufContext, keyCStr);
    env->ReleaseStringUTFChars(key, keyCStr);
    if (keyId == -1 || gguf_get_kv_type(ggufContext, keyId) != GGUF_TYPE_STRING) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(gguf_get_val_str(ggufContext, keyId));
}

static int64_t readIntegerAt(const gguf_context* ctx, int64_t keyId, gguf_type type, const void* data, size_t i) {
    switch (type) {
        case GGUF_TYPE_UINT8:  return ((const uint8_t*) data)[i];
        case GGUF_TYPE_INT8:   return ((const int8_t*) data)[i];
        case GGUF_TYPE_UINT16: return ((const uint16_t*) data)[i];
        case GGUF_TYPE_INT16:  return ((const int16_t*) data)[i];
        case GGUF_TYPE_UINT32: return ((const uint32_t*) data)[i];
        case GGUF_TYPE_INT32:  return ((const int32_t*) data)[i];
        case GGUF_TYPE_UINT64: return (int64_t) ((const uint64_t*) data)[i];
        case GGUF_TYPE_INT64:  return ((const int64_t*) data)[i];
        default:               return -1;
    }
}

// Returns an integer value for `key` (for integer arrays: the maximum element), or -1 if missing.
extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_GGUFReader_getInteger(JNIEnv* env, jobject thiz, jlong nativeHandle, jstring key) {
    auto*       ggufContext = reinterpret_cast<gguf_context*>(nativeHandle);
    const char* keyCStr     = env->GetStringUTFChars(key, nullptr);
    int64_t     keyId       = gguf_find_key(ggufContext, keyCStr);
    env->ReleaseStringUTFChars(key, keyCStr);
    if (keyId == -1) {
        return -1;
    }
    gguf_type type = gguf_get_kv_type(ggufContext, keyId);
    if (type == GGUF_TYPE_ARRAY) {
        gguf_type arrType = gguf_get_arr_type(ggufContext, keyId);
        size_t    n       = gguf_get_arr_n(ggufContext, keyId);
        if (arrType == GGUF_TYPE_STRING || arrType == GGUF_TYPE_ARRAY) {
            return -1;
        }
        const void* data = gguf_get_arr_data(ggufContext, keyId);
        int64_t     best = -1;
        for (size_t i = 0; i < n; i++) {
            int64_t v = readIntegerAt(ggufContext, keyId, arrType, data, i);
            if (v > best) best = v;
        }
        return best;
    }
    switch (type) {
        case GGUF_TYPE_UINT8:  return gguf_get_val_u8(ggufContext, keyId);
        case GGUF_TYPE_INT8:   return gguf_get_val_i8(ggufContext, keyId);
        case GGUF_TYPE_UINT16: return gguf_get_val_u16(ggufContext, keyId);
        case GGUF_TYPE_INT16:  return gguf_get_val_i16(ggufContext, keyId);
        case GGUF_TYPE_UINT32: return gguf_get_val_u32(ggufContext, keyId);
        case GGUF_TYPE_INT32:  return gguf_get_val_i32(ggufContext, keyId);
        case GGUF_TYPE_UINT64: return (jlong) gguf_get_val_u64(ggufContext, keyId);
        case GGUF_TYPE_INT64:  return gguf_get_val_i64(ggufContext, keyId);
        default:               return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_GGUFReader_free(JNIEnv* env, jobject thiz, jlong nativeHandle) {
    if (nativeHandle != 0) {
        gguf_free(reinterpret_cast<gguf_context*>(nativeHandle));
    }
}
