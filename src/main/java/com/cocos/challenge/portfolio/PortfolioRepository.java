package com.cocos.challenge.portfolio;

import com.cocos.challenge.orders.OrderSide;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class PortfolioRepository {

    private static final String FILLED_ORDERS_QUERY = """
            SELECT
                o.instrumentId,
                o.side,
                o.size,
                o.price
            FROM orders o
            WHERE o.userId = :userId
              AND o.status = 'FILLED'
              AND o.side IN ('BUY', 'SELL')
            ORDER BY o.datetime, o.id
            """;

    private static final String LATEST_MARKET_DATA_QUERY = """
            SELECT DISTINCT ON (i.id)
                i.id AS instrumentId,
                i.ticker,
                i.name,
                md.close,
                md.previousClose
            FROM instruments i
            JOIN marketdata md ON md.instrumentId = i.id
            WHERE i.id IN (:instrumentIds)
            ORDER BY i.id, md.date DESC, md.id DESC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PortfolioRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<FilledOrder> findFilledOrders(int userId) {
        return jdbcTemplate.query(
                FILLED_ORDERS_QUERY,
                Map.of("userId", userId),
                (rs, rowNum) -> new FilledOrder(
                        rs.getInt("instrumentId"),
                        OrderSide.valueOf(rs.getString("side")),
                        rs.getLong("size"),
                        rs.getBigDecimal("price")
                )
        );
    }

    public Map<Integer, MarketData> findLatestMarketData(Set<Integer> instrumentIds) {
        if (instrumentIds.isEmpty()) {
            return Map.of();
        }

        return jdbcTemplate.query(
                        LATEST_MARKET_DATA_QUERY,
                        Map.of("instrumentIds", instrumentIds),
                        (rs, rowNum) -> new MarketData(
                                rs.getInt("instrumentId"),
                                rs.getString("ticker"),
                                rs.getString("name"),
                                rs.getBigDecimal("close"),
                                rs.getBigDecimal("previousClose")
                        )
                ).stream()
                .collect(Collectors.toMap(MarketData::instrumentId, Function.identity()));
    }

    public record FilledOrder(
            int instrumentId,
            OrderSide side,
            long size,
            BigDecimal price
    ) {
    }

    public record MarketData(
            int instrumentId,
            String ticker,
            String name,
            BigDecimal close,
            BigDecimal previousClose
    ) {
    }
}
