# Tools

A `Tool<T>` is something the model can ask the harness to do: a name, a
sentence explaining when to use it, a record describing its arguments, and
a method that runs.

```java
public interface Tool<T> {
  String name();
  String description();
  Class<T> inputType();
  Awaited<ToolResult> execute(T input, ToolContext context);
  default ToolSpec spec() { ... }
  default CompletionPolicy requiredCompletion() { return CompletionPolicy.IMMEDIATE; }
}
```

The JSON Schema the model sees is derived from `inputType()`, never
hand-written — `Schemas.of(...)` reads the record's components and its
`@JsonPropertyDescription` annotations, so the schema cannot drift from the
code.

## `Tool.of` — the config factory

`Tool.of` composes a first-party tool from a config, not a builder: fluent
setters, no public `build()` (design of record 2026-08-16 §1) —

```java
record CreateAccount(String name, String type) {}

var tool = Tool.of(CreateAccount.class, t -> t
    .description("Create a new bank account.")
    .executes(cmd -> bankSvc.createAccount(cmd.name(), cmd.type())));
```

The name defaults to kebab-case of the input record's simple name —
`CreateAccount` becomes `create-account` — and `.name(...)` overrides it.
`.description(...)` is mandatory; it is written for the model, not for you.

Exactly one handler door must be filled in:

- `.executes(Function<T, ?>)` — the answer needs only the input.
- `.executes(BiFunction<T, ToolContext, ?>)` — the answer also needs the
  invocation's `ToolContext`.
- `.defers(BiConsumer<T, ToolContext>)` — the answer arrives through a
  durable computation; the starter kicks off the work and returns, and the
  built tool's `execute` always answers `Awaited.deferred()`.

`.defers(...)` sets `requiredCompletion()` to `CompletionPolicy.DURABLE`
unless `.requires(CompletionPolicy)` overrides it, in either order. A tool
built with zero or more than one handler door fails at `finish()` time,
naming the tool and the count found.

## `Awaited` — ready now, or later

`execute` never blocks past a `Ready`. It returns one of two arms, no
third:

```java
public sealed interface Awaited<T> {
  record Ready<T>(T value) implements Awaited<T> {}
  record Deferred<T>() implements Awaited<T> {}
}
```

`Ready` carries the answer, in hand right now. `Deferred` says the answer
arrives through a durable computation — a callback, an approval, a job.
The marker carries no identity: the id lives nowhere but the computation
`context.defer()` created, and returning `deferred()` without having
called `defer()` first is an in-band failure — see below.

## Deferring — the door

A tool that cannot answer right away calls `context.defer()`. It is the
mirror of `ApprovalContext.defer()`: it creates this call's durable
computation, folds `ToolDeferred` into the scope, waits for that fold to
commit, and only then hands back the id. By the time a tool can give the
id to anyone else — a queue, a webhook, a ticket system — the phase
already names the wait.

```java
public interface ToolContext {
  ToolCall call();
  ComputationId invocation();
  void progress(String message);
  ComputationId defer();
}
```

`invocation()` is not the id `defer()` returns. It's a stable idempotency
key, deterministic from the call's coordinates, for deduplicating an
external side effect across redispatch; `defer()` mints the real,
Continuum-backed callback address.

```java
@Override
public Awaited<ToolResult> execute(RestartHost input, ToolContext context) {
  ComputationId id = context.defer();          // creates, folds, commits — then returns
  tickets.open(input.host(), callbackFor(id)); // the id IS the callback address
  return Awaited.deferred();
}
```

The order matters, and it is the whole point: by the time `defer()` returns,
the scope's phase already says it is awaiting that computation. So the
ticket system may answer a millisecond later — or on the same thread, before
`execute` has even returned — and the answer still lands. There is no window
to lose it in, and nothing has to be fast enough. Whoever holds the id
completes it later through the harness's own door:

```java
harness.completions().complete(id, ToolResult.ok("host restarted"));
```

`Awaited.deferred()` is legal only after this call's own `defer()`. An
executor that never saw `defer()` called fails the call in-band with
`"deferring tool never called context.defer()"`; returning
`Awaited.ready(...)` after `defer()` already folded is likewise an in-band
failure, `"tool answered after deferring"`. `defer()` is idempotent — a
second call in the same execution returns the same id and creates nothing
new — and it throws if the fold cannot commit, in which case nothing was
parked and the tool should let the exception propagate; the executor
answers the call in-band with the failure.

## `timeout` — the deadline a deferred answer has to beat

A deferring tool can declare a deadline at registration:

```java
Tool.of(SlowJob.class, t -> t
    .description("...")
    .defers((cmd, ctx) -> jobs.submit(cmd, ctx))
    .timeout(Duration.ofMinutes(10)));
```

