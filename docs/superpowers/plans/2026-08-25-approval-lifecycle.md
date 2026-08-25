# Approval Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every tool call is approved before it runs; each call's lifecycle — `Pending`, `AwaitingApproval`, `Running`, `AwaitingResult`, `Finished` — folds into the scope's phase; a grant carries one `Approver`, a `Memory`-like facade that answers now or defers through `ApprovalContext.defer()`; the lease pays for a message and never for work; `DispatchIndex`, three decision vocabularies, the two-step assemble/decide pipeline and the `approvalNotifier` retire.

**Architecture:** `nessy-api` gains the vocabulary (`Approval`, `ApprovalOutcome`, `Approver`, `ApprovalContext`, `ApprovalRequest` + `Facts`, `Approvers`, `Rule`). `nessy-agent`'s reducer gains per-call statuses and two new events; `ExecuteTool` splits into `SeekApproval` and `RunTool`; `RegistryToolCallExecutor` becomes two doors with no conditional inside; `DeliveryWorker`'s consumers only fold; `ApprovalDesk` gains coordinates, principal, withdraw. Continuum's approval kind carries `Approval` results and an `ApprovalRouting(routing, request)` continuation.

**Tech Stack:** Java 25, Maven reactor, Jackson 2 (the pinned mapper), Continuum 0.4.0, JUnit 6 + AssertJ, hand-written fakes only.

**Spec:** `docs/superpowers/specs/2026-08-25-approval-lifecycle-design.md` — the binding authority. Read §1 (vocabulary), §2–§4 (phase, events, executor) and §6 (recovery) before any task.

## Global Constraints

- **Never suppress warnings.** No `@SuppressWarnings` of any kind.
- **No star imports.** No `module-info.java`.
- **Sealed-grammar etiquette:** every `switch` over a sealed type is exhaustive with **no `default` arm**.
- **No mocking library.** Fakes are hand-written, in test sources.
- **S5778:** exception-assertion lambdas hold exactly one throwing invocation; setup outside. **S5841:** assert non-emptiness before any all/none-match predicate.
- **The reducer is pure.** Nothing in `Phase`, `CallStatus`, `Transition` does I/O, reads a clock, or mints an id.
- **Exact names** (spec §1–§3): `Approval {Approved(Optional<String> reference), Denied(String reason, Optional<String> reference)}`; `ApprovalOutcome {Answered(Approval), Deferred(ComputationId)}`; `Approver.approve(ApprovalContext)`; `ApprovalContext.request()`, `defer()`; `ApprovalRequest(agentType, agentId, call, action, facts)`; `Facts`; `CallStatus {Pending, AwaitingApproval(ComputationId approval, ApprovalRequest request), Running, AwaitingResult(ComputationId tool), Finished(ToolResultBlock result)}`; events `ApprovalDeferred(call, approval, request)`, `ApprovalAnswered(call, Optional<ComputationId> approval, Approval answer)`, `ToolDeferred(call, tool)`, `ToolFinished(call, Optional<ComputationId> tool, outcome)`; effects `SeekApproval(call)`, `RunTool(call)`; executor doors `seekApproval(call, responseId, sink)`, `runTool(call, responseId, sink)`.
- **Wire discriminators:** `Phase` keeps `idle` / `awaiting-model` / `awaiting-tools`; `CallStatus` uses `pending` / `awaiting-approval` / `running` / `awaiting-result` / `finished`. Maps and sets serialize in sorted order.
- **No deadline concept.** The approval kind keeps `HarnessConfig.APPROVAL_DEADLINE` (7 days); `defer()` takes no argument.
- **Test naming** follows each file's existing voice (camelCase in `nessy-agent`'s reducer tests, snake_case in `host/`); a new file matches its nearest sibling.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`. One Maven process at a time; warm scoped builds while iterating; `./mvnw -q clean verify` **once** per task before its last commit.
- Task 1 is additive and leaves everything green. Task 2 is the cut-over and is the only task whose intermediate states are red; it ends green. Never dispatch Task 3 before Task 2's reactor is green.

---

## File map

| Task | Creates | Modifies | Deletes |
|---|---|---|---|
| 1 | `nessy-api/.../tool/approval/{Approval, ApprovalOutcome, Approver, ApprovalContext, ApprovalRequest, Facts, Approvers, Rule, Rules}.java`; `.../tool/authorization/RiskRules.java`; tests | `.../tool/authorization/Key.java` (record) | — |
| 2 | `nessy-agent/.../agent/{CallStatus, ApprovalCodec, ApprovalRouting, ComputationApprovalContext}.java`; `nessy-intent/.../IntentRules.java` | `Phase`, `AgentEvent`, `Effect`, `DefaultAgent`, `DeliveryWorker`, `ApprovalDesk`, `Harness`, `TurnOutcome`, `ToolFoldRemembrance`, `Kinds`, `codec/StateCodec`, `spi/ToolCallExecutor`, `spi/DeferredToolCallPolicy`, `ComputationDeferredToolCallPolicy`, `tool/RegistryToolCallExecutor`, `host/HarnessConfig`, `host/Console`; `nessy-api`: `ToolGrant`, `Enricher`, `Enrichers`, `AuthorizationReport`, `turn/TurnEvent`; `nessy-intent`: `IntentEnricher`; examples `Governed`, `Approvals`; every test named in Task 2 | `PolicyDecision`, `Decision`, `UsagePolicy` (+ `Allow`, `Deny`, `RequireApproval`, `AllOfPolicy`), `RiskPolicies`, `AuthzContext`, `AuthzContextImpl`; `nessy-spi/.../approval/{Approver, Adjudication, ApprovalRequest}`; `nessy-agent/.../{DispatchIndex, DispatchEntry, DecisionCodec, ComputationApprover}`; `nessy-intent/.../IntentPolicies`; tests `DispatchIndexTest`, `AbsorptionTest`, `GrantDeliveryPendingWindowTest`, `ComputationApproverTest` |
| 3 | `nessy-testing/.../{ScriptedApprover, RecordingApprover}.java`; `nessy-agent` tests `SlowApprovedToolRunsOnceTest`, `PumpsAreNeverStarvedTest`, `EarlyAnswerTest`, `ApprovalDeskTest` additions | — | — |
| 4 | — | `docs/concepts/durable-computation.md`, `docs/guides/harness.md`, `docs/guides/providers.md`, `CHANGELOG.md`, three spec amendments | — |

---

### Task 1: The vocabulary — additive, in `nessy-api`

**Files:**
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Approval.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/ApprovalOutcome.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Approver.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/ApprovalContext.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Facts.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/ApprovalRequest.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Approvers.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Rule.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/approval/Rules.java`
- Create: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/authorization/RiskRules.java`
- Modify: `nessy-api/src/main/java/org/jwcarman/nessy/api/tool/authorization/Key.java` — becomes a record (value equality)
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/approval/FactsTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/approval/ApprovalRequestTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/approval/ApproversTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/approval/RulesTest.java`
- Test: `nessy-api/src/test/java/org/jwcarman/nessy/api/tool/authorization/RiskRulesTest.java`

**Interfaces:**
- Consumes: `ComputationId`, `ToolCall`, `RiskAssessment`, `RiskLevel` (all existing).
- Produces: every type named in Global Constraints under "Exact names," plus `Approvers.allow()/deny(reason)/defer()`, `Approvers.rules(Rule...)`, `Approvers.allOf(Approver...)`, `Approvers.Static` (sealed marker over the two statics, with `Approval answer()`), `Rule.judge(ApprovalRequest) → Rule.Verdict {Answered(Approval), Undecided(), Defer()}`, `Rules.allow()/deny(reason)/defer()`, `RiskRules.threshold(approveAt, denyAt)`, `ApprovalRequest.draft(agentType, agentId, call, mapper)`, `ApprovalRequest.Draft.action(String)/deposit(Key, T)/freeze()`, `ApprovalRequest.codec(mapper)`, `Facts.get(Key<T>)/names()/raw(name)`, `ApprovalRequest.PRINCIPAL` (`Key<String>`), `ApprovalRequest.RISK` (`Key<RiskAssessment>`).
- Nothing existing changes shape except `Key`. Task 2 consumes all of it.

- [ ] **Step 1: `Key` becomes a record**

Replace the class body of `Key.java` (keep the license header and package) with:

```java
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;

/**
 * A typed slot in a fact bag: a class token plus a name. Value equality, deliberately: facts are
 * stored by name in a JSON document (approval-lifecycle spec §1.2), so two keys with the same name
 * address the same fact wherever they were constructed — an enricher in one module and a rule in
 * another agree on {@code new Key<>(Intent.class, "intent.declared")} by construction. Namespace
 * names with a dotted prefix; a bare name is the framework's own.
 *
 * @param <T> the type of value this key looks up
 */
public record Key<T>(Class<T> type, String name) {

  public Key {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  @Override
  public String toString() {
    return "Key[" + name + ": " + type.getSimpleName() + "]";
  }
}
```

`Key` currently has `type()`/`name()` accessors and identity equality; `AuthzContextImpl` keys a `Map<Key<?>, Object>` on it. Value equality does not break that map (it only makes equal keys collide, which is now the intent). `KeyTest`, if it asserts identity inequality of two same-named keys, is corrected to assert equality — read it and adjust.

- [ ] **Step 2: Write the failing tests for `Facts` and `ApprovalRequest`**

`FactsTest.java`:

```java
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.authorization.Key;

class FactsTest {

  record Weather(String sky, int degrees) {}

  /** A value Jackson cannot render: no properties, no creator, a self-reference. */
  static final class Unrenderable {
    final Unrenderable self = this;
  }

  private static final Key<Weather> WEATHER = new Key<>(Weather.class, "test.weather");
  private static final Key<String> NOTE = new Key<>(String.class, "test.note");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void aDepositedFactReadsBackTyped() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(WEATHER, new Weather("clear", 21));

    Facts facts = deposits.freeze();

    assertThat(facts.get(WEATHER)).contains(new Weather("clear", 21));
  }

  @Test
  void anAbsentFactIsEmptyNotAnError() {
    Facts facts = Facts.deposits(mapper).freeze();

    assertThat(facts.get(NOTE)).isEqualTo(Optional.empty());
  }

  @Test
  void anUnrenderableValueFailsAtDepositNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    Key<Unrenderable> key = new Key<>(Unrenderable.class, "test.unrenderable");
    Unrenderable value = new Unrenderable();

    assertThatThrownBy(() -> deposits.put(key, value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("test.unrenderable");
  }

  @Test
  void aNullValueIsRefusedNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);

    assertThatThrownBy(() -> deposits.put(NOTE, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("test.note");
  }

  @Test
  void factsRoundTripThroughJsonAndReadBackTypedOnceAttached() throws Exception {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(WEATHER, new Weather("rain", 12));
    deposits.put(NOTE, "bring a coat");
    Facts original = deposits.freeze();

    String json = mapper.writeValueAsString(original);
    Facts decoded = mapper.readValue(json, Facts.class).attach(mapper);

    assertThat(decoded.names()).containsExactly("test.note", "test.weather");
    assertThat(decoded.get(WEATHER)).contains(new Weather("rain", 12));
    assertThat(decoded.get(NOTE)).contains("bring a coat");
    assertThat(decoded.raw("test.note").asText()).isEqualTo("bring a coat");
  }

  @Test
  void aDecodedBagReadsRawJsonWithoutAMapperButRefusesTypedReads() throws Exception {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(NOTE, "x");
    String json = mapper.writeValueAsString(deposits.freeze());
    Facts decoded = mapper.readValue(json, Facts.class);

    assertThat(decoded.raw("test.note").asText()).isEqualTo("x");
    assertThatThrownBy(() -> decoded.get(NOTE)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aFactThatDoesNotDecodeAsItsKeysTypeFailsNamingTheKey() {
    Facts.Deposits deposits = Facts.deposits(mapper);
    deposits.put(NOTE, "not a weather");
    Facts facts = deposits.freeze();
    Key<Weather> misread = new Key<>(Weather.class, "test.note");

    assertThatThrownBy(() -> facts.get(misread))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("test.note");
  }
}
```

