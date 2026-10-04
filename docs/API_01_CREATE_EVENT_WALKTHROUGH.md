# API 1: create a demo event

Today's API is **`POST /api/demo/events`**. It creates a concert/show and its numbered seats. Your goal is to explain how one JSON request becomes database rows and a response.

Read one step, then pause in IntelliJ. You can stop after any step and resume at the next number. The core walkthrough ends at step 4; running or debugging is optional.

## 1. Start with the request

Imagine sending this to replica A, when it is running:

```http
POST http://localhost:8105/api/demo/events
Content-Type: application/json

{"name":"My first concert","seatCount":3}
```

Expected result: HTTP **201 Created**, one event row, and three seat rows numbered 1, 2 and 3. No booking or payment is created by this API.

The response has this shape; the UUID and timestamp below are illustrative:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "My first concert",
  "seatCount": 3,
  "priceMinor": 5000,
  "currency": "INR",
  "createdAt": "2026-10-05T10:00:00Z"
}
```

Here, “event” means the concert/show whose seats we sell. It is the inventory entity.

## 2. Find the HTTP entry in IntelliJ

Press **Ctrl+N**, open [BookingController](../src/main/java/com/example/booking/BookingController.java), then search for `/api/demo/events` with **Ctrl+F**. Focus on this method:

```java
@PostMapping("/api/demo/events")
ResponseEntity<Event> create(@Valid @RequestBody EventRequest request) {
    return ResponseEntity.status(201).body(bookings.createEvent(request));
}
```

Read it from top to bottom:

- `@PostMapping` connects the URL and POST method to this Java method.
- `@RequestBody` lets Spring turn the JSON into an `EventRequest`.
- `@Valid` checks the request's validation rules before the service runs.
- `bookings.createEvent(request)` calls the service. After it returns successfully, the controller builds the 201 response.

**Ctrl+Click `EventRequest`** to open [Models](../src/main/java/com/example/booking/Models.java). Read only `EventRequest` and `Event`:

```java
public record EventRequest(@NotBlank @Size(max = 80) String name,
                          @NotNull @Min(1) @Max(200) Integer seatCount) {}
public record Event(UUID id, String name, int seatCount, int priceMinor,
                    String currency, Instant createdAt) {}
```

`EventRequest` is the input: a nonblank name of at most 80 characters and a required seat count from 1 to 200. `Event` is the output, with generated identity, database defaults and creation time. Records give you accessors such as `request.name()`.

**Pause point:** find which line delegates the work to the service. You have finished the HTTP layer.

## 3. Follow the service into SQL

Return to the controller and **Ctrl+Click `createEvent`**. IntelliJ opens [BookingService](../src/main/java/com/example/booking/BookingService.java):

```java
public Event createEvent(EventRequest request) {
    return store.tx(() -> {
        UUID id = UUID.randomUUID();
        store.jdbc().update("INSERT INTO events(id,name,seat_count) VALUES (?,?,?)",
                id, request.name(), request.seatCount());
        store.jdbc().update("INSERT INTO seats SELECT ?, n FROM generate_series(1,?) n",
                id, request.seatCount());
        return event(id);
    });
}
```

The `() -> { ... }` is a lambda: a block of work passed to `store.tx` to execute inside a transaction.

If the two nested lambdas are confusing, pause here and read
[how the lambdas execute, one call at a time](API_01_LAMBDA_EXECUTION_WALKTHROUGH.md).

| Line | What happens for our request |
| --- | --- |
| `UUID.randomUUID()` | Creates the event's identifier. |
| First `update` | Inserts one row with that ID, the name and seat count 3. |
| Second `update` | Inserts three seats using the same event ID. |
| `event(id)` | Reads the event row and converts it into the response record. |

The SQL `?` placeholders receive the arguments following the SQL string, in order. `generate_series(1,3)` produces 1, 2 and 3 in PostgreSQL, so the second statement creates all three seats in one SQL call. `update` can insert multiple rows.

Read the adjacent `event(UUID id)` method. Its `SELECT * FROM events WHERE id = ?` reads the row, and its mapper constructs `new Event(...)` from the result. **`event(id)` is a Java method call, not another HTTP request.**

**Pause point:** trace the same UUID through both INSERT statements and the SELECT.

## 4. Understand the transaction and database defaults

**Ctrl+Click `tx`** to open [BookingStore](../src/main/java/com/example/booking/BookingStore.java). Focus on `transaction.execute(...)` and `return work.get()`.

`TransactionTemplate` starts or joins a transaction, runs the lambda and commits successfully before `store.tx(...)` returns to the controller. The JDBC operations use the transaction's connection. The SELECT happens inside that transaction; the HTTP response is built after successful completion.

The key concept today is **atomicity**: if the seat INSERT fails and the transaction rolls back, the event INSERT rolls back too. A successful commit keeps the event and all its seats together.

Next, open [V1__booking.sql](../src/main/resources/db/migration/V1__booking.sql). Read only the first two tables, `events` and `seats`:

- `events` supplies defaults for `price_minor` (5000), `currency` (`INR`) and `created_at` (the database clock). In INR, 5000 minor units means ₹50.00.
- `seats.event_id` references an existing event.
- `PRIMARY KEY (event_id, seat_number)` permits seat 1 in different events, but prevents duplicate seat 1 within the same event.

These defaults explain why the response contains fields you did not send.

**Stop here for today's core reading.** The complete path is:

```text
JSON → BookingController.create → BookingService.createEvent
     → BookingStore.tx → PostgreSQL INSERTs + SELECT
     → Event record → HTTP 201 JSON
```

## One small exercise

Without running anything, use the `seatCount: 3` example and say these three sentences in your own words:

1. “This request creates ___ event row and ___ seat rows.”
2. “The response's price and currency come from ___.”
3. “If the seat INSERT fails, the event row ___ because ___.”

Answers, when you want to check: **one and three; database column defaults; rolls back because both INSERTs share one transaction.**

## Optional: try just this request

Import [the API 1 Postman collection](../postman/api-01-create-event.postman_collection.json):

1. In Postman, click **Import**, then select that JSON file.
2. Open **Ticket booking - API 1 - Create demo event → Create demo event**.
3. Select **No environment**; the collection includes `baseUrl = http://localhost:8105`.
4. When replica A is running, click **Send** once. Read the response and the test results.

The tests check HTTP 201, the returned fields and the generated UUID. They save
the response ID in the collection variable `eventId`; they do not independently
query the database to count seats. No separate environment file is needed.

Record the real returned event ID. Keep today's practice to this single request.

Sending this request again creates another event, even with the same name and count. This endpoint has no request-key deduplication contract; avoid repeated sends if you want just one fixture.

For one validation example, change `seatCount` to `0`. Spring rejects it with HTTP 400 before `createEvent` runs. [Errors](../src/main/java/com/example/booking/Errors.java), method `invalid`, maps validation errors to the `INVALID_REQUEST` response.

## Optional: breakpoint locations for a later debug session

When you run the application under IntelliJ's debugger, place breakpoints in the controller's `create`, on `UUID.randomUUID()`, and on `return event(id)`. Use **F8** to step over a statement, or **F7** to enter a method.

Setting breakpoints alone does not attach IntelliJ to a running Docker API. A local debug process must use a free port if Docker already owns 8105. See the host-run instructions in the [README](../README.md) when you are ready for runtime setup.

Today's lesson is complete when you can explain the request-to-row path and why the two INSERTs commit together. Continue with another API only when you are ready.
