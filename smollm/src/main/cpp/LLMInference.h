#pragma once
#include "chat.h"
#include "common.h"
#include "llama.h"
#include <string>
#include <vector>

class LLMInference {
    // llama.cpp-specific types
    llama_context* _ctx       = nullptr;
    llama_model*   _model     = nullptr;
    llama_sampler* _sampler   = nullptr;
    llama_token    _currToken = 0;
    llama_batch*   _batch     = nullptr;

    llama_batch g_batch;

    // container to store user/assistant messages in the chat
    std::vector<llama_chat_message> _messages;
    // stores the string generated after applying
    // the chat-template to all messages in `_messages`
    std::vector<char> _formattedMessages;
    // stores the tokens for the last query
    // appended to `_messages`
    std::vector<llama_token> _promptTokens;
    const char*              _chatTemplate;

    // stores the complete response for the given query
    std::string _response;
    std::string _cacheResponseTokens;
    // whether to cache previous messages in `_messages`
    bool _storeChats;

    // response generation metrics
    int64_t _responseGenerationTime = 0;
    long    _responseNumTokens      = 0;
    // true until the prompt tokens have been decoded (prefill step),
    // used to exclude prompt-processing time from the tokens/sec metric
    bool _isPrefill = false;

    // length of context window consumed during the conversation
    int _nCtxUsed = 0;

    // sampling parameters, applied by _rebuildSampler()
    float    _temperature     = 0.8f;
    float    _topP            = 1.0f;
    int      _topK            = 0;
    float    _minP            = 0.1f;
    float    _presencePenalty = 0.0f;
    uint32_t _seed            = LLAMA_DEFAULT_SEED;

    bool _promptOpensThink = false;

    bool _isValidUtf8(const char* response);

    void _rebuildSampler();

    // renders `_messages` with the chat template and tokenizes the result
    bool _preparePrompt(bool enableThinking);

  public:
    void loadModel(const char* modelPath, float minP, float temperature, bool storeChats, long contextSize,
                   const char* chatTemplate, int nThreads, bool useMmap, bool useMlock);

    std::string benchModel(int pp, int tg, int pl, int nr);

    void addChatMessage(const char* message, const char* role);

    void clearMessages();

    // returns the response generation speed in tokens/sec (excludes prompt processing)
    float getResponseGenerationSpeed() const;

    int getContextSizeUsed() const;

    // Returns true if Jinja template was used, false if legacy fallback was needed.
    bool startCompletion(const char* query);

    // Uses the messages already added with addChatMessage() (no extra user message is appended).
    // `enableThinking` is forwarded to the chat template as the `enable_thinking` kwarg.
    // Returns true if Jinja template was used, false if legacy fallback was needed.
    bool startChatCompletion(bool enableThinking);

    // Replaces the sampler chain: presence penalty -> top-k -> top-p -> min-p -> temperature -> dist
    void setSamplingParams(float temperature, float topP, int topK, float minP, float presencePenalty, int64_t seed);

    // true if the rendered prompt ends with an opening <think> tag (the template opened the block)
    bool promptOpensThink() const;

    // number of tokens in the prompt prepared by the last start*Completion() call
    int getPromptTokenCount() const;

    // context window size of the loaded context
    int getContextSize() const;

    std::string completionLoop();

    void stopCompletion();

    ~LLMInference();
};