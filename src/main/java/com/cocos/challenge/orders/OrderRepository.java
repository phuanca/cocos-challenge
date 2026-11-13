package com.cocos.challenge.orders;

import com.cocos.challenge.orders.dto.OrderResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class OrderRepository {

    private static final String RETURNING_COLUMNS = """
            id, instrumentId, userId, side, size, price, type, status, datetime
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OrderRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OrderResponse create(
            int userId,
            int instrumentId,
            OrderSide side,
            long quantity,
            BigDecimal price,
            OrderType type,
            OrderStatus status
    ) {
        String sql = """
                INSERT INTO orders (
                    instrumentId, userId, side, size, price, type, status, datetime
                ) VALUES (
                    :instrumentId, :userId, :side, :size, :price, :type, :status,
                    CURRENT_TIMESTAMP
                )
                RETURNING
                """ + RETURNING_COLUMNS;

        return jdbcTemplate.queryForObject(
                sql,
                new MapSqlParameterSource()
                        .addValue("instrumentId", instrumentId)
                        .addValue("userId", userId)
                        .addValue("side", side.name())
                        .addValue("size", quantity)
                        .addValue("price", price)
                        .addValue("type", type.name())
                        .addValue("status", status.name()),
                (rs, rowNum) -> mapOrder(rs)
        );
    }

    public OrderResponse findByIdAndUser(int orderId, int userId) {
        List<OrderResponse> orders = jdbcTemplate.query(
                "SELECT " + RETURNING_COLUMNS + " FROM orders "
                        + "WHERE id = :orderId AND userId = :userId",
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("userId", userId),
                (rs, rowNum) -> mapOrder(rs)
        );
        return orders.isEmpty() ? null : orders.getFirst();
    }

    public OrderResponse cancel(int orderId, int userId) {
        return jdbcTemplate.queryForObject(
                """
                UPDATE orders
                SET status = 'CANCELLED'
                WHERE id = :orderId AND userId = :userId AND status = 'NEW'
                RETURNING
                """ + RETURNING_COLUMNS,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("userId", userId),
                (rs, rowNum) -> mapOrder(rs)
        );
    }

    private OrderResponse mapOrder(ResultSet rs) throws SQLException {
        return new OrderResponse(
                rs.getInt("id"),
                rs.getInt("instrumentId"),
                rs.getInt("userId"),
                OrderSide.valueOf(rs.getString("side")),
                rs.getLong("size"),
                rs.getBigDecimal("price"),
                OrderType.valueOf(rs.getString("type")),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("datetime").toLocalDateTime()
        );
    }
}
