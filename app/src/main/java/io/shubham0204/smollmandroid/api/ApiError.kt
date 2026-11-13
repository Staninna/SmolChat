/*
 * Copyright (C) 2026 SmolChat contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.shubham0204.smollmandroid.api

/** Error codes from docs/LOCAL_LLM_API.md §4. */
enum class ApiError(val httpStatus: Int, val code: String, val type: String) {
    InvalidRequest(400, "invalid_request", "invalid_request_error"),
    ContextLengthExceeded(400, "context_length_exceeded", "invalid_request_error"),
    Unauthorized(401, "unauthorized", "authentication_error"),
    NotFound(404, "not_found", "invalid_request_error"),
    ServerBusy(429, "server_busy", "server_error"),
    ModelNotLoaded(503, "model_not_loaded", "server_error"),
    InternalError(500, "internal_error", "server_error");

    fun body(message: String) = ErrorResponse(ErrorBody(message = message, type = type, code = code))
}

/** Thrown inside the server to end a request with a contract error. */
class ApiException(val error: ApiError, override val message: String) : Exception(message)