`timeout(Duration)` is optional and means **fail at this point**, not
retry at this point: retryable redispatch is not implemented in Nessy
today (Continuum's own retryable client exists for exactly this, unused
here so far). Set, an overdue durable computation is expired and folded as
an in-band failure the model reads — once, never resubmitted. Unset, the
computation still gets a default deadline rather than waiting forever
(Continuum requires every computation to carry one) — except an approval,
which is deliberately given a long, fixed deadline harness-wide, since it
may sit for days waiting on a person. See
[Durable Computation](durable-computation.md#deadlines).

## Sealed inputs — a vocabulary as one argument

A tool's `inputType()` can be a sealed interface of records instead of a
single record. Annotate it with the two standard Jackson polymorphism
annotations, naming each permitted record:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
  @JsonSubTypes.Type(value = Diagnose.class, name = "Diagnose")
})
sealed interface OpsIntent permits Restart, Diagnose {}
record Restart(String target, String reason) implements OpsIntent {}
record Diagnose(String target) implements OpsIntent {}
```

`Schemas` reads those same annotations and renders a `oneOf` over the
permitted records, each gaining a required const `"type"` property naming
the record — so the schema shown to the model and the binding
`RegistryToolCallExecutor` performs agree by construction via the
annotations, because both read the same ones. (`Schemas` builds its own
schema generator rather than reading the caller's mapper, so a mapper-level
customization — a registered module, a mix-in, a custom
`AnnotationIntrospector` — is visible to binding but not to the generated
schema.) Jackson's own polymorphic machinery binds the arriving call: a
missing or unknown `"type"` fails in-band with an `IllegalArgumentException`
naming the offense — the model reads the error and corrects, rather than
the call vanishing into a generic binding failure.

A sealed interface missing `@JsonTypeInfo`/`@JsonSubTypes` is rejected by
`Schemas` up front — it cannot generate a discriminated schema without that
information, so the failure names what to add rather than producing
whatever shape victools would otherwise guess at. Sealed *interfaces* are
the whole contract — a sealed abstract class is not recognized as an input
vocabulary.

Test your vocabulary over `InMemorySubstrate` (see [Storage](storage.md)):
storage there is real encoded bytes, so a missing or mis-set annotation
fails in your own unit tests, not in production.

## `CompletionPolicy` — filtering precedes failing

`requiredCompletion()` declares the strongest completion semantics a tool
needs:

```java
public enum CompletionPolicy {
  IMMEDIATE,   // only computations already completed when returned
  AWAITABLE,   // immediate, plus process-local asynchronous completion
  DURABLE      // immediate, asynchronous, and durable suspension
}
```

Declaration order is capability order — `IMMEDIATE ⊂ AWAITABLE ⊂ DURABLE` —
so `ToolRegistry.limited(base, policy)` filters out every grant whose tool
asks for more than a wiring can honor, before the model ever sees the tool
list. A wiring that cannot suspend never advertises a durable tool; every
harness wires a Continuum, so `context.defer()` itself never fails for
lack of a store. The in-band failures policing `Awaited`'s two arms
against what `defer()` actually recorded — [above](#deferring-the-door) —
are the backstop for a tool that under-declares its own requirement, not
the primary defense.

## `ToolEvent` — progress mid-execution

A running tool can narrate progress before it finishes, through the
`ToolContext` it receives:

```java
public sealed interface ToolEvent {
  record Progress(String message) implements ToolEvent {}
}
```

The executor turns a `Progress` event into a `TurnEvent.ToolCallProgressed`
on the turn observer. `ToolEvent` is sealed so a new variant fails the
build everywhere it isn't handled yet, rather than silently doing nothing.

## The MCP toolbox

`McpToolbox.connect(transport, mapper)` opens one MCP server's tools as
plain `Tool<JsonNode>` values — one `initialize`/`tools/list` handshake up
front, then `tools()` and `tool(name)` are in-memory lookups against that
snapshot.

```java
try (McpToolbox toolbox = McpToolbox.connect(transport, mapper)) {
  Tool<JsonNode> search = toolbox.tool("web-search");
  var grant = ToolGrant.grant(search, Approvers.defer());
}
```

Nothing about the toolbox pre-authorizes anything. Every tool it opens
still needs its own [`ToolGrant`](authorization.md) — an MCP tool is
governed exactly like a first-party one. Two servers make two toolboxes and
two namespaces; a name collision between them is the application's own
business, and the grant list is where it becomes visible.

## Where next

- [Authorization](authorization.md) — how a granted tool's call is judged
  before it ever runs.
- [Intent](intent.md) — the `declare-intent` tool and the sealed-input
  vocabulary it rides.
- [Durable Computation](durable-computation.md) — what a `Deferred` answer
  and a `DURABLE` completion policy actually rest on.
