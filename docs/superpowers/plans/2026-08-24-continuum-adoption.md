# Continuum Adoption Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Nessy's hand-rolled durable-computation machinery with `org.jwcarman.continuum` 0.1.0, so Continuum owns the computation lifecycle and the outbox while Nessy keeps the reducer, the desks, and routing.

**Architecture:** Two Continuum kinds per agent type — `approval/<agentType>` and `tool/<agentType>` — each on the non-retryable `ContinuumClient`. Dispatch memory, which the deleted SHA-256 id derivation used to provide for free, moves to a Substrate index owned by `ComputationDeferredToolCallPolicy`. Delivery becomes at-least-once, which the reducer's existing ToolCallId dedup already absorbs.

**Tech Stack:** Java 25, Maven, `org.jwcarman.continuum` 0.1.0 (`continuum-core`, `continuum-memory`), `org.jwcarman.codec` 0.2.0, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-continuum-adoption-design.md`

## Global Constraints

- **Wire Continuum to `continuum-memory`, never `continuum-jdbc`.** Spec §11.1: a durable computation store against an in-memory `Substrate` silently drops every delivery. `continuum-jdbc` is gated on a durable `Substrate` existing, which it does not.
- **Build economics.** While iterating use warm scoped builds: `./mvnw -q -pl <module> -am test`. Run `./mvnw -q clean verify` ONCE per task, before that task's final commit. Never run two Maven processes concurrently in one worktree.
- **Before every commit:** `./mvnw license:format -Plicense && ./mvnw spotless:apply`
- **No star imports.** Explicit single-symbol imports everywhere, static imports included.
- **Never suppress warnings.** No `@SuppressWarnings`. Fix the underlying issue.
- **Exception-assertion lambdas** (`assertThatThrownBy`) contain exactly ONE throwing invocation; all setup goes outside the lambda (Sonar S5778).
- **Assert emptiness before any all/none-match assertion** on the same collection, so the predicate cannot pass vacuously (S5841).
- **Prose test style**, no mocking library. Nessy uses hand-written fakes; follow the existing pattern in `nessy-agent/src/test`.
- **Model policy for dispatch:** `implementer` on Sonnet; `task-reviewer` on Sonnet, except Tasks 3, 4 and 5 which touch reducer semantics and concurrency and MUST be reviewed on Opus.

---

## File Structure

**Created:**
- `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Routing.java` — the continuation payload record. Replaces the JSON `ScopeRouting` hand-builds.
- `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DispatchIndex.java` — the Substrate-backed call-to-computation index.
- `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DispatchEntry.java` — its stored value.
- `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ComputationScheduler.java` — the shared pump scheduler.

**Modified:**
- `Kinds.java` — names Continuum kinds instead of Substrate kinds.
- `ComputationApprover.java` — approval client + index write.
- `ApprovalDesk.java`, `CompletionDesk.java` — hold clients.
- `ComputationDeferredToolCallPolicy.java` — tool client + index.
- `DeliveryWorker.java` — fold half only, driven by two `deliverResults` consumers.
- `host/HarnessConfig.java`, `Harness.java` — wiring.
- `CallAddress.java` — digest produces an index key, not a `ComputationId`.

**Deleted:** `SubstrateComputations`, `OutcomeCodec`, `Outcome`, `CompletionResult`, `CreateResult`, `PendingComputation`, `ScopeRouting`, `RetrySemantics`, `DurableDecisions.toAdjudication`, `DefaultAgent.redispatch()`.

---

### Task 1: Continuum dependencies and the `Routing` continuation payload

**Files:**
- Modify: `pom.xml` (properties + dependencyManagement)
- Modify: `nessy-agent/pom.xml`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Routing.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/RoutingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record Routing(String agentType, String agentId, String responseId, ToolCall call)` and `Routing.codec(ObjectMapper)` returning `Codec<Routing>`. Tasks 3, 4 and 5 use both.

- [ ] **Step 1: Add the Continuum BOM and dependencies**

In the root `pom.xml` `<properties>`, beside `<codec.version>0.2.0</codec.version>`:

```xml
<continuum.version>0.1.0</continuum.version>
```

In `<dependencyManagement><dependencies>`, beside the `codec-core` entry:

```xml
<dependency>
  <groupId>org.jwcarman.continuum</groupId>
  <artifactId>continuum-bom</artifactId>
  <version>${continuum.version}</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

In `nessy-agent/pom.xml` `<dependencies>`:

```xml
<dependency>
  <groupId>org.jwcarman.continuum</groupId>
  <artifactId>continuum-core</artifactId>
</dependency>
<dependency>
  <groupId>org.jwcarman.continuum</groupId>
  <artifactId>continuum-memory</artifactId>
</dependency>
```

- [ ] **Step 2: Verify the dependencies resolve**

Run: `./mvnw -q -pl nessy-agent -am dependency:resolve`
Expected: success, no missing artifacts. Continuum 0.1.0 is on Maven Central.

- [ ] **Step 3: Write the failing test**

Create `nessy-agent/src/test/java/org/jwcarman/nessy/agent/RoutingTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.junit.jupiter.api.Test;

class RoutingTest {

  private final Codec<Routing> codec = Routing.codec(new ObjectMapper());

