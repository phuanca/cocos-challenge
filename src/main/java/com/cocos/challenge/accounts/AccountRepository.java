package com.cocos.challenge.accounts;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Map;

@Repository
public class AccountRepository {

    private static final String SETTLED_CASH_QUERY = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN side = 'CASH_IN' THEN size
                    WHEN side = 'CASH_OUT' THEN -size
                    WHEN side = 'BUY' THEN -(size * price)
                    WHEN side = 'SELL' THEN size * price
                    ELSE 0
                END
            ), 0)
            FROM orders
            WHERE userId = :userId AND status = 'FILLED'
            """;

    private static final String RESERVED_CASH_QUERY = """
            SELECT COALESCE(SUM(size * price), 0)
            FROM orders
            WHERE userId = :userId AND side = 'BUY' AND status = 'NEW'
            """;

    private static final String AVAILABLE_QUANTITY_QUERY = """
            SELECT
                COALESCE(SUM(CASE
                    WHEN status = 'FILLED' AND side = 'BUY' THEN size
                    WHEN status = 'FILLED' AND side = 'SELL' THEN -size
                    WHEN status = 'NEW' AND side = 'SELL' THEN -size
                    ELSE 0
                END), 0)
            FROM orders
            WHERE userId = :userId AND instrumentId = :instrumentId
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AccountRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean userExists(int userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = :userId",
                Map.of("userId", userId),
                Integer.class
        );
        return count != null && count > 0;
    }

    public boolean lockUser(int userId) {
        return !jdbcTemplate.query(
                "SELECT id FROM users WHERE id = :userId FOR UPDATE",
                Map.of("userId", userId),
                (rs, rowNum) -> rs.getInt("id")
        ).isEmpty();
    }

    public BigDecimal settledCash(int userId) {
        BigDecimal value = jdbcTemplate.queryForObject(
                SETTLED_CASH_QUERY,
                Map.of("userId", userId),
                BigDecimal.class
        );
        return value != null ? value : BigDecimal.ZERO;
    }

    public BigDecimal availableCash(int userId) {
        BigDecimal reserved = jdbcTemplate.queryForObject(
                RESERVED_CASH_QUERY,
                Map.of("userId", userId),
                BigDecimal.class
        );
        return settledCash(userId).subtract(reserved != null ? reserved : BigDecimal.ZERO);
    }

    public long availableQuantity(int userId, int instrumentId) {
        Long value = jdbcTemplate.queryForObject(
                AVAILABLE_QUANTITY_QUERY,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("instrumentId", instrumentId),
                Long.class
        );
        return value != null ? value : 0L;
    }
}
