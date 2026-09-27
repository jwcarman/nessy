# Events

`AgentEvent` is the durable record of what happened to an agent: facts, in
order, and the only thing that moves an `AgentState`. It lives in
`nessy-backend-spi`, and `AgentEvents` is where a backend stores it.

This is a different grammar from [Narration](../guides/narration.md).
`Narration` is announced once, to whoever happens to be listening, and is
gone if nobody was there; an `AgentEvent` is written in the fold's own
transaction and read back forever — it is what a model is eventually shown.
Plenty of narration has no event behind it at all, such as a call still
waiting on a person, and plenty of events are not worth announcing.

## The grammar

```java
public sealed interface AgentEvent {
  Seq seq();

  record TurnStarted(Seq seq, TurnId turn, PayloadRef input) implements AgentEvent {}
  record InferenceAnswered(Seq seq, TurnId turn, PayloadRef answer, Usage usage) implements AgentEvent {}
  record InferenceRefused(Seq seq, TurnId turn, String category, Usage usage) implements AgentEvent {}
  record InferenceFailed(Seq seq, TurnId turn, Failure failure, Usage usage) implements AgentEvent {}
  record ActionsRequested(Seq seq, TurnId turn, PayloadRef request, List<ActionRequest> actions, Usage usage) implements AgentEvent {}
  record ToolApproved(Seq seq, TurnId turn, CallId callId, Optional<String> reference) implements AgentEvent {}
  record ToolDenied(Seq seq, TurnId turn, CallId callId, String reason, Optional<String> reference) implements AgentEvent {}
  record ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result) implements AgentEvent {}
  record ToolFailed(Seq seq, TurnId turn, CallId callId, String message) implements AgentEvent {}
  record Terminated(Seq seq) implements AgentEvent {}
}
```

Most events belong to a turn and carry its `TurnId`; `Terminated` belongs to
the agent's life and sits between turns instead.

**No payloads.** Every event carries identifiers, status, a human decision
or a count — and a `PayloadRef` where content would otherwise sit. That is
what keeps the stream small enough to replay on every command.

**Four arms carry `Usage`**: `InferenceAnswered`, `InferenceRefused`,
`InferenceFailed` and `ActionsRequested`. A tool-using turn pays for every
inference along the way, not only the last one that produced an answer —
`ActionsRequested` is itself a model call and costs like one. A refusal
still costs too: the model read the input before declining to answer it.

**This grammar is public backend SPI.** `AgentEvents` is typed on it, and a
JDBC backend genuinely inspects arms — it writes a `starts_turn` column by
asking whether an event is a `TurnStarted`. Adding an arm here is a public
API change.

## AgentEvents

```java
public interface AgentEvents {
  void append(AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast);
  Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq after);
  default Stream<AgentEvent> streamAll(AgentType type, AgentId agent);
  default List<AgentEvent> readFrom(AgentType type, AgentId agent, Seq after);
  default List<AgentEvent> readAll(AgentType type, AgentId agent);
  List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent);
  Instant writtenAt(AgentType type, AgentId agent, Seq seq);
}
```

**Keyed by type and id together, not id alone.** An id was enough while
every id was minted fresh, but a caller names its own — `ask` and `tell`
both take an id the application chose, not one the engine minted — so an
application keying agents off a business identifier can run two agent
types, each its own harness, over the same id. Every method here takes
both, for that reason: keyed by id alone, those two agent types would share
a single story.

**`streamFrom` is the primitive.** `streamAll`, `readFrom` and `readAll`
are conveniences built over it, so a backend implements one method and gets
all four shapes.

**Close the stream.** It holds whatever the store needed to produce it — a
result set, a cursor, a connection — until it is closed, exactly as
`Files.lines` does. The JDBC implementation backs it with a live cursor, so
a caller that does not close it leaks a connection:

```java
try (Stream<AgentEvent> events = agentEvents.streamFrom(type, agent, after)) {
  events.forEach(this::handle);
}
```

Do not do slow work per element inside the stream, either — a network call
inside `map` holds whatever transaction the store opened for as long as the
call takes, which on PostgreSQL holds back vacuum too. Read what is wanted,
close the stream, then go slow.

**Reading an agent back costs its last turn, not its history.** Replay
begins at the last turn that started, via `sinceLastTurnStarted`, so
reconstitution is bounded by the length of a turn rather than by how long
the agent has lived. Nothing stores where that boundary is; it is a fact
the events already carry, found by reading backwards to the nearest
`TurnStarted`.

## Where next

- [Narration](../guides/narration.md), the announced-and-gone channel this record is not
- [Storage](storage.md), where the tables behind this interface live
- [Observability](../guides/observability.md), the traces and metrics recorded alongside a turn
