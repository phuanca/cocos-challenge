package com.cocos.challenge.accounts;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.closeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
@Sql(
        scripts = "/database.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class AccountFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void shouldBuildPortfolioFromFilledOrdersAndReserveNewOrders() throws Exception {
        mockMvc.perform(get("/api/users/1/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.totalValue").value(904784.00))
                .andExpect(jsonPath("$.availableCash").value(627500.00))
                .andExpect(jsonPath("$.positions.length()").value(2))
                .andExpect(jsonPath("$.positions[0].instrumentId").value(47))
                .andExpect(jsonPath("$.positions[0].quantity").value(40))
                .andExpect(jsonPath("$.positions[0].marketValue").value(37034.00))
                .andExpect(jsonPath("$.positions[0].totalReturnPercentage")
                        .value(closeTo(-0.4462, 0.0001)))
                .andExpect(jsonPath("$.positions[0].dailyReturnPercentage")
                        .value(closeTo(0.4394, 0.0001)))
                .andExpect(jsonPath("$.positions[1].instrumentId").value(54))
                .andExpect(jsonPath("$.positions[1].quantity").value(500))
                .andExpect(jsonPath("$.positions[1].marketValue").value(114750.00))
                .andExpect(jsonPath("$.positions[1].totalReturnPercentage")
                        .value(closeTo(-8.2, 0.0001)))
                .andExpect(jsonPath("$.positions[1].dailyReturnPercentage")
                        .value(closeTo(-1.0776, 0.0001)));
    }

    @Test
    void shouldReturnNotFoundForUnknownPortfolioUser() throws Exception {
        mockMvc.perform(get("/api/users/999/portfolio"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldReturnNotFoundForUnknownEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/healthz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldCreateFilledMarketOrderUsingLatestClose() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "BUY",
                                  "type": "MARKET",
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instrumentId").value(47))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.price").value(925.85))
                .andExpect(jsonPath("$.status").value("FILLED"));
    }

    @Test
    void shouldCreateNewLimitOrderAndCalculateQuantityFromAmount() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 45,
                                  "side": "BUY",
                                  "type": "LIMIT",
                                  "amount": 2000,
                                  "price": 710
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.price").value(710.00))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    void shouldPersistRejectedBuyWhenCashIsInsufficient() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "BUY",
                                  "type": "MARKET",
                                  "quantity": 1000000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void shouldPersistRejectedSellWhenSharesAreInsufficient() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "SELL",
                                  "type": "MARKET",
                                  "quantity": 41
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void shouldRejectInvalidOrderPayload() throws Exception {
        double errorsBefore = meterRegistry.counter(
                "application.errors",
                "code", "INVALID_ORDER",
                "status", "400"
        ).count();

        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "BUY",
                                  "type": "LIMIT",
                                  "quantity": 1,
                                  "amount": 1000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_ORDER"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        double errorsAfter = meterRegistry.counter(
                "application.errors",
                "code", "INVALID_ORDER",
                "status", "400"
        ).count();
        assertEquals(errorsBefore + 1, errorsAfter, 0.001);
    }

    @Test
    void shouldRequirePriceForLimitOrder() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "BUY",
                                  "type": "LIMIT",
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectUnknownOrderType() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "BUY",
                                  "type": "STOP",
                                  "quantity": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldReserveSharesForNewSellOrders() throws Exception {
        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "SELL",
                                  "type": "LIMIT",
                                  "quantity": 40,
                                  "price": 1000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(post("/api/users/1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "instrumentId": 47,
                                  "side": "SELL",
                                  "type": "LIMIT",
                                  "quantity": 1,
                                  "price": 1000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void shouldCancelNewOrderAndReleaseReservedCash() throws Exception {
        mockMvc.perform(post("/api/users/1/orders/5/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mockMvc.perform(get("/api/users/1/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCash").value(663000.00));
    }

    @Test
    void shouldRejectCancellationOfFilledOrder() throws Exception {
        mockMvc.perform(post("/api/users/1/orders/2/cancel"))
                .andExpect(status().isConflict());
    }
}
