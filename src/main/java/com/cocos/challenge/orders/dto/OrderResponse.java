package com.cocos.challenge.orders.dto;

import com.cocos.challenge.orders.OrderSide;
import com.cocos.challenge.orders.OrderStatus;
import com.cocos.challenge.orders.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        int id,
        int instrumentId,
        int userId,
        OrderSide side,
        long quantity,
        BigDecimal price,
        OrderType type,
        OrderStatus status,
        LocalDateTime datetime
) {
}
