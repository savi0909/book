# API 1: how the two lambdas execute

Stay with `createEvent()` for this lesson. The key line is **`work.get()`**: it runs the lambda you passed to `store.tx(...)`.

Read sections 1 and 2 first. The expanded Java and debugger steps can wait.

## 1. Give the two blocks names

In [BookingService](../src/main/java/com/example/booking/BookingService.java), call this **the event-work lambda**:

```java
return store.tx(() -> {
    UUID id = UUID.randomUUID();
    store.jdbc().update("INSERT INTO events(id,name,seat_count) VALUES (?,?,?)",
            id, request.name(), request.seatCount());
    store.jdbc().update("INSERT INTO seats SELECT ?, n FROM generate_series(1,?) n",
            id, request.seatCount());
    return event(id);
});
```

`tx` expects a `Supplier<T>`. For this call, `T` is `Event`, so think **`Supplier<Event>`**. A Supplier has one abstract method:

```java
T get();
```

The lambda supplies the implementation of `get()`. The empty `()` means `get()` takes no arguments. Passing this lambda to `tx` does **not** yet run its INSERTs. The parameter `work` refers to this supplied behavior.

In [BookingStore](../src/main/java/com/example/booking/BookingStore.java), call this **the transaction lambda**:

```java
<T> T tx(Supplier<T> work) {
    return transaction.execute(status -> {
        jdbc.execute("SET LOCAL lock_timeout = '2s'");
        jdbc.execute("SET LOCAL statement_timeout = '3s'");
        return work.get();
    });
}
```

`transaction.execute` expects Spring's `TransactionCallback<T>`. Its one abstract method is:

```java
T doInTransaction(TransactionStatus status);
```

`status -> { ... }` supplies that method's implementation. Spring supplies the `status` argument, which describes the transaction; this block does not use it directly. It captures `work`, so it can call the event-work lambda later.

| Lambda | Target interface for this call | Who invokes it? |
| --- | --- | --- |
| `() -> { INSERTs; return event(id); }` | `Supplier<Event>` | Your code, at `work.get()` |
| `status -> { SETs; return work.get(); }` | `TransactionCallback<Event>` | Spring, at `doInTransaction(status)` |

**Pause:** find `work.get()` in IntelliJ. That is the bridge between the two blocks.

## 2. Follow the actual order

For this HTTP call, there is no surrounding service transaction, so Spring starts one here:

```text
1. createEvent(request) passes the event-work lambda to tx.
2. tx(work) passes the transaction lambda to transaction.execute.
3. Spring starts the database transaction.
4. Spring invokes the transaction lambda: doInTransaction(status).
5. That lambda runs both SET LOCAL statements.
6. It calls work.get(). Now the event-work lambda begins.
7. Generate UUID → INSERT event → INSERT seats → SELECT event.
8. event(id) returns an Event to the event-work lambda.
9. That lambda returns the Event from get().
10. The transaction lambda returns the same Event to Spring.
11. Spring commits the transaction.
12. execute returns the Event → tx returns it → createEvent returns it.
```

The call stack while the INSERT runs is approximately:

```text
createEvent
  → tx
    → TransactionTemplate.execute
      → transaction lambda
        → work.get / event-work lambda
          → JdbcTemplate.update
```

These are ordinary **synchronous calls on the calling thread**. Neither lambda creates a background job or a new thread. Lambda syntax alone does not make code asynchronous.

`return event(id)` exits the event-work lambda. It does not jump directly out of `createEvent`, and it does not commit the transaction. Each caller receives the value and returns it in turn; Spring performs the commit before `execute` returns successfully.

If a JDBC statement throws an unchecked database exception, the success returns are interrupted. The exception travels back to Spring, which rolls the transaction back and propagates the failure. A commit can also fail; an Event constructed inside the lambda does not guarantee a successful HTTP response.

**Stop here if the sequence is clear.** The remaining sections explain the syntax more explicitly.

## 3. Read the first lambda without arrow syntax

This is an equivalent way to express the event-work block for learning. It is an illustration, not a requested application change or a description of the compiler's exact generated classes:

```java
public Event createEvent(EventRequest request) {
    Supplier<Event> work = new Supplier<Event>() {
        @Override
        public Event get() {
            UUID id = UUID.randomUUID();
            store.jdbc().update("INSERT INTO events(id,name,seat_count) VALUES (?,?,?)",
                    id, request.name(), request.seatCount());
            store.jdbc().update("INSERT INTO seats SELECT ?, n FROM generate_series(1,?) n",
                    id, request.seatCount());
            return event(id);
        }
    };
    return store.tx(work);
}
```

Creating `work` supplies the method body; calling `work.get()` executes it. The lambda captures `request` from `createEvent`, so that data is available when the method body runs. A captured local variable or parameter must be final or effectively final: here, `request` is not reassigned. This does not imply that all objects referenced by lambdas are immutable.

## 4. Read the second lambda without arrow syntax

Similarly, the transaction callback can be expressed as:

```java
<T> T tx(Supplier<T> work) {
    TransactionCallback<T> callback = new TransactionCallback<T>() {
        @Override
        public T doInTransaction(TransactionStatus status) {
            jdbc.execute("SET LOCAL lock_timeout = '2s'");
            jdbc.execute("SET LOCAL statement_timeout = '3s'");
            return work.get();
        }
    };
    return transaction.execute(callback);
}
```

For this example, the interfaces are `java.util.function.Supplier`, `org.springframework.transaction.support.TransactionCallback` and `org.springframework.transaction.TransactionStatus`.

Spring's normal transaction-manager path has this simplified success sequence internally:

```java
TransactionStatus status = transactionManager.getTransaction(this);
T result = callback.doInTransaction(status);
transactionManager.commit(status);
return result;
```

This excerpt omits error handling and alternative manager paths. The local Spring `TransactionTemplate` bytecode was checked: its normal path invokes the callback, commits, then returns the saved result. Transaction propagation can join an existing transaction in other call contexts; this API's controller-to-service call starts one here.

The generic declaration `<T> T tx(Supplier<T> work)` means: “accept work that returns a type T, and return that same type.” It is not two separate values named T. For `createEvent`, Java infers `T = Event`.

## 5. See the order in IntelliJ

Use these locations when the application is running under IntelliJ's debugger:

1. Break at `return transaction.execute(...)` in `tx`: the UUID has not been generated yet.
2. Break at the first `SET LOCAL` statement: Spring has entered the transaction callback, but event work has not started.
3. Break at `UUID.randomUUID()`: this is reached through `work.get()`.
4. Break at `return event(id)`: both INSERTs ran; Spring has not yet committed this transaction.

Open the debugger's **Frames** view at step 3 to see the nested calls. Lambda frame names may contain `lambda$...`; the exact names are generated details. In `tx`, **Ctrl+Click `execute`** to inspect Spring's source or IntelliJ's decompiled class and find `action.doInTransaction(status)` and `transactionManager.commit(status)`.

Setting these breakpoints does not attach IntelliJ to a Docker API. Follow the [API 1 runtime guidance](API_01_CREATE_EVENT_WALKTHROUGH.md) when arranging a local debug process. Keep pauses short inside a database transaction because this lab configures transaction and SQL timeouts.

## One check before moving on

Which line starts executing the event-work lambda: `store.tx(...)`, `transaction.execute(...)`, or `work.get()`?

Answer: **`work.get()`**. The earlier calls pass callbacks and arrange the transaction; this invocation enters your UUID/INSERT/SELECT block.