`ApprovalRequestTest.java`:

```java
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.Key;

class ApprovalRequestTest {

  private static final Key<String> NOTE = new Key<>(String.class, "test.note");
  private final ObjectMapper mapper = new ObjectMapper();
  private final ToolCall call =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode().put("target", "eu"));

  @Test
  void aDraftFreezesIntoTheQuestionWithItsActionAndFacts() {
    ApprovalRequest request =
        ApprovalRequest.draft("ops", "prod-eu", call, mapper)
            .action("restart eu")
            .deposit(NOTE, "approved last week")
            .freeze();

    assertThat(request.agentType()).isEqualTo("ops");
    assertThat(request.agentId()).isEqualTo("prod-eu");
    assertThat(request.call()).isEqualTo(call);
    assertThat(request.action()).isEqualTo("restart eu");
    assertThat(request.facts().get(NOTE)).contains("approved last week");
  }

  @Test
  void anUnsetActionFreezesAsTheEmptyString() {
    ApprovalRequest request = ApprovalRequest.draft("ops", "prod-eu", call, mapper).freeze();

    assertThat(request.action()).isEmpty();
  }

  @Test
  void theRequestIsAJsonDocumentThatRoundTripsByteForByte() {
    ApprovalRequest original =
        ApprovalRequest.draft("ops", "prod-eu", call, mapper)
            .action("restart eu")
            .deposit(NOTE, "n")
            .freeze();
    var codec = ApprovalRequest.codec(mapper);

    byte[] bytes = codec.encode(original);
    ApprovalRequest decoded = codec.decode(bytes);

    assertThat(decoded).isEqualTo(original);
    assertThat(codec.encode(decoded)).isEqualTo(bytes);
    assertThat(decoded.facts().get(NOTE)).contains("n"); // the codec attaches the mapper
  }

  @Test
  void aDraftIsSingleUse() {
    ApprovalRequest.Draft draft = ApprovalRequest.draft("ops", "prod-eu", call, mapper);
    draft.freeze();

    assertThatThrownBy(draft::freeze).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theBuiltInKeysNameConcreteTypes() {
    assertThat(ApprovalRequest.PRINCIPAL.type()).isEqualTo(String.class);
    assertThat(ApprovalRequest.RISK.name()).isEqualTo("risk");
  }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q -pl nessy-api test -Dtest='FactsTest,ApprovalRequestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure — the package does not exist.

- [ ] **Step 4: Write `Facts`**

```java
package org.jwcarman.nessy.api.tool.approval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.jwcarman.nessy.api.tool.authorization.Key;

/**
 * Typed facts, stored as JSON (approval-lifecycle spec §1.2). {@link Deposits#put} encodes the
 * value through the pinned mapper immediately; {@link #get} decodes to the key's declared type.
 * There is no way to put an unrenderable value in, so there is no way for a request to fail to
 * render — a value the mapper cannot encode fails inside the enricher, at the line that deposited
 * it, naming the key.
 *
 * <p>The document is the storage form: a {@code Facts} is a {@code Map<String, JsonNode>} keyed by
 * {@link Key#name()}, serialized as exactly that. Typed reads need a mapper to decode with — the
 * one the harness pinned, since it may carry user modules — which {@link Deposits#freeze} attaches
 * for the live request and {@link ApprovalRequest#codec} re-attaches after decoding. A bag decoded
 * without one still answers {@link #raw(String)} (the desk and the console render JSON) and refuses
 * {@link #get} with a message saying why.
 */
public final class Facts {

  private final Map<String, JsonNode> entries;
  @JsonIgnore private final ObjectMapper mapper; // null until attached

  private Facts(Map<String, JsonNode> entries, ObjectMapper mapper) {
    this.entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
    this.mapper = mapper;
  }

  /** Jackson's door: the document alone, unattached. */
  @JsonCreator
  static Facts fromEntries(Map<String, JsonNode> entries) {
    return new Facts(Objects.requireNonNull(entries, "entries must not be null"), null);
  }

  /** The document — what serializes. */
  @JsonValue
  Map<String, JsonNode> entries() {
    return entries;
  }

  /** The mutable half, alive only during enrichment. */
  public static Deposits deposits(ObjectMapper pinned) {
    return new Deposits(Objects.requireNonNull(pinned, "pinned mapper must not be null"));
  }

  /** A copy of this bag that decodes with {@code pinned}. */
  public Facts attach(ObjectMapper pinned) {
    return new Facts(entries, Objects.requireNonNull(pinned, "pinned mapper must not be null"));
  }

  /** The fact under {@code key}, decoded to its declared type; empty if nothing was deposited. */
  public <T> Optional<T> get(Key<T> key) {
    Objects.requireNonNull(key, "key must not be null");
    JsonNode node = entries.get(key.name());
    if (node == null) {
      return Optional.empty();
    }
    if (mapper == null) {
      throw new IllegalStateException(
          "facts decoded from storage are not attached to a mapper; read raw(\""
              + key.name()
              + "\") or attach(mapper) first");
    }
    try {
      return Optional.of(mapper.treeToValue(node, key.type()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "fact '" + key.name() + "' does not decode as " + key.type().getName(), e);
    }
  }

  /** The fact under {@code name} as JSON, or null — for renderers that never decode. */
  public JsonNode raw(String name) {
    return entries.get(Objects.requireNonNull(name, "name must not be null"));
  }

  /** Every deposited name, sorted. */
  public Set<String> names() {
    return entries.keySet();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Facts other && entries.equals(other.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "Facts" + entries;
  }

  /** Enrichment's mutable bag. {@link #freeze} hands back the immutable, attached document. */
  public static final class Deposits {

    private final Map<String, JsonNode> entries = new TreeMap<>();
    private final ObjectMapper pinned;

    private Deposits(ObjectMapper pinned) {
      this.pinned = pinned;
    }

    /**
     * Encodes {@code value} now. A value the mapper cannot render fails HERE, naming the key.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if the mapper cannot render {@code value}
     */
    public <T> void put(Key<T> key, T value) {
      Objects.requireNonNull(key, "key must not be null");
      Objects.requireNonNull(value, () -> "fact '" + key.name() + "' must not be null");
      try {
        entries.put(key.name(), pinned.valueToTree(value));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "fact '" + key.name() + "' cannot be rendered as JSON: " + e.getMessage(), e);
      }
    }

    public Facts freeze() {
      return new Facts(entries, pinned);
    }
  }
}
```

Note `valueToTree` throws `IllegalArgumentException` on an unrenderable value in Jackson 2 — the catch re-throws the same type with the key named. If the implementer finds Jackson 2.22 throws a different unchecked type for the self-referencing fixture, catch that type instead and say so in the report; the test's contract is "fails at deposit, names the key."

- [ ] **Step 5: Write `ApprovalRequest`**

```java
package org.jwcarman.nessy.api.tool.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.Key;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;

/**
 * The question an approver answers: this call, on this agent, with these facts (approval-lifecycle
 * spec §1.2).
 *
 * <p>A JSON document by contract. Every field renders through the harness's pinned mapper,
 * deterministically, and the rendered document is the record of what was decided on: read by the
 * approver, parked with the computation when the approver defers, shown to the desk, and pointed
 * at by the answer's reference. Rendered once — the {@code action} line and every fact are fixed
 * at enrichment and never re-derived at read time.
 *
 * @param action the {@code ActionContributor}'s line, rendered at enrichment; empty when the grant
 *     rendered none
 */
public record ApprovalRequest(
    String agentType, String agentId, ToolCall call, String action, Facts facts) {

  /** The principal a call acts for, if a principal-resolving enricher deposited one. */
  public static final Key<String> PRINCIPAL = new Key<>(String.class, "principal");

  /** The risk a risk-assessing enricher deposited, if any. */
  public static final Key<RiskAssessment> RISK = new Key<>(RiskAssessment.class, "risk");

  public ApprovalRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(facts, "facts must not be null");
  }

  /** What the harness starts from. Enrichment fills the rest; {@link Draft#freeze} ends it. */
  public static Draft draft(String agentType, String agentId, ToolCall call, ObjectMapper pinned) {
    return new Draft(agentType, agentId, call, pinned);
  }

  /** A codec over the pinned mapper; decoding re-attaches it so typed reads work. */
  public static Codec<ApprovalRequest> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(ApprovalRequest value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("unencodable approval request", e);
        }
      }

      @Override
      public ApprovalRequest decode(byte[] bytes) {
        try {
          ApprovalRequest decoded =
              mapper.readValue(new String(bytes, StandardCharsets.UTF_8), ApprovalRequest.class);
          return new ApprovalRequest(
              decoded.agentType(),
              decoded.agentId(),
              decoded.call(),
              decoded.action(),
              decoded.facts().attach(mapper));
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable approval request", e);
        }
      }
    };
  }

  /**
   * The request while it is being enriched. Mutable on purpose and short-lived: the harness hands
   * it to the contributor and each enricher in turn, then freezes it. Nothing outside enrichment
   * ever sees a Draft, and a Draft freezes once.
   */
  public static final class Draft {

    private final String agentType;
    private final String agentId;
    private final ToolCall call;
    private final Facts.Deposits deposits;
    private String action = "";
    private boolean frozen;

    private Draft(String agentType, String agentId, ToolCall call, ObjectMapper pinned) {
      this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
      this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
      this.call = Objects.requireNonNull(call, "call must not be null");
      this.deposits = Facts.deposits(pinned);
    }

    public String agentType() {
      return agentType;
    }

    public String agentId() {
      return agentId;
    }

    public ToolCall call() {
      return call;
    }

    public Draft action(String rendered) {
      requireOpen();
      this.action = Objects.requireNonNull(rendered, "action must not be null");
      return this;
    }

    /** Encodes {@code value} now — an unrenderable fact fails here, naming the key. */
    public <T> Draft deposit(Key<T> key, T value) {
      requireOpen();
      deposits.put(key, value);
      return this;
    }

    public ApprovalRequest freeze() {
      requireOpen();
      frozen = true;
      return new ApprovalRequest(agentType, agentId, call, action, deposits.freeze());
    }

    private void requireOpen() {
      if (frozen) {
        throw new IllegalStateException("this draft was already frozen");
      }
    }
  }
}
```

`nessy-api` already depends on `codec-core` (`org.jwcarman.codec.spi.Codec`) — verify in `nessy-api/pom.xml`; if it does not, add `codec-core` at compile scope (it is managed in the root pom).

- [ ] **Step 6: Run the two tests to verify they pass**

Run: `./mvnw -q -pl nessy-api test -Dtest='FactsTest,ApprovalRequestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 12 pass. If `RiskAssessment` does not round-trip through Jackson (it is a record of enums and `RiskFactors`), add `@JsonCreator`/property annotations to it — its round trip is exercised by Task 2's `GovernedTurnDemo`.

- [ ] **Step 7: Write the failing tests for the approver facade and the toolkit**

`ApproversTest.java`:

