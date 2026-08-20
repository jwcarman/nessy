# Durable Computation and Agent Execution Specification

> **Reconciliation preamble (binding) — 2026-08-20.** This document entered the repository as a
> companion to `2026-08-18-agent-as-scope-design.md`, which remains the design of record where the
> two overlap. This preamble governs the body below.
>
> **The thesis, sharpened:** a durable computation is one whose *work*, not merely whose wait, is
> node-agnostic. Any node may perform a pending computation (claims arbitrate execution), any node
> may complete it (the single PENDING→terminal flip arbitrates completion), and any node may resume
> its waiter (bind). This is agent-as-scope's "the store is the lock" extended from state to work.
> The §4.1 summary claim-write, the park desk, and desk expiry are all instances of this one
> pattern.
>
> **Adopted** (Plan 4 builds on these): the computation primitive and its three lifetimes (§6);
> `CompletionPolicy` and its hierarchy (§5) — the name for what agent-as-scope called "parking is
> a capability of the wiring"; outcomes as values (§8); the backend SPI (§9) and one-flip lifecycle
> (§10); continuations as data (§11); race-free await (§12); tool capability declaration and
> registry filtering (§14) — which supersedes fail-loudly as the *first* line of defense, with the
> in-band loud failure retained as the backstop; the SQL reference (§17–24), whose outbox is the
> prompt-delivery upgrade over the lazy re-drive floor; timeouts (§31), cancellation (§32),
> retention (§33), observability (§34).
>
> **Superseded by agent-as-scope** (do not implement from this document): §13's
> `Computation`-returning `ToolCallExecutor` — the push-shaped Sink seam stands, with the durable
> backend consumed *inside* the executor; §15's runtime-inspects-the-kind behavior — the shell
> never learns what kind of computation a tool was; §16's `AgentRunState` — history belongs to
> `Memory`, control state is `Phase` + version, and `RUNNABLE/RUNNING/SUSPENDED` status enums are
> rejected per reversal §10.3 (suspension is deliberately unrepresentable: a parked call is an
> ordinary pending call); `AgentRunId` — the scope coordinate `(AgentType, AgentId)` is the
> identity, and the continuation is `ResumeScope(type, id, toolCallId)`, which is the desk entry.
>
> **Rulings woven in after review (2026-08-20, binding):**
>
> 1. **The computation model is two-armed, not three.** `AttachedComputation` (§6.2) is a
>    `CompletionStage`-era artifact: under the push-shaped sink seam, virtual threads, and
>    recovery-as-retry, "locally awaitable" is every executor's default posture between dispatch
>    and delivery, not a category. The two cases that differ semantically are **in-process**
>    (crash ⇒ re-execute; the recovery arm re-fires, tools are idempotent per the at-least-once
>    contract) and **durable** (crash ⇒ resume; the outcome survives in the slot and the work is
>    NOT re-run). "Immediate" is the degenerate fast case of each, not a third row. The
>    `CompletionPolicy` names keep all three tiers for tool declaration and filtering, but only
>    the AWAITABLE/DURABLE boundary gates behavior.
> 2. **The continuation vocabulary is fully generic.** The backend stores
>    `(continuation_type, continuation_data)` as opaque rows; a **continuation dispatcher** maps
>    registered types to handlers. Agent resumption is one registered handler
>    (`RESUME_SCOPE` → bind `(AgentType, AgentId)`, deliver the completion event) contributed by
>    the agent layer — the primitive knows nothing about agents.
> 3. **Exactly-once is layered, never promised of execution.** Completion: one PENDING→terminal
>    flip — the outcome exists exactly once. Delivery attempt: outbox lease (or the lazy re-drive
>    floor) — usually one at a time, an optimization. Receipt: completion-id dedup in the phase
>    fold plus the state store's version CAS — the *effect* happens exactly once. Residue:
>    commit-before-save can duplicate a committed message on a lost race — the accepted §5.2
>    class, which the lease narrows. Exactly-once *execution* of the callback is a non-goal (§3),
>    honestly.
> 4. **Durable computations have deterministic identity per logical piece of work** (submit-once
>    discipline): the slot id derives from the work's coordinates (scope + call id, scope +
>    version), never a fresh random id per attempt, so a recovery re-fire finds the existing slot
>    and re-`await`s idempotently instead of double-submitting the work. Without this rule the
>    sweep double-spends.
> 5. **The consumer roster** this primitive serves: HITL approvals (the park desk is the first
>    consumer; the token is the completion capability), batch/async model calls (submit-once,
>    resume-not-redo), durable timers (a slot with only a deadline), **subagent callbacks** (the
>    child's terminal turn completes the parent's slot — answering the parked callback-desk
>    question from the store rework), and external jobs completing via webhook.
>
> **Removed** (ruled 2026-08-20): the Restate and Temporal backend sections and their comparison.
> Workflow runtimes hosting the agent contradict the architecture (§29 as rewritten); provider
> backends beyond SQL are future work with no binding text here.


