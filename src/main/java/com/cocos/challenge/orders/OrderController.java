package com.cocos.challenge.orders;

import com.cocos.challenge.orders.dto.CreateOrderRequest;
import com.cocos.challenge.orders.dto.OrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/{userId}/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(
            @PathVariable int userId,
            @RequestBody CreateOrderRequest request
    ) {
        return service.create(userId, request);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(
            @PathVariable int userId,
            @PathVariable int orderId
    ) {
        return service.cancel(userId, orderId);
    }
}