```java
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.ToolCall;

class ApproversTest {

  private static final Approval APPROVED = new Approval.Approved(Optional.empty());

  private static ApprovalRequest request() {
    return ApprovalRequest.draft(
            "ops",
            "a1",
            new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode()),
            new ObjectMapper())
        .freeze();
  }

  /** A context whose defer() parks nothing durable — it just mints an id and counts. */
  static final class FakeContext implements ApprovalContext {
    final AtomicInteger defers = new AtomicInteger();
    private final ApprovalRequest request = request();
    private ApprovalOutcome deferred;

    @Override
    public ApprovalRequest request() {
      return request;
    }

    @Override
    public ApprovalOutcome defer() {
      if (deferred == null) {
        defers.incrementAndGet();
        deferred = new ApprovalOutcome.Deferred(ComputationId.of("fake-" + defers.get()));
      }
      return deferred;
    }
  }

  @Nested
  class TheStatics {

    @Test
    void allowAnswersApprovedWithoutReadingTheRequest() {
      assertThat(Approvers.allow().approve(new FakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(APPROVED));
    }

    @Test
    void denyAnswersDeniedWithTheReason() {
      assertThat(Approvers.deny("nope").approve(new FakeContext()))
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("nope", Optional.empty())));
    }

    @Test
    void deferParksThroughTheContext() {
      var context = new FakeContext();

      ApprovalOutcome outcome = Approvers.defer().approve(context);

      assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
      assertThat(context.defers).hasValue(1);
    }

    @Test
    void allowAndDenyAreStaticAndDeferIsNot() {
      assertThat(Approvers.allow()).isInstanceOf(Approvers.Static.class);
      assertThat(Approvers.deny("x")).isInstanceOf(Approvers.Static.class);
      assertThat(Approvers.defer()).isNotInstanceOf(Approvers.Static.class);
      assertThat(((Approvers.Static) Approvers.allow()).answer()).isEqualTo(APPROVED);
    }

    @Test
    void aBlankDenialReasonIsRefused() {
      assertThatThrownBy(() -> Approvers.deny(" ")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class AllOf {

    @Test
    void everyMemberApprovingApproves() {
      Approver gate = Approvers.allOf(Approvers.allow(), Approvers.allow());

      assertThat(gate.approve(new FakeContext())).isEqualTo(new ApprovalOutcome.Answered(APPROVED));
    }

    @Test
    void theFirstDenialWinsAndLaterMembersAreNotConsulted() {
      var consulted = new AtomicInteger();
      Approver counting =
          context -> {
            consulted.incrementAndGet();
            return new ApprovalOutcome.Answered(APPROVED);
          };
      Approver gate = Approvers.allOf(Approvers.deny("first"), counting);

      ApprovalOutcome outcome = gate.approve(new FakeContext());

      assertThat(outcome)
          .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("first", Optional.empty())));
      assertThat(consulted).hasValue(0);
    }

    @Test
    void aMemberThatDefersIsAProgrammingError() {
      Approver gate = Approvers.allOf(Approvers.allow(), Approvers.defer());
      var context = new FakeContext();

      assertThatThrownBy(() -> gate.approve(context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("allOf");
      assertThat(context.defers).hasValue(0);
    }

    @Test
    void anEmptyGateIsRefused() {
      assertThatThrownBy(Approvers::allOf).isInstanceOf(IllegalArgumentException.class);
    }
  }
}
```

`RulesTest.java`:

```java
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RulesTest {

  private static final Approval APPROVED = new Approval.Approved(Optional.empty());

  @Test
  void theFirstAnswerWins() {
    Approver ladder = Approvers.rules(Rules.undecided(), Rules.deny("second"), Rules.allow());

    assertThat(ladder.approve(new ApproversTest.FakeContext()))
        .isEqualTo(new ApprovalOutcome.Answered(new Approval.Denied("second", Optional.empty())));
  }

  @Test
  void deferAsTheLastWordParks() {
    Approver ladder = Approvers.rules(Rules.undecided(), Rules.defer());
    var context = new ApproversTest.FakeContext();

    ApprovalOutcome outcome = ladder.approve(context);

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Deferred.class);
    assertThat(context.defers).hasValue(1);
  }

  @Test
  void aLadderThatEndsUndecidedDeniesLoudly() {
    Approver ladder = Approvers.rules(Rules.undecided());

    ApprovalOutcome outcome = ladder.approve(new ApproversTest.FakeContext());

    assertThat(outcome).isInstanceOf(ApprovalOutcome.Answered.class);
    Approval answer = ((ApprovalOutcome.Answered) outcome).approval();
    assertThat(answer).isInstanceOf(Approval.Denied.class);
    assertThat(((Approval.Denied) answer).reason()).contains("no rule decided");
  }

  @Test
  void aRuleThatThrowsDeniesNamingIt() {
    Rule broken = Rule.named("broken", request -> { throw new IllegalStateException("kaboom"); });
    Approver ladder = Approvers.rules(broken, Rules.allow());

    ApprovalOutcome outcome = ladder.approve(new ApproversTest.FakeContext());

    Approval answer = ((ApprovalOutcome.Answered) outcome).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("broken").contains("kaboom");
  }

  @Test
  void anEmptyLadderIsRefused() {
    assertThatThrownBy(Approvers::rules).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void allowIsAnAnswer() {
    assertThat(Rules.allow().judge(new ApproversTest.FakeContext().request()))
        .isEqualTo(new Rule.Verdict.Answered(APPROVED));
  }
}
```

`RiskRulesTest.java` (in `tool/authorization`):

```java
package org.jwcarman.nessy.api.tool.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Rule;

class RiskRulesTest {

  private static ApprovalRequest requestAt(RiskLevel level) {
    // Choose a Likelihood/Impact pair whose RiskAssessment.of(...) yields `level`; the existing
    // RiskAssessmentTest's severity matrix lists them. Deposit under ApprovalRequest.RISK.
    ApprovalRequest.Draft draft =
        ApprovalRequest.draft(
            "ops", "a1", new ToolCall("c1", "x", JsonNodeFactory.instance.objectNode()), new ObjectMapper());
    draft.deposit(ApprovalRequest.RISK, RiskAssessments.at(level)); // test helper: see below
    return draft.freeze();
  }

  @Test
  void belowApproveAtApproves() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    assertThat(rule.judge(requestAt(RiskLevel.LOW)))
        .isEqualTo(new Rule.Verdict.Answered(new Approval.Approved(Optional.empty())));
  }

  @Test
  void betweenTheThresholdsDefers() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    assertThat(rule.judge(requestAt(RiskLevel.HIGH))).isEqualTo(new Rule.Verdict.Defer());
  }

  @Test
  void atDenyAtDeniesNamingTheSeverity() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);

    Rule.Verdict verdict = rule.judge(requestAt(RiskLevel.VERY_HIGH));

    assertThat(verdict).isInstanceOf(Rule.Verdict.Answered.class);
    Approval answer = ((Rule.Verdict.Answered) verdict).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("VERY_HIGH");
  }

  @Test
  void noRiskFactDeniesClosed() {
    Rule rule = RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH);
    ApprovalRequest bare =
        ApprovalRequest.draft(
                "ops", "a1", new ToolCall("c1", "x", JsonNodeFactory.instance.objectNode()), new ObjectMapper())
            .freeze();

    Rule.Verdict verdict = rule.judge(bare);

    Approval answer = ((Rule.Verdict.Answered) verdict).approval();
    assertThat(((Approval.Denied) answer).reason()).contains("no risk");
  }

  @Test
  void approveAtAboveDenyAtIsRefused() {
    assertThatThrownBy(() -> RiskRules.threshold(RiskLevel.VERY_HIGH, RiskLevel.LOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

`RiskAssessments.at(level)` is a small test helper in the same test package: read `RiskAssessmentTest`'s severity matrix and return a `RiskAssessment.of(likelihood, impact, factors)` whose `risk()` equals `level`, for each `RiskLevel` — write it once, as a `switch` over `RiskLevel`.

- [ ] **Step 8: Run them to verify they fail**

Run: `./mvnw -q -pl nessy-api test -Dtest='ApproversTest,RulesTest,RiskRulesTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 9: Write the facade — `Approval`, `ApprovalOutcome`, `Approver`, `ApprovalContext`**

```java
package org.jwcarman.nessy.api.tool.approval;

import java.util.Objects;
import java.util.Optional;

/**
 * The answer to "may this call run?" (approval-lifecycle spec §1.1). One type, wherever the answer
 * travels: spoken by a grant's approver in-process, by a person at the desk, or delivered by
 * Continuum days later.
 *
 * <p>{@code reference} is an opaque pointer into whatever system produced the answer — its own
 * decision id, a ticket, a hash of its evidence. Nessy never interprets it; it is the join between
 * the fold's record and the audit trail that knows who and why (spec §7).
 */
public sealed interface Approval {

  record Approved(Optional<String> reference) implements Approval {
    public Approved {
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  record Denied(String reason, Optional<String> reference) implements Approval {
    public Denied {
      Objects.requireNonNull(reason, "reason must not be null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("reason must not be blank");
      }
      Objects.requireNonNull(reference, "reference must not be null");
    }
  }

  static Approval approved() {
    return new Approved(Optional.empty());
  }

  static Approval denied(String reason) {
    return new Denied(reason, Optional.empty());
  }
}
```

```java
package org.jwcarman.nessy.api.tool.approval;

import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationId;

/** What an approver returns (spec §1.3): decided, or parked under a computation someone will answer. */
public sealed interface ApprovalOutcome {

  record Answered(Approval approval) implements ApprovalOutcome {
    public Answered {
      Objects.requireNonNull(approval, "approval must not be null");
    }
  }

  /** Minted only by {@link ApprovalContext#defer()}; the id names the parked computation. */
  record Deferred(ComputationId id) implements ApprovalOutcome {
    public Deferred {
      Objects.requireNonNull(id, "id must not be null");
    }
  }
}
```

```java
package org.jwcarman.nessy.api.tool.approval;

/**
 * A facade in the way {@code Memory} is (approval-lifecycle spec §1.3): one method, and a world
 * behind it — a rule ladder, a risk service, a Slack post, a policy engine, a quorum, a person at
 * a terminal — none of it visible to the harness, and all of it free to be asynchronous through
 * {@link ApprovalContext#defer()}. An approver either answers or says it will get back to us; it
 * never sees Continuum, a kind, a continuation or a lease. Telling people is its business: whatever
 * it does after {@code defer()} hands it an id is how the human learns there is a question.
 *
 * <p>Approvers are at-least-once, like tools: a re-fired call asks again. A rule ladder is free; a
 * service is called twice; a console prompt re-asks its human.
 */
@FunctionalInterface
public interface Approver {

  ApprovalOutcome approve(ApprovalContext context);
}
```

```java
package org.jwcarman.nessy.api.tool.approval;

/**
 * What an approver learns about the invocation it is serving, plus what it can do with it — the
 * mirror of {@code ToolContext} (spec §1.3). {@link #defer()} does the plumbing: it parks the
 * question, records the fact in the scope, waits for that record to commit, and only then hands
 * back the id. By the time an approver can tell anyone, the phase already names the ask.
 * Idempotent: a second call returns the same outcome.
 */
public interface ApprovalContext {

  /** The question, enriched and frozen. */
  ApprovalRequest request();

  /** "I'll get back to you": the outcome to return, carrying the parked computation's id. */
  ApprovalOutcome defer();
}
```

- [ ] **Step 10: Write `Approvers`, `Rule`, `Rules`, `RiskRules`**