**Status:** Draft  
**Audience:** Agent runtime and infrastructure implementers  
**Primary language:** Java  
**Purpose:** Define a common computation model supporting immediate, attached asynchronous, and durable asynchronous execution for agent tool calls.

---

## 1. Overview

The agent runtime must support three execution environments:

1. **Direct/CLI invocation**, where the caller may block until the agent completes.
2. **Interactive web invocation**, where execution may be asynchronous but remains attached to the current application/process lifecycle.
3. **Autonomous execution**, where an agent may wait hours or days for a tool, external system, human, timer, or other agent and must survive process restarts.

The fundamental abstraction is a **computation**.

A computation represents a value that is either:

- available immediately,
- expected asynchronously within the lifetime of the current process, or
- expected asynchronously across process lifetimes.

The runtime MUST distinguish the last case because a Java `Future`, `CompletionStage`, thread, callback, or stack frame cannot safely represent work that may survive a JVM restart.

The durable case therefore uses:

> **stable computation identity + persisted outcome + durable continuation**

rather than an in-memory callback.

---

# 2. Design goals

The design SHOULD:

- Preserve a lightweight path for CLI and interactive web execution.
- Avoid requiring a database or workflow engine for ordinary asynchronous calls.
- Allow durable work to survive JVM/process/infrastructure restarts.
- Provide a common abstraction to `ToolCallExecutor`.
- Make durable execution an explicit capability of an agent invocation.
- Allow SQL, Restate, Temporal, or another backend to provide durability.
- Keep tool implementations unaware of CLI, HTTP, or autonomous-agent concerns.
- Prevent missed-completion races.
- Support at-least-once continuation delivery.
- Make completion idempotent.
- Treat tool errors as values the agent can reason about.
- Allow the same mechanism to eventually support tools, human approval, timers, child agents, jobs, and other asynchronous work.

---

# 3. Non-goals

The initial abstraction does NOT attempt to provide:

- arbitrary serialization of Java lambdas;
- persistence of JVM stacks or threads;
- exactly-once execution of arbitrary side effects;
- distributed transactions with arbitrary external systems;
- a complete workflow language;
- transparent durability of arbitrary Java code.

Durability occurs only at defined agent-runtime boundaries.

---

# 4. Terminology

### Computation

An operation expected to eventually produce a terminal outcome.

### Attached computation

A computation whose pending state exists only within the current application/process lifetime.

Typically represented by `CompletionStage<T>`.

### Durable computation

A computation identified by a persistent identifier whose outcome can be retrieved after the originating JVM no longer exists.

### Promise

The capability to complete a durable computation.

### Continuation

A durable description of **what becomes runnable when a computation completes**.

A continuation is data, not Java executable state.

### Agent run

A particular execution of an agent.

### Suspension

The state of an agent run that cannot currently make progress because it is waiting for a durable computation.

---

# 5. Invocation completion policy

Each agent invocation MUST declare the strongest computation semantics it supports.

```java
public enum CompletionPolicy {

    /**
     * Only computations already completed when returned are permitted.
     */
    IMMEDIATE,

    /**
     * Immediate and process-local asynchronous computations are permitted.
     */
    AWAITABLE,

    /**
     * Immediate, process-local asynchronous, and durable computations
     * are permitted.
     */
    DURABLE
}
```

The policies form an increasing capability hierarchy:

```text
IMMEDIATE ⊂ AWAITABLE ⊂ DURABLE
```

Typical mappings are:

| Invocation | Policy |
|---|---|
| Direct synchronous API | `IMMEDIATE` or `AWAITABLE` |
| CLI | `AWAITABLE` |
| Interactive HTTP/SSE/WebSocket | `AWAITABLE` |
| HTTP async-job endpoint | `DURABLE` |
| Autonomous agent | `DURABLE` |

A durable computation MUST NOT be returned when the enclosing invocation is incapable of durable suspension.

---

# 6. Computation model

The common return type from an executable operation SHOULD explicitly represent the three lifetime semantics.

```java
public sealed interface Computation<T>
        permits CompletedComputation,
                AttachedComputation,
                DurableComputation {
}
```

## 6.1 Completed computation

```java
public record CompletedComputation<T>(
    Outcome<T> outcome
) implements Computation<T> {
}
```

The result is available immediately.

## 6.2 Attached computation

```java
public record AttachedComputation<T>(
    CompletionStage<Outcome<T>> completion
) implements Computation<T> {
}
```

The computation may complete asynchronously, but its lifecycle is tied to the current JVM.

If the JVM terminates, no completion guarantee exists.

## 6.3 Durable computation

```java
public record DurableComputation<T>(
    ComputationId id,
    TypeReference<T> resultType
) implements Computation<T> {
}
```

`DurableComputation` MUST contain no required JVM-local execution state.

It MUST be possible to persist the reference, deserialize it in another JVM, and refer to the same logical computation.

---

# 7. Computation identity

```java
public record ComputationId(UUID value) {
}
```

Implementations MAY use another identifier representation, provided identifiers are:

- globally unique within the backend;
- stable for the retention period;
- serializable;
- safe for correlation across processes.

Provider-specific IDs MAY be wrapped:

```java
public record ComputationId(
    String provider,
    String value
) {
}
```

For example:

```text
sql:0195...
restate:sign_1PePOqp
temporal:workflow/activity/token-reference
```

Provider-specific values SHOULD generally remain opaque to application code.

---

# 8. Outcomes

Normal success and failure of the represented work MUST be modeled as outcomes rather than harness exceptions.

```java
public sealed interface Outcome<T>
        permits Success, Failure, Cancelled {
}

public record Success<T>(
    T value
) implements Outcome<T> {
}

public record Failure<T>(
    FailureInfo failure
) implements Outcome<T> {
}

public record Cancelled<T>(
    String reason
) implements Outcome<T> {
}
```

Example failure representation:

```java
public record FailureInfo(
    String code,
    String message,
    boolean retryable,
    Map<String, Object> metadata
) {
}
```

Exceptions SHOULD represent failures of the computation infrastructure itself rather than normal failure of the tool.

For example:

```text
GitHub tool returned HTTP 403
    -> Failure

SQL connection required by DurableComputationBackend is unavailable
    -> infrastructure exception
```

---

# 9. Durable computation backend SPI

The durable implementation SHOULD sit behind a provider-neutral SPI.

```java
public interface DurableComputationBackend {

    <T> DurablePromise<T> create(
        TypeReference<T> resultType
    );

    <T> ComputationSnapshot<T> get(
        DurableComputation<T> computation
    );

    <T> AwaitResult<T> await(
        DurableComputation<T> computation,
        Continuation continuation
    );

    <T> CompletionResult complete(
        ComputationId id,
        Outcome<T> outcome
    );
}
```

A created promise exposes a consumer reference:

```java
public interface DurablePromise<T> {

    DurableComputation<T> computation();

    CompletionResult complete(T value);

    CompletionResult fail(FailureInfo failure);

    CompletionResult cancel(String reason);
}
```

The ability to complete a promise MAY be secured separately from the public computation ID.

---

# 10. Durable computation lifecycle

The durable computation itself has a deliberately small lifecycle:

```java
public enum ComputationStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
```

A computation MUST make at most one transition from:

```text
PENDING
```

to a terminal state:

```text
SUCCEEDED
FAILED
CANCELLED
```

No terminal-to-terminal transition is permitted.

States such as `RUNNING`, `RETRYING`, or `WAITING_FOR_CALLBACK` belong to the producer/execution subsystem, not this primitive.

---

# 11. Continuations

