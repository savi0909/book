package com.aaztra.mockpg.saga;

import java.util.Map;

/** Saga traffic is reconciled through the operation API, never external webhooks. */
public final class SagaOrigin {
    private SagaOrigin() { }
    public static boolean isSaga(Map<String, String> notes) {
        return notes != null && "order-saga".equals(notes.get("origin"));
    }
}