```java
package org.jwcarman.nessy.api.tool.approval;

import java.util.List;
import java.util.Objects;

/** The built-in approvers and the two compositions people reach for (spec §1.4). */
public final class Approvers {

  private Approvers() {}

  /** Every call runs; no request is built (the executor's rung-0 fast path). */
  public static Approver allow() {
    return Allow.INSTANCE;
  }

  /** Every call is refused with {@code reason}; no request is built. */
  public static Approver deny(String reason) {
    return new Deny(Approval.denied(reason));
  }

  /** Every call is parked for someone else to answer; nobody is told. */
  public static Approver defer() {
    return ApprovalContext::defer;
  }

  /**
   * A ladder: rules in order, first answer wins, a {@link Rule.Verdict.Defer} parks, and a ladder
   * that runs out of rules undecided denies loudly rather than approving by omission.
   */
  public static Approver rules(Rule... rules) {
    Objects.requireNonNull(rules, "rules must not be null");
    if (rules.length == 0) {
      throw new IllegalArgumentException("rules must not be empty");
    }
    List<Rule> ordered = List.of(rules);
    return context -> {
      ApprovalRequest request = context.request();
      for (Rule rule : ordered) {
        Rule.Verdict verdict;
        try {
          verdict = rule.judge(request);
        } catch (RuntimeException e) {
          String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
          return new ApprovalOutcome.Answered(
              Approval.denied("rule " + rule.displayName().orElse("#" + ordered.indexOf(rule)) + " failed: " + detail));
        }
        switch (verdict) {
          case Rule.Verdict.Answered(Approval approval) -> {
            return new ApprovalOutcome.Answered(approval);
          }
          case Rule.Verdict.Defer _ -> {
            return context.defer();
          }
          case Rule.Verdict.Undecided _ -> {
            // next rule
          }
        }
      }
      return new ApprovalOutcome.Answered(
          Approval.denied("no rule decided; end a ladder with Rules.allow(), Rules.deny(...) or Rules.defer()"));
    };
  }

  /**
   * Gates: every member must approve; the first denial wins and later members are not consulted.
   * Members answer — a member that defers is a programming error, refused before it can park.
   */
  public static Approver allOf(Approver... approvers) {
    Objects.requireNonNull(approvers, "approvers must not be null");
    if (approvers.length == 0) {
      throw new IllegalArgumentException("approvers must not be empty");
    }
    List<Approver> members = List.of(approvers);
    return context -> {
      ApprovalContext answering = new AnsweringOnly(context.request());
      for (Approver member : members) {
        ApprovalOutcome outcome = member.approve(answering);
        switch (outcome) {
          case ApprovalOutcome.Answered(Approval.Denied denied) -> {
            return new ApprovalOutcome.Answered(denied);
          }
          case ApprovalOutcome.Answered(Approval.Approved _) -> {
            // next member
          }
          case ApprovalOutcome.Deferred _ ->
              throw new IllegalStateException("allOf members must answer; one deferred");
        }
      }
      return new ApprovalOutcome.Answered(Approval.approved());
    };
  }

  /**
   * The marker the executor recognises to answer without building a request (spec §1.4). Sealed to
   * the two built-ins on purpose: a third implementor would skip enrichment for a call that might
   * need it.
   */
  public sealed interface Static extends Approver permits Allow, Deny {
    Approval answer();
  }

  static final class Allow implements Static {
    static final Allow INSTANCE = new Allow();

    @Override
    public Approval answer() {
      return Approval.approved();
    }

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      return new ApprovalOutcome.Answered(answer());
    }
  }

  static final class Deny implements Static {
    private final Approval denied;

    Deny(Approval denied) {
      this.denied = denied;
    }

    @Override
    public Approval answer() {
      return denied;
    }

    @Override
    public ApprovalOutcome approve(ApprovalContext context) {
      return new ApprovalOutcome.Answered(denied);
    }
  }

  /** The context {@link #allOf} hands its members: the same request, a door that refuses. */
  private record AnsweringOnly(ApprovalRequest request) implements ApprovalContext {
    @Override
    public ApprovalOutcome defer() {
      throw new IllegalStateException("allOf members must answer; defer() is not available here");
    }
  }
}
```

```java
package org.jwcarman.nessy.api.tool.approval;

import java.util.Objects;
import java.util.Optional;

/**
 * One step of a ladder (spec §1.4): answers, passes, or says "park it". Three outcomes is the
 * toolkit's vocabulary, never {@link Approver}'s — "I am unable to decide" is a rule's word.
 */
@FunctionalInterface
public interface Rule {

  Verdict judge(ApprovalRequest request);

  default Optional<String> displayName() {
    return Optional.empty();
  }

  static Rule named(String displayName, Rule delegate) {
    Objects.requireNonNull(displayName, "displayName must not be null");
    if (displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new Rule() {
      @Override
      public Verdict judge(ApprovalRequest request) {
        return delegate.judge(request);
      }

      @Override
      public Optional<String> displayName() {
        return Optional.of(displayName);
      }
    };
  }

  sealed interface Verdict {
    record Answered(Approval approval) implements Verdict {
      public Answered {
        Objects.requireNonNull(approval, "approval must not be null");
      }
    }

    record Undecided() implements Verdict {}

    record Defer() implements Verdict {}
  }
}
```

```java
package org.jwcarman.nessy.api.tool.approval;

/** The rules every ladder ends with. */
public final class Rules {

  private Rules() {}

  public static Rule allow() {
    return Rule.named("allow", request -> new Rule.Verdict.Answered(Approval.approved()));
  }

  public static Rule deny(String reason) {
    Approval denied = Approval.denied(reason);
    return Rule.named("deny", request -> new Rule.Verdict.Answered(denied));
  }

  public static Rule defer() {
    return Rule.named("defer", request -> new Rule.Verdict.Defer());
  }

  public static Rule undecided() {
    return Rule.named("undecided", request -> new Rule.Verdict.Undecided());
  }
}
```

`RiskRules.java` replaces `RiskPolicies` (which Task 2 deletes):

```java
package org.jwcarman.nessy.api.tool.authorization;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.tool.approval.Approval;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;
import org.jwcarman.nessy.api.tool.approval.Rule;

/** Rules over {@link ApprovalRequest#RISK} — the one-line judgment every deployment wants. */
public final class RiskRules {

  private RiskRules() {}

  /**
   * Severity below {@code approveAt} approves; from {@code approveAt} up to (but below) {@code
   * denyAt} defers; {@code denyAt} or above denies naming the severity. No risk fact denies closed.
   *
   * @throws IllegalArgumentException if {@code approveAt} is more severe than {@code denyAt}
   */
  public static Rule threshold(RiskLevel approveAt, RiskLevel denyAt) {
    Objects.requireNonNull(approveAt, "approveAt must not be null");
    Objects.requireNonNull(denyAt, "denyAt must not be null");
    if (approveAt.compareTo(denyAt) > 0) {
      throw new IllegalArgumentException("approveAt must not exceed denyAt");
    }
    return Rule.named(
        "risk threshold",
        request -> {
          Optional<RiskAssessment> assessment = request.facts().get(ApprovalRequest.RISK);
          if (assessment.isEmpty()) {
            return new Rule.Verdict.Answered(Approval.denied("no risk assessment deposited under 'risk'"));
          }
          RiskLevel severity = assessment.get().risk();
          if (severity.compareTo(approveAt) < 0) {
            return new Rule.Verdict.Answered(Approval.approved());
          }
          if (severity.compareTo(denyAt) < 0) {
            return new Rule.Verdict.Defer();
          }
          return new Rule.Verdict.Answered(
              Approval.denied("risk severity " + severity + " meets or exceeds threshold " + denyAt));
        });
  }
}
```

- [ ] **Step 11: Run the tests to verify they pass**

Run: `./mvnw -q -pl nessy-api test -Dtest='ApproversTest,RulesTest,RiskRulesTest,FactsTest,ApprovalRequestTest,KeyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: all pass.

- [ ] **Step 12: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-api
git commit -m "feat: the approval vocabulary — Approval, Approver, ApprovalContext, ApprovalRequest as a JSON document"
```

---

### Task 2: The cut-over — every call is approved, and the lifecycle folds

This is the reducer change. It is one task because the pieces cannot be green apart: `ToolGrant` carrying an `Approver` forces the executor; the executor's two doors force the reducer's two effects; the reducer's statuses force the delivery worker and the desk. **Work in the order below; the reactor is red until Step 14 and green after.** Commit in the three groups Step 15 names.

**Files:**
- Modify (api): `ToolGrant.java`, `authorization/Enricher.java`, `authorization/Enrichers.java`, `authorization/AuthorizationReport.java`, `authorization/GrantStory.java` (if it names "policy"), `turn/TurnEvent.java`
- Delete (api): `tool/PolicyDecision.java`, `Decision.java`, `tool/UsagePolicy.java` and its package-private `Allow`, `Deny`, `RequireApproval`, `AllOfPolicy` classes, `authorization/RiskPolicies.java`, `authorization/AuthzContext.java`, `authorization/AuthzContextImpl.java`
- Delete (spi): `spi/approval/Approver.java`, `Adjudication.java`, `ApprovalRequest.java` (the package becomes empty; remove it)
- Create (agent): `CallStatus.java`, `ApprovalCodec.java`, `ApprovalRouting.java`, `ComputationApprovalContext.java`
- Modify (agent): `Phase.java`, `AgentEvent.java`, `Effect.java`, `DefaultAgent.java`, `DeliveryWorker.java`, `ApprovalDesk.java`, `Harness.java`, `TurnOutcome.java`, `ToolFoldRemembrance.java`, `Kinds.java`, `CallAddress.java` (rename `indexKey()` → `digest()`), `codec/StateCodec.java`, `spi/ToolCallExecutor.java`, `spi/DeferredToolCallPolicy.java`, `ComputationDeferredToolCallPolicy.java`, `tool/RegistryToolCallExecutor.java`, `host/HarnessConfig.java`, `host/Console.java`
- Delete (agent): `DispatchIndex.java`, `DispatchEntry.java`, `DecisionCodec.java`, `ComputationApprover.java`
- Modify (intent): `IntentEnricher.java`; create `IntentRules.java`; delete `IntentPolicies.java`
- Modify (examples): `governed/Governed.java`, `approvals/Approvals.java`
- Tests: see Step 13.

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: `ToolGrant.approver()`, `ToolGrant.request(agentType, agentId, call, input, mapper)`; `Enricher.enrich(ApprovalRequest.Draft)`; `TurnEvent.ToolCallDecided(call, Approval)`; the reducer grammar in Global Constraints; `ToolCallExecutor.seekApproval/runTool`; `ApprovalDesk.approve(id, principal, note)`, `deny(id, principal, reason)`, `approve(AgentId, callId, principal, note)`, `deny(AgentId, callId, principal, reason)`, `withdraw(id, reason)`, `request(AgentId, callId)`; `TurnOutcome.Parked(ComputationId approval, ApprovalRequest request)`; `HarnessConfig` without `approvalNotifier`. Task 3's tests use these.

- [ ] **Step 1: `ToolGrant` carries an `Approver` and builds the request**

Replace `ToolGrant`'s policy field, accessors, `assemble`, `decide` and the three factories. The class javadoc's "policy" prose becomes "approver"; the pipeline paragraph becomes: *"`request(...)` binds the input, renders the action, deposits it on the draft, runs the enrichers in order, and freezes — the approver reads the frozen request."*

```java
  private final Tool<?> tool;
  private final Approver approver;
  private final List<Enricher> enrichers;
  private final ActionContributor<?, ?> contributor;
  private final Function<Object, String> renderAction;

  private ToolGrant(
      Tool<?> tool,
      Approver approver,
      List<Enricher> enrichers,
      ActionContributor<?, ?> contributor,
      Function<Object, String> renderAction) {
    this.tool = Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    this.approver = Objects.requireNonNull(approver, APPROVER_MUST_NOT_BE_NULL);
    this.enrichers = List.copyOf(Objects.requireNonNull(enrichers, "enrichers must not be null"));
    this.contributor = Objects.requireNonNull(contributor, "contributor must not be null");
    this.renderAction = Objects.requireNonNull(renderAction, "renderAction must not be null");
  }

  /** The {@link Approver} the executor consults before the tool runs. */
  public Approver approver() {
    return approver;
  }

  /**
   * Builds the question (approval-lifecycle spec §1.2): a draft from the coordinates, the action
   * rendered and set, each enricher run in order over the draft, then frozen. A {@code
   * RuntimeException} escaping the action render or any enricher is rethrown as an {@link
   * IllegalStateException} naming the stage — the chokepoint fails closed on the stage name.
   */
  public ApprovalRequest request(
      String agentType, String agentId, ToolCall call, Object input, ObjectMapper pinned) {
    ApprovalRequest.Draft draft = ApprovalRequest.draft(agentType, agentId, call, pinned);
    stage("action stage: ", () -> draft.action(renderAction.apply(input)));
    int index = 0;
    for (Enricher enricher : enrichers) {
      String label = enricher.displayName().orElse("#" + index);
      stage("enricher stage " + label + ": ", () -> { enricher.enrich(draft); return null; });
      index++;
    }
    return draft.freeze();
  }

  private static final ActionContributor<Object, String> DEFAULT_CONTRIBUTOR =
      ActionContributor.named("String.valueOf", String::valueOf);

  /** Rung 0/1: the default contributor. No enrichers. */
  public static ToolGrant grant(Tool<?> tool, Approver approver) {
    return grant(tool, DEFAULT_CONTRIBUTOR, List.of(), approver);
  }

  /** Rung 2: typed weld, no enrichers. */
  public static <I> ToolGrant grant(
      Tool<I> tool, ActionContributor<? super I, ?> contributor, Approver approver) {
    return grant(tool, contributor, List.of(), approver);
  }

  /** Rung 2/3: the contributor renders the action; enrichers run in order over the draft. */
  public static <I> ToolGrant grant(
      Tool<I> tool,
      ActionContributor<? super I, ?> contributor,
      List<Enricher> enrichers,
      Approver approver) {
    Objects.requireNonNull(tool, TOOL_MUST_NOT_BE_NULL);
    Objects.requireNonNull(contributor, "contributor must not be null");
    Objects.requireNonNull(enrichers, "enrichers must not be null");
    Objects.requireNonNull(approver, APPROVER_MUST_NOT_BE_NULL);
    Function<Object, String> renderAction =
        input -> String.valueOf(contributor.actionOf(tool.inputType().cast(input)));
    return new ToolGrant(tool, approver, new ArrayList<>(enrichers), contributor, renderAction);
  }
```

