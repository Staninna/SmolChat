# Local LLM API — contract v1

This document is the shared contract between the **server** (the SmolChat fork,
package `io.shubham0204.smollmandroid`) and its **clients** (for example the June
fork, package `com.denser.june` / `com.denser.june.debug`). The same file lives in
both repositories at `docs/LOCAL_LLM_API.md`; change it in both or in neither.

Design goals: fully on-device, FOSS only, no traffic beyond `127.0.0.1`, one model
in RAM, OpenAI-compatible enough that existing client code needs few changes, and
explicit, revocable per-app consent.

---

## 1. Transport

| Item          | Value                                                             |
|---------------|-------------------------------------------------------------------|
| Bind address  | `127.0.0.1` only (never `0.0.0.0`, never IPv6 any)                |
| Port          | `8080` by default, configurable in the server's settings          |
| Scheme        | plain `http` (loopback only; TLS adds nothing here)               |
| Encoding      | UTF-8 JSON, `Content-Type: application/json`                      |
| Streaming     | Server-Sent Events, `Content-Type: text/event-stream`             |
| API version   | `1` (returned by `/health` and the pairing result)                |

All paths below are relative to `base_url`, which the pairing result provides
(for example `http://127.0.0.1:8080`). Clients must not hard-code the port.

> **GrapheneOS:** loopback connections count as network access. Every **client** app
> needs the *Network* permission toggle ON (Settings → Apps → *client* → Permissions).
> Without it, `connect()` fails with `EACCES`/`ECONNREFUSED` and the client should
> show its "server not reachable" state. Clients that target API 28+ must also allow
> cleartext traffic to `127.0.0.1` in their network security config.

### 1.1 Authentication

Every endpoint except `GET /health` requires:

```
Authorization: Bearer <token>
```

* One token per client app, issued by pairing (§3). Tokens look like
  `sk-local-` followed by 43 base64url characters (256 random bits).
* The server stores only a SHA-256 hash of each token, so a token cannot be shown
  again. Re-pairing replaces the app's previous token.
* Revoking an app in the server's UI invalidates its token at once. Requests then
  get `401 unauthorized`, and the client should offer to pair again.

### 1.2 Concurrency

The server runs **one inference at a time**. Requests are queued **FIFO**.

* Queue capacity: 4 waiting requests (in addition to the running one).
* When the queue is full the server answers `429 server_busy` with `Retry-After: 5`.
* If the client disconnects while queued or streaming, the request is cancelled and
  generation stops at the next token.

---

## 2. Endpoints

### 2.1 `GET /health` (no auth)

Lets a client check whether the server is running before (or without) pairing.

```json
{
  "status": "ok",
  "api_version": 1,
  "model": "Qwen3.5-4B-Q4_K_M",
  "queue_length": 0
}
```

`status` is one of:

| status      | meaning                                                         |
|-------------|-----------------------------------------------------------------|
| `ok`        | a model is loaded and ready                                     |
| `busy`      | a model is loaded and generating                                |
| `loading`   | a model is being loaded (first request after start or idle)     |
| `no_model`  | the server runs, but no model is imported or selected           |
| `idle`      | the model was unloaded after the idle timeout; it reloads on demand |

HTTP status is always `200` while the server is up. "Connection refused" means the
server (its foreground service) is not running.

### 2.2 `GET /v1/models`

```json
{
  "object": "list",
  "data": [
    {
      "id": "Qwen3.5-4B-Q4_K_M",
      "object": "model",
      "created": 1763000000,
      "owned_by": "local",
      "architecture": "qwen35",
      "context_length": 8192,
      "loaded": true
    }
  ]
}
```

Only the **active** model is listed. The server keeps exactly one model loaded, so
the `model` field of a chat request is informational and is ignored.
`architecture`, `context_length` and `loaded` are extensions.

### 2.3 `POST /v1/chat/completions`

#### Request

```json
{
  "model": "Qwen3.5-4B-Q4_K_M",
  "messages": [
    {"role": "system", "content": "You rewrite my voice-dictated notes…"},
    {"role": "user",   "content": "so uh today i went to albert hine…"}
  ],
  "temperature": 0.7,
  "top_p": 0.8,
  "top_k": 20,
  "min_p": 0.0,
  "presence_penalty": 0.0,
  "max_tokens": 2048,
  "stream": false,
  "chat_template_kwargs": {"enable_thinking": false},
  "include_thinking": false,
  "seed": 42
}
```

