# The Harness

A harness is the door to one **kind** of agent. You build one per agent
type, keep it for the life of the process, and tell it things.

```java
harness.observe(AgentId.of("house-12"), "the porch light came on");
```

That is the whole surface for getting work done. There is no per-agent
handle to hold, and deliberately so: a handle is a thing that can go stale,
and sharding already knows where an agent lives.

```java
public interface Harness<O> {
  AgentType type();
  void observe(AgentId agentId, O observation);
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber);
  AgentSubscription subscribe(AgentId agentId, AgentSubscriber subscriber, String lastEventId);
}
```

## Kept, not closed

Build it once and keep it. A harness closes over the model, the tools, the
prompt and memory; building one per request would rebuild all of that and
buy nothing. It holds no per-agent state, so one instance serves every id
your domain has.

There is no `shutdown`. Entities belong to the cluster, not to whoever
asked for a harness.

## Two configurations, and the difference matters

**`EngineConfig`** is the engine: one per process.

```java
var factory = new PekkoHarnessFactory(engine -> engine
        .system(actorSystem)          // required
        .models(modelProvider)        // required
        .dataSource(dataSource));     // optional — see below
```

| Setting | Default |
|---|---|
| `system` | *required* — the `ActorSystem` the engine runs on |
| `models` | *required* — the gateway that resolves a `ModelId` |
| `dataSource` | an in-memory H2 the engine builds **and initializes** |
| `maxTokens` | 4096 |
| `capabilities` | none |
| `blocking` | virtual threads |
| `clock` | `Clock.systemUTC()` |
| `replyTokens` | ephemeral keys — tokens die with the process |
| `traces` | no-op |

The engine initializes only a `DataSource` it created. One you supply is
never touched uninvited — see [Storage](../concepts/storage.md) for how to
apply the schema yourself.

**`HarnessConfig`** is one agent type: as many as you like.

```java
Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("watchman"))
        .systemPrompt("You watch a house.")
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .memory(memory)
        .tool(new DiskUsageTool())
        .tool(new PruneImagesTool(), binding -> binding
                .approver(desk)
                .action(input -> "docker image prune -af")));
```

| Setting | What it decides |
|---|---|
| `type` | the agent type — also the persistence id prefix, so renaming it orphans stored state |
| `systemPrompt` | the standing instruction |
| `model` | which model, resolved against your `ModelProvider` |
| `renderer` | how an observation becomes a `UserMessage` |
| `coalescer` | what an arriving observation does to the ones already waiting |
| `memory` | the transcript; defaults to a recent-characters window, **announced loudly** |
| `tool` | grants one tool, optionally gated and described |

## Observing

`observe` is a post, not a call. It returns as soon as the observation is
durable, and the turn happens afterwards:

```java
harness.observe(agentId, "the porch light came on");
```

Two steps, in this order and never the other: the row is committed, then the
agent is told the backlog changed. Reversed, the agent could look for work
before the row lands, find nothing, and go back to sleep with work sitting
in the table.

The signal itself carries nothing — not a count, not an id. A busy agent
drops it on the floor, because going idle always ends with a look at the
backlog; duplicates are free, because looking at an empty backlog is a
no-op. That is what makes it safe to send one every time.

## Coalescing: what happens to what is already waiting

An agent works one turn at a time, so observations arriving during a turn
wait. What *should* wait is your decision, not Nessy's:

```java
BacklogCoalescer<String> coalescer = (waiting, arriving) -> {
    if (!isTick(arriving)) {
        var all = new ArrayList<>(waiting);
        all.add(arriving);
        return all;                       // keep everything
    }
    var kept = waiting.stream().filter(item -> !isTick(item)).toList();
    var all = new ArrayList<>(kept);
    all.add(arriving);
    return all;                           // one heartbeat, the newest
};
```

The coalescer takes what is waiting and what arrived, and returns **the
backlog**. It may drop, merge or reorder, and the order it returns is the
order work is taken in — so "what happens next" is its answer, not a
timestamp comparison Nessy invented.

It sees only what is *waiting*. The observation a turn is currently working
on is not in that list, so a superseding policy cannot merge away the very
thing being worked on.

Rendering happens when an observation is taken, not when it arrives — so an
observation that gets coalesced away is never rendered at all, and your
coalescer compares real observations rather than string-matching its way
back out of a rendered message.

## Watching

```java
try (AgentSubscription subscription = harness.subscribe(agentId, event -> {
        switch (event) {
            case AgentEvent.TextDelta delta -> System.out.print(delta.text());
            case AgentEvent.TurnEnded ended -> System.out.println();
            default -> { }
        }
    })) {
    harness.observe(agentId, "hello");
}
```

**Close it.** An unclosed subscription leaks a routing entry.

Every event carries a time-ordered id, so a listener that drops off can
resume:

```java
harness.subscribe(agentId, subscriber, lastEventIdItSaw);
```

Over SSE that is one line, because a browser sends `Last-Event-ID` on
reconnect by itself:

```java
public SseEmitter events(@PathVariable String id,
                         @RequestHeader(name = "Last-Event-ID", required = false) String cursor) {
    return streams.open(AgentId.of(id), cursor);
}
```

## The console: the whole application in one call

For a terminal agent, `Repl.run` does discovery, the actor system, the
cluster-of-one, reply tokens, the harness and the loop:

```java
public static void main(String[] args) {
    Repl.run(config -> config
            .systemPrompt("You are a helpful assistant.")
            .tool(new AddTool())
            .tool(new SendEmailTool(), binding -> binding
                    .approver(ConsoleApprover.atTheTerminal())
                    .action(email -> "Send an email to " + email.to())));
}
```

The barrier to writing a console agent was never the read-line loop; it was
the actor-system bootstrap. An easy button may *default* a component, but it
is never the only way to get one — every piece above is still settable.

## Writing an approver

An approver answers a question about one call. It can answer now:

```java
Approver always = request -> Awaited.ready(ApprovalResult.approved());
```

or later:

```java
Approver desk = request -> {
    pending.save(request, request.replyToken());
    return Awaited.deferred(clock.instant().plus(Duration.ofDays(3)));
};
```

Deferring parks the call, arms a durable alarm, and frees the agent. Days
later, whoever holds the token answers:

```java
replies.approve(token, ApprovalResult.denied("not this time"));
```

**A denial is an answer, not an absence.** The model is told the call was
refused, with the reason, and decides what to do about that — it is not a
failed turn, and it is not a broken tool.

## Describing what is being approved

A person consents to a sentence, so write the sentence:

```java
.tool(sendEmail, binding -> binding
        .approver(desk)
        .action(email -> "Send an email to %s%n  subject: %s%n  body: %s"
                .formatted(email.to(), email.subject(), trimmed(email.body()))))
```

Consenting to a message you have not read is not consent. Include the body;
trim it if your surface is a terminal prompt, and don't if it is a page.

## Where next

- [Getting Started](getting-started.md) — the shortest path to a running agent
- [Tools](../concepts/tools.md) — writing tools, and deferring
- [Authorization](../concepts/authorization.md) — grants and approvers
- [Storage](../concepts/storage.md) — the tables, and applying the schema
- [Spring Boot](spring-boot.md) — the starter
