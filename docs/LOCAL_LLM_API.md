# Local LLM API — contract v2

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
| API version   | `2` (returned by `/health` and the pairing result)                |

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
* A **blocking** or **streaming** request belongs to its connection: if the client
  disconnects while it is queued, while the prompt is processed or while it streams, the
  request is cancelled and the model stops within a few tokens.
* A **background** request (§2.4) does not depend on a connection. It keeps its place in
  the queue and runs to the end whatever happens to the client.

### 1.3 Long requests

On a phone one request can take minutes: the prompt is processed first (a second or more
per 10–20 tokens on slow cores), then the answer is written token by token. A client must
not rely on one long HTTP call for that. Android freezes background apps, connections
drop, and a read timeout then looks like a server failure. Use one of:

* **Background jobs (§2.4), recommended.** Every HTTP call is short, the job survives a
  frozen or killed client and a restart of the server, and its progress can be shown.
  There is no time limit: a generation may run for hours.
* **Streaming.** While the prompt is processed and between tokens the server sends an SSE
  comment line (`: keep-alive`) every 5 seconds, so a read timeout of 30 seconds is safe.
  Nothing is sent while the request waits in the queue or the model loads.

The server holds a partial wake lock from the moment a request is accepted until it ends
(renewed for as long as there is work), so the screen can be off.

### 1.4 Prompt cache

The server keeps the model's state between requests and only processes the part of the
prompt that differs from the previous one. Clients that repeat a long system prompt get
most of it for free: put the stable text **first** (system prompt), the variable text last.
`timings.cached_prompt_tokens` and `progress.prompt_tokens_cached` say how much was reused.

---

## 2. Endpoints

### 2.1 `GET /health` (no auth)

Lets a client check whether the server is running before (or without) pairing.

```json
{
  "status": "ok",
  "api_version": 1,
  "model": "Qwen3.5-4B-Q4_K_M",
  "queue_length": 0,
  "features": ["background_jobs", "prompt_cache", "request_id", "durable_jobs"]
}
```

`features` (v2) lists optional capabilities. A v1 server has no such field.

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
  "seed": 42,
  "background": false,
  "request_id": "7d0c6f0e-2a55-4f0b-9d0e-6c6f4d1b2a90"
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
| `background` *(extension, v2)*         | boolean | no       | `false`; `true` answers `202` with a job (§2.4) and wins over `stream` |
| `request_id` *(extension, v2)*         | string  | no       | the client's own id for a background request (§2.4): 8–128 characters of `A–Z a–z 0–9 . _ : -`, for example a UUID |

