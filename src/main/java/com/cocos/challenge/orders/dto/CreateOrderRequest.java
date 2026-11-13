package com.cocos.challenge.orders.dto;

import com.cocos.challenge.orders.OrderSide;
import com.cocos.challenge.orders.OrderType;

import java.math.BigDecimal;

public record CreateOrderRequest(
        Integer instrumentId,
        OrderSide side,
        OrderType type,
        Long quantity,
        BigDecimal amount,
        BigDecimal price
) {
}