The action becomes a `String` on the request (spec §1.2); a contributor rendering a richer type has it `String.valueOf`'d. `ActionContributor`'s javadoc about `AuthzContext.ACTION_KEY` becomes "set as the request's `action`."

`Enricher`:

```java
@FunctionalInterface
public interface Enricher {

  /** Deposits facts on {@code draft}; must not freeze it. */
  void enrich(ApprovalRequest.Draft draft);

  default Optional<String> displayName() { return Optional.empty(); }

  static Enricher named(String displayName, Enricher delegate) { /* as today, over the new signature */ }
}
```

`Enrichers.principal(Supplier<String> resolver)` deposits `ApprovalRequest.PRINCIPAL`. `AuthorizationReport.story(...)`: `Approver approver = grant.approver(); boolean actionRendered = !(approver instanceof Approvers.Static);` and `policySummary` becomes `approverSummary`: `Static` → `"allow()"` / `"deny(\"reason\")"` via `answer()`; identity with `Approvers.defer()` cannot be tested (it is a lambda) — summarise a non-static approver by its class's simple name, or `"approver"` when blank.

`TurnEvent.ToolCallDecided(ToolCall call, Approval approval)` — import `org.jwcarman.nessy.api.tool.approval.Approval`; delete the `Decision` import. `TurnObserverConfig`/`TurnObserverAdapter` compile unchanged.

Delete the six api files and the three spi files listed above. `nessy-spi/pom.xml` needs nothing.

- [ ] **Step 2: The reducer — `CallStatus`, `Phase.AwaitingTools`, events, effects**

`CallStatus.java`:

```java
package org.jwcarman.nessy.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Where one call's lifecycle stands (approval-lifecycle spec §2). States are named for what they
 * await; the acts that put a call there have their own past-tense names in {@link AgentEvent}.
 * Two statuses wait on Continuum and are one mechanism used twice: the status records the
 * computation's id, the delivery is recognised by it, and the call is never re-fired.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = CallStatus.Pending.class, name = "pending"),
  @JsonSubTypes.Type(value = CallStatus.AwaitingApproval.class, name = "awaiting-approval"),
  @JsonSubTypes.Type(value = CallStatus.Running.class, name = "running"),
  @JsonSubTypes.Type(value = CallStatus.AwaitingResult.class, name = "awaiting-result"),
  @JsonSubTypes.Type(value = CallStatus.Finished.class, name = "finished")
})
public sealed interface CallStatus {

  /** Approval sought; no answer recorded. Re-fire re-seeks. */
  record Pending() implements CallStatus {}

  /** The approver deferred; Continuum holds the ask. Never re-fired. */
  record AwaitingApproval(ComputationId approval, ApprovalRequest request) implements CallStatus {
    public AwaitingApproval {
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
    }
  }

  /** Approved; the tool is executing. Re-fire re-runs. */
  record Running() implements CallStatus {}

  /** The tool deferred; Continuum holds the result. Never re-fired. */
  record AwaitingResult(ComputationId tool) implements CallStatus {
    public AwaitingResult {
      Objects.requireNonNull(tool, "tool must not be null");
    }
  }

  /** An outcome exists — success, denial or failure. */
  record Finished(ToolResultBlock result) implements CallStatus {
    public Finished {
      Objects.requireNonNull(result, "result must not be null");
    }
  }
}
```

`AgentEvent`:

```java
public sealed interface AgentEvent {

  record Observed(List<ContentBlock> content) implements AgentEvent { /* unchanged */ }

  record ModelFinished(ModelOutcome outcome) implements AgentEvent { /* unchanged */ }

  /** The approver deferred: the ask is parked under {@code approval}, and this is the question. */
  record ApprovalDeferred(ToolCall call, ComputationId approval, ApprovalRequest request)
      implements AgentEvent {
    public ApprovalDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(request, "request must not be null");
    }
  }

  /** An answer: in-process ({@code approval} empty) or delivered from a parked computation. */
  record ApprovalAnswered(ToolCall call, Optional<ComputationId> approval, Approval answer)
      implements AgentEvent {
    public ApprovalAnswered {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(approval, "approval must not be null");
      Objects.requireNonNull(answer, "answer must not be null");
    }
  }

  /** The tool deferred: its result is parked under {@code tool}. */
  record ToolDeferred(ToolCall call, ComputationId tool) implements AgentEvent {
    public ToolDeferred {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(tool, "tool must not be null");
    }
  }

  /** A result: in-process ({@code tool} empty) or delivered from a parked computation. */
  record ToolFinished(ToolCall call, Optional<ComputationId> tool, ToolOutcome outcome)
      implements AgentEvent {
    public ToolFinished {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(tool, "tool must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }
  }
}
```

Update the class javadoc: "Six variants: every effect has exactly one completion event, `Observed` is the sole inbound fact, and the two `*Deferred` events record a park (spec §3)."

`Effect`:

```java
public sealed interface Effect {
  record CallModel() implements Effect {}
  record SeekApproval(ToolCall call) implements Effect { /* requireNonNull */ }
  record RunTool(ToolCall call) implements Effect { /* requireNonNull */ }
}
```

`Phase.AwaitingTools` — replace the record, its constructor, `handle`, `outstandingEffects`, and `resultBlock` with:

```java
  /**
   * @param calls each call the assistant turn asked for, keyed by call id, with where its lifecycle
   *     stands — a sorted map so the wire form is deterministic
   */
  record AwaitingTools(Message assistantTurn, Map<String, CallStatus> calls, ModelResponseId responseId)
      implements Phase {

    public AwaitingTools {
      Objects.requireNonNull(assistantTurn, "assistantTurn must not be null");
      Objects.requireNonNull(responseId, "responseId must not be null");
      calls = Collections.unmodifiableSortedMap(new TreeMap<>(calls));
      if (calls.isEmpty()) {
        throw new IllegalArgumentException("awaiting tools with no calls is not a phase");
      }
      Set<String> toolUseIds = toolUseIds(assistantTurn);
      Set<String> unknown = new HashSet<>(calls.keySet());
      unknown.removeAll(toolUseIds);
      if (!unknown.isEmpty()) {
        throw new IllegalArgumentException(
            "call ids missing from the assistant turn's tool-use blocks: " + unknown);
      }
    }

    static AwaitingTools opening(Message assistantTurn, List<ToolCall> requested, ModelResponseId responseId) {
      Map<String, CallStatus> pending = new TreeMap<>();
      for (ToolCall call : requested) {
        pending.put(call.id(), new CallStatus.Pending());
      }
      return new AwaitingTools(assistantTurn, pending, responseId);
    }

    @Override
    public Transition handle(AgentEvent event) {
      return switch (event) {
        case AgentEvent.ApprovalAnswered(var call, var approval, var answer) ->
            onApprovalAnswered(call, approval, answer);
        case AgentEvent.ApprovalDeferred(var call, var approval, var request) ->
            status(call).filter(CallStatus.Pending.class::isInstance).isPresent()
                ? Transition.to(with(call.id(), new CallStatus.AwaitingApproval(approval, request)))
                : Transition.ignore();
        case AgentEvent.ToolDeferred(var call, var tool) ->
            status(call).filter(CallStatus.Running.class::isInstance).isPresent()
                ? Transition.to(with(call.id(), new CallStatus.AwaitingResult(tool)))
                : Transition.ignore();
        case AgentEvent.ToolFinished(var call, var tool, var outcome) ->
            onToolFinished(call, tool, outcome);
        case AgentEvent.ModelFinished _ -> Transition.ignore();
        case AgentEvent.Observed _ ->
            throw new IllegalStateException("observations absorb only at Idle");
      };
    }

    private Transition onApprovalAnswered(ToolCall call, Optional<ComputationId> approval, Approval answer) {
      Optional<CallStatus> current = status(call);
      if (current.isEmpty()) {
        return Transition.ignore();
      }
      boolean admitted =
          switch (current.get()) {
            case CallStatus.Pending _ -> approval.isEmpty();               // in-process answer
            case CallStatus.AwaitingApproval(var id, var _) -> approval.filter(id::equals).isPresent();
            case CallStatus.Running _, CallStatus.AwaitingResult _, CallStatus.Finished _ -> false;
          };
      if (!admitted) {
        return Transition.ignore(); // early (Pending + delivered id), stale (orphan), or duplicate
      }
      return switch (answer) {
        case Approval.Approved _ ->
            Transition.to(with(call.id(), new CallStatus.Running()), new Effect.RunTool(call));
        case Approval.Denied(var reason, var _) ->
            finish(call, new ToolResultBlock(call.id(), reason, true));
      };
    }

    private Transition onToolFinished(ToolCall call, Optional<ComputationId> tool, ToolOutcome outcome) {
      Optional<CallStatus> current = status(call);
      if (current.isEmpty()) {
        return Transition.ignore();
      }
      boolean admitted =
          switch (current.get()) {
            case CallStatus.Running _ -> tool.isEmpty();
            case CallStatus.AwaitingResult(var id) -> tool.filter(id::equals).isPresent();
            case CallStatus.Pending _, CallStatus.AwaitingApproval _, CallStatus.Finished _ -> false;
          };
      if (!admitted) {
        return Transition.ignore();
      }
      return finish(call, resultBlock(call, outcome));
    }

    /** Marks {@code call} finished; when every call is, commits the turn and calls the model. */
    private Transition finish(ToolCall call, ToolResultBlock result) {
      AwaitingTools next = with(call.id(), new CallStatus.Finished(result));
      if (next.calls.values().stream().allMatch(CallStatus.Finished.class::isInstance)) {
        return Transition.to(new AwaitingModel(), new Effect.CallModel())
            .commit(assistantTurn, Message.toolResults(next.resultsInTurnOrder()));
      }
      return Transition.to(next);
    }

    private AwaitingTools with(String callId, CallStatus status) {
      Map<String, CallStatus> updated = new TreeMap<>(calls);
      updated.put(callId, status);
      return new AwaitingTools(assistantTurn, updated, responseId);
    }

    private Optional<CallStatus> status(ToolCall call) {
      return Optional.ofNullable(calls.get(call.id()));
    }

    /** Results in the order the assistant turn asked, which is the order the model expects. */
    private List<ToolResultBlock> resultsInTurnOrder() {
      List<ToolResultBlock> results = new ArrayList<>();
      for (ToolCall call : requestedCalls()) {
        if (calls.get(call.id()) instanceof CallStatus.Finished(var result)) {
          results.add(result);
        }
      }
      return List.copyOf(results);
    }

    @Override
    public List<Effect> outstandingEffects() {
      List<Effect> effects = new ArrayList<>();
      for (ToolCall call : requestedCalls()) {
        switch (calls.get(call.id())) {
          case CallStatus.Pending _ -> effects.add(new Effect.SeekApproval(call));
          case CallStatus.Running _ -> effects.add(new Effect.RunTool(call));
          case CallStatus.AwaitingApproval _, CallStatus.AwaitingResult _, CallStatus.Finished _ -> {
            // Continuum holds the first two; the last is done
          }
          case null -> throw new IllegalStateException("no status for call " + call.id());
        }
      }
      return List.copyOf(effects);
    }

    /** The calls the assistant turn asked for, in its own order. */
    List<ToolCall> requestedCalls() {
      List<ToolCall> requested = new ArrayList<>();
      for (var block : assistantTurn.content()) {
        if (block instanceof ToolUseBlock(ToolCall call, _)) {
          requested.add(call);
        }
      }
      return requested;
    }

    private static Set<String> toolUseIds(Message assistantTurn) { /* as today's inline */ }

    private static ToolResultBlock resultBlock(ToolCall call, ToolOutcome outcome) { /* unchanged */ }
  }
```