A durable continuation MUST be serializable data.

It MUST NOT be represented as:

```java
Runnable
Consumer<T>
Function<T, ?>
CompletableFuture<T>
lambda
```

because those objects cannot safely survive process termination.

Instead:

```java
public sealed interface Continuation
        permits ResumeAgentRun {
}

public record ResumeAgentRun(
    AgentRunId runId,
    ToolCallId toolCallId
) implements Continuation {
}
```

The semantic meaning is:

> When this computation becomes terminal, make Agent Run X runnable again.

The continuation does not describe arbitrary Java instructions.

---

# 12. Await semantics

Registration of a continuation and observation of completion MUST be race-free.

This sequence is NOT allowed:

```java
var snapshot = backend.get(computation);

if (!snapshot.isComplete()) {
    backend.registerContinuation(computation.id(), continuation);
}
```

because completion may occur between the two operations.

Instead the backend MUST provide one logical atomic operation:

```java
AwaitResult<T> await(
    DurableComputation<T> computation,
    Continuation continuation
);
```

For example:

```java
public sealed interface AwaitResult<T> {

    record Registered<T>()
        implements AwaitResult<T> {
    }

    record AlreadyCompleted<T>(
        Outcome<T> outcome
    ) implements AwaitResult<T> {
    }
}
```

The backend MUST guarantee exactly one of:

```text
A. The computation was already terminal and its outcome is returned.

OR

B. The computation remained pending and the continuation was durably
   registered before completion could proceed.
```

There MUST NOT be a state where the computation has completed but its waiter can be permanently missed.

---

# 13. ToolCallExecutor integration

The tool seam becomes:

```java
public interface ToolCallExecutor {

    Computation<ToolCallResult> execute(
        ToolCall call,
        ToolCallContext context
    );
}
```

`ToolCallContext` includes the invocation capability:

```java
public record ToolCallContext(
    CompletionPolicy completionPolicy,
    AgentRunId runId,
    ToolCallId toolCallId
) {
}
```

A tool may return any computation supported by the policy.

### Immediate example

```java
return new CompletedComputation<>(
    new Success<>(result)
);
```

### Attached example

```java
return new AttachedComputation<>(
    client.callAsync()
        .thenApply(Success::new)
);
```

### Durable example

```java
DurablePromise<ToolCallResult> promise =
    backend.create(TOOL_CALL_RESULT);

externalSystem.submit(
    request,
    callbackFor(promise.computation().id())
);

return promise.computation();
```

---

# 14. Tool capability declaration

Tools that inherently require durable execution SHOULD declare it in metadata.

```java
public record ToolDescriptor(
    String name,
    CompletionPolicy requiredPolicy
) {
}
```

The runtime SHOULD avoid exposing tools whose requirements exceed the current invocation policy.

For example:

```text
calculator                  IMMEDIATE
web-search                  AWAITABLE
long-running-build          DURABLE
request-human-approval      DURABLE
```

An interactive web run using `AWAITABLE` should therefore not expose `request-human-approval` to the model.

This is preferable to letting the model invoke an operation that cannot be supported.

---

# 15. Agent runtime behavior

## 15.1 Completed computation

```java
case CompletedComputation<ToolCallResult>(var outcome) ->
    continueAgent(outcome);
```

## 15.2 Attached computation

```java
case AttachedComputation<ToolCallResult>(var future) ->
    future.thenAccept(this::continueAgent);
```

The agent remains logically active.

No durable suspension is required.

## 15.3 Durable computation

The runtime registers itself as a continuation:

```java
var continuation =
    new ResumeAgentRun(runId, toolCallId);

switch (backend.await(computation, continuation)) {

    case AlreadyCompleted(var outcome) ->
        continueAgent(outcome);

    case Registered() ->
        suspendAgent(runId, computation.id());
}
```

The run is then persisted as suspended and execution stops.

No thread or Java stack remains blocked.

---

# 16. Agent run state

A durable agent runtime SHOULD persist at least:

```java
public record AgentRunState(
    AgentRunId id,
    AgentRunStatus status,
    List<Message> messages,
    Optional<PendingToolCall> pendingToolCall
) {
}
```

For example:

```java
public record PendingToolCall(
    ToolCallId toolCallId,
    ComputationId computationId
) {
}
```