  @Test
  void aRoutingSurvivesTheRoundTrip() {
    var original =
        new Routing("assistant", "scope-1", "resp-7", new ToolCall("call-3", "lookup", "{\"q\":1}"));

    Routing decoded = codec.decode(codec.encode(original));

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void theCallArgumentsSurviveVerbatim() {
    var original =
        new Routing("assistant", "scope-1", "resp-7", new ToolCall("c", "t", "{\"deep\":{\"n\":2}}"));

    Routing decoded = codec.decode(codec.encode(original));

    assertThat(decoded.call().arguments()).isEqualTo("{\"deep\":{\"n\":2}}");
  }
}
```

NOTE: check `ToolCall`'s actual constructor shape in `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/ToolCall.java` before writing this and match it exactly — the three-arg `(id, name, arguments)` form above reflects what `ScopeRouting` writes, but confirm the argument type is `String` and not `JsonNode`.

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=RoutingTest`
Expected: compilation failure — `Routing` does not exist.

- [ ] **Step 5: Write `Routing`**

Create `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Routing.java`. It is a plain record plus a codec factory; the codec delegates to the pinned `ObjectMapper` exactly as `StateCodec` does. Copy the license header from a neighbouring file.

```java
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * Where a computation's outcome is delivered: the scope coordinates plus the originating call.
 * This is the continuation payload for both Continuum kinds, and it travels with every delivery,
 * so folding an outcome needs no lookup.
 *
 * @param agentType the agent type
 * @param agentId the scope id
 * @param responseId the model response that produced the call
 * @param call the tool call itself, arguments included
 */
public record Routing(String agentType, String agentId, String responseId, ToolCall call) {

  public Routing {
    requireText(agentType, "agentType");
    requireText(agentId, "agentId");
    requireText(responseId, "responseId");
    Objects.requireNonNull(call, "call must not be null");
  }

  /**
   * A codec over the pinned mapper.
   *
   * @param mapper the pinned mapper
   * @return the codec
   */
  public static Codec<Routing> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(Routing value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable routing", e);
        }
      }

      @Override
      public Routing decode(byte[] bytes) {
        try {
          return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), Routing.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable routing", e);
        }
      }
    };
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
```

Replace the fully-qualified `JsonProcessingException` with a proper import — it is written out above only to show which exception is meant.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=RoutingTest`
Expected: PASS, both tests.

- [ ] **Step 7: Full verification and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add pom.xml nessy-agent/pom.xml nessy-agent/src/main/java/org/jwcarman/nessy/agent/Routing.java nessy-agent/src/test/java/org/jwcarman/nessy/agent/RoutingTest.java
git commit -m "feat: continuum dependencies and the Routing continuation payload"
```

---

### Task 2: The dispatch index

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DispatchEntry.java`
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/DispatchIndex.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/CallAddress.java`
- Modify: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/Kinds.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DispatchIndexTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `CallAddress.indexKey()` returning `String` — the SHA-256 hex of the four coordinates, purpose-free.
  - `record DispatchEntry(String computationId, DispatchKind kind)` with `enum DispatchKind { APPROVAL, TOOL }`.
  - `DispatchIndex` with `Optional<DispatchEntry> find(CallAddress)`, `void record(CallAddress, DispatchEntry)`, `Substrate.Op deleteOp(CallAddress)`.
  - `Kinds.dispatchIndex(AgentType)` returning `String`.

Tasks 3, 4 and 5 use all of these.

- [ ] **Step 1: Write the failing test**

Create `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DispatchIndexTest.java`:

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jwcarman.nessy.agent.DispatchEntry.DispatchKind;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.junit.jupiter.api.Test;

class DispatchIndexTest {

  private final Substrate substrate = new InMemorySubstrate();
  private final DispatchIndex index =
      new DispatchIndex(substrate, new ObjectMapper(), "dispatch/assistant");

  private final CallAddress address = new CallAddress("assistant", "scope-1", "resp-7", "call-3");

  @Test
  void anUnknownCallHasNoEntry() {
    assertThat(index.find(address)).isEmpty();
  }

  @Test
  void aRecordedCallIsFoundAgain() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    assertThat(index.find(address))
        .contains(new DispatchEntry("comp-1", DispatchKind.APPROVAL));
  }

  @Test
  void recordingAgainReplacesTheEntry() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));
    index.record(address, new DispatchEntry("comp-2", DispatchKind.TOOL));

    assertThat(index.find(address)).contains(new DispatchEntry("comp-2", DispatchKind.TOOL));
  }

  @Test
  void aDifferentCallHasItsOwnEntry() {
    var other = new CallAddress("assistant", "scope-1", "resp-7", "call-4");
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    assertThat(index.find(other)).isEmpty();
  }

  @Test
  void theDeleteOpRemovesTheEntry() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    substrate.batch(List.of(index.deleteOp(address)));

    assertThat(index.find(address)).isEmpty();
  }

  @Test
  void theDeleteOpForAnAbsentEntryIsHarmless() {
    substrate.batch(List.of(index.deleteOp(address)));

    assertThat(index.find(address)).isEmpty();
  }
}
```

The last case matters: the fold always contributes a delete op, including for calls that never went durable, so an absent entry must not fail the batch. If `Substrate.DeleteDocument` at version 0 on an absent key throws `ConflictException`, `deleteOp` must return an op the batch tolerates — read the current version and skip contributing an op when absent. Adjust `deleteOp` to return `Optional<Substrate.Op>` if that is what the substrate contract requires, and update this test and every call site in Tasks 4 and 5 to match.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=DispatchIndexTest`
Expected: compilation failure — `DispatchIndex`, `DispatchEntry` do not exist.

