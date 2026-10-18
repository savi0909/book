package com.aaztra.mockpg.saga;

import java.util.UUID;

public record SagaOperationResponse(UUID operationId, UUID orderId, String operationType,
                                    long amountMinor, String currency, String status, String providerId) { }
