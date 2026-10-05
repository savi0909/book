# ADR-001: Show-specific inventory and atomic groups

**Status:** Accepted
**Date:** 2026-10-05
**Deciders:** User selected atomic multiple-seat bookings and retained holds
after failed payment, with a fresh payment attempt before original expiry.

## Context

The original lab reserves one seat per event and one payment per booking. The
movie requirements add5-100 screens/multiplex,200-500 seats/screen, categories,
dated shows, atomic groups and repeated user payment attempts after failure.

## Decision

Add movie tables/services through V5 alongside the inherited V1-V4 domain.
Reuse PostgreSQL, transaction deadlines, lifecycle admission, the same executable
and two replicas. Freeze inventory/prices per show. Lock the complete selected
group in ascending seat order, then its booking, then its payment. Reserve1-10
seats in one transaction. Preserve each payment intent/receipt and checkout key.

Use a five-second token/time-fenced lease and separately committed mock receipts.
One logical retry belongs to the same payment ID. Failure returns the group to
HELD; a fresh key creates a new payment ID without extending the hold deadline.

## Options considered

| Option | Complexity | Consequence |
| --- | --- | --- |
| Rewrite original tables | High | Changes every old callback/quarantine/outbox contract |
| Add movie domain in the existing executable | Moderate | Additive migration; separate reservation/payment implementations |
| Split into services now | High | Introduces distributed coordination before measuring local behavior |

## Trade-offs and consequences

Disjoint shows/seats can proceed independently; a hot seat remains serialized.
Large groups take more locks and can time out. Scheduling locks a screen before
checking intervals including turnaround. Catalog APIs are public local fixtures.

The movie mock is in-process with a separate receipt transaction but shares
PostgreSQL availability. It is not the inherited optional HTTP provider. Movie
outbox, general poison policy and actual refunds are future work; original outbox
tables do not publish movie transitions automatically. New feature commits use
actual timestamps; only imported source snapshots use reconstructed dates.

## Action items

1. Implement V5, show inventory, atomic groups and persistent payment attempts.
2. Verify races, rollback, expiry, receipt replay, token fencing and late success.
3. Deliver tutorial/APIs/Postman and bounded A/B runtime evidence.
4. Plan thousands-user simulation separately with measured resource limits.