- [ ] **Step 3: Add the index key to `CallAddress`**

In `CallAddress.java`, delete `approval()` and `execution()` and the `PURPOSE_APPROVAL`/`PURPOSE_EXECUTION` constants. Replace with a single purpose-free key. Keep `newDigest()` and `updateLengthPrefixed` exactly as they are, and drop the `ComputationId` import.

```java
  /**
   * This call's key in the dispatch index — a stable digest of the four coordinates. Purpose-free:
   * one call has one entry, whichever kind it is currently in flight under.
   *
   * @return the index key
   */
  public String indexKey() {
    MessageDigest digest = newDigest();
    updateLengthPrefixed(digest, agentType);
    updateLengthPrefixed(digest, agentId);
    updateLengthPrefixed(digest, responseId);
    updateLengthPrefixed(digest, callId);
    return HexFormat.of().formatHex(digest.digest());
  }
```

- [ ] **Step 4: Add the kind namer**

In `Kinds.java`, add alongside the existing namers:

```java
  static String dispatchIndex(AgentType type) {
    return "dispatch/" + type.name();
  }
```

- [ ] **Step 5: Write `DispatchEntry`**

```java
package org.jwcarman.nessy.agent;

import java.util.Objects;

/**
 * The computation a call is currently in flight under.
 *
 * @param computationId the Continuum computation id, as its opaque string value
 * @param kind which client owns it
 */
public record DispatchEntry(String computationId, DispatchKind kind) {

  public DispatchEntry {
    Objects.requireNonNull(computationId, "computationId must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
  }

  /** Which Continuum client a dispatch entry belongs to. */
  public enum DispatchKind {
    /** An approval computation, awaiting a human decision. */
    APPROVAL,
    /** A tool computation, awaiting an external result. */
    TOOL
  }
}
```

- [ ] **Step 6: Write `DispatchIndex`**

Model it on `SubstrateComputations`'s use of `store.document(kind, codec)` — read that file first for the house pattern. It holds a `DocumentStore<DispatchEntry>` and does read-then-CAS-write with a retry on `ConflictException`, exactly as the existing code does.

```java
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jwcarman.nessy.spi.substrate.ConflictException;
import org.jwcarman.nessy.spi.substrate.DocumentStore;
import org.jwcarman.nessy.spi.substrate.Substrate;
import org.jwcarman.nessy.spi.substrate.Versioned;

/**
 * Dispatch memory: which computation a call is currently in flight under. This is what the
 * deleted id derivation used to provide for free — the gate absorbs a staleness redrive by
 * reading this rather than by recomputing an id Continuum no longer lets it choose.
 */
public final class DispatchIndex {

  private final DocumentStore<DispatchEntry> entries;

  public DispatchIndex(Substrate store, ObjectMapper mapper, String kind) {
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(mapper, "mapper must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    this.entries = store.document(kind, codec(mapper));
  }

  public Optional<DispatchEntry> find(CallAddress address) {
    Objects.requireNonNull(address, "address must not be null");
    return entries.read(address.indexKey()).map(Versioned::value);
  }

  /**
   * Records the computation this call is now in flight under, replacing any earlier entry — a
   * granted approval whose tool then defers writes a second computation under the same key.
   */
  public void record(CallAddress address, DispatchEntry entry) {
    Objects.requireNonNull(address, "address must not be null");
    Objects.requireNonNull(entry, "entry must not be null");
    String key = address.indexKey();
    while (true) {
      long expected = entries.version(key).orElse(0L);
      try {
        entries.write(key, entry, expected);
        return;
      } catch (ConflictException _) {
        // another writer moved this call along; re-read and re-apply
      }
    }
  }

  /** An op deleting this call's entry, for the fold batch; empty when there is nothing to delete. */
  public Optional<Substrate.Op> deleteOp(CallAddress address) {
    Objects.requireNonNull(address, "address must not be null");
    OptionalLong version = entries.version(address.indexKey());
    return version.isPresent()
        ? Optional.of(entries.deleteOp(address.indexKey(), version.getAsLong()))
        : Optional.empty();
  }

  private static org.jwcarman.codec.spi.Codec<DispatchEntry> codec(ObjectMapper mapper) {
    // Same shape as Routing.codec — extract a shared helper if a third one appears.
    throw new UnsupportedOperationException("write this mirroring Routing.codec");
  }
}
```

Write the `codec` method for real, mirroring `Routing.codec`. Do not leave the throw. `deleteOp` returns `Optional` — update `DispatchIndexTest`'s last two cases to `index.deleteOp(address).ifPresent(op -> substrate.batch(List.of(op)))`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=DispatchIndexTest`
Expected: PASS, all six.

- [ ] **Step 8: Fix the compilation fallout from removing `approval()`/`execution()`**

`ComputationDeferredToolCallPolicy` and `RegistryToolCallExecutor` reference them. Tasks 3 and 4 rewrite both properly; for now make the module compile by having `pendingComputation` return `Optional.empty()` and `onDeferred` use a locally-derived placeholder id, with a `// Task 4 replaces this` comment. Existing absorption tests will fail — that is expected and is what Task 3 and Task 4 restore.

Run: `./mvnw -q -pl nessy-agent -am test-compile`
Expected: compiles.

- [ ] **Step 9: Full verification and commit**

Because Step 8 knowingly breaks absorption tests, this task's `clean verify` will fail on them. Record which tests fail in the commit body so the next task can confirm it fixed exactly those.