`AwaitingModel.handle`'s tool-calls arm becomes:

```java
        case AgentEvent.ModelFinished(ModelOutcome.Responded(var content, var calls, var responseId)) ->
            Transition.to(AwaitingTools.opening(Message.assistant(content), calls, responseId))
                .emit(calls.stream().map(Effect.SeekApproval::new).map(Effect.class::cast).toList());
```

`Idle.handle` and `AwaitingModel.handle` gain `case AgentEvent.ApprovalDeferred _, AgentEvent.ApprovalAnswered _, AgentEvent.ToolDeferred _ -> Transition.ignore();` — every phase switch stays exhaustive with no default. `AwaitingModel.handle`'s existing `ToolFinished` arm stays an ignore.

Note `case null ->` in a switch over a sealed type: a `Map.get` miss is a programming error, surfaced loudly — keep it, it is not a `default`.

`StateCodec.phase(...)`: drop the two `requireArrayIfPresent` lines (there is no `pending`/`gathered`); add `Codecs.requireObjectIfPresent(root, "calls", "awaiting-tools phase")` if `Codecs` has such a helper, else nothing — Jackson's binding validates the map.

- [ ] **Step 3: The SPI — two doors**

`ToolCallExecutor`:

```java
public interface ToolCallExecutor {

  /** Ask: evaluate the grant's approver; deliver ApprovalAnswered or (via defer) ApprovalDeferred. Never runs a tool. */
  void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink);

  /** Run: past the gate; deliver ToolFinished or ToolDeferred. Never consults an approver. */
  void runTool(ToolCall call, ModelResponseId responseId, Sink sink);
}
```

`DeferredToolCallPolicy`: delete `pendingComputation`; keep `onDeferred(call, address, timeout) → ToolExecution`. `ComputationDeferredToolCallPolicy` loses the index: constructor `(ContinuumClient<ToolResult, Routing> client)`; `onDeferred` creates the computation and returns `Deferred(id)` — no `index.record`.

- [ ] **Step 4: `RegistryToolCallExecutor` — two doors, neither with a conditional inside**

Replace `executeTool`, `executeGrantedToolNow`, `execute`, `executePastGate`, `gate`, and the `defaultApprover` with:

```java
  @Override
  public void seekApproval(ToolCall call, ModelResponseId responseId, Sink sink) {
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(() -> sink.deliver(seek(call, responseId, sink)));
  }

  @Override
  public void runTool(ToolCall call, ModelResponseId responseId, Sink sink) {
    Objects.requireNonNull(responseId, "responseId must not be null");
    executor.execute(
        () -> {
          CallAddress address = address(call, responseId);
          switch (runPastGate(call, address)) {
            case ToolExecution.Immediate(ToolOutcome outcome) ->
                sink.deliver(new AgentEvent.ToolFinished(call, Optional.empty(), outcome));
            case ToolExecution.Deferred(ComputationId id) ->
                sink.deliver(new AgentEvent.ToolDeferred(call, id));
          }
        });
  }

  /** The ask. Returns the event to deliver; a deferral has already delivered its own. */
  private AgentEvent seek(ToolCall call, ModelResponseId responseId, Sink sink) {
    Optional<ToolGrant> found = registry.find(call.name());
    if (found.isEmpty()) {
      return answered(call, Approval.denied("unknown tool: " + call.name()));
    }
    ToolGrant grant = found.get();
    if (grant.approver() instanceof Approvers.Static fixed) {
      return answered(call, fixed.answer()); // rung 0: no request built, no enricher run
    }
    ApprovalRequest request;
    try {
      Object input = convert(call, grant.tool());
      request = grant.request(type.name(), id.value(), call, input, mapper);
    } catch (RuntimeException e) {
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return answered(call, Approval.denied("authorization failed: " + detail));
    }
    ApprovalContext context = approvalContexts.contextFor(call, responseId, request, sink);
    ApprovalOutcome outcome;
    try {
      outcome = grant.approver().approve(context);
    } catch (RuntimeException e) {
      String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return answered(call, Approval.denied("approver failed: " + detail));
    }
    return switch (outcome) {
      case ApprovalOutcome.Answered(Approval approval) -> answered(call, approval);
      case ApprovalOutcome.Deferred _ -> null; // defer() delivered ApprovalDeferred itself
    };
  }
```

`seekApproval`'s lambda must not deliver a null: write it as `AgentEvent event = seek(...); if (event != null) sink.deliver(event);`. `answered(call, approval)` narrates `TurnEvent.ToolCallDecided(call, approval)` on `turn` and returns `new AgentEvent.ApprovalAnswered(call, Optional.empty(), approval)`. `runPastGate` is today's `executePastGate` renamed. `address(call, responseId)` builds the `CallAddress`.

The executor takes an `ApprovalContexts` factory instead of an `Approver`:

```java
/** Builds the per-call ApprovalContext; the Continuum-backed one lives in the agent package. */
@FunctionalInterface
public interface ApprovalContexts {
  ApprovalContext contextFor(ToolCall call, ModelResponseId responseId, ApprovalRequest request, Sink sink);
}
```

