# Sprint smoke tests — explicit invocation only

Selected on 2026-10-18 at the user's request. This is a fixed selection of
**10 existing JUnit test methods**, not ten whole test classes. Run it only when
the user explicitly invokes the sprint smoke test, for example: "run the sprint
smoke tests." Sprint completion, application startup, deployment and restart
do not invoke it automatically. A smoke request does not authorize load tests.

## Selected checks

All paths below are under `src/test/java/com/example/booking/`.

| # | Behavior checked | Existing test method |
| --- | --- | --- |
| 1 | Generic hold/checkout/payment succeeds, keeps the seat booked and records audit actions | `BookingIntegrationTest#successfulPaymentConfirmsAndKeepsSeatBooked` |
| 2 | Replaying an expired hold returns the original expired booking; a fresh request can acquire the seat | `BookingIntegrationTest#expiredReplayNeverCreatesAnotherHold` |
| 3 | Movie seat-group pricing is correct; a conflicting group leaves no partial reservation | `MovieBookingIntegrationTest#atomicGroupHoldPricesDifferentCategoriesAndConflictRollsBackEverything` |
| 4 | Failed movie payment preserves the whole hold and deadline; a fresh payment preserves attempt history | `MovieBookingIntegrationTest#failedSecondCallKeepsWholeHoldAndFreshPaymentDoesNotResetDeadlineOrHistory` |
| 5 | Expired movie seats can be reassigned; late payment success requires refund without taking them back | `MovieBookingIntegrationTest#expiredGroupIsReassignedAndLateSuccessCannotStealAnySeat` |
| 6 | Generic confirmation and its outbox event roll back together; the durable provider receipt supports recovery | `OutboxIntegrationTest#rolledBackConfirmationAndOutboxDisappearTogetherWhileProviderReceiptSurvives` |
| 7 | Order API returns stored 202 replay and server price, rejects changed input and hides another owner's order | `OrderSagaIntegrationTest#createsAnAcceptedOrderWithServerPriceAndStoredReplay` |
| 8 | Saga confirms only after payment and inventory commit; duplicate replies do not duplicate capture or confirmation | `OrderSagaIntegrationTest#happyPathRequiresPaymentAndCommittedInventoryAndDuplicatesAreHarmless` |
| 9 | Inventory commit failure after capture refunds payment and releases seats before the order becomes FAILED | `OrderSagaIntegrationTest#commitFailureAfterChargeRefundsAndReleasesBeforeFailure` |
| 10 | Real Kafka and the copied gateway confirm through duplicate publication, cancel pending work and refund captured pending cancellation | `OrderKafkaIntegrationTest#kafkaAndCopiedGatewayConfirmWithDuplicatePublicationThenCancelPendingOrder` |

## Invocation — PowerShell

Run from `D:/sd-book-my-show` **only after explicit smoke invocation**:

```powershell
$smokeTests = @(
  'BookingIntegrationTest#successfulPaymentConfirmsAndKeepsSeatBooked'
  'BookingIntegrationTest#expiredReplayNeverCreatesAnotherHold'
  'MovieBookingIntegrationTest#atomicGroupHoldPricesDifferentCategoriesAndConflictRollsBackEverything'
  'MovieBookingIntegrationTest#failedSecondCallKeepsWholeHoldAndFreshPaymentDoesNotResetDeadlineOrHistory'
  'MovieBookingIntegrationTest#expiredGroupIsReassignedAndLateSuccessCannotStealAnySeat'
  'OutboxIntegrationTest#rolledBackConfirmationAndOutboxDisappearTogetherWhileProviderReceiptSurvives'
  'OrderSagaIntegrationTest#createsAnAcceptedOrderWithServerPriceAndStoredReplay'
  'OrderSagaIntegrationTest#happyPathRequiresPaymentAndCommittedInventoryAndDuplicatesAreHarmless'
  'OrderSagaIntegrationTest#commitFailureAfterChargeRefundsAndReleasesBeforeFailure'
  'OrderKafkaIntegrationTest#kafkaAndCopiedGatewayConfirmWithDuplicatePublicationThenCancelPendingOrder'
)
mvn -B -ntp "-Dtest=$($smokeTests -join ',')" `
  '-Dsurefire.failIfNoSpecifiedTests=true' '-DfailIfNoTests=true' `
  '-Dsurefire.skipAfterFailureCount=1' test
```

Maven compiles the root application/test sources and selects these methods;
the array order does not prescribe JUnit execution order. Do not replace this
command with an unfiltered `test`/`verify`, a full-class selector or a loader.
Do not use Maven `clean`.

Prerequisites are Java 21, Maven and a running Docker engine for Testcontainers.
The selected classes use separate PostgreSQL test containers; the Kafka test
also starts a separate Kafka container and builds/starts the copied gateway
using `-DskipTests package`. It does not run that gateway's own test suite.
The other saga tests use a local provider stub and direct participant delivery;
they do not claim broker coverage. No retained-stack startup is needed.
Container startup, Flyway catalog migrations and gateway packaging add overhead;
the duration of this exact selection has not been measured.

This selection excludes load-generator suites, load scripts, sustained traffic,
retained-database business requests and fault/load experiments. Keep the Java
and Node load-generator services stopped. Full regression, performance and
deployment checks remain separate selections.

## Reporting

Use fresh `target/surefire-reports` for the selected classes to report the actual
executed count, failures, errors, skips and elapsed time. Success requires all
10 selected methods to execute with zero failures, errors or skips. The failure
threshold requests stopping after the first failure; report remaining checks as
not executed and do not automatically broaden or retry the suite. Preserve logs
and existing files/data. A successful smoke run covers these behaviors only.

**Selection status:** source-reviewed and documented on 2026-10-18; this exact
selection has not been executed. Earlier full-suite results retain their own
dates and do not establish a new smoke result.
