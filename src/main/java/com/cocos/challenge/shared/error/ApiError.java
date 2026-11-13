package com.cocos.challenge.shared.error;

public record ApiError(
        int status,
        String code,
        String message,
        String traceId
) {
}