```bash
./mvnw -q clean verify -DskipTests
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent/src/main/java/org/jwcarman/nessy/agent/ nessy-agent/src/test/java/org/jwcarman/nessy/agent/DispatchIndexTest.java
git commit -m "feat: the dispatch index — call coordinates key a computation, not derive one"
```

---

### Task 3: The approval kind onto Continuum

**REVIEW ON OPUS** — this task changes when a human is notified and how a grant reaches the tool.

**Files:**
- Modify: `ComputationApprover.java`, `ApprovalDesk.java`, `Kinds.java`, `host/HarnessConfig.java`, `Harness.java`, `DeliveryWorker.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/ApprovalOnContinuumTest.java`

**Interfaces:**
- Consumes: `Routing`, `Routing.codec` (Task 1); `DispatchIndex`, `DispatchEntry`, `CallAddress.indexKey` (Task 2).
- Produces: `Kinds.approval(AgentType)` now returning a Continuum kind string; a `ContinuumClient<Decision, Routing>` constructed in `HarnessConfig.finish()` and threaded to `ComputationApprover`, `ApprovalDesk` and `DeliveryWorker`.

- [ ] **Step 1: Write the failing test**

Cover the three behaviours that must survive: an ask creates one computation and notifies once, a redrive does not re-notify, and an approval reaches the tool.

```java
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

// imports omitted — mirror the existing AbsorptionTest fixture setup

class ApprovalOnContinuumTest {

  @Test
  void askingCreatesOneComputationAndNotifiesOnce() { /* ... */ }

  @Test
  void aRedriveWhileTheAskIsPendingDoesNotNotifyAgain() { /* ... */ }

  @Test
  void approvingRunsTheToolExactlyOnce() { /* ... */ }

  @Test
  void denyingFoldsAFailureWithoutRunningTheTool() { /* ... */ }

  @Test
  void anExpiredApprovalFoldsAFailure() { /* ... */ }
}
```

Read `nessy-agent/src/test/java/org/jwcarman/nessy/agent/AbsorptionTest.java` first and reuse its harness fixture wholesale — it already builds a harness with a counting approver notifier and a counting tool. Write each body against that fixture; do not invent a new one.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=ApprovalOnContinuumTest`
Expected: FAIL.

- [ ] **Step 3: Mint the approval client in `HarnessConfig.finish()`**

Replace the `effectiveApprovalBackend` construction. The repository is in-memory per the Global Constraints.

```java
    var continuum =
        new DefaultContinuum(new InMemoryContinuumRepository(), InstantSource.system());
    ContinuumClient<Decision, Routing> approvalClient =
        continuum.client(
            Kinds.approval(agentType),
            Decision.class,
            Routing.class,
            cfg -> cfg.continuationCodec(Routing.codec(pinned)).deadline(APPROVAL_DEADLINE));
```

with `private static final Duration APPROVAL_DEADLINE = Duration.ofDays(7);` on `HarnessConfig` (spec §7, ruled). Supply a result codec for `Decision` the same way — check whether `cfg.codecs(CodecFactory)` can resolve it from the pinned mapper before writing an explicit one.

- [ ] **Step 4: Rewrite `ComputationApprover.adjudicate`**

The `created.created()` notify guard is gone — creation always succeeds now — so the index is what prevents a re-notify, and `gate` consults it before `adjudicate` is ever reached. Write the index entry immediately after create, in that order (spec §5: create-then-index, never the reverse).

```java
  @Override
  public Adjudication adjudicate(ApprovalRequest request) {
    String responseId = committedResponseId();
    var address =
        new CallAddress(
            request.agentType(), request.agentId(), responseId, request.call().id());
    var routing =
        new Routing(request.agentType(), request.agentId(), responseId, request.call());
    Computation created = client.create(routing);
    index.record(address, new DispatchEntry(created.id().value().toString(), DispatchKind.APPROVAL));
    notifier.accept(request);
    return new Adjudication.Suspended(ComputationId.of(created.id().value().toString()));
  }
```

`ApprovalRequest.id()` is no longer a caller-derived id — check whether the record still needs that component and remove it if nothing reads it.

- [ ] **Step 5: Rewrite `ApprovalDesk`**

```java
  public void approve(ComputationId id) {
    decide(id, Decision.allow());
  }

  public void deny(ComputationId id, String reason) {
    decide(id, new Decision.Deny(reason));
  }

  private void decide(ComputationId id, Decision decision) {
    client.complete(continuumId(id), decision);
    nudge.run();
  }
```

`continuumId` converts Nessy's string-valued `ComputationId` to Continuum's `UUID`-valued one. Put that conversion in one place — a package-private helper — and use it everywhere; do not scatter `UUID.fromString` calls.

- [ ] **Step 6: Add the approval delivery consumer to `DeliveryWorker`**

A new method the scheduler and `nudge()` call. The stale-grant guard is load-bearing (spec §5, §11.3): a grant whose computation is not the one the index names for that call is an orphan and must not run the tool.

```java
  int drainApprovals(BatchSize batchSize) {
    return approvalClient.deliverResults(
        batchSize,
        APPROVAL_LEASE,
        APPROVAL_BACKOFF,
        (routing, outcome) -> {
          var address =
              new CallAddress(
                  routing.agentType(), routing.agentId(), routing.responseId(), routing.call().id());
          switch (outcome) {
            case TypedOutcome.Success<Decision>(Decision.Allow _) -> deliverGrant(address, routing);
            case TypedOutcome.Success<Decision>(Decision.Deny(String reason)) ->
                foldFailure(address, routing, reason);
            case TypedOutcome.Failure<Decision>(String message) ->
                foldFailure(address, routing, message);
            case TypedOutcome.Expired<Decision>(var kind, var message) ->
                foldFailure(address, routing, kind + ": " + message);
          }
        });
  }