| Field                                  | Type    | Required | Default (server setting)       |
|----------------------------------------|---------|----------|--------------------------------|
| `model`                                | string  | no       | ignored, the active model is used |
| `messages`                             | array   | **yes**  | at least one item              |
| `messages[].role`                      | string  | yes      | `system`, `user`, `assistant` (`developer` is treated as `system`) |
| `messages[].content`                   | string  | yes      | plain text only (no content parts) |
| `temperature`                          | number  | no       | `0.7`  (range 0–2)             |
| `top_p`                                | number  | no       | `0.8`  (range 0–1)             |
| `top_k`                                | integer | no       | `20`   (0 = disabled)          |
| `min_p`                                | number  | no       | `0.0`  (range 0–1)             |
| `presence_penalty`                     | number  | no       | `0.0`  (range −2–2)            |
| `max_tokens`                           | integer | no       | the rest of the context window |
| `stream`                               | boolean | no       | `false`                        |
| `chat_template_kwargs.enable_thinking` | boolean | no       | `false`                        |
| `include_thinking` *(extension)*       | boolean | no       | `false`                        |
| `seed` *(extension)*                   | integer | no       | random                         |

Unknown fields are ignored. Out-of-range values give `400 invalid_request`.

**Thinking.** `chat_template_kwargs.enable_thinking` goes straight to the model's
chat template (for Qwen3.5, `false` renders an empty `<think></think>` block so the
model answers directly). Whatever the setting, the server **removes every
`<think>…</think>` block from `content`**. With `include_thinking: true` the removed
text is returned in `reasoning_content` (the llama.cpp server convention); without
it, the text is dropped.

**Context.** If `prompt_tokens + max_tokens > context_length` (or, without
`max_tokens`, if the prompt alone fills the context), the server rejects the request
with `400 context_length_exceeded` before it generates anything.

#### Response (`stream: false`)

```json
{
  "id": "chatcmpl-3f9c1a2b7d4e",
  "object": "chat.completion",
  "created": 1763000000,
  "model": "Qwen3.5-4B-Q4_K_M",
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": "Today I went to Albert Heijn…"},
      "finish_reason": "stop"
    }
  ],
  "usage": {"prompt_tokens": 512, "completion_tokens": 140, "total_tokens": 652},
  "timings": {"prompt_ms": 2100.0, "generation_ms": 9800.0, "tokens_per_second": 14.3}
}
```

* `finish_reason`: `stop` (end of generation) or `length` (`max_tokens` reached or
  the context window filled up during generation).
* `message.reasoning_content` is present only when `include_thinking` is `true`.
* `timings` is an extension.

#### Response (`stream: true`)

The response is `text/event-stream`. Each event is one `data:` line followed by a
blank line:

```
data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":1763000000,"model":"Qwen3.5-4B-Q4_K_M","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":1763000000,"model":"Qwen3.5-4B-Q4_K_M","choices":[{"index":0,"delta":{"content":"Today I"},"finish_reason":null}]}

data: {"id":"chatcmpl-…","object":"chat.completion.chunk","created":1763000000,"model":"Qwen3.5-4B-Q4_K_M","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":512,"completion_tokens":140,"total_tokens":652}}

data: [DONE]

```

* The `<think>` filter works on the stream too: thinking text never appears in
  `delta.content`. With `include_thinking`, it arrives as `delta.reasoning_content`.
* The final chunk has an empty `delta`, a `finish_reason` and `usage`.
* An error **after** the stream has started is sent as one event,
  `data: {"error":{…}}` (same shape as §4), followed by `data: [DONE]`.
* Errors found **before** generation starts (auth, validation, context overflow,
  busy, no model) are plain JSON error responses with the HTTP status from §4,
  even when `stream` is `true`.

---

## 3. Pairing (consent and token exchange)

Pairing uses an explicit Intent, so the user always sees and approves the client.

### 3.1 Request (client → server)

```kotlin
val intent = Intent("io.shubham0204.smollmandroid.action.PAIR")
    .setPackage("io.shubham0204.smollmandroid")
    .putExtra("client_name", "June")   // human readable, shown in the dialog
launcher.launch(intent)                 // ActivityResultContracts.StartActivityForResult
```

* Action: **`io.shubham0204.smollmandroid.action.PAIR`**
* Package: **`io.shubham0204.smollmandroid`**
* Extra `client_name` (String, optional): display name. The server shows the
  calling app's **label and icon from the PackageManager** and uses `client_name`
  only when no label is available, so an app cannot pretend to be another app.