Relevant run states might include:

```text
RUNNABLE
RUNNING
SUSPENDED
COMPLETED
FAILED
CANCELLED
```

When resumed, the runtime loads the run state, obtains the durable computation outcome, adds the tool result to the agent context, and proceeds to the next model invocation.

---

# 17. SQL reference implementation

A relational implementation can provide the reference semantics without requiring a workflow platform.

PostgreSQL is assumed below, although the design is portable.

## 17.1 Durable computation table

```sql
CREATE TABLE durable_computation (
    computation_id      UUID PRIMARY KEY,
    result_type         VARCHAR(255) NOT NULL,

    status              VARCHAR(32) NOT NULL
        CHECK (status IN (
            'PENDING',
            'SUCCEEDED',
            'FAILED',
            'CANCELLED'
        )),

    outcome_payload     JSONB,
    failure_payload     JSONB,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,

    version             BIGINT NOT NULL DEFAULT 0,

    CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR
        (status <> 'PENDING' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_durable_computation_status
    ON durable_computation(status);
```

Production implementations may choose `BYTEA` rather than `JSONB` for serialized values.

---

# 18. Continuation table

```sql
CREATE TABLE computation_continuation (
    continuation_id     UUID PRIMARY KEY,
    computation_id      UUID NOT NULL
        REFERENCES durable_computation(computation_id),

    continuation_type   VARCHAR(128) NOT NULL,
    continuation_data   JSONB NOT NULL,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (
        computation_id,
        continuation_type,
        continuation_data
    )
);

CREATE INDEX idx_continuation_computation
    ON computation_continuation(computation_id);
```

Example `continuation_data`:

```json
{
  "agentRunId": "...",
  "toolCallId": "..."
}
```

---

# 19. Continuation outbox

Completion and scheduling MUST NOT rely on:

```text
commit database
then publish message
```

because the process could die between the two operations.

Use a transactional outbox.

```sql
CREATE TABLE continuation_outbox (
    outbox_id            UUID PRIMARY KEY,
    continuation_id      UUID NOT NULL
        REFERENCES computation_continuation(continuation_id),

    available_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    leased_until         TIMESTAMPTZ,
    delivered_at         TIMESTAMPTZ,

    delivery_attempts    INTEGER NOT NULL DEFAULT 0,

    UNIQUE (continuation_id)
);

CREATE INDEX idx_outbox_ready
    ON continuation_outbox(available_at)
    WHERE delivered_at IS NULL;
```

---

# 20. Race-free SQL `await`

The computation row can act as the serialization point.

Pseudo-SQL:

```sql
BEGIN;

SELECT status,
       outcome_payload,
       failure_payload
FROM durable_computation
WHERE computation_id = :computation_id
FOR UPDATE;
```

If terminal:

```text
return AlreadyCompleted(outcome)
```

and:

```sql
COMMIT;
```

If pending:

```sql
INSERT INTO computation_continuation (
    continuation_id,
    computation_id,
    continuation_type,
    continuation_data
)
VALUES (
    :continuation_id,
    :computation_id,
    'RESUME_AGENT_RUN',
    :continuation_data
)
ON CONFLICT DO NOTHING;

COMMIT;
```

Because completion also locks the computation row, registration and completion are serialized.

This prevents the lost-wakeup race.

---

# 21. Atomic SQL completion

Completion SHOULD lock the same computation row.

```sql
BEGIN;

SELECT status,
       outcome_payload
FROM durable_computation
WHERE computation_id = :computation_id
FOR UPDATE;
```

If pending:

```sql
UPDATE durable_computation
SET status = 'SUCCEEDED',
    outcome_payload = :payload,
    completed_at = now(),
    version = version + 1
WHERE computation_id = :computation_id;
```

Then queue all continuations within the same transaction:

```sql
INSERT INTO continuation_outbox (
    outbox_id,
    continuation_id
)
SELECT gen_random_uuid(),
       continuation_id
FROM computation_continuation
WHERE computation_id = :computation_id
ON CONFLICT (continuation_id) DO NOTHING;
```

Finally:

```sql
COMMIT;
```

The critical invariant is:

> The terminal outcome and the durable scheduling of all registered continuations commit atomically.