```

`deliverGrant` first checks the guard and returns without running when it does not hold:

```java
    Optional<DispatchEntry> entry = index.find(address);
    if (entry.isEmpty() || !entry.get().computationId().equals(thisComputationId)) {
      return; // a stale grant from an orphaned approval — acknowledge, do not run
    }
```

`thisComputationId` is not on `Routing`. Continuum's `CompletionDelivery` carries `computationId`, but `deliverResults` hands the consumer only `(C, TypedOutcome<R>)`. **Resolve this before implementing:** either widen the consumer's inputs by using the repository's `claimDeliveries` directly, or accept the weaker guard "an entry exists and its kind is APPROVAL." Prefer the weaker guard if the id is genuinely unavailable, and record the limitation in the spec's §11.3 — the weaker form still stops a redrive-created duplicate from running a second time once the first has folded and deleted the entry.

`APPROVAL_LEASE` is `Lease.ofMinutes(2)` and `APPROVAL_BACKOFF` is `Backoff.ofSeconds(30)` — spec §11.2, sized for approval-gated inline tools.

- [ ] **Step 7: Run the tests**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=ApprovalOnContinuumTest`
Expected: PASS.

- [ ] **Step 8: Full verification and commit**

Tool-path tests still fail — Task 4 restores them. Note that in the commit body.

```bash
./mvnw -q clean verify -DskipTests
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent/src
git commit -m "feat: approvals move to a continuum kind of their own"
```

---

### Task 4: The tool kind onto Continuum

**REVIEW ON OPUS** — this task changes the deferred-tool lifecycle and the gate's absorption.

**Files:**
- Modify: `ComputationDeferredToolCallPolicy.java`, `CompletionDesk.java`, `DeliveryWorker.java`, `host/HarnessConfig.java`, `spi/DeferredToolCallPolicy.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/DeferredToolOnContinuumTest.java`; repoint `AbsorptionTest`, `GrantDeliveryPendingWindowTest`

**Interfaces:**
- Consumes: everything from Tasks 1-3.
- Produces: `ContinuumClient<ToolResult, Routing>` threaded to the policy, `CompletionDesk`, and `DeliveryWorker.drainTools(BatchSize)`.

- [ ] **Step 1: Write the failing test**

```java
class DeferredToolOnContinuumTest {

  @Test
  void aDeferredToolCreatesOneComputationAndRecordsIt() { /* ... */ }

  @Test
  void aRedriveWhileTheToolIsPendingDoesNotDispatchAgain() { /* ... */ }

  @Test
  void completingTheComputationFoldsTheResult() { /* ... */ }

  @Test
  void aRedeliveredCompletionIsIgnored() { /* ... */ }

  @Test
  void anExpiredToolComputationFoldsAFailure() { /* ... */ }

  @Test
  void theIndexEntryIsGoneAfterTheFold() { /* ... */ }
}
```

`aRedeliveredCompletionIsIgnored` is the spec §4 claim under test — fold a result, deliver the same outcome again, assert the transition was ignored and no second remembrance was written. Read `ToolFoldRemembrance` to see how to observe that.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=DeferredToolOnContinuumTest`
Expected: FAIL.

- [ ] **Step 3: Mint the tool client**

In `HarnessConfig.finish()`, beside the approval client from Task 3, on the same `DefaultContinuum`:

```java
    ContinuumClient<ToolResult, Routing> toolClient =
        continuum.client(
            Kinds.tool(agentType),
            ToolResult.class,
            Routing.class,
            cfg -> cfg.continuationCodec(Routing.codec(pinned)).deadline(DEFAULT_TOOL_DEADLINE));
```

Rename `Kinds.computation` to `Kinds.tool` and have it return `"tool/" + type.name()`.

- [ ] **Step 4: Rewrite `onDeferred` and `pendingComputation`**

`onDeferred` loses `retrySemantics` and `alsoCommit` — retryability is not implemented (spec §3), and there is no longer an outbox delete op to compose, because the grant delivery is Continuum's and is acknowledged by its own consumer returning.

```java
  @Override
  public ToolExecution onDeferred(
      ToolCall call, CallAddress address, Optional<Duration> timeout) {
    var routing =
        new Routing(address.agentType(), address.agentId(), address.responseId(), call);
    Computation created =
        timeout.map(t -> client.create(routing, t)).orElseGet(() -> client.create(routing));
    index.record(address, new DispatchEntry(created.id().value().toString(), DispatchKind.TOOL));
    return new ToolExecution.Deferred(ComputationId.of(created.id().value().toString()));
  }

  @Override
  public Optional<ComputationId> pendingComputation(CallAddress address) {
    return index.find(address).map(entry -> ComputationId.of(entry.computationId()));
  }
```

Update `DeferredToolCallPolicy`'s interface signature to match, and update `RegistryToolCallExecutor.run`'s `Awaited.Deferred` arm — it no longer passes `tool.retrySemantics()` or `alsoCommit`.

- [ ] **Step 5: Rewrite `CompletionDesk`**

```java
  public void complete(ComputationId id, ToolResult result) {
    client.complete(continuumId(id), result);
    nudge.run();
  }

  public void fail(ComputationId id, String reason) {
    client.fail(continuumId(id), reason);
    nudge.run();
  }
