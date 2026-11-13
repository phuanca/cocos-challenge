package com.cocos.challenge.shared.error;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public GlobalExceptionHandler(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return error(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                exception
        );
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Request is invalid",
                exception
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(
            NoResourceFoundException exception
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "ENDPOINT_NOT_FOUND",
                "Endpoint not found",
                exception
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(Exception exception) {
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Unexpected error",
                exception
        );
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status,
            String code,
            String message,
            Exception exception
    ) {
        String traceId = currentTraceId();

        meterRegistry.counter(
                "application.errors",
                "code", code,
                "status", String.valueOf(status.value())
        ).increment();

        if (status.is5xxServerError()) {
            LOGGER.error(
                    "Request failed code={} status={} traceId={}",
                    code,
                    status.value(),
                    traceId,
                    exception
            );
        } else {
            LOGGER.warn(
                    "Request rejected code={} status={} traceId={}",
                    code,
                    status.value(),
                    traceId
            );
        }

        return ResponseEntity.status(status).body(
                new ApiError(status.value(), code, message, traceId)
        );
    }

    private String currentTraceId() {
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
