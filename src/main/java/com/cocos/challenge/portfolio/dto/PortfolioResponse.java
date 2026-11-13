package com.cocos.challenge.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record PortfolioResponse(
        int userId,
        BigDecimal totalValue,
        BigDecimal availableCash,
        List<PositionResponse> positions
) {
}