```

- [ ] **Step 6: Add the tool delivery consumer and join the index delete to the fold batch**

```java
  int drainTools(BatchSize batchSize) {
    return toolClient.deliverResults(
        batchSize,
        TOOL_LEASE,
        TOOL_BACKOFF,
        (routing, outcome) -> foldOutcome(routing, toToolOutcome(outcome)));
  }
```

`TOOL_LEASE` is `Lease.ofSeconds(30)` — this consumer only folds (spec §11.2).

In `foldOps`, replace the outbox delete with the index delete:

```java
    index.deleteOp(address).ifPresent(ops::add);
```

- [ ] **Step 7: Repoint the absorption tests**

`AbsorptionTest` and `GrantDeliveryPendingWindowTest` drive `DefaultAgent.redispatch()`, which Task 6 deletes. Change both to drive `drive()` with a `StalenessPolicy` that always reports stale, so they exercise the real §6.1 arm. Keep every assertion.

- [ ] **Step 8: Run the full agent suite**

Run: `./mvnw -q -pl nessy-agent -am test`
Expected: PASS — including the tests Task 2 and Task 3 knowingly left failing.

- [ ] **Step 9: Full verification and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent/src
git commit -m "feat: deferred tool calls move to a continuum kind of their own"
```

---

### Task 5: The shared pump scheduler

**REVIEW ON OPUS** — concurrency.

**Files:**
- Create: `nessy-agent/src/main/java/org/jwcarman/nessy/agent/ComputationScheduler.java`
- Modify: `DeliveryWorker.java` (delete the heartbeat), `Harness.java`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/ComputationSchedulerTest.java`

**Interfaces:**
- Consumes: `DeliveryWorker.drainApprovals(BatchSize)`, `drainTools(BatchSize)` (Tasks 3, 4).
- Produces: `ComputationScheduler` with `void register(DeliveryWorker<?>)` and `void close()`.

- [ ] **Step 1: Write the failing test**

Assert the six tasks are scheduled per registered worker, that fixed-delay is used (a slow pump does not stack), and that `close()` stops them. Drive it with an injected `ScheduledExecutorService` fake rather than real sleeps — read the existing tests for the house style on time control (`MutableInstantSource` is the pattern for clocks; do the equivalent for scheduling).

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=ComputationSchedulerTest`
Expected: FAIL.

- [ ] **Step 3: Write `ComputationScheduler`**

One shared pool for all agent types (spec §7, ruled). Platform threads, not virtual — this supersedes the earlier virtual-thread lean and sidesteps JDBC pinning.

```java
public final class ComputationScheduler implements AutoCloseable {

  private static final BatchSize DELIVER_BATCH = BatchSize.of(25);
  private static final BatchSize EXPIRE_BATCH = BatchSize.of(12);
  private static final BatchSize PURGE_BATCH = BatchSize.of(200);
  private static final ResultTtl RESULT_TTL = ResultTtl.ofHours(1);

  private final ScheduledExecutorService scheduler;

  public void register(DeliveryWorker<?> worker) {
    schedule(() -> worker.drainApprovals(DELIVER_BATCH), Duration.ofSeconds(1));
    schedule(() -> worker.drainTools(DELIVER_BATCH), Duration.ofSeconds(1));
    schedule(() -> worker.expireApprovals(EXPIRE_BATCH), Duration.ofMinutes(1));
    schedule(() -> worker.expireTools(EXPIRE_BATCH), Duration.ofSeconds(15));
    schedule(() -> worker.purgeApprovals(PURGE_BATCH, RESULT_TTL), Duration.ofMinutes(10));
    schedule(() -> worker.purgeTools(PURGE_BATCH, RESULT_TTL), Duration.ofMinutes(10));
  }
```

`schedule` uses `scheduleWithFixedDelay` and wraps each runnable so a thrown exception is logged rather than cancelling the schedule — `ScheduledExecutorService` silently stops a task that throws, which would disable a pump forever.

Add the four expiry/purge methods to `DeliveryWorker`, each delegating to the matching client's `failExpiredComputations` / `purgeExpiredResults`.

- [ ] **Step 4: Delete the heartbeat**

Remove `heartbeat`, `DEFAULT_POLL_INTERVAL`, `safeDrainOnce`, `safeReapOnce`, and the thread start in `Harness.of(...)`. Construct and register a `ComputationScheduler` there instead.

- [ ] **Step 5: Make `nudge()` submit rather than run inline**

`nudge()` submits one `drainApprovals` + `drainTools` pass to the scheduler, so `ApprovalDesk.approve()` returns immediately rather than blocking for as long as a granted inline tool takes (spec §7).

- [ ] **Step 6: Run the full suite**

Run: `./mvnw -q -pl nessy-agent -am test`
Expected: PASS. Watch for tests that relied on `nudge()` being synchronous — they must now await the fold rather than assume it. Fix them by awaiting, not by reverting the submit.

