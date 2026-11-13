package com.cocos.challenge.portfolio.dto;

import java.math.BigDecimal;

public record PositionResponse(
        int instrumentId,
        String ticker,
        String name,
        long quantity,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal totalReturnPercentage,
        BigDecimal dailyReturnPercentage
) {
}