---

# 22. Outbox worker

Workers claim continuation deliveries using row locking:

```sql
BEGIN;

SELECT outbox_id,
       continuation_id
FROM continuation_outbox
WHERE delivered_at IS NULL
  AND available_at <= now()
  AND (
      leased_until IS NULL
      OR leased_until < now()
  )
ORDER BY available_at
FOR UPDATE SKIP LOCKED
LIMIT 100;
```

Claim:

```sql
UPDATE continuation_outbox
SET leased_until = now() + interval '30 seconds',
    delivery_attempts = delivery_attempts + 1
WHERE outbox_id = ANY(:claimed_ids);

COMMIT;
```

The worker then resolves the continuation:

```text
ResumeAgentRun(runId)
        |
        v
mark AgentRun RUNNABLE
        |
        v
enqueue/schedule run
```

After successful delivery:

```sql
UPDATE continuation_outbox
SET delivered_at = now(),
    leased_until = NULL
WHERE outbox_id = :outbox_id;
```

Delivery is **at least once**.

Consumers MUST therefore be idempotent.

---

# 23. Idempotent completion

Distributed completion can be retried.

For example:

```text
external webhook
    -> timeout
    -> sender retries
```

Both requests may contain the same completion.

Completion SHOULD therefore return something like:

```java
public sealed interface CompletionResult {

    record Completed()
        implements CompletionResult {}

    record AlreadyCompleted()
        implements CompletionResult {}

    record Conflict()
        implements CompletionResult {}
}
```

Recommended semantics:

```text
complete(X), complete(X)
    -> idempotent

complete(X), complete(Y)
    -> conflict
```

Payload hashes or explicit idempotency keys MAY be stored to distinguish these cases.

---

# 24. Resumption

Continuation delivery MUST NOT directly depend upon the process that completed the computation.

The preferred sequence is:

```text
computation completes
        |
        v
persist terminal outcome
        |
        v
persist continuation delivery
        |
        v
worker processes continuation
        |
        v
AgentRun becomes RUNNABLE
        |
        v
agent worker claims run
        |
        v
load run state
        |
        v
load ToolCall outcome
        |
        v
append tool result
        |
        v
continue agent loop
```

This decoupling allows either side to crash safely.

---

# 29. Backend architecture rule

**Harness-managed execution is the architecture** (ruled 2026-08-20): the agent state machine —
the phase fold, the shell, the store CAS — is owned by the Nessy harness, and the durable
computation backend sits *behind* the `ToolCallExecutor` seam as a collaborator. Hosting the agent
inside a workflow runtime is rejected: it would dissolve the shell, the phase machine, and the
store-is-the-lock model that agent-as-scope §3 establishes.

```text
Agent state machine (nessy-agent)
      |
      v
DurableComputationBackend (SQL reference, §17-24)
      |
      v
continuation delivery: lazy re-drive (agent-as-scope §6.1) as the floor,
                       the §19 outbox as the prompt-delivery upgrade
```

# 30. Backend capability abstraction

The SPI MAY expose backend characteristics:

```java
public interface DurableExecutionBackend {

    BackendCapabilities capabilities();

}
```

```java
public record BackendCapabilities(
    boolean externalCompletion,
    boolean durableTimers,
    boolean nativeWorkflowSuspension,
    boolean humanSignals,
    boolean childComputations
) {
}
```

This permits:

additional backends may exist without assuming all providers use identical mechanics; the SQL
reference implementation is the only one this specification binds.

---

# 31. Timeout semantics

Durable computations SHOULD optionally support an absolute deadline.

```java
public record DurableComputationOptions(
    Optional<Instant> deadline
) {
}
```

A timeout SHOULD produce a terminal outcome such as:

```text
FAILED/TIMEOUT
```

or:

```text
CANCELLED
```

according to framework policy.

The timeout itself MUST be durable.

A JVM-local `ScheduledExecutorService` is insufficient for multi-day computation.

SQL implementations may maintain a deadline column and scheduler.



---

# 32. Cancellation

Cancellation SHOULD be best effort.

Cancelling the durable computation means:

```text
consumer is no longer interested in the result
```

It does not inherently guarantee cancellation of an external side effect.