- [ ] **Step 7: Full verification and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-agent/src
git commit -m "feat: one scheduled executor drives every pump"
```

---

### Task 6: Delete what Continuum replaced

**Files:** Delete `SubstrateComputations.java`, `OutcomeCodec.java`, `Outcome.java`, `CompletionResult.java`, `CreateResult.java`, `PendingComputation.java`, `ScopeRouting.java`, `nessy-api/.../RetrySemantics.java`; modify `DurableDecisions.java`, `DefaultAgent.java`, `ToolConfig.java`, `Tool.java`, `ConfiguredTool.java`.

- [ ] **Step 1: Delete the dead types**

```bash
git rm nessy-agent/src/main/java/org/jwcarman/nessy/agent/SubstrateComputations.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/OutcomeCodec.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/Outcome.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/CompletionResult.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/CreateResult.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/PendingComputation.java \
       nessy-agent/src/main/java/org/jwcarman/nessy/agent/ScopeRouting.java \
       nessy-api/src/main/java/org/jwcarman/nessy/api/tool/RetrySemantics.java
```

Plus their tests: `SubstrateComputationsTest`, `OutcomeCodecTest`, and any `ScopeRouting` test.

- [ ] **Step 2: Remove `RetrySemantics` from the tool API**

Delete `ToolConfig.retrySemantics(...)` and its field, `Tool#retrySemantics()`, and the `ConfiguredTool` component. Delete the one test that sets `RETRYABLE` — `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/ToolOfTest.java` around line 299.

- [ ] **Step 3: Remove `DurableDecisions.toAdjudication` and `DefaultAgent.redispatch()`**

Both are dead: `toAdjudication` has only test callers, `redispatch()` has none in production. Delete their tests too — `DefaultAgentRedispatchTest` goes entirely.

- [ ] **Step 4: Compile and run everything**

Run: `./mvnw -q clean verify`
Expected: PASS. Any remaining reference is a real leftover — fix it rather than restoring the deleted type.

- [ ] **Step 5: Commit**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "refactor: the hand-rolled computation machinery retires"
```

---

### Task 7: Guards and documentation

**Files:**
- Modify: `host/HarnessConfig.java`, `DeliveryWorker.java`
- Modify: `docs/concepts/storage.md`, `docs/concepts/durable-computation.md`, `docs/concepts/tools.md`
- Test: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/DurabilityMismatchWarningTest.java`

- [ ] **Step 1: Write the failing test**

Assert `finish()` logs a warning when exactly one of the two stores is in-memory, and does not when both are. Capture the log through the existing test approach — grep the suite for how other tests assert on `slf4j` output before inventing one.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl nessy-agent -am test -Dtest=DurabilityMismatchWarningTest`
Expected: FAIL.

- [ ] **Step 3: Add the startup check**

In `finish()`, after both stores exist (spec §11.1):

```java
    boolean substrateVolatile = effectiveSubstrate instanceof InMemorySubstrate;
    boolean computationsVolatile = repository instanceof InMemoryContinuumRepository;
    if (substrateVolatile != computationsVolatile) {
      log.warn(
          "Durability mismatch: the substrate is {} and the computation store is {}. "
              + "These must match — a durable computation store against a volatile substrate "
              + "silently drops every delivery, and the reverse hangs calls permanently.",
          substrateVolatile ? "in-memory" : "durable",
          computationsVolatile ? "in-memory" : "durable");
    }
```

- [ ] **Step 4: Log the moment of loss**

In `DeliveryWorker`, where `readState` falls back to `State.initial()`, log at warn when a delivery folds against a scope with no stored state. Today that is indistinguishable from an ordinary duplicate-delivery ignore, which is exactly why the §11.1 failure would be silent. Restructure `readState` to return `Optional<State>` so the caller can tell the two apart.

- [ ] **Step 5: Log a failed index write**

Spec §11.5: if `DispatchIndex.record` fails while Continuum is healthy, every
redrive creates another approval computation and re-notifies the human,
bounded only by redrive frequency. `record` currently retries `ConflictException`
forever; wrap the call sites in `ComputationApprover.adjudicate` and
`ComputationDeferredToolCallPolicy.onDeferred` so any other `RuntimeException`
is logged at error naming the call, then rethrown. Silent is the one thing it
must not be.

- [ ] **Step 6: Write the docs warning**

In `docs/concepts/storage.md` and `docs/concepts/durable-computation.md`, add the durability-matching rule as an admonition, covering both directions and the symptom a reader observes: a tool result that never arrives, or a call that never completes. `storage.md` currently says to "supply a durable implementation through `.substrate(Substrate)` to persist" — that sentence must now name its partner.

In `docs/concepts/tools.md`, remove every mention of `RetrySemantics` and retryable tools.

Dispatch this step to the `docs-writer` agent with the brand guide; do not write it inline.

- [ ] **Step 7: Full verification and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "docs: durability must match, and the retryable tool vocabulary retires"
```

---

## Open questions the implementer must resolve

1. **Task 3 Step 6:** whether `deliverResults`'s consumer can see the delivery's `computationId`. If not, the stale-grant guard weakens to "an entry exists and its kind is APPROVAL" — decide, implement, and amend spec §11.3 to match what shipped.
2. **Task 1 Step 3:** `ToolCall`'s real component types.
3. **Task 2 Step 6:** whether `DocumentStore.deleteOp` at an absent key conflicts, which decides `deleteOp`'s return type.

---

### Task 8: Upgrade to Continuum 0.3.0, and close the stale-grant guard

Added mid-execution and revised as upstream moved. The migration lands on 0.1.0;
this task takes 0.3.0 in one reviewable step against a green suite.

