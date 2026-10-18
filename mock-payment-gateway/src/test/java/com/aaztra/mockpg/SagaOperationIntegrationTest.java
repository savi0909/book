package com.aaztra.mockpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "mock-pg.saga-token=integration-secret")
@AutoConfigureMockMvc
@Testcontainers
class SagaOperationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void captureIsDurableAndIdempotentAndRejectsChangedRequest() throws Exception {
        UUID operation = UUID.randomUUID(), order = UUID.randomUUID();
        String request = request(order, "CAPTURE", 12300, null);
        JsonNode first = body(postOperation(operation, request, 200));
        assertThat(first.path("status").asText()).isEqualTo("CAPTURED");
        assertThat(body(postOperation(operation, request, 200))).isEqualTo(first);
        assertThat(body(mvc.perform(get(path(operation)).header("Authorization", "Bearer integration-secret"))
                .andExpect(status().isOk()).andReturn())).isEqualTo(first);
        postOperation(operation, request(order, "CAPTURE", 12400, null), 409);
        assertThat(count("mock_orders", "receipt", order.toString())).isEqualTo(1);
        assertThat(count("mock_payments", "payment_id", first.path("providerId").asText())).isEqualTo(1);
    }

    @Test void concurrentSameOperationAndDistinctCapturesDoNotDoubleCharge() throws Exception {
        UUID order = UUID.randomUUID(), operation = UUID.randomUUID();
        String request = request(order, "CAPTURE", 20000, null);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> postOperation(operation, request, 200));
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(calls))
                assertThat(body(future.get()).path("status").asText()).isEqualTo("CAPTURED");
        }
        calls.clear();
        for (int i = 0; i < 4; i++) calls.add(() -> postOperation(UUID.randomUUID(), request, 409));
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var future : executor.invokeAll(calls)) future.get();
        }
        assertThat(jdbc.queryForObject("select count(*) from payment_processor.mock_payments p join payment_processor.mock_orders o on o.order_id=p.order_id where o.receipt=?", Long.class, order.toString())).isEqualTo(1);
    }

    @Test void differentOperationIdsRaceForOneCaptureAndOneRefund() throws Exception {
        UUID order = UUID.randomUUID();
        List<Callable<MvcResult>> captures = new ArrayList<>();
        for (int i = 0; i < 6; i++) captures.add(() -> rawPost(UUID.randomUUID(), request(order, "CAPTURE", 50000, null)));
        UUID winningCapture = null;
        int captured = 0, captureConflicts = 0;
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (var future : executor.invokeAll(captures)) {
                MvcResult result = future.get();
                if (result.getResponse().getStatus() == 200) {
                    captured++;
                    winningCapture = UUID.fromString(body(result).path("operationId").asText());
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(409);
                    captureConflicts++;
                }
            }
        }
        assertThat(captured).isEqualTo(1);
        assertThat(captureConflicts).isEqualTo(5);
        String refundRequest = request(order, "REFUND", 50000, winningCapture);
        List<Callable<MvcResult>> refunds = new ArrayList<>();
        for (int i = 0; i < 6; i++) refunds.add(() -> rawPost(UUID.randomUUID(), refundRequest));
        int refunded = 0, refundConflicts = 0;
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (var future : executor.invokeAll(refunds)) {
                var result = future.get();
                if (result.getResponse().getStatus() == 200) {
                    assertThat(body(result).path("status").asText()).isEqualTo("REFUNDED");
                    refunded++;
                } else {
                    assertThat(result.getResponse().getStatus()).isEqualTo(409);
                    refundConflicts++;
                }
            }
        }
        assertThat(refunded).isEqualTo(1);
        assertThat(refundConflicts).isEqualTo(5);
        assertThat(count("mock_orders", "receipt", order.toString())).isEqualTo(1);
    }

    @Test void refundUsesOriginalCaptureAndConcurrentRetriesDoNotDoubleRefund() throws Exception {
        UUID order = UUID.randomUUID(), capture = UUID.randomUUID(), refund = UUID.randomUUID();
        JsonNode captured = body(postOperation(capture, request(order, "CAPTURE", 30000, null), 200));
        String request = request(order, "REFUND", 30000, capture);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) calls.add(() -> postOperation(refund, request, 200));
        try (var executor = Executors.newFixedThreadPool(6)) {
            for (var future : executor.invokeAll(calls))
                assertThat(body(future.get()).path("status").asText()).isEqualTo("REFUNDED");
        }
        postOperation(UUID.randomUUID(), request, 409);
        assertThat(count("mock_refunds", "payment_id", captured.path("providerId").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount_refunded from payment_processor.mock_payments where payment_id=?", Long.class, captured.path("providerId").asText())).isEqualTo(30000);
        assertThat(jdbc.queryForObject("select count(*) from payment_processor.mock_webhooks", Long.class)).isZero();
    }

    @Test void specialAmountsNeverClaimAFullCaptureForAuthorizationOrPartialCapture() throws Exception {
        for (long amount : new long[]{1055, 1045}) {
            JsonNode result = body(postOperation(UUID.randomUUID(), request(UUID.randomUUID(), "CAPTURE", amount, null), 200));
            assertThat(result.path("status").asText()).isEqualTo("ACTION_REQUIRED");
            assertThat(jdbc.queryForObject("select captured from payment_processor.mock_payments where payment_id=?", Boolean.class, result.path("providerId").asText())).isFalse();
        }
        for (long amount : new long[]{1099, 1088, 1077}) {
            JsonNode result = body(postOperation(UUID.randomUUID(), request(UUID.randomUUID(), "CAPTURE", amount, null), 200));
            assertThat(result.path("status").asText()).isEqualTo("DECLINED");
        }
    }

    @Test void refundsRejectDifferentOrderAndPartialAmountsAndRecordProviderFailure() throws Exception {
        UUID order = UUID.randomUUID(), capture = UUID.randomUUID();
        postOperation(capture, request(order, "CAPTURE", 1011, null), 200);
        assertThat(body(postOperation(UUID.randomUUID(), request(UUID.randomUUID(), "REFUND", 1011, capture), 200)).path("status").asText()).isEqualTo("REJECTED");
        // A wrong amount is rejected without consuming the valid refund identity for this order.
        postOperation(UUID.randomUUID(), request(order, "REFUND", 1000, capture), 409);
        JsonNode failed = body(postOperation(UUID.randomUUID(), request(order, "REFUND", 1011, capture), 200));
        assertThat(failed.path("status").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select status from payment_processor.mock_refunds where refund_id=?", String.class, failed.path("providerId").asText())).isEqualTo("failed");
    }

    @Test void authenticationAndValidationAreEnforced() throws Exception {
        UUID operation = UUID.randomUUID();
        mvc.perform(post(path(operation)).contentType("application/json").content(request(UUID.randomUUID(), "CAPTURE", 10000, null)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path(operation)).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get(path(operation)).header("Authorization", "Bearer integration-secret")).andExpect(status().isNotFound());
        postOperation(operation, request(UUID.randomUUID(), "CAPTURE", -1, null), 400);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/mock-pg/saga;foo/operations/",
            "/mock-pg;foo/saga/operations/",
            "/mock-pg;foo/saga;bar/operations;baz/",
            "/mock-pg/saga;foo=%2F/operations/",
            "/mock-pg/saga;foo%3Bbar/operations/",
            "/mock-pg/s%61ga/operations/",
            "/mock%2Dpg/saga/operations/"
    })
    void routedMatrixAndEncodedPathsRequireValidCredentials(String prefix) throws Exception {
        UUID operation = UUID.randomUUID();
        String request = request(UUID.randomUUID(), "CAPTURE", 12000, null);
        JsonNode original = body(postOperation(operation, request, 200));
        URI alternatePath = URI.create(prefix + operation);
        // Establish that Spring routes this exact URI to the saga controller.
        assertThat(body(mvc.perform(get(alternatePath).header("Authorization", "Bearer integration-secret"))
                .andExpect(status().isOk()).andReturn())).isEqualTo(original);
        mvc.perform(get(alternatePath)).andExpect(status().isUnauthorized());
        mvc.perform(get(alternatePath).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post(alternatePath).contentType("application/json").content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(alternatePath).header("Authorization", "Bearer wrong")
                .contentType("application/json").content(request)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/mock-pg/saga%3Bfoo/operations/", "/mock-pg%3bfoo/saga/operations/"})
    void encodedMatrixDelimitersDoNotRouteToSagaOperations(String prefix) throws Exception {
        UUID operation = UUID.randomUUID();
        postOperation(operation, request(UUID.randomUUID(), "CAPTURE", 12000, null), 200);
        URI alternatePath = URI.create(prefix + operation);
        // Encoded semicolons are literal segment content in Spring MVC, not matrix delimiters.
        mvc.perform(get(alternatePath)).andExpect(status().isNotFound());
        mvc.perform(get(alternatePath).header("Authorization", "Bearer wrong")).andExpect(status().isNotFound());
        mvc.perform(get(alternatePath).header("Authorization", "Bearer integration-secret"))
                .andExpect(status().isNotFound());
    }

    @Test void operationInsertFailureRollsBackLegacyOrderAndPayment() throws Exception {
        UUID operation = UUID.randomUUID(), order = UUID.randomUUID();
        // An actual database failure at the operation insert must roll back preceding legacy work.
        jdbc.execute("alter table payment_processor.saga_operations add constraint reject_capture_test "
                + "check (operation_id <> '" + operation + "'::uuid)");
        postOperation(operation, request(order, "CAPTURE", 45000, null), 500);
        assertThat(count("mock_orders", "receipt", order.toString())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_processor.saga_operations where operation_id=?", Long.class, operation)).isZero();
    }

    @Test void operationInsertFailureRollsBackLegacyRefundAndRefundedAmount() throws Exception {
        UUID capture = UUID.randomUUID(), refund = UUID.randomUUID(), order = UUID.randomUUID();
        JsonNode captured = body(postOperation(capture, request(order, "CAPTURE", 46000, null), 200));
        jdbc.execute("alter table payment_processor.saga_operations add constraint reject_refund_test "
                + "check (operation_id <> '" + refund + "'::uuid)");
        postOperation(refund, request(order, "REFUND", 46000, capture), 500);
        assertThat(count("mock_refunds", "payment_id", captured.path("providerId").asText())).isZero();
        assertThat(jdbc.queryForObject("select amount_refunded from payment_processor.mock_payments where payment_id=?", Long.class, captured.path("providerId").asText())).isZero();
    }

    @Test void legacyMutationRoutesCannotChangeDurableSagaFacts() throws Exception {
        JsonNode authorized = body(postOperation(UUID.randomUUID(), request(UUID.randomUUID(), "CAPTURE", 1055, null), 200));
        String payment = authorized.path("providerId").asText();
        mvc.perform(post("/mock-pg/v1/payments/" + payment + "/capture")
                .contentType("application/json").content("{\"amount\":1055,\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest());
        String gatewayOrder = jdbc.queryForObject("select order_id from payment_processor.mock_payments where payment_id=?", String.class, payment);
        mvc.perform(post("/mock-pg/v1/orders/" + gatewayOrder + "/payments")
                .contentType("application/json").content("{\"method\":\"upi\",\"vpa\":\"caller@mock\"}"))
                .andExpect(status().isBadRequest());
        JsonNode captured = body(postOperation(UUID.randomUUID(), request(UUID.randomUUID(), "CAPTURE", 10000, null), 200));
        mvc.perform(post("/mock-pg/v1/payments/" + captured.path("providerId").asText() + "/refund")
                .contentType("application/json").content("{\"amount\":10000}"))
                .andExpect(status().isBadRequest());
    }

    private MvcResult postOperation(UUID operation, String request, int expected) throws Exception {
        return mvc.perform(post(path(operation)).header("Authorization", "Bearer integration-secret")
                .contentType("application/json").content(request)).andExpect(status().is(expected)).andReturn();
    }
    private MvcResult rawPost(UUID operation, String request) throws Exception {
        return mvc.perform(post(path(operation)).header("Authorization", "Bearer integration-secret")
                .contentType("application/json").content(request)).andReturn();
    }
    private String request(UUID order, String type, long amount, UUID capture) throws Exception {
        var values = new java.util.HashMap<String, Object>(Map.of("orderId", order, "operationType", type,
                "amountMinor", amount, "currency", "INR"));
        if (capture != null) values.put("captureOperationId", capture);
        return json.writeValueAsString(values);
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()); }
    private String path(UUID operation) { return "/mock-pg/saga/operations/" + operation; }
    private long count(String table, String column, String value) {
        return jdbc.queryForObject("select count(*) from payment_processor." + table + " where " + column + "=?", Long.class, value);
    }
}
