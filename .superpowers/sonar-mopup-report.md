# Sonar mop-up report — jwcarman_nessy CODE_SMELL wave (20 issues)

Fetched fresh from `https://sonarcloud.io/api/issues/search?componentKeys=jwcarman_nessy&resolved=false&ps=100`
at the start of the task. 20 open CODE_SMELL issues, all addressed: 19 fixed, 1 skipped-with-reasons
(a judgment call the brief explicitly allowed skipping).

## Fixed (19)

### S5853 × 4 — "Join these multiple assertions subject to one assertion chain"
- `ChatWebSmokeTest.java:127` — `assertThat(transcriptAfterPark).isNotEmpty()` +
  `.anyMatch(...)` on the same subject joined into one chain.
- `ChatWebSmokeTest.java:130` — same pattern for `approvalsAfterPark`.
- `ChatWebSmokeTest.java:152` — same pattern for `transcriptAfterApproval`.
- `TranscriptViewTest.java:49` — `isNotEmpty()` + `containsExactly(...)` joined.

### S1186 × 2 — "Add a nested comment explaining why this method is empty..."
- `ChatWebSmokeTest.java:274` (anonymous `ModelStream.close()`) — added a one-line comment:
  "scripted stream holds no resources to release".
- `ListenerDeclarationsTest.java:79` (same anonymous `ModelStream.close()` pattern) — same fix.

### S1141 × 3 — "Extract this nested try block into a separate method"
- `JdbcMemory.java:218` (`inTransaction`) — extracted the inner
  `try { ... } catch (SQLException) { ... } catch (RuntimeException) { ... } finally { ... }`
  into a new private static `runInTransaction(Connection, SqlFunction)` method; the outer
  try-with-resources now just delegates to it, so it no longer contains a nested try.
- `JdbcConversationStore.java:389` (`inTransaction(int isolationLevel, ...)`) — same extraction
  pattern, `runInTransaction(Connection, int, SqlFunction)`.
- `ConversationLoop.java:122` (`drive`'s retry loop nested inside the `try (var _ = ...)` /
  `catch (RuntimeException)` / `finally` block) — extracted the `for` + inner
  `try/catch(StaleStateException)` loop into a new private `driveWithRetries(id, observer)`
  method; `drive` now just calls it inside the try-with-resources.

### S7467 × 3 — "Replace 'x' with an unnamed pattern"
- `JdbcMemory.java:256` — `catch (SQLException ignored)` → `catch (SQLException _)`.
- `JdbcConversationStore.java:429` — same, `ignored` → `_`.
- `ConversationLoop.java:216` (post-refactor line moved; the unused `catch (StaleStateException e)`
  in `driveOnce`'s tail-save finally block) → `catch (StaleStateException _)`.

### S1192 — "Define a constant instead of duplicating this literal 'token must not be null' 3 times"
- `JdbcConversationStore.java` — added `private static final String TOKEN_MUST_NOT_BE_NULL =
  "token must not be null";` and replaced all three `Objects.requireNonNull(token, "token must
  not be null")` call sites (`findPark`, `findParkConversation`, `consumeToken`) with the constant.

### S7475 × 2 — "Remove unused type from unnamed pattern"
- `GatedToolCallExecutor.java:283` — `event instanceof ToolProgress(var _, var _, String message)`
  → `event instanceof ToolProgress(_, _, String message)` (dropping the redundant `var` on the
  two unnamed bindings; the rule fires once per binding, hence the ×2).

### S1948 — "Make non-static 'id' transient or serializable" (StaleStateException.java:27)
Judgment call from the brief — resolved by making the field type serializable rather than
dropping it. `ConversationId` is a simple immutable record wrapping a `String` (itself
`Serializable`), so it was made `implements Serializable` (`ConversationId.java`) — additive,
non-breaking, and it preserves `StaleStateException`'s full diagnostic value (`id()`, `expected()`,
`found()` all survive serialization) rather than marking the field `transient` and losing the
conversation identity on a serialized stack trace. `expected`/`found` are primitive `long`s and
were already fine.

### S5778 × 2 — "Refactor the lambda to have only one invocation possibly throwing"
- `ConversationStoreContract.java:89` — the lambda called both `store()` and `.save(...)`; hoisted
  `store()` into a local `ConversationStore store = store();` above the assertion so the lambda
  is just `() -> store.save(secondReader, List.of())`.
- `ListMemoryTest.java:92` — the lambda called both `messages.add(...)` and `Message.user(...)`;
  hoisted the `Message.user("mutation")` construction into a local `Message mutation = ...;` above
  the assertion so the lambda is just `() -> messages.add(mutation)`.