Set `max_tokens` whenever the length of the answer is predictable. Without it a model that
does not stop writes until the context window is full, which can take many minutes.

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
  "timings": {"prompt_ms": 2100.0, "generation_ms": 9800.0, "tokens_per_second": 14.3,
              "queue_ms": 0, "load_ms": 3100, "cached_prompt_tokens": 380}
}
```

* `finish_reason`: `stop` (end of generation) or `length` (`max_tokens` reached or
  the context window filled up during generation).
* `message.reasoning_content` is present only when `include_thinking` is `true`.
* `timings` is an extension. `queue_ms` is the wait for the model, `load_ms` is present
  when this request had to load the model, `cached_prompt_tokens` is the part of the prompt
  taken from the prompt cache (§1.4).

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
* Lines that start with `:` are SSE comments (`: keep-alive`, §1.3). Ignore them.
* The final chunk has an empty `delta`, a `finish_reason` and `usage`.
* An error **after** the stream has started is sent as one event,
  `data: {"error":{…}}` (same shape as §4), followed by `data: [DONE]`.
* Errors found **before** generation starts (auth, validation, context overflow,
  busy, no model) are plain JSON error responses with the HTTP status from §4,
  even when `stream` is `true`.

### 2.4 Background jobs (v2)

`POST /v1/chat/completions` with `"background": true` validates the request, puts it in the
queue and answers **at once**:

```
HTTP/1.1 202 Accepted
Location: /v1/jobs/chatcmpl-3f9c1a2b7d4e5f6a7b8c9d0e
```

```json
{
  "id": "chatcmpl-3f9c1a2b7d4e5f6a7b8c9d0e",
  "object": "chat.completion.job",
  "request_id": "7d0c6f0e-2a55-4f0b-9d0e-6c6f4d1b2a90",
  "status": "queued",
  "created": 1763000000,
  "queue_position": 1,
  "resumes": 0,
  "progress": {"prompt_tokens": 0, "prompt_tokens_processed": 0, "prompt_tokens_cached": 0,
               "completion_tokens": 0, "elapsed_ms": 3}
}
```

A v1 server ignores `background` and answers `200` with the finished completion, so a
client can send it to both: look at `object` (`chat.completion.job` or `chat.completion`).

#### `request_id`: the client names its generation

The client should choose a `request_id` (a UUID) and **store it before it sends the
request**. For the server, the pair (paired app, `request_id`) is the identity of the
generation:

* Posting the same `request_id` again never starts a second generation. The answer is the
  job that already has this id, whatever state it is in (`202`; a finished job comes with
  its `response`). So a POST whose answer got lost is simply sent again.
* Every `/v1/jobs/{id}` call accepts the `request_id` in place of the server's `id`.
  A client that only remembers its own id finds its generation again.
* Ids are scoped to the paired app. Two apps can use the same id without meeting.
* The id is free again once the job has been deleted (below) or has expired.

#### Durability

A background job is written to the server's disk when it is accepted: the request, then a
checkpoint of the answer every 32 tokens or 20 seconds, then the result.

* If the server is **stopped** (Stop button), the job is parked. If the server process is
  **killed** or crashes, the job is simply still on disk. Either way it goes back into the
  queue when the server runs again and **continues from its checkpoint**: what was written
  is kept, nothing is generated twice. `resumes` counts how often that happened.
* While the server is down, calls get "connection refused". That says nothing about the
  job. Keep the `request_id` and ask again later.
* A finished job and its result are kept for **24 hours** (at most 50 results), across
  restarts, and can be fetched any number of times. Then the id answers `404 not_found`.
* After a phone reboot the server does not start by itself; the job continues when the
  user opens the server app.

#### `GET /v1/jobs/{id}?wait=N&content=true`

Returns the job; `{id}` is the server's `id` or the client's `request_id`. With `wait`
(seconds, at most 30) the server holds the call until the job has ended or `N` seconds
have passed, whichever comes first, so a client can poll with `wait=2` and still see the
result the moment it exists. With `content=true` the answer also has `content`: the
visible text written so far, so a client that reconnects can show it.

| `status`            | meaning                                                          |
|---------------------|------------------------------------------------------------------|
| `queued`            | waiting for the model; `queue_position` is 1 for the next in line |
| `loading_model`     | the model is being loaded for this job                            |
| `processing_prompt` | `progress.prompt_tokens_processed` of `progress.prompt_tokens`    |
| `generating`        | `progress.completion_tokens` written so far, `tokens_per_second`  |
| `completed`         | `response` holds the chat completion of §2.3                      |
| `failed`            | `error` holds the error body of §4                                |
| `cancelled`         | stopped by `DELETE` or by the user in the server app; `error.code` is `cancelled` |

```json
{
  "id": "chatcmpl-3f9c1a2b7d4e5f6a7b8c9d0e",
  "object": "chat.completion.job",
  "request_id": "7d0c6f0e-2a55-4f0b-9d0e-6c6f4d1b2a90",
  "status": "generating",
  "created": 1763000000,
  "model": "Qwen3.5-4B-Q4_K_M",
  "resumes": 1,
  "progress": {"prompt_tokens": 912, "prompt_tokens_processed": 912, "prompt_tokens_cached": 520,
               "completion_tokens": 141, "max_tokens": 1000, "tokens_per_second": 6.4,
               "elapsed_ms": 48210}
}
```

* `progress.max_tokens` is the upper bound of the answer (the request's `max_tokens`, or
  the room left in the context window). It is a limit, not a forecast.
* Jobs are private to the app that created them. Another app's token gets `404 not_found`.
* Status calls are not written to the request log. The job itself is logged once, when it ends.

#### `GET /v1/jobs`

`{"object": "list", "data": [ …jobs… ]}`: the calling app's background jobs that the server
knows right now (queued, running, and finished ones not yet deleted or expired), oldest
first, without `response`. Useful after a reinstall or lost client state.

#### `DELETE /v1/jobs/{id}`

* Queued or running: the job is stopped. The answer is its final state (`cancelled`).
* Finished: the stored result is deleted. This is how a client says "I have it".

Either way the job is gone afterwards and its `request_id` is free. Clients should delete
jobs whose result they have or no longer want: a running job keeps the model busy for
everybody else, and a stored result is text on disk.

#### What a client should do

1. Create a `request_id` and store it together with something that identifies the input
   (for example a hash). Only then `POST` with `background: true`, the `request_id` and a
   sensible `max_tokens`.
2. Loop `GET /v1/jobs/{request_id}?wait=2` until the status is `completed`, `failed` or
   `cancelled`. Show `status` and `progress`. Use short HTTP timeouts (10–20 seconds).
3. A failed or timed-out call says nothing about the job: try again. "Connection refused"
   means the server is not running; keep the id and try again later.
4. Whenever the app comes back (from the background, after being killed, after any error)
   and the stored input still matches: `POST` again with the **same** `request_id`. The
   server answers with the same job, and the loop of step 2 continues.
5. `404 not_found` for a stored id means the server no longer has the job (expired, or its
   data was cleared): `POST` again with the same id to start it anew.
6. When the result has arrived, `DELETE` the job and forget the `request_id`.

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
| `api_version`    | int    | `2`                           |
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
| 404  | `not_found`               | `invalid_request_error` | Unknown path, or a job that is unknown, deleted, expired or another app's |
| 429  | `server_busy`             | `server_error`          | Queue full; retry after `Retry-After` seconds        |
| 503  | `model_not_loaded`        | `server_error`          | No model selected, or loading it failed; open the server app |
| 500  | `internal_error`          | `server_error`          | Inference failed (native error)                      |
| —    | `cancelled`               | `server_error`          | Only inside a job (§2.4) or a started stream: the request was stopped |

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
* While a request runs, the server app shows who asked, the phase, token counts and, on
  demand, the tail of the text being written. That preview lives in memory only.
* A **background job** is the one exception to "no text on disk": its request, a checkpoint
  of the answer and the result are stored in the server's private database so the job
  survives a restart (§2.4). They are deleted when the client deletes the job, and at the
  latest 24 hours after it ended. Blocking and streaming requests are never stored.
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

* **v2**: background jobs (`background: true`, `GET`/`DELETE /v1/jobs/{id}`, `GET /v1/jobs`,
  §2.4) that are addressed by the client's own `request_id`, stored on disk, and continued
  from a checkpoint after the server was stopped or killed; SSE
  keep-alive comments (§1.3); prompt cache (§1.4); `timings.queue_ms`, `load_ms`,
  `cached_prompt_tokens`; `features` in `/health`. A blocking or streaming request is now
  really cancelled when its client disconnects (v1 said so, but the server kept generating).
  Everything in v1 still works unchanged.
* **v1**: first version.