The producer MAY provide cancellation integration separately.

The durable state machine guarantees only that once `CANCELLED` becomes terminal, subsequent completion attempts cannot replace that outcome.

---

# 33. Retention

Terminal computations SHOULD have configurable retention.

For example:

```text
interactive jobs       24 hours
agent tasks             30 days
audit-sensitive runs    longer
```

Deletion MUST account for references from agent histories and audit requirements.

Durable computation IDs MUST NOT be reused.

---

# 34. Observability

Every computation SHOULD expose:

```text
computation.id
computation.type
status
created timestamp
completed timestamp
producer/tool
agent run
tool call
attempt information
failure information
backend
```

Recommended tracing relationship:

```text
AgentRun
   |
   +-- ModelCall
   |
   +-- ToolCall
          |
          +-- ComputationId
```

A durable computation ID SHOULD be included in logs, traces, and external callback metadata.

---

# 35. Security

The public computation ID SHOULD NOT automatically imply authority to complete it.

For Internet-facing callbacks, consider:

```text
ComputationId
+
unguessable completion token
```

or a signed callback token:

```text
signed(
    computationId,
    expiry,
    allowedOperation
)
```

Provider-native security mechanisms should be used for Restate or Temporal where possible.

Completion endpoints MUST treat external payloads as untrusted input.

---

# 36. Recommended initial implementation

A practical delivery sequence is:

### Phase 1 — Core computation model

Implement:

```text
Computation<T>
CompletedComputation<T>
AttachedComputation<T>
DurableComputation<T>
Outcome<T>
CompletionPolicy
```

Support CLI and interactive web using only completed and attached computations.

### Phase 2 — SQL durable backend

Implement:

```text
DurableComputationBackend
ComputationId
DurablePromise<T>
Continuation
ResumeAgentRun
transactional await
transactional completion
outbox
resumption worker
```

This becomes the reference implementation and executable specification of the semantics.

### Phase 3 — Restate adapter

Implement autonomous agent runs as Restate durable handlers/workflows.

Prefer:

```text
Awakeable
```

for detached one-shot tools.

Use workflow promises when the awaited value is naturally scoped and named within one Restate workflow.

### Phase 4 — Temporal adapter

Implement autonomous agent runs as Temporal Workflows.

Represent model and external tool operations as Activities.

Prefer Temporal asynchronous Activity completion for detached tool calls.

---

# 37. Example end-to-end durable tool call

Consider an agent that invokes:

```text
requestHumanArchitectureReview
```

The tool begins a human review expected to take several days.

```text
AgentRun 42
    |
    v
ToolCall 17
    |
    v
ToolCallExecutor.execute()
    |
    v
create Computation 99
    |
    v
send reviewer callback URL containing Computation 99
    |
    v
return DurableComputation(99)
```

The runtime performs:

```text
await(
    Computation 99,
    ResumeAgentRun(
        run=42,
        toolCall=17
    )
)
```

and receives:

```text
Registered
```

Agent Run 42 becomes:

```text
SUSPENDED
waitingOn = Computation 99
```

No process needs to remain alive.

Three days later:

```text
Reviewer
    |
    v
POST callback
    |
    v
complete(Computation 99, Approved)
```

The backend atomically:

```text
marks Computation 99 SUCCEEDED
+
enqueues ResumeAgentRun(42)
```

A worker resumes Run 42.

The runtime loads:

```text
ToolCall 17
Computation 99
Outcome = Approved
```

and adds the equivalent tool message:

```text
requestHumanArchitectureReview:
    Approved
```

to the model context.

The next model invocation then proceeds normally.

---

# 38. Core architectural principle

The central distinction is not execution duration.

It is ownership of pending state.

```text
Immediate:
    caller owns the value

Attached:
    current JVM owns the pending computation

Durable:
    durable infrastructure owns the pending computation
```

For CLI and normal interactive web execution:

```text
Completed
or
CompletionStage
```

is sufficient.

For autonomous execution:

```text
stable identity
+
durable outcome
+
durable continuation
```

is required.

The durable backend may implement that explicitly using SQL or implicitly through a durable execution runtime such as Restate or Temporal.

The agent harness SHOULD define the semantics while allowing the selected backend to own the mechanism.