### S119 — "Rename this generic name to match '^[A-Z][0-9]?$'" (ListenerDeclarations.java:43)
Judgment call from the brief. `SELF` is a self-type parameter used only within this one file
(`grep -rn "SELF" --include=*.java .` found no other reference anywhere in the repo — not even in
the two implementers, `HarnessBuilder` and `AgentBuilder<I>`, since a type parameter's name is
never part of a caller's contract). Renamed `SELF` → `S` throughout the interface — a single-letter
self-type parameter is the idiom used elsewhere in Java builder APInts (e.g. `Builder<S extends
Builder<S>>`), reads fine here, and is source/binary compatible for every caller.

## Skipped, with reasons (1)

### S2326 — "T is not used in the interface" (Awaited.java:27)
`Awaited<T>` is root public sealed API (`Ready<T>`/`Parked<T>`), consumed across the codebase
(`ToolInvoker`, `ToolCallExecutor`, `GatedToolCallExecutor`, `ProviderModelCallExecutor`,
`ModelCallExecutor`, `Tool`, `Approver` and its two default implementations, and both example
modules). Sonar flags this because the interface itself declares no method using `T` — only the
two nested records do; the two static factories (`ready`, `parked`) have their own captured `T`
from the argument, not the interface's.

I looked for a clean fix that adds a method using `T` on the interface without touching the sealed
grammar (no default arm anywhere, still exactly `Ready`/`Parked`). The mechanical options I
considered:
- A default method like `orElse(T fallback)` or `map(Function<T,R>)` would technically satisfy the
  rule and stay additive/non-breaking. But nothing in the design of record
  (`docs/superpowers/specs/2026-08-09-nessy-agent-harness-design-v2.md`, which documents `Awaited`
  as root API alongside `ParkToken` and the sealed grammar) calls for such convenience methods, and
  every current consumer already destructures `Awaited` via an exhaustive
  `switch`/`instanceof` pattern rather than wanting an accessor. Adding one on spec-less guesswork
  risks committing this foundational public type to an API shape nobody asked for, purely to
  satisfy a linter.

Per the brief's explicit judgment clause ("if nothing clean exists, skip-and-report"), I left this
one unfixed. A spec amendment proposing a specific `Awaited` convenience method (and its exact
semantics) would be the right way to resolve it, not a mop-up guess.

## Verification

- Offline: `./mvnw -q clean verify` — green (exit 0), full reactor build including all modules,
  no API key, no model-provider network access. The `IllegalStateException`/`ERROR`-level log
  lines visible in the console output are expected — they come from tests that deliberately throw
  from listeners/observers to prove the harness swallows and narrates them, not from failures.
- Docker-touched modules: `./mvnw -pl nessy-store-jdbc -pl nessy-examples/chat-web test
  -Dnessy.excludedGroups=live` — green, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` for the
  reactor slice shown in the final summary (plus `JdbcConversationStoreTest`, `JdbcMemoryTest`,
  `StateCodecTest` and its nested classes, all passing, visible earlier in the log) —
  `BUILD SUCCESS`.
- `./mvnw license:format -Plicense && ./mvnw spotless:apply` run before the final verify; spotless
  reformatted one Javadoc line-wrap in `ConversationLoop.java` (the newly extracted
  `driveWithRetries` method's comment), which is reflected in the committed diff.

## Files touched
- `nessy-core/src/main/java/org/jwcarman/nessy/ListenerDeclarations.java`
- `nessy-core/src/main/java/org/jwcarman/nessy/api/conversation/ConversationId.java`
- `nessy-core/src/main/java/org/jwcarman/nessy/internal/ConversationLoop.java`
- `nessy-core/src/main/java/org/jwcarman/nessy/spi/execute/GatedToolCallExecutor.java`
- `nessy-core/src/test/java/org/jwcarman/nessy/ListenerDeclarationsTest.java`
- `nessy-core/src/test/java/org/jwcarman/nessy/spi/conversation/ConversationStoreContract.java`
- `nessy-core/src/test/java/org/jwcarman/nessy/spi/memory/ListMemoryTest.java`
- `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/ChatWebSmokeTest.java`
- `nessy-examples/chat-web/src/test/java/org/jwcarman/nessy/examples/chatweb/TranscriptViewTest.java`
- `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/JdbcConversationStore.java`
- `nessy-store-jdbc/src/main/java/org/jwcarman/nessy/store/jdbc/JdbcMemory.java`

No changes to `nessy-core/src/main/java/org/jwcarman/nessy/api/Awaited.java` (skipped, see above)
or `nessy-core/src/main/java/org/jwcarman/nessy/spi/conversation/StaleStateException.java`
(resolved indirectly via `ConversationId`, so the exception class itself needed no edit).

## Deviations from the brief
None. All house rules honored: no suppressions of any kind, no star imports, S5778/S5841
conventions followed, no default arms added to any sealed switch.