Put `ApprovalContexts` in `org.jwcarman.nessy.agent.spi`. Constructors: the 6-arg form defaults to a factory whose `defer()` throws `IllegalStateException(APPROVAL_UNAVAILABLE)` — a wiring with no Continuum cannot park, and says so loudly (this replaces the "refuses in-band" default; the executor's `approver failed:` catch turns the throw into a denial the model reads). Keep the field `mapper` (the pinned `ObjectMapper`) beside `codecs`.

- [ ] **Step 5: `ComputationApprovalContext` — `defer()` does the plumbing**

```java
package org.jwcarman.nessy.agent;

/**
 * The Continuum-backed door behind {@link ApprovalContext#defer()} (approval-lifecycle spec §1.3):
 * creates the approval computation with this call's routing and the frozen request as its
 * continuation, folds {@link AgentEvent.ApprovalDeferred} through the sink — synchronously, so
 * the phase names the ask before this returns — and hands back the outcome. Idempotent.
 */
final class ComputationApprovalContext implements ApprovalContext {

  private final ContinuumClient<Approval, ApprovalRouting> client;
  private final Routing routing;
  private final ApprovalRequest request;
  private final Sink sink;
  private ApprovalOutcome deferred;

  ComputationApprovalContext(
      ContinuumClient<Approval, ApprovalRouting> client, Routing routing, ApprovalRequest request, Sink sink) {
    this.client = Objects.requireNonNull(client, "client must not be null");
    this.routing = Objects.requireNonNull(routing, "routing must not be null");
    this.request = Objects.requireNonNull(request, "request must not be null");
    this.sink = Objects.requireNonNull(sink, "sink must not be null");
  }

  @Override
  public ApprovalRequest request() {
    return request;
  }

  @Override
  public synchronized ApprovalOutcome defer() {
    if (deferred != null) {
      return deferred;
    }
    Computation created = client.create(new ApprovalRouting(routing, request));
    ComputationId id = ComputationId.of(created.id().value().toString());
    sink.deliver(new AgentEvent.ApprovalDeferred(routing.call(), id, request)); // folds now
    deferred = new ApprovalOutcome.Deferred(id);
    return deferred;
  }
}
```

`Sink.deliver` is `DefaultAgent::deliver` (or the worker's `binder.deliver`), which applies the event on the calling thread — that is the "waits for the fold to commit" guarantee, by construction.

`ApprovalRouting(Routing routing, ApprovalRequest request)` — a record with `codec(mapper)` in the `Routing` style (encode the record; on decode, rebuild with `request.facts().attach(mapper)`). `ApprovalCodec.codec(mapper)` replaces `DecisionCodec`: `{"type":"APPROVED"|"DENIED","reason":...,"reference":...}` with `reference` omitted when empty.

`HarnessConfig` wires: `ContinuumClient<Approval, ApprovalRouting> effectiveApprovalClient = effectiveContinuum.client(Kinds.approval(agentType), Approval.class, ApprovalRouting.class, cfg -> cfg.resultCodec(ApprovalCodec.codec(pinned)).continuationCodec(ApprovalRouting.codec(pinned)).deadline(APPROVAL_DEADLINE));` and the tool executor factory becomes:

```java
            (scopeId, scopeTurnObserver) ->
                new RegistryToolCallExecutor(
                    registry,
                    agentType,
                    scopeId,
                    scopeTurnObserver,
                    exec,
                    new ComputationDeferredToolCallPolicy(effectiveToolClient),
                    (call, responseId, request, sink) ->
                        new ComputationApprovalContext(
                            effectiveApprovalClient,
                            new Routing(agentType.name(), scopeId.value(), responseId.value(), call),
                            request,
                            sink),
                    pinned),
```

Delete the `approvalNotifier` field, its setter, the capturing wrapper and `DispatchIndex` construction. `approvalWaiters` becomes `ConcurrentMap<AgentId, CompletableFuture<TurnOutcome.Parked>>` and is still created here and passed to `Harness.of`. Remove `Kinds.dispatchIndex`.

- [ ] **Step 6: `DefaultAgent` — dispatch, parking, remembrance**

`dispatch`:

```java
  private void dispatch(Effect effect, Phase phase) {
    switch (effect) {
      case Effect.CallModel _ -> model.callModel(this::deliver);
      case Effect.SeekApproval(var call) -> tools.seekApproval(call, responseIdOf(phase), this::deliver);
      case Effect.RunTool(var call) -> tools.runTool(call, responseIdOf(phase), this::deliver);
    }
  }
```

`applyOnce`, after `observer.applied(event, t)`: `if (event instanceof AgentEvent.ApprovalDeferred(var _, var approval, var request)) { harness.parked(binding.id(), new TurnOutcome.Parked(approval, request)); }` — the fold is the park. `ask()` keeps its shape: `CompletableFuture<TurnOutcome.Parked> approvalWait = harness.awaitApproval(id); approvalWait.thenAccept(outcome::complete);`.

`remember`: add `case AgentEvent.ApprovalDeferred _, AgentEvent.ApprovalAnswered _, AgentEvent.ToolDeferred _ -> { /* no message committed; nothing to remember */ }` and pass `outcome` through to `ToolFoldRemembrance` for `ToolFinished(var call, var _, var outcome)`.

`Harness`: `awaitApproval`/`cancelApprovalWait` over `TurnOutcome.Parked`; add `void parked(AgentId id, TurnOutcome.Parked parked)` — `approvalWaiters.remove(id)` and complete it if present. `Harness.of` and the constructor drop `DispatchIndex`; `approvalClient` is `ContinuumClient<Approval, ApprovalRouting>`; `ApprovalDesk` is constructed with `(approvalClient, storeFactory, worker::nudge)`.

`TurnOutcome.Parked(ComputationId approval, ApprovalRequest request)`. The class javadoc's "resolves off-channel through the notifier" becomes "resolves from the `ApprovalDeferred` fold — the park is a fact." The module-placement note stays true (`ApprovalRequest` is now `nessy-api`, so the note can shrink to one sentence or go).

`ToolFoldRemembrance`: `CallAddress.digest()` (renamed from `indexKey()`) keys the `ToolExchange`; nothing else changes.

- [ ] **Step 7: `DeliveryWorker` — consumers that only fold**

Replace the approval consumer, `handleApprovalDecision`, `deliverApprovalGrant`, `foldApprovalFailure`, `isCurrentDispatch`, `foldApprovalResult`, `foldOps`, `foldToolOutcome`, `dispatchEffects` with:

```java
  private static final Lease APPROVAL_LEASE = Lease.ofSeconds(30);   // one fold, never a tool
  private static final Backoff APPROVAL_BACKOFF = Backoff.ofSeconds(5);

  @Override
  public int drainApprovals(BatchSize batchSize) {
    return approvalClient.deliverResults(
        batchSize,
        APPROVAL_LEASE,
        APPROVAL_BACKOFF,
        delivery -> {
          Routing routing = delivery.continuation().routing();
          ComputationId id = ComputationId.of(delivery.computationId().value().toString());
          Approval answer =
              switch (delivery.outcome()) {
                case TypedOutcome.Success<Approval> success -> success.value();
                case TypedOutcome.Failure<Approval> failure ->
                    new Approval.Denied(failure.message(), Optional.of("continuum:failure"));
                case TypedOutcome.Expired<Approval> expired ->
                    new Approval.Denied(expired.kind() + ": " + expired.message(), Optional.of("continuum:expired"));
              };
          fold(routing, new AgentEvent.ApprovalAnswered(routing.call(), Optional.of(id), answer), id);
        });
  }

  void foldOutcome(TypedDelivery<Routing, ToolResult> delivery) {
    Routing routing = delivery.continuation();
    ComputationId id = ComputationId.of(delivery.computationId().value().toString());
    fold(routing, new AgentEvent.ToolFinished(routing.call(), Optional.of(id), toToolOutcome(delivery.outcome())), id);
  }

  /**
   * One fold-advance for either kind: read state, reduce, remember, CAS-write, dispatch. An ignored
   * transition is acknowledged as stale — EXCEPT when the call is still Pending/Running for THIS
   * delivery's kind, which means the park has not folded yet (spec §4): throw, so Continuum
   * releases the delivery and re-delivers after the backoff, by which time it has.
   */
  private void fold(Routing routing, AgentEvent event, ComputationId delivered) {
    AgentType type = AgentType.of(routing.agentType());
    AgentId id = AgentId.of(routing.agentId());
    while (true) {
      State state = warnIfNoStoredState(id, readState(id));
      Transition transition = state.phase().handle(event);
      if (transition.isIgnored()) {
        if (isEarly(state.phase(), event)) {
          throw new EarlyDeliveryException(delivered);
        }
        return; // stale or duplicate — acknowledged
      }
      if (event instanceof AgentEvent.ToolFinished(var call, var _, var outcome)) {
        ToolFoldRemembrance.remember(harness.memoryFor(id), type, id, state.phase(), call, outcome, transition);
      }
      try {
        states.write(id.value(), transition.next(), state.version());
      } catch (ConflictException _) {
        continue; // lost the race — re-read and re-handle
      }
      dispatchEffects(type, id, transition.next(), transition.effects());
      return;
    }
  }

  private static boolean isEarly(Phase phase, AgentEvent event) {
    if (!(phase instanceof Phase.AwaitingTools awaiting)) {
      return false;
    }
    return switch (event) {
      case AgentEvent.ApprovalAnswered(var call, var _, var _) ->
          awaiting.calls().get(call.id()) instanceof CallStatus.Pending;
      case AgentEvent.ToolFinished(var call, var _, var _) ->
          awaiting.calls().get(call.id()) instanceof CallStatus.Running;
      case AgentEvent.Observed _, AgentEvent.ModelFinished _, AgentEvent.ApprovalDeferred _, AgentEvent.ToolDeferred _ -> false;
    };
  }

  private void dispatchEffects(AgentType type, AgentId id, Phase phase, List<Effect> effects) {
    for (Effect effect : effects) {
      switch (effect) {
        case Effect.CallModel _ -> harness.modelExecutorFor(id).callModel(event -> binder.deliver(type, id, event));
        case Effect.SeekApproval(var call) ->
            harness.toolExecutorFor(id).seekApproval(call, responseIdOf(phase), event -> binder.deliver(type, id, event));
        case Effect.RunTool(var call) ->
            harness.toolExecutorFor(id).runTool(call, responseIdOf(phase), event -> binder.deliver(type, id, event));
      }
    }
  }
```

`EarlyDeliveryException` is a package-private `RuntimeException` in the agent package carrying the id. `states.write(...)` — use whatever `DocumentStore<Phase>` door writes with CAS today (`writeOp` inside a one-op `store.batch` if there is no direct write). The constructor drops `DispatchIndex`; the class javadoc's two grant paragraphs go, replaced by one sentence: *"Both consumers fold; neither runs a tool (spec §5)."* The `APPROVAL_LEASE` javadoc changes to say why it is now short.

- [ ] **Step 8: `ApprovalDesk` — two doors, a principal, withdraw, request**

```java
public final class ApprovalDesk {

  private final ContinuumClient<Approval, ApprovalRouting> client;
  private final Function<String, AgentStateStore> stores;
  private final Runnable nudge;

  public ApprovalDesk(ContinuumClient<Approval, ApprovalRouting> client, Function<String, AgentStateStore> stores, Runnable nudge) { /* requireNonNull ×3 */ }

  /** Approves by id, on behalf of {@code principal}; {@code note} may be empty. */
  public void approve(ComputationId id, String principal, String note) {
    answer(id, new Approval.Approved(Optional.of(reference(principal, note))));
  }

  public void deny(ComputationId id, String principal, String reason) {
    answer(id, new Approval.Denied(reason, Optional.of(reference(principal, ""))));
  }

  /** Approves the call {@code callId} the scope {@code id} is awaiting approval of. */
  public void approve(AgentId id, String callId, String principal, String note) {
    approve(awaiting(id, callId).approval(), principal, note);
  }

  public void deny(AgentId id, String callId, String principal, String reason) {
    deny(awaiting(id, callId).approval(), principal, reason);
  }

  /** Abandons a parked ask: folds as a denial the model reads, referenced "withdrawn". */
  public void withdraw(ComputationId id, String reason) {
    answer(id, new Approval.Denied("withdrawn: " + reason, Optional.of("withdrawn")));
  }

  /** The parked question for {@code callId} on {@code id} — the document the approver saw. */
  public ApprovalRequest request(AgentId id, String callId) {
    return awaiting(id, callId).request();
  }

  private CallStatus.AwaitingApproval awaiting(AgentId id, String callId) {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(callId, "callId must not be null");
    Phase phase = stores.apply(id.value()).load().phase();
    if (phase instanceof Phase.AwaitingTools awaiting
        && awaiting.calls().get(callId) instanceof CallStatus.AwaitingApproval parked) {
      return parked;
    }
    throw new IllegalStateException(
        "call " + callId + " on " + id.value() + " is not awaiting approval (phase: " + phase + ")");
  }

  private void answer(ComputationId id, Approval approval) {
    Objects.requireNonNull(id, "id must not be null");
    client.complete(ContinuumIds.continuumId(id.value()), approval);
    nudge.run();
  }

  private static String reference(String principal, String note) {
    Objects.requireNonNull(principal, "principal must not be null");
    if (principal.isBlank()) {
      throw new IllegalArgumentException("principal must not be blank — the desk does not take an anonymous answer");
    }
    Objects.requireNonNull(note, "note must not be null");
    return note.isBlank() ? "desk:" + principal : "desk:" + principal + ":" + note;
  }
}
```

`Console.Approver.decide(TurnOutcome.Parked parked)` renders `parked.request()` (agent coordinates, call name, arguments, action) and answers with `harness.approvals().approve(parked.approval(), "console", "")` / `deny(parked.approval(), "console", reason)`. `Console.settle` passes the `Parked` itself.

- [ ] **Step 9: `nessy-intent` and the examples**

`IntentEnricher<T>`: constructor `(IntentStore<T> store, Class<T> vocabulary)`; `enrich(draft)` deposits `store.latest()` under `IntentEnricher.declared(vocabulary)` — a `public static <T> Key<T> declared(Class<T> vocabulary)` returning `new Key<>(vocabulary, "intent.declared")`. The `IntentStore<T>` type parameter must expose `latest()` as `Optional<T>`; if it is `Optional<?>` today, narrow it. `IntentRules.requireDeclared(Class<T> vocabulary)` is a `Rule`: `Undecided` when the fact is present, `Denied("no <Vocabulary> declared — declare your intent with the declare-intent tool before acting")` otherwise. Delete `IntentPolicies`.

`Governed.restartGrant`:

```java
    return ToolGrant.grant(
        new RestartTool(),
        RESTART_ACTION,
        List.of(new IntentEnricher<>(intentStore, OpsIntent.class), riskAssessor()),
        Approvers.rules(
            IntentRules.requireDeclared(OpsIntent.class),
            RiskRules.threshold(RiskLevel.MODERATE, RiskLevel.VERY_HIGH)));
```

`riskAssessor()` deposits under `ApprovalRequest.RISK`. `Governed` used `approvalNotifier(approvalRequests::add)`; replace with an approver that *is* the notifier — the demo's point, now in its own vocabulary:

```java
  /** The demo's approver: parks, then tells the demo (a queue) — telling is the approver's job. */
  private static Approver queueing(BlockingQueue<TurnOutcome.Parked> ...)
```

— simplest: wrap the ladder: `Approvers.rules(...)` stays the judgment, and the grant's approver is `context -> { var outcome = ladder.approve(context); if (outcome instanceof ApprovalOutcome.Deferred d) approvalRequests.add(new Ask(d.id(), context.request())); return outcome; }` with a local `record Ask(ComputationId id, ApprovalRequest request)`. Same shape in `Approvals`: `Approvers.defer()` becomes `context -> { var d = context.defer(); printRequest(context.request(), d.id(), queue); return d; }`, and the approve/deny calls gain `"demo"` as principal.

- [ ] **Step 10: Compile the main sources**

Run: `./mvnw -q -pl nessy-api,nessy-spi,nessy-intent,nessy-agent -am compile`
Expected: clean. Fix any mismatch against the code above before touching a test.

- [ ] **Step 11: The reducer tests — `AwaitingToolsPhaseTest` rewritten to the §3 matrix**

Replace the file's tests with one per matrix row plus the two turn-level transitions. Fixture: an `assistantTurn` with two `ToolUseBlock`s (`c1`, `c2`), `responseId`, and a helper `awaiting(Map.of("c1", status1, "c2", status2))`. Every test asserts the exact `Transition` (next phase and effects) or `isIgnored()`. The rows, as test names:

```
pendingApprovedInProcessRunsTheTool
pendingDeniedInProcessFinishesWithAFailedResult
pendingDeferredBecomesAwaitingApproval
pendingWithADeliveredAnswerIsIgnoredAsEarly
awaitingApprovalApprovedByItsIdRunsTheTool
awaitingApprovalDeniedByItsIdFinishesWithAFailedResult
awaitingApprovalAnsweredUnderAnotherIdIsIgnoredAsStale
runningFinishedInProcessFinishes
runningDeferredBecomesAwaitingResult
runningWithADeliveredResultIsIgnoredAsEarly
awaitingResultFinishedByItsIdFinishes
awaitingResultFinishedUnderAnotherIdIsIgnoredAsStale
finishedIgnoresEverythingForThatCall
anUnknownCallIsIgnored
theLastCallFinishingCommitsTheTurnInTheAssistantsOrderAndCallsTheModel
aDeniedCallsResultIsAnErrorBlockCarryingTheReason
outstandingEffectsReseekPendingAndRerunRunningAndLeaveTheParkedOnesAlone
```

Write each in the file's existing camelCase voice with the assert style already there. `PhaseOutstandingEffectsTest`, `IdlePhaseTest`, `AwaitingModelPhaseTest`: `ExecuteTool` → `SeekApproval`; new ignore arms covered by one test each. `EventGrammarTest`: `ToolFinished` gains `Optional.empty()`; add construction tests for the three new events (null rejection each). `StateCodecTest`: the awaiting-tools fixture becomes `calls` with one of each status, including an `AwaitingApproval` whose request round-trips; assert the JSON's `type` discriminators by name.

- [ ] **Step 12: The executor, worker, desk and harness tests — mechanical rules**

Apply these rules to every test the grep in this task's header lists, then run the module:

1. `UsagePolicy.allow()/deny(r)/requireApproval()` → `Approvers.allow()/deny(r)/defer()`. `UsagePolicy.allOf(List.of(a, b))` → `Approvers.allOf(a, b)` when members answer, else `Approvers.rules(...)`.
2. `new AgentEvent.ToolFinished(call, outcome)` → `new AgentEvent.ToolFinished(call, Optional.empty(), outcome)`; `Effect.ExecuteTool` → `Effect.SeekApproval` where a dispatch is asserted, and a following `RunTool` where the test then expected the tool to run.
3. `ScriptedToolExecutor` implements both doors: `seekApproval` delivers `ApprovalAnswered(call, Optional.empty(), approved())` (or a scripted denial); `runTool` delivers the scripted `ToolFinished`. Add `deny(callId, reason)` and `defer(callId)` scripting doors; `executed()` keeps recording `runTool` calls only.
4. `.approvalNotifier(requests::add)` → a grant whose approver is `context -> { var d = context.defer(); requests.add(new Ask(d.id(), context.request())); return d; }` with a local `record Ask(ComputationId id, ApprovalRequest request)`; `harness.approvals().approve(ask.id())` → `.approve(ask.id(), "test", "")`; `deny(id, reason)` → `deny(id, "test", reason)`.
5. `TurnOutcome.Parked.ask()` → `.request()` / `.approval()`; `parked.ask().id()` → `parked.approval()`.
6. `Harness.of(...)`: remove the `dispatchIndex` argument; `TestApprovalClients.client(...)` returns `ContinuumClient<Approval, ApprovalRouting>` over `ApprovalCodec`/`ApprovalRouting.codec`.
7. `CallAddress.indexKey()` → `digest()` in `CallAddressTest`.
8. `RegistryToolCallExecutorTest`: the gate tests become two-door tests — `seekApproval` with a static approver delivers `ApprovalAnswered` without building a request (assert an enricher that throws is never called); with a throwing enricher delivers `Denied("authorization failed: ...")`; with `defer()` and a fake `ApprovalContexts` delivers `ApprovalDeferred` and nothing else; `runTool` runs and delivers `ToolFinished`; a tool returning `Awaited.Deferred` delivers `ToolDeferred`.
9. `ConsoleApproverTest`, `AgentAskTest.Parked`, `HarnessApprovalDemo`, `SharedContinuumTest`, `DurableResumeTest`, `ApprovalOnContinuumTest`, `DeferredToolOnContinuumTest`, `GovernedTurnDemo`, `TypedIntentDemo`, `NessyHarnessDoorTest`, `GrantRaceTest`, `AgentSubscriptionTest`, `DefaultAgentApplyTest`, `DefaultAgentRecoveryTest`, `DeliveryWorkerSilentLossWarningTest`, `HarnessDemo`: rules 1–7. Where a test asserted `Phase.AwaitingTools.pending()`/`gathered()`, assert `calls()` statuses instead. Where a test asserted the phase after a park, it now reads `AwaitingTools` with the call `AwaitingApproval`.
10. Delete `DispatchIndexTest`, `AbsorptionTest`, `GrantDeliveryPendingWindowTest`, `ComputationApproverTest`. Replace the last with `ComputationApprovalContextTest`: `defer()` creates one computation whose continuation is the request, delivers exactly one `ApprovalDeferred` through the sink *before* returning, and returns the same outcome on a second call.
11. `nessy-api` tests: `UsagePolicyTest`, `PolicyDecision`-related tests, `AuthzContextTest`, `RiskPoliciesTest` are deleted; `ToolGrantTest` asserts `request(...)` builds the action and runs enrichers in order and names a failing stage; `AuthorizationReportTest` expects `allow()`/`deny("…")`/class-name summaries; `EnrichersTest` deposits `PRINCIPAL`. `nessy-intent` tests: `IntentEnricher<T>`, `IntentRules`.

Run per module while iterating: `./mvnw -q -pl nessy-api test`, then `-pl nessy-intent -am test`, then `-pl nessy-agent -am test`, then the examples.

- [ ] **Step 13: Break-and-restore the two properties this task exists for**

(a) In `DeliveryWorker.fold`, replace the `isEarly` throw with `return;`. The reducer test cannot see this, so write the worker-level test now — `DeliveryWorkerEarlyDeliveryTest`: a scope whose call is `Pending`, a delivery of `ApprovalAnswered(id)` — assert `EarlyDeliveryException`. Watch it go red under the break; restore.
(b) In `ComputationApprovalContext.defer`, move `sink.deliver(...)` after the `deferred = ...` assignment and return — no; instead remove the `sink.deliver` line entirely and watch `ComputationApprovalContextTest` and `AgentAskTest.Parked` go red (the phase never records the park; `ask` hangs → the test's timeout fails it). Restore.

- [ ] **Step 14: Full verify**

Run: `./mvnw -q clean verify`
Expected: green, zero skipped. Record `nessy-agent`'s test total in the report (it was 364 + the day's additions).

- [ ] **Step 15: Commit in three groups**

```bash
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-api nessy-spi nessy-intent
git commit -m "feat: a grant carries an Approver; the request is built once and the approver reads it"
git add nessy-agent
git commit -m "feat: the approval lifecycle folds into the scope — per-call status, two effects, consumers that only fold"
git add nessy-examples
git commit -m "docs: the examples speak the approval vocabulary — the approver tells, the desk takes a principal"
```

---

### Task 3: The proofs the spec promised, and the testing kit

**Files:**
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/ScriptedApprover.java`
- Create: `nessy-testing/src/main/java/org/jwcarman/nessy/testing/RecordingApprover.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/SlowApprovedToolRunsOnceTest.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/PumpsAreNeverStarvedTest.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/host/EarlyAnswerTest.java`
- Create: `nessy-agent/src/test/java/org/jwcarman/nessy/agent/ApprovalDeskTest.java` (or extend if one exists)
- Test: `nessy-testing/src/test/java/org/jwcarman/nessy/testing/ScriptedApproverTest.java`

**Interfaces:**
- Consumes: Task 2's doors and events.
- Produces: `ScriptedApprover.answering(Approval...)`, `.deferring()`, `RecordingApprover.requests()`.

- [ ] **Step 1: The kit**

```java
package org.jwcarman.nessy.testing;

/** An approver that answers from a script, like ScriptedModel; when the script runs out it defers. */
public final class ScriptedApprover implements Approver {
  private final Deque<Approval> answers;
  private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

  public static ScriptedApprover answering(Approval... answers) { ... }
  public static ScriptedApprover deferring() { return answering(); }

  @Override
  public ApprovalOutcome approve(ApprovalContext context) {
    requests.add(context.request());
    Approval next = answers.poll();
    return next == null ? context.defer() : new ApprovalOutcome.Answered(next);
  }

  public List<ApprovalRequest> requests() { return List.copyOf(requests); }
}
```

`RecordingApprover` wraps any `Approver`, recording every request and outcome. `ScriptedApproverTest` covers: answers in order, then defers; requests recorded.

- [ ] **Step 2: A slow approved tool runs once**

Spec §10. A `Ready` tool that sleeps 3× the approval lease (Task 2 set it to 30s — for the test, construct the harness's worker through the package-private seam that lets a test shorten the lease, or drive the scenario with `PumpedExecutor` and assert on invocation count after the desk approves and the pumps have run twice). Assert `tool.invocations == 1` and the turn completed. The point: the delivery consumer returned before the tool ran, so nothing could re-claim it.

- [ ] **Step 3: Two slow grants do not starve the harness**

Park two approvals whose tools each block on a latch; approve both; while they block, complete an unrelated deferred tool through `harness.completions()` and assert its result folds (phase reaches `Finished` for that call) before the latches release.

- [ ] **Step 4: Early answers**

An approver whose `defer()` outcome is answered *inside* `approve` (call `harness.approvals().approve(id, "test", "")` before returning `Deferred`) — assert the call still resolves: the park folded before `defer()` returned, so the desk found `AwaitingApproval`. And the by-coordinates door against a `Pending` call throws `IllegalStateException` containing "not awaiting approval".

- [ ] **Step 5: The desk**

By-id and by-coordinates reach the same fold; blank principal is refused; `withdraw` folds a denial whose reason starts with "withdrawn:"; `request(id, callId)` returns the document the approver saw (equal to `ScriptedApprover.requests().getFirst()`).

- [ ] **Step 6: Full verify, then commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add nessy-testing nessy-agent
git commit -m "test: the proofs — a slow approved tool runs once, pumps are never starved, early answers resolve, the desk"
```

---

### Task 4: Documentation

**Files:**
- Modify: `docs/concepts/durable-computation.md`, `docs/guides/harness.md`, `docs/guides/providers.md`, `CHANGELOG.md`
- Modify: `docs/superpowers/specs/2026-08-18-agent-as-scope-design.md`, `2026-08-20-action-and-tool-vocabulary.md`, `2026-08-24-continuum-adoption-design.md` — one appended amendment each
- Modify: `docs/superpowers/specs/2026-08-25-approval-lifecycle-design.md` §1.6 — `request(id)` is by coordinates (the Continuum client has no read door); note it

- [ ] **Step 1: `durable-computation.md`** — the "parks are an address book" section becomes "a call's lifecycle is in the phase"; the dispatch-index and notifier passages go; the audit division (spec §7) gets a short section.
- [ ] **Step 2: `harness.md`** — `.approvalNotifier` bullet removed; grants take an approver; a "Writing an approver" section with the Slack example and the rule ladder verbatim from the spec; the sharing rule's Continuum half reworded to "loud, not silent".
- [ ] **Step 3: `providers.md`** — `ApprovalPlayground` walkthrough narrates the wait.
- [ ] **Step 4: `CHANGELOG.md`** — under Unreleased: the vocabulary collapse (breaking), the phase's per-call statuses and the wire-format change to `awaiting-tools`, the desk's new doors, the retirements.
- [ ] **Step 5: the three amendments** — each two paragraphs pointing at the new spec, in the voice the earlier amendments use.
- [ ] **Step 6: verify and commit**

```bash
./mvnw -q clean verify
./mvnw license:format -Plicense && ./mvnw spotless:apply
git add docs CHANGELOG.md
git commit -m "docs: every call is approved — concepts, guides, changelog, and three spec amendments"
```

---

## Self-review

**Spec coverage.** §1.1 `Approval` → T1. §1.2 request/facts/draft → T1; `Enricher` over the draft → T2. §1.3 facade + `defer()` plumbing → T1 (types) + T2 (`ComputationApprovalContext`). §1.4 built-ins, `Static`, rules, `allOf`, kit → T1 + T3. §1.5 grant → T2; no deadline → nothing. §1.6 desk → T2 (with `request` by coordinates; §1.6's by-id `request` is corrected in T4 because the client has no read door). §2 phase → T2. §3 events/effects/matrix → T2 (`AwaitingToolsPhaseTest`). §4 doors, static short-circuit, ordering inside `defer()`/`runTool`, early release → T2 (`EarlyDeliveryException`) + T3. §5 → T2. §6 re-fire/re-enrich → T2 (`outstandingEffects`) + T3. §7 audit → T2 (`reference`, desk principal). §8 retirements → T2. §10 tests → T2 + T3. §11 docs → T4.

**Placeholder scan.** Step bodies that say "as today" refer to code the implementer has open in the same file; none says "implement later."

**Type consistency.** `ApprovalOutcome.Deferred(ComputationId id)` in T1 is what `ComputationApprovalContext` returns in T2 and `ScriptedApprover` forwards in T3. `ApprovalAnswered(call, Optional<ComputationId>, Approval)` is what the executor delivers (empty) and the worker delivers (present). `ToolFinished` gained the optional id everywhere it is constructed (rule 2). `Harness.of` lost `dispatchIndex` in both `HarnessConfig` and the seven test callers (rule 6).
