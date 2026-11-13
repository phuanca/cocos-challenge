package com.cocos.challenge.orders;

import com.cocos.challenge.accounts.AccountRepository;
import com.cocos.challenge.instruments.InstrumentRepository;
import com.cocos.challenge.orders.dto.CreateOrderRequest;
import com.cocos.challenge.orders.dto.OrderResponse;
import com.cocos.challenge.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class OrderService {

    private final AccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final OrderRepository orderRepository;

    public OrderService(
            AccountRepository accountRepository,
            InstrumentRepository instrumentRepository,
            OrderRepository orderRepository
    ) {
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse create(int userId, CreateOrderRequest request) {
        validateRequest(request);

        if (!accountRepository.lockUser(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }

        InstrumentRepository.TradeInstrument instrument =
                instrumentRepository.findForTrading(request.instrumentId());
        if (instrument == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "INSTRUMENT_NOT_FOUND",
                    "Instrument not found"
            );
        }

        BigDecimal executionPrice = resolvePrice(request, instrument);
        long quantity = resolveQuantity(request, executionPrice);
        OrderStatus status = resolveStatus(userId, request, quantity, executionPrice);

        return orderRepository.create(
                userId,
                instrument.id(),
                request.side(),
                quantity,
                executionPrice,
                request.type(),
                status
        );
    }

    @Transactional
    public OrderResponse cancel(int userId, int orderId) {
        if (!accountRepository.lockUser(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }

        OrderResponse order = orderRepository.findByIdAndUser(orderId, userId);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
        }
        if (order.status() != OrderStatus.NEW) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORDER_NOT_CANCELLABLE",
                    "Only NEW orders can be cancelled"
            );
        }
        return orderRepository.cancel(orderId, userId);
    }

    private void validateRequest(CreateOrderRequest request) {
        if (request == null) {
            badRequest("Request body is required");
        }
        if (request.instrumentId() == null || request.instrumentId() <= 0
                || request.side() == null || request.type() == null) {
            badRequest("instrumentId, side and type are required");
        }

        boolean hasQuantity = request.quantity() != null;
        boolean hasAmount = request.amount() != null;
        if (hasQuantity == hasAmount) {
            badRequest("Send exactly one of quantity or amount");
        }
        if (hasQuantity && request.quantity() <= 0) {
            badRequest("quantity must be positive");
        }
        if (hasQuantity && request.quantity() > Integer.MAX_VALUE) {
            badRequest("quantity is too large");
        }
        if (hasAmount && request.amount().signum() <= 0) {
            badRequest("amount must be positive");
        }

        if (request.type() == OrderType.LIMIT
                && (request.price() == null || request.price().signum() <= 0)) {
            badRequest("price must be positive for LIMIT orders");
        }
        if (request.type() == OrderType.MARKET && request.price() != null) {
            badRequest("price must not be sent for MARKET orders");
        }
    }

    private BigDecimal resolvePrice(
            CreateOrderRequest request,
            InstrumentRepository.TradeInstrument instrument
    ) {
        if (request.type() == OrderType.LIMIT) {
            return request.price().setScale(2, RoundingMode.HALF_UP);
        }
        if (instrument.close() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MARKET_DATA_NOT_FOUND",
                    "Market data not found for instrument"
            );
        }
        return instrument.close().setScale(2, RoundingMode.HALF_UP);
    }

    private long resolveQuantity(CreateOrderRequest request, BigDecimal price) {
        if (request.quantity() != null) {
            return request.quantity();
        }

        BigDecimal wholeShares = request.amount().divideToIntegralValue(price);
        if (wholeShares.signum() <= 0) {
            badRequest("amount is lower than the price of one share");
        }
        try {
            long quantity = wholeShares.longValueExact();
            if (quantity > Integer.MAX_VALUE) {
                badRequest("calculated quantity is too large");
            }
            return quantity;
        } catch (ArithmeticException exception) {
            badRequest("calculated quantity is too large");
            return 0;
        }
    }

    private OrderStatus resolveStatus(
            int userId,
            CreateOrderRequest request,
            long quantity,
            BigDecimal price
    ) {
        boolean hasResources;
        if (request.side() == OrderSide.BUY) {
            BigDecimal requiredCash = price.multiply(BigDecimal.valueOf(quantity));
            hasResources = accountRepository.availableCash(userId)
                    .compareTo(requiredCash) >= 0;
        } else {
            hasResources = accountRepository.availableQuantity(
                    userId,
                    request.instrumentId()
            ) >= quantity;
        }

        if (!hasResources) {
            return OrderStatus.REJECTED;
        }
        return request.type() == OrderType.MARKET
                ? OrderStatus.FILLED
                : OrderStatus.NEW;
    }

    private void badRequest(String reason) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER", reason);
    }
}
