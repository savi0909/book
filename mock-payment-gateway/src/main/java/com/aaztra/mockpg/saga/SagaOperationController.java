package com.aaztra.mockpg.saga;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/mock-pg/saga/operations")
public class SagaOperationController {
    private final SagaOperationService operations;
    public SagaOperationController(SagaOperationService operations) { this.operations = operations; }
    @PostMapping("/{operationId}")
    public SagaOperationResponse execute(@PathVariable UUID operationId, @Valid @RequestBody SagaOperationRequest request) {
        return operations.execute(operationId, request);
    }
    @GetMapping("/{operationId}")
    public SagaOperationResponse get(@PathVariable UUID operationId) { return operations.get(operationId); }
}
