# API 3: get one event by ID

Today's API is **`GET /api/events/{id}`**. It reads one existing event. Stay with this endpoint; read one step at a time in IntelliJ.

## 1. Understand the request

Use a real event UUID from the API 1 response or an item in API 2's list:

```http
GET http://localhost:8105/api/events/YOUR-EVENT-UUID
```

Replace `YOUR-EVENT-UUID` with the actual ID. There is no body and no `limit` or `offset`. Success is HTTP **200 OK** and a single JSON object containing `id`, `name`, `seatCount`, `priceMinor`, `currency` and `createdAt`.

API 2 returned an array, `[{...}, {...}]`. This API returns one object, `{...}`. It does not create an event or report how many seats are currently available.

## 2. Follow the controller to the SELECT

Press **Ctrl+N**, open [BookingController](../src/main/java/com/example/booking/BookingController.java), then **Ctrl+F** for `@GetMapping("/api/events/{id}")`:

```java
@GetMapping("/api/events/{id}")
Event event(@PathVariable UUID id) {
    return bookings.event(id);
}
```

`@PathVariable` reads the `{id}` segment of the URL. Spring converts that string to a Java `UUID` before calling the method. The service receives this UUID; Spring serializes the returned `Event` into JSON.

**Pause:** point to the URL placeholder and the matching Java parameter.

**Ctrl+Click `bookings.event`** to open [BookingService](../src/main/java/com/example/booking/BookingService.java):

```java
public Event event(UUID id) {
    return BookingStore.one(store.jdbc().query(
        "SELECT * FROM events WHERE id = ?",
        (rs,n) -> new Event(
            rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getInt("seat_count"), rs.getInt("price_minor"),
            rs.getString("currency"), BookingStore.instant(rs,"created_at")),
        id));
}
```

The `?` is bound to `id`. The database's `events.id` primary key means the SELECT can return zero or one row. The normal GET path performs this SELECT without an explicit `store.tx(...)` wrapper.

You have seen this same method before: `createEvent()` calls `event(id)` inside its transaction to build its response. This GET endpoint calls it directly. The method reuses the query and mapping code in both paths.

## 3. Understand this lambda and `one(...)`

The expression `(rs,n) -> new Event(...)` implements a **`RowMapper<Event>`**, whose method is:

```java
Event mapRow(ResultSet rs, int rowNum);
```

`JdbcTemplate.query(...)` executes the SQL. As it iterates the result set, it calls the mapper for each returned row, supplying `rs` positioned on that row and the row number `n`. Your lambda constructs and returns an `Event`.

This is a synchronous callback. It is not the `Supplier.get()` lambda from event creation. This endpoint's query executes the mapper through `mapRow(...)`; no `work.get()` is involved in this GET path.

Read the nested expression as these two conceptual steps:

```java
List<Event> rows = store.jdbc().query(sql, rowMapper, id);
Event result = BookingStore.one(rows);
return result;
```

These lines illustrate the order; `sql` and `rowMapper` stand for the arguments in the actual method. **The query and row mapping finish before `one(rows)` runs.**

**Ctrl+Click `one`** to open [BookingStore](../src/main/java/com/example/booking/BookingStore.java):

```java
static <T> T one(List<T> rows) {
    if (rows.isEmpty()) throw ApiException.missing();
    return rows.getFirst();
}
```

For this call, `T` is `Event`:

| Query result | Mapper calls | What `one` does |
| --- | --- | --- |
| One matching row | Once | Returns the first Event. |
| No matching row | Zero | Throws `ApiException.missing()`. |

The helper itself only checks for an empty list; it does not reject multiple rows. The primary key makes a multiple-row result impossible for this query.

[ApiException.missing](../src/main/java/com/example/booking/ApiException.java) creates a 404 error with code `NOT_FOUND`. [Errors.domain](../src/main/java/com/example/booking/Errors.java) converts it to the HTTP response.

```text
URL ID → UUID → controller → service SELECT
       → RowMapper → List<Event> → one(list)
       → Event → HTTP 200 object
          or empty list → NOT_FOUND → HTTP 404
```

**Stop here for today's core reading.**

## One small exercise

If the UUID is valid but no event has that ID, does the row-mapper lambda run? Which method decides the request should return 404?

Answer when ready: **the mapper does not run because there are no rows. `BookingStore.one` throws the missing-resource exception, and `Errors` maps it to HTTP 404.** A malformed ID such as `hello` instead fails UUID conversion and returns HTTP 400 before the service runs.

## Optional: Postman practice

1. Import [the API 3 collection](../postman/api-03-get-event.postman_collection.json).
2. Select **No environment**.
3. Open the collection's **Variables** tab. Set `eventId` to a real UUID copied from API 1 or API 2 and save the value used for requests.
4. Open **Get one event** and click **Send** when replica A is running.

The API 1 collection's variable is scoped to that collection; it does not automatically transfer to this new one. Paste the UUID explicitly. The API 3 collection supplies `baseUrl = http://localhost:8105` and no setup requests. Its script warns you if `eventId` is empty or malformed.

The checks expect HTTP 200, JSON, one object, the requested ID and valid metadata fields. They expect an existing event; deliberate 400/404 experiments will fail those success checks.

## Optional: IntelliJ breakpoints

For a local process already attached to IntelliJ's debugger, break in the controller's `event`, on the service's `return BookingStore.one(...)`, and inside `BookingStore.one`. At the last breakpoint, inspect `rows` to see the mapped Event before it is returned.

The method's mapper also runs when `createEvent` calls it; use this GET request to follow today's direct read path. See [API 1's runtime guidance](API_01_CREATE_EVENT_WALKTHROUGH.md) before setting up a debug process alongside Docker.
