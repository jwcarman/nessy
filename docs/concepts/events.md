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

  record TurnStarted(Seq seq, TurnId turn, PayloadRef input, Instant startedAt) implements AgentEvent {}
  record InferenceAnswered(Seq seq, TurnId turn, PayloadRef answer, Usage usage) implements AgentEvent {}
  record InferenceRefused(Seq seq, TurnId turn, String category, Usage usage) implements AgentEvent {}
  record InferenceFailed(Seq seq, TurnId turn, Failure failure, Usage usage) implements AgentEvent {}
  record InferenceAttempted(Seq seq, TurnId turn, Failure failure, Usage usage) implements AgentEvent {}
  record TurnFailed(Seq seq, TurnId turn, String reason) implements AgentEvent {}
  record ActionsRequested(Seq seq, TurnId turn, PayloadRef request, List<ActionRequest> actions, Usage usage) implements AgentEvent {}
  record ToolApproved(Seq seq, TurnId turn, CallId callId, Optional<String> reference) implements AgentEvent {}
  record ToolDenied(Seq seq, TurnId turn, CallId callId, String reason, Optional<String> reference) implements AgentEvent {}
  record ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result, String rendered) implements AgentEvent {}
  record ToolFailed(Seq seq, TurnId turn, CallId callId, String message) implements AgentEvent {}
  record Terminated(Seq seq) implements AgentEvent {}
}
```

Most events belong to a turn and carry its `TurnId`; `Terminated` belongs to
the agent's life and sits between turns instead.

**Content by reference, and two lines for each tool call.** Inputs, answers
and successful tool results are in the events only as a `PayloadRef` into
`nessy_payload`. For each tool call the events also hold two lines of text.
`ActionsRequested` holds a list of `ActionRequest`s, and each
`ActionRequest.ToolCall(CallId id, ToolName name, String action)` carries
`action`, what the call would do. Usually the tool's binding makes it from the
call's arguments. When the binding's stringifier gives nothing, it is the
tool's name. When the arguments do not parse, or the stringifier throws, it is
the name and a note that the arguments could not be read. When no tool of
that name is bound, it is `<name> (no such tool)`. `ToolSucceeded.rendered`
is what the call returned, made by the same binding from the result, and it
may be empty. Each line is at most 1,000 characters (`ToolConfig.LINE_CAP`);
the cap is applied when the binding is built, not by the records. A line is
fixed when it is written and never worked out again. See
[Tools](tools.md#what-a-call-leaves-behind).

Events hold other text too. A failed call's message is at most 1,000
characters; a longer one has its middle dropped and `...` in the gap, and the
shortened text is what the model reads back for the call. A denial's reason and
its reference, why a turn failed, a refusal's category and a failure's reason
are not bounded.

So an agent's content is in three places: its payload rows, the lines and
sentences in its events, and the summaries of its chapters in
`nessy_chapter`. The references, and the bound on the two lines, are what
keep the stream small enough to replay on every command.

**Five arms carry `Usage`**: `InferenceAnswered`, `InferenceRefused`,
`InferenceFailed`, `InferenceAttempted` and `ActionsRequested`. A
tool-using turn pays for every inference along the way, not only the last
one that produced an answer — `ActionsRequested` is itself a model call
and costs like one. A refusal still costs too: the model read the input
before declining to answer it.

**`InferenceAttempted` is a model call that failed and was tried
again**, told apart from `InferenceFailed` by finality — which is the
difference that matters. `InferenceFailed` ends a turn; `InferenceAttempted`
is a turn carrying on. A reader that treats them alike will count a turn
that stumbled twice and answered as three failures. It exists because the
attempt cost something and nothing else records it: a retried call reaches
the fold once, when it finally settles, carrying only the last attempt's
count, so without this event the tokens spent on the attempts before it
are invisible to anything asking what a turn has spent — precisely the
reading a budget needs most, since a turn that is thrashing is spending
where nobody is looking. Two classifications reach it: `Failure.Transient`,
a provider saying the call might work next time, and `Failure.Unknown`, an
attempt that threw and whose usage is therefore always unreported.

**`TurnFailed` is a turn ended by a policy** — see
[Turn Policy](turn-policy.md) — rather than an inference that failed:
`InferenceFailed` is for a call that was made and produced nothing,
`TurnFailed` is for a turn a policy stopped without making a call at all.
It carries **no `Usage`**, because deciding not to ask costs nothing — what
the turn actually spent is already recorded on the events that spent it,
and a `TurnFailed` with a field for a count would invite claiming a call
happened that nothing measured.

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
