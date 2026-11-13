package com.cocos.challenge.portfolio;

import com.cocos.challenge.accounts.AccountRepository;
import com.cocos.challenge.orders.OrderSide;
import com.cocos.challenge.portfolio.dto.PortfolioResponse;
import com.cocos.challenge.portfolio.dto.PositionResponse;
import com.cocos.challenge.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final int CALCULATION_SCALE = 8;
    private static final int RESPONSE_SCALE = 4;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final AccountRepository accountRepository;
    private final PortfolioRepository portfolioRepository;

    public PortfolioService(
            AccountRepository accountRepository,
            PortfolioRepository portfolioRepository
    ) {
        this.accountRepository = accountRepository;
        this.portfolioRepository = portfolioRepository;
    }

    public PortfolioResponse getPortfolio(int userId) {
        if (!accountRepository.userExists(userId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found");
        }

        BigDecimal settledCash = accountRepository.settledCash(userId);
        BigDecimal availableCash = accountRepository.availableCash(userId);
        Map<Integer, PositionState> states = buildPositions(
                portfolioRepository.findFilledOrders(userId)
        );

        List<PositionState> activePositions = states.values().stream()
                .filter(position -> position.quantity > 0)
                .toList();

        Set<Integer> instrumentIds = activePositions.stream()
                .map(position -> position.instrumentId)
                .collect(Collectors.toSet());
        Map<Integer, PortfolioRepository.MarketData> marketData =
                portfolioRepository.findLatestMarketData(instrumentIds);

        List<PositionResponse> positions = activePositions.stream()
                .map(position -> toResponse(
                        position,
                        marketData.get(position.instrumentId)
                ))
                .toList();

        BigDecimal positionsValue = positions.stream()
                .map(PositionResponse::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PortfolioResponse(
                userId,
                money(settledCash.add(positionsValue)),
                money(availableCash),
                positions
        );
    }

    private Map<Integer, PositionState> buildPositions(
            List<PortfolioRepository.FilledOrder> orders
    ) {
        Map<Integer, PositionState> positions = new LinkedHashMap<>();

        for (PortfolioRepository.FilledOrder order : orders) {
            PositionState state = positions.computeIfAbsent(
                    order.instrumentId(),
                    ignored -> new PositionState(order)
            );

            if (order.side() == OrderSide.BUY) {
                applyBuy(state, order);
            }

            if (order.side() == OrderSide.SELL) {
                applySell(state, order);
            }
        }
        return positions;
    }

    private void applyBuy(
            PositionState state,
            PortfolioRepository.FilledOrder order
    ) {
        state.totalCost = state.totalCost.add(
                order.price().multiply(BigDecimal.valueOf(order.size()))
        );
        state.quantity += order.size();
    }

    private void applySell(
            PositionState state,
            PortfolioRepository.FilledOrder order
    ) {
        if (state.quantity <= 0) {
            return;
        }

        BigDecimal averageCost = state.totalCost.divide(
                BigDecimal.valueOf(state.quantity),
                CALCULATION_SCALE,
                RoundingMode.HALF_UP
        );
        state.quantity -= order.size();

        if (state.quantity <= 0) {
            state.quantity = 0;
            state.totalCost = BigDecimal.ZERO;
            return;
        }

        state.totalCost = averageCost.multiply(BigDecimal.valueOf(state.quantity));
    }

    private PositionResponse toResponse(
            PositionState state,
            PortfolioRepository.MarketData marketData
    ) {
        if (marketData == null || marketData.close() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MARKET_DATA_NOT_FOUND",
                    "Missing market data for instrument " + state.instrumentId
            );
        }

        BigDecimal averageCost = state.totalCost.divide(
                BigDecimal.valueOf(state.quantity),
                CALCULATION_SCALE,
                RoundingMode.HALF_UP
        );
        BigDecimal marketValue = marketData.close()
                .multiply(BigDecimal.valueOf(state.quantity));
        BigDecimal totalReturn = percentageChange(marketData.close(), averageCost);
        BigDecimal dailyReturn = marketData.previousClose() == null
                ? null
                : percentageChange(marketData.close(), marketData.previousClose());

        return new PositionResponse(
                state.instrumentId,
                marketData.ticker(),
                marketData.name(),
                state.quantity,
                money(marketData.close()),
                money(marketValue),
                percentage(totalReturn),
                dailyReturn == null ? null : percentage(dailyReturn)
        );
    }

    private BigDecimal percentageChange(BigDecimal current, BigDecimal reference) {
        if (reference.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return current.subtract(reference)
                .divide(reference, CALCULATION_SCALE, RoundingMode.HALF_UP)
                .multiply(ONE_HUNDRED);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(BigDecimal value) {
        return value.setScale(RESPONSE_SCALE, RoundingMode.HALF_UP);
    }

    private static final class PositionState {
        private final int instrumentId;
        private long quantity;
        private BigDecimal totalCost = BigDecimal.ZERO;

        private PositionState(PortfolioRepository.FilledOrder order) {
            this.instrumentId = order.instrumentId();
        }

    }
}
