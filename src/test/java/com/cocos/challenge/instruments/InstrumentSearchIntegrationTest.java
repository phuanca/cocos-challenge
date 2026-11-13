
package com.cocos.challenge.instruments;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql(
        scripts = "/database.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS
)
class InstrumentSearchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ---------------------------------------------------------
    // Search
    // ---------------------------------------------------------

    @Test
    void shouldFindInstrumentByExactTicker() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(34))
                .andExpect(jsonPath("$.content[0].ticker").value("GGAL"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldFindInstrumentByTickerIgnoringCase() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "ggal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("GGAL"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldFindInstrumentByPartialTicker() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("GGAL"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldFindInstrumentByPartialNameIgnoringCase() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "galicia"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(34))
                .andExpect(jsonPath("$.content[0].name")
                        .value("Grupo Financiero Galicia"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldTrimQueryBeforeSearching() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", " galicia "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("GGAL"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReturnEmptyResultsWhenNoMatchExists() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "ZZZ_NOT_FOUND"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    // ---------------------------------------------------------
    // SQL wildcard escaping
    // ---------------------------------------------------------

    @Test
    void shouldTreatPercentAsLiteralCharacter() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void shouldTreatUnderscoreAsLiteralCharacter() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------------------------------------------------------
    // Pagination
    // ---------------------------------------------------------

    @Test
    void shouldApplyDefaultPagination() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void shouldReturnDifferentResultsForDifferentPages() throws Exception {
        JsonNode firstPage = search("a", 0, 2);
        JsonNode secondPage = search("a", 1, 2);

        assertEquals(2, firstPage.path("content").size());
        assertEquals(2, secondPage.path("content").size());

        assertNotEquals(
                firstPage.path("content").get(0).path("id").asInt(),
                secondPage.path("content").get(0).path("id").asInt()
        );

        assertEquals(0, firstPage.path("page").asInt());
        assertEquals(1, secondPage.path("page").asInt());
    }

    @Test
    void shouldReturnSameOrderForRepeatedSearches() throws Exception {
        JsonNode firstResult = search("a", 0, 10);
        JsonNode secondResult = search("a", 0, 10);

        assertEquals(
                firstResult.path("content"),
                secondResult.path("content")
        );
    }

    @Test
    void shouldReturnEmptyContentWhenPageIsOutOfRange() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL")
                        .param("page", "100")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void shouldAcceptMaximumPageSize() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "a")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    // ---------------------------------------------------------
    // Validation
    // ---------------------------------------------------------

    @Test
    void shouldRejectMissingQuery() throws Exception {
        mockMvc.perform(get("/api/instruments"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectEmptyQuery() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectBlankQuery() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectQueryLongerThan100Characters() throws Exception {
        String query = "a".repeat(101);

        mockMvc.perform(get("/api/instruments")
                        .param("q", query))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNegativePage() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectZeroPageSize() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL")
                        .param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectPageSizeGreaterThanMaximum() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectNonNumericPage() throws Exception {
        mockMvc.perform(get("/api/instruments")
                        .param("q", "GGAL")
                        .param("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------

    private JsonNode search(String query, int page, int size)
            throws Exception {

        String response = mockMvc.perform(get("/api/instruments")
                        .param("q", query)
                        .param("page", String.valueOf(page))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response);
    }
}
