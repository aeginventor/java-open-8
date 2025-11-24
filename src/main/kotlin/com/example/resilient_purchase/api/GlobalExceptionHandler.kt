package com.example.resilient_purchase.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    data class ErrorResponse(
        val success: Boolean = false,
        val error: String,
        val detail: String? = null
    )

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handleBadRequest(ex: RuntimeException): ResponseEntity<ErrorResponse> {
        val body = ErrorResponse(
            success = false,
            error = "BAD_REQUEST",
            detail = ex.message
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val firstError = ex.bindingResult.fieldErrors.firstOrNull()
        val message = firstError?.defaultMessage ?: "요청 값이 올바르지 않습니다."
        val body = ErrorResponse(
            success = false,
            error = "VALIDATION_ERROR",
            detail = message
        )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }
}
