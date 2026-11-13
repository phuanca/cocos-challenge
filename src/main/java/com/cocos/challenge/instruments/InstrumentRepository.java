package com.cocos.challenge.instruments;

import com.cocos.challenge.instruments.dto.InstrumentResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

@Repository
public class InstrumentRepository {

    private static final String SEARCH_QUERY = """
            SELECT
                id,
                ticker,
                name,
                type
            FROM instruments
            WHERE
                ticker ILIKE :pattern
                OR name ILIKE :pattern
            ORDER BY
                CASE
                    WHEN LOWER(ticker) = LOWER(:query) THEN 0
                    WHEN LOWER(name) = LOWER(:query) THEN 1
                    ELSE 2
                END,
                ticker NULLS LAST,
                id
            LIMIT :size
            OFFSET :offset
            """;

    private static final String COUNT_QUERY = """
            SELECT COUNT(*)
            FROM instruments
            WHERE
                ticker ILIKE :pattern
                OR name ILIKE :pattern
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public InstrumentRepository(
            NamedParameterJdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<InstrumentResponse> search(
            String query,
            int size,
            long offset
    ) {
        Map<String, Object> parameters = Map.of(
                "query", query,
                "pattern", toSearchPattern(query),
                "size", size,
                "offset", offset
        );

        return jdbcTemplate.query(
                SEARCH_QUERY,
                parameters,
                (rs, rowNum) -> new InstrumentResponse(
                        rs.getInt("id"),
                        rs.getString("ticker"),
                        rs.getString("name"),
                        rs.getString("type")
                )
        );
    }

    public long count(String query) {
        Map<String, Object> parameters = Map.of(
                "pattern", toSearchPattern(query)
        );

        Long result = jdbcTemplate.queryForObject(
                COUNT_QUERY,
                parameters,
                Long.class
        );

        return result != null ? result : 0L;
    }

    public TradeInstrument findForTrading(int instrumentId) {
        List<TradeInstrument> instruments = jdbcTemplate.query(
                """
                SELECT i.id, i.ticker, i.type, latest.close
                FROM instruments i
                LEFT JOIN LATERAL (
                    SELECT md.close
                    FROM marketdata md
                    WHERE md.instrumentId = i.id
                    ORDER BY md.date DESC, md.id DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE i.id = :instrumentId
                """,
                Map.of("instrumentId", instrumentId),
                (rs, rowNum) -> new TradeInstrument(
                        rs.getInt("id"),
                        rs.getString("ticker"),
                        rs.getString("type"),
                        rs.getBigDecimal("close")
                )
        );
        return instruments.isEmpty() ? null : instruments.getFirst();
    }

    private String toSearchPattern(String query) {
        String escaped = query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return "%" + escaped + "%";
    }

    public record TradeInstrument(
            int id,
            String ticker,
            String type,
            BigDecimal close
    ) {
    }
}
