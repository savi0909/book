# API 2: list events

[Tutorial index and learning guide](TUTORIAL_INDEX.md) · One lesson at a time; runtime exercises require a selected hands-on session.

Today's API is **`GET /api/events`**. It reads existing events without creating a fixture. Your goal is to explain how URL parameters become a SQL query and then a JSON array.

Read one step at a time in IntelliJ. Stop after step 3; the Postman practice is optional.

## 1. Understand the request

```http
GET http://localhost:8132/api/events?limit=3&offset=0
```

This is the deployed local HAProxy gateway; API A is also reachable on port 8130.
There is no JSON request body and no authentication header is required.

| Query parameter | This request | Default | Allowed values |
| --- | --- | --- | --- |
| `limit` | `3` | `50` | `1`–`100` |
| `offset` | `0` | `0` | `0`–`10000` |

`limit=3` asks for at most three events; `offset=0` skips none. Successful status:
HTTP **200 OK**, with a JSON array. The 2026-10-18 study invocation returned three
retained events; this excerpt shows only the first, omitting its `createdAt` field
and the other two items:

```json
[
  {
    "id": "8a1d9a2a-47b5-45cc-b7ae-042fe22b090d",
    "name": "Java load 4280e600e6b44bc1",
    "seatCount": 1,
    "priceMinor": 5000,
    "currency": "INR"
  }
]
```

This is an excerpt of the earlier study response, not a newly executed request
or a permanent fixture guarantee. Future names and IDs can differ. An empty
database returns `[]`, not a missing-event error. `priceMinor=5000` means ₹50.
The response contains event metadata; it does not count currently available seats.

Compact IDE flow (open each class and find the named method):

```text
BookingController.events(limit, offset)
  -> BookingService.events(limit, offset)
    -> BookingStore.jdbc() returns JdbcTemplate
      -> JdbcTemplate.query(SQL, rowMapper, limit, offset)
        -> PostgreSQL: events table
```

Source: [controller](../src/main/java/com/example/booking/BookingController.java),
[service](../src/main/java/com/example/booking/BookingService.java),
[store](../src/main/java/com/example/booking/BookingStore.java).
There is no separate repository method on this path; the SQL lives in the service.

## 2. Find the controller method

Press **Ctrl+N**, open [BookingController](../src/main/java/com/example/booking/BookingController.java), then **Ctrl+F** for `@GetMapping("/api/events")`:

```java
@GetMapping("/api/events")
List<Event> events(@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit,
                   @RequestParam(defaultValue="0") @Min(0) @Max(10000) int offset) {
    return bookings.events(limit,offset);
}
```

- `@RequestParam` reads values from the URL's query string.
- Omitting both parameters uses `limit=50` and `offset=0`.
- Validation permits limit 1–100 and offset 0–10000. The class's `@Validated` enables these method-parameter checks.
- The method returns `List<Event>`. Spring serializes it as a JSON array with status 200 on success.

**Pause point:** find where the two numbers are passed to the service.

## 3. Follow the SELECT and row mapper

**Ctrl+Click `bookings.events`** to open [BookingService](../src/main/java/com/example/booking/BookingService.java). Read only `events(int limit, int offset)`:

```java
public List<Event> events(int limit, int offset) {
    return store.jdbc().query(
        "SELECT * FROM events ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
        (rs,n) -> new Event(
            rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getInt("seat_count"), rs.getInt("price_minor"),
            rs.getString("currency"), BookingStore.instant(rs,"created_at")),
        limit, offset);
}
```

| Part | Meaning |
| --- | --- |
| `SELECT * FROM events` | Read the event columns. |
| `ORDER BY created_at DESC, id` | Newest events first; ascending UUID breaks equal-timestamp ties. |
| `LIMIT ? OFFSET ?` | Bind our limit 3 and offset 0 as query arguments. |
| `(rs,n) -> new Event(...)` | Convert each returned database row into an `Event` record. |

`rs` is the result set positioned at the current row. `n` is the mapper's row number and is unused here. SQL's `seat_count` becomes Java's `seatCount`, which becomes the response's `seatCount` field. The [Event record](../src/main/java/com/example/booking/Models.java) is the same response model used in API 1.

Unlike creation, this method does not call `store.tx(...)`: it performs one SELECT and maps its rows. Database availability is still required.

**Pause point:** point to the ordering, the page selection and the row-to-record conversion. Today's path is complete:

```text
URL query parameters → BookingController.events → BookingService.events
                     → PostgreSQL SELECT → List<Event> → HTTP 200 JSON array
```

## One small exercise

Imagine the query's ordered results are **A, B, C, D, E**. What does `limit=2&offset=2` return?

Answer when ready: **C and D**. Offset skips A and B; limit takes the next two.

The UUID tie break gives a deterministic order for the same data. It does not freeze the list between requests: if another event is inserted at the front, offset-based pages can repeat or skip items. This API provides no snapshot cursor.

## Optional: use Postman

Import [the API 2 collection](../postman/api-02-list-events.postman_collection.json), select **No environment**, and open **List events**. Its URL and query parameters are already filled in. Click **Send** when replica A is running.

The collection checks HTTP 200, JSON, the array shape, the page-size bound, event fields and newest-first timestamps. These checks allow an empty array; they do not prove a particular fixture exists or independently verify UUID ordering for tied timestamps.

Change the request's `limit` parameter to `2` and `offset` to `2` to try the exercise against real data. Repeated GETs do not create events. No event ID from API 1 is needed for this endpoint.

Stop here. Continue with the next API only when you are ready.