0.3.0 carries two things this plan treated as unreachable: `TypedDelivery`
exposes `computationId()`, which makes the **strong** stale-grant guard
writable, and both `continuum-bom` and `codec-bom` are now parentless, which
retires Task 1's workaround.

**Files:** `pom.xml`, `DeliveryWorker`, `ApprovalOnContinuumTest`, spec §11.3.

- [ ] **Step 1: Confirm 0.3.0 resolves**

Run: `./mvnw -q dependency:get -Dartifact=org.jwcarman.continuum:continuum-core:0.3.0`
Expected: success. If it 404s, Central sync is still in flight — stop and report
rather than proceeding against a version Central does not serve.

- [ ] **Step 2: Bump both versions**

```xml
<continuum.version>0.3.0</continuum.version>
<codec.version>0.4.0</codec.version>
```

**Why both, stated correctly.** An earlier draft of this task claimed that
pinning an older codec would resolve clean, compile clean, and fail at runtime
with a `NoSuchMethodError`. **That claim was false and is retracted.** Codec
0.2.0 and 0.3.0 have byte-for-byte identical public signatures — verified by
unpacking both jars and diffing `javap -public` across every class, 48 signature
lines each, no difference. There is no signature to be missing, so no
`NoSuchMethodError` is available to happen.

The real consequence of pinning an older codec is narrower: codec 0.2.0 declares
`spring-boot-autoconfigure` at compile scope, so keeping it reinstates a Spring
transitive that later codecs removed. A dependency-tree regression, not a runtime
failure. Bump both because the tree should be clean, not because one breaks
without the other.

- [ ] **Step 3: Drop both BOM workarounds**

Remove the `org.junit:junit-bom` import Task 1 added ahead of `continuum-bom`,
and its explanatory comment. Both BOMs are parentless as of continuum 0.2.0 and
codec 0.4.0, so neither leaks build pins. Check whether Nessy carries any
equivalent workaround for `codec-bom` and remove that too.

- [ ] **Step 4: Migrate `deliverResults` to the envelope**

0.3.0 replaces the two-argument consumer with a single `TypedDelivery`. The
two-argument overload is **gone, not deprecated** — keeping both made overloaded
method references ambiguous, since a reference like `seen::add` is potentially
applicable to both arities.

```java
// before
client.deliverResults(batch, lease, backoff, (continuation, outcome) -> ...);
// after
client.deliverResults(batch, lease, backoff, delivery -> ...);
```

`continuation` becomes `delivery.continuation()`, `outcome` becomes
`delivery.outcome()`. Where the body switches on the outcome, bind it first —
`switch (delivery.outcome())` — because a lambda body can no longer be a bare
switch expression over the parameter. Two call sites: `drainApprovals` and
`drainTools`.

- [ ] **Step 5: Upgrade the guard to the strong form**

This is the point of the task. `DeliveryWorker.deliverApprovalGrant` currently
admits a grant iff the call's dispatch entry exists and names `APPROVAL` — a
predicate on the *address*, which discriminates finished calls from unfinished
ones rather than real approvals from orphans. Replace it with an identity check
against `delivery.computationId()`, and **apply the same check to the failure
arm** (`foldApprovalFailure`), which is currently unguarded.

Both arms need it. Spec §11.3 gap 3 is that an orphaned approval's expiry folds a
failure over the live call, deletes the index entry, and causes the real
approval's grant to be swallowed afterwards — the human's decision discarded and
a timeout the model never suffered folded in its place.

**A third site, found in Task 4's review:** `DeliveryWorker.foldToolOutcome`
deletes the dispatch entry unguarded, without checking the entry names *this*
computation. Same shape as the approval arm's weakness — a stale redelivery can
remove a live entry and reopen the absorption door. Identity-check it too. Note
that gap 2 is already closed (Task 4's `onDeferred` overwrites the entry to
`TOOL` on every deferral), so this step closes gaps 1 and 3 plus the tool arm,
not all three gaps.

The contract this relies on is now explicit in Continuum's own javadoc:
**returning acknowledges the delivery, throwing releases it** with the call-site
backoff and an incremented `deliveryAttempt()`. Returning without acting is the
supported way to consume a delivery you have judged stale.

- [ ] **Step 6: Prove the guard with a test**

Extend `ApprovalOnContinuumTest.aStaleGrantDoesNotRunTheTool` so it fails against
the weak guard and passes against the strong one. The weak guard survives the
existing ordering because the real approval folds and deletes the entry first;
the discriminating case is **two live approvals for one call, the orphan
completing first**. Add a failure-arm case too: an orphan expiring while the real
approval is live must not fold a failure over the live call.

- [ ] **Step 7: Amend spec §11.3 to the closed state**

§11.3 documents three gaps as known and open. Steps 5 and 6 close them. Rewrite
it to describe the guard as shipped — identity-checked on both arms — keeping one
sentence of history noting the weak form existed while 0.1.0's typed consumer
withheld the computation id. Do not leave open-hole text standing beside a closed
hole.

- [ ] **Step 8: Verify and commit**

```bash
./mvnw -q -pl nessy-agent dependency:tree -Dverbose > /tmp/tree.txt 2>&1
```

Read it for three things: the JUnit artifacts agree with the workaround gone;
`codec-core` resolves to 0.4.0; and `spring-boot-autoconfigure` no longer arrives
by way of `continuum-core`.

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add -A
git commit -m "build: continuum 0.3.0, and the stale-grant guard closes"
```