* The Intent **must** be started for a result (`startActivityForResult` /
  `ActivityResultLauncher`). The server identifies the client by
  `Activity.getCallingPackage()`. Without a calling package it returns
  `RESULT_CANCELED` at once.
* Clients targeting API 30+ must declare package visibility:

```xml
<queries>
    <package android:name="io.shubham0204.smollmandroid" />
    <intent><action android:name="io.shubham0204.smollmandroid.action.PAIR" /></intent>
</queries>
```

### 3.2 Consent UI (server)

A translucent activity shows a Material 3 dialog:

> **Allow *June* to use your local AI?**
> *June (com.denser.june) will be able to send text to the model running on this
> phone. Nothing leaves the device. You can revoke access at any time in Paired apps.*
>
> [Don't allow] [Allow]

### 3.3 Result (server → client)

`RESULT_OK` with these extras:

| Extra            | Type   | Example                       |
|------------------|--------|-------------------------------|
| `token`          | String | `sk-local-Qm9v…`              |
| `base_url`       | String | `http://127.0.0.1:8080`       |
| `api_version`    | int    | `1`                           |
| `server_package` | String | `io.shubham0204.smollmandroid`|

`RESULT_CANCELED` (no extras) if the user declines, dismisses the dialog, or the
request is invalid. Clients store the token privately (app-private storage, not in
backups that leave the device) and never log it.

If the user later changes the server's port, existing clients still have the old
`base_url` and have to pair again. The server says so next to the port setting.

### 3.4 Opening the server

When `/health` cannot be reached, clients should offer **"Open server app"**:

```kotlin
context.packageManager.getLaunchIntentForPackage("io.shubham0204.smollmandroid")
```

When `null` is returned (the server is not installed), say so instead of crashing.
Opening the server's main screen starts its foreground service once a model is set up.

---

## 4. Errors

Every error uses the OpenAI error envelope:

```json
{
  "error": {
    "message": "No model is loaded. Open the server app and pick a model.",
    "type": "server_error",
    "code": "model_not_loaded"
  }
}
```

| HTTP | `code`                    | `type`                  | Meaning / client action                              |
|------|---------------------------|-------------------------|------------------------------------------------------|
| 400  | `invalid_request`         | `invalid_request_error` | Malformed JSON, missing `messages`, value out of range |
| 400  | `context_length_exceeded` | `invalid_request_error` | Prompt (+ `max_tokens`) does not fit in the context window; shorten the input |
| 401  | `unauthorized`            | `authentication_error`  | Missing, unknown or revoked token; pair again        |
| 404  | `not_found`               | `invalid_request_error` | Unknown path                                         |
| 429  | `server_busy`             | `server_error`          | Queue full; retry after `Retry-After` seconds        |
| 503  | `model_not_loaded`        | `server_error`          | No model selected, or loading it failed; open the server app |
| 500  | `internal_error`          | `server_error`          | Inference failed (native error)                      |

A request that arrives while the model is loading (cold start or after the idle
unload) **waits** in the queue until loading is done; it does not get an error.

---

## 5. Privacy

* The server binds to `127.0.0.1` only and makes **no** outbound network
  connections.
* The request log stores **metadata only**: time, client app, endpoint, HTTP status,
  error code, token counts, duration and tokens/sec. Prompt and response text are
  stored only while the user has turned on *Debug: log request bodies* in the
  server's settings, and turning it off deletes the stored bodies.
* No analytics, crash reporting, Google Play Services or Firebase on either side.

---

## 6. Reference defaults (Qwen3.5, non-thinking)

| Setting            | Default             |
|--------------------|---------------------|
| Model              | `Qwen3.5-4B-Q4_K_M.gguf` (`general.architecture = qwen35`) |
| `n_ctx`            | 8192                |
| temperature        | 0.7                 |
| top_p              | 0.8                 |
| top_k              | 20                  |
| min_p              | 0.0                 |
| presence_penalty   | 0.0                 |
| enable_thinking    | false               |
| threads            | number of performance cores (detected from max CPU frequencies) |
| idle unload        | after 10 minutes without requests (configurable, can be off) |

Every value can be changed in the server's settings. Per-request fields override
the sampling values for that request only.

---

## 7. Changelog

* **v1**: first version.
