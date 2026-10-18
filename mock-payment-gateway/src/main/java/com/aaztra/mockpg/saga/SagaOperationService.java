package com.aaztra.mockpg.saga;

import com.aaztra.mockpg.dto.*;
import com.aaztra.mockpg.service.MockOrderService;
import com.aaztra.mockpg.service.MockPaymentService;
import com.aaztra.mockpg.service.MockRefundService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SagaOperationService {
    private final JdbcTemplate jdbc;
    private final MockOrderService orders;
    private final MockPaymentService payments;
    private final MockRefundService refunds;

    public SagaOperationService(JdbcTemplate jdbc, MockOrderService orders,
                                MockPaymentService payments, MockRefundService refunds) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.payments = payments;
        this.refunds = refunds;
    }

    /** JPA legacy effects and JDBC operation inserts share the same Spring transaction/connection. */
    @Transactional
    public SagaOperationResponse execute(UUID operationId, SagaOperationRequest request) {
        boolean capture = request.operationType() == SagaOperationRequest.OperationType.CAPTURE;
        if (request.orderId() == null || request.operationType() == null || request.amountMinor() <= 0
                || !"INR".equals(request.currency()) || capture == (request.captureOperationId() != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid full-amount operation");
        }
        // Two namespaces and a fixed lock order prevent same-key and same-order races across replicas.
        lock("operation:" + operationId);
        String hash = hash(request);
        var prior = find(operationId);
        if (!prior.isEmpty()) {
            String priorHash = jdbc.queryForObject("select request_hash from payment_processor.saga_operations where operation_id=?", String.class, operationId);
            if (!hash.equals(priorHash)) conflict("Operation ID already belongs to a different request");
            return prior.getFirst();
        }
        lock("order:" + request.orderId());
        if (jdbc.queryForObject("select count(*) from payment_processor.saga_operations where order_id=? and operation_type=?",
                Long.class, request.orderId(), request.operationType().name()) != 0) {
            conflict("Order already has an operation of this type; replay its original ID");
        }
        SagaOperationResponse result = capture ? capture(operationId, request) : refund(operationId, request);
        jdbc.update("""
                insert into payment_processor.saga_operations
                (operation_id,order_id,operation_type,amount_minor,currency,capture_operation_id,request_hash,status,provider_id)
                values (?,?,?,?,?,?,?,?,?)
                """, operationId, request.orderId(), request.operationType().name(), request.amountMinor(),
                request.currency(), request.captureOperationId(), hash, result.status(), result.providerId());
        return result;
    }

    @Transactional(readOnly = true)
    public SagaOperationResponse get(UUID operationId) {
        return find(operationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Operation not found"));
    }

    private SagaOperationResponse capture(UUID operationId, SagaOperationRequest request) {
        var notes = Map.of("origin", "order-saga", "operation_id", operationId.toString());
        var order = orders.createOrder(new CreateMockOrderRequest(request.amountMinor(), request.currency(),
                request.orderId().toString(), notes));
        var payment = payments.simulateSagaPayment(order.id(), new SimulatePaymentRequest("upi", null,
                "order-saga@mock", null, null, null, null, null, null, null, "Order saga capture", notes));
        String status = "failed".equals(payment.status()) ? "DECLINED" : "ACTION_REQUIRED";
        if ("captured".equals(payment.status()) && Boolean.TRUE.equals(payment.captured())
                && payment.amount() == request.amountMinor()) status = "CAPTURED";
        return response(operationId, request, status, payment.id());
    }

    private SagaOperationResponse refund(UUID operationId, SagaOperationRequest request) {
        var original = find(request.captureOperationId());
        if (original.isEmpty() || !"CAPTURE".equals(original.getFirst().operationType())
                || !original.getFirst().orderId().equals(request.orderId())
                || !"CAPTURED".equals(original.getFirst().status())) {
            return response(operationId, request, "REJECTED", null);
        }
        var capture = original.getFirst();
        if (capture.amountMinor() != request.amountMinor() || !capture.currency().equals(request.currency())) {
            conflict("Refund must match the complete original capture amount and currency");
        }
        var refund = refunds.createSagaRefund(capture.providerId(), new CreateMockRefundRequest(
                request.amountMinor(), "normal", request.orderId().toString(),
                Map.of("origin", "order-saga", "operation_id", operationId.toString())));
        return response(operationId, request, "processed".equals(refund.status()) ? "REFUNDED" : "REJECTED", refund.id());
    }

    private void lock(String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (rs, row) -> 0, "mock-pg-saga:" + key);
    }
    private List<SagaOperationResponse> find(UUID operationId) {
        return jdbc.query("select * from payment_processor.saga_operations where operation_id=?", (rs, row) ->
                new SagaOperationResponse(rs.getObject("operation_id", UUID.class), rs.getObject("order_id", UUID.class),
                        rs.getString("operation_type"), rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getString("status"), rs.getString("provider_id")), operationId);
    }
    private SagaOperationResponse response(UUID id, SagaOperationRequest request, String status, String providerId) {
        return new SagaOperationResponse(id, request.orderId(), request.operationType().name(), request.amountMinor(),
                request.currency(), status, providerId);
    }
    private void conflict(String reason) { throw new ResponseStatusException(HttpStatus.CONFLICT, reason); }
    private String hash(SagaOperationRequest request) {
        String canonical = request.orderId() + "|" + request.operationType() + "|" + request.amountMinor()
                + "|" + request.currency() + "|" + request.captureOperationId();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
