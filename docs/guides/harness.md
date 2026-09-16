# The Harness

A harness is the door to one **kind** of agent. You build one per agent
type, keep it for the life of the process, and tell it things.

```java
harness.observe(agentId, "the porch light came on");
```

That is the whole surface for getting work done. There is no per-agent
handle to hold, and deliberately so: a handle is a thing that can go stale,
and the agent's row already knows where it is.

```java
public interface Harness<O> {
  void observe(AgentId agentId, O observation);
  void terminate(AgentId agentId);
}
```

## Kept, not closed

Build it once and keep it. A harness closes over the provider, the tools,
the prompt and the context policy; building one per request would rebuild
all of that and buy nothing. It holds no per-agent state, so one instance
serves every id your domain has.

The factory owns the lifecycle: `DefaultHarnessFactory` is `AutoCloseable`,
and closing it stops every harness it made and the scheduler that drives
their work.

## Two configurations, and the difference matters

**`EngineConfig`** is the engine: one per process.

```java
DefaultHarnessFactory factory = new DefaultHarnessFactory(engine -> engine
        .dataSource(dataSource)                                          // required
        .inference(provider, InferenceOptions.of("claude-sonnet-5"))     // required
        .listener(auditLog)
        .replyTokens(ReplyTokens.withKeys(currentKey, previousKey))
        .observations(observationRegistry)
        .storage(encryption));
```

| Setting | Default |
|---|---|
| `dataSource` | *required*: agents, their stories and their outstanding work are all rows |
| `inference` | *required*: the provider every harness calls, and the model and token cap they inherit |
| `listener` | none; repeatable. Hears every agent of every harness |
| `replyTokens` | ephemeral keys, so tokens die with the process |
| `observations` | no-op registry |
| `storage` | nothing after Jackson; a `Codec<byte[]>` here compresses or encrypts every row |
| `recordInferenceContexts` | on; every model call's request is written down whole |

Everything that touches the database is built inside the factory from that
one `DataSource`: the stores, the transaction manager, the JDBC client. There
is nothing for a caller to assemble and nothing for two callers to assemble
differently.

**`HarnessConfig`** is one agent type: as many as you like.

```java
Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("watchman"))
        .systemPrompt("You watch a house.")
        .inference(in -> in
                .model("claude-haiku-4-5")
                .maxTokens(1024)
                .context(ctx -> ctx.maxTail(20).ambient(PlanTools.plan(plans))))
        .listener(summarizer.listener())
        .tool(new DiskUsageTool())
        .tool(new PruneImagesTool(), binding -> binding
                .approver(desk)
                .action(input -> "docker image prune -af")));
```

| Setting | What it decides |
|---|---|
| `agentType` | the agent type, and the key every row is stored under. Renaming it orphans stored state |
| `systemPrompt` | the standing instruction, as a string or a `SystemPromptSource` decided per call |
| `observationRenderer` | how an observation becomes the blocks a model reads; `String` renders as itself |
| `observationCoalescer` | what an arriving observation does to the ones already waiting |
| `inference` | model, token cap, timeout, retry policy, and the context policy |
| `effects` | how often this harness looks for due work, and how much runs at once |
| `listener` | somebody who hears this harness's agents, after the engine's listeners |
| `tool` | grants one tool, optionally gated, timed and described |

The defaults that matter: a tool call gets 30 seconds, an approver 10
minutes, a model call 5 minutes, none of them retried; the tail is the last
20 turns; work is polled every 250 milliseconds with at most 4 effects in
flight per harness.

## Observing

`observe` is a post, not a call. It returns as soon as the observation is
durable, and the turn happens afterwards:

```java
harness.observe(agentId, "the porch light came on");
```

One transaction takes the agent's row lock, folds the observation into its
state, appends to the story, and writes down the work the fold decided on.
The model call itself runs later, on its own virtual thread, when the
harness's dispatcher picks the row up. See
[Agent as Scope](../concepts/agent-as-scope.md).

## Coalescing: what happens to what is already waiting

An agent works one turn at a time, so observations arriving during a turn
wait in its backlog. What *should* wait is your decision:

```java
config.observationCoalescer(ObservationCoalescer.keepAll());            // the default
config.observationCoalescer(ObservationCoalescer.replaceBy(Reading::sensor));
config.observationCoalescer(ObservationCoalescer.<Tick, String>dropRepeats(Tick::kind).capped(50));
```

`keepAll` is right for anything a person said. `replaceBy(key)` keeps the
newest observation per key, so ten readings from one sensor become one.
`dropRepeats(key)` ignores an arrival whose key is already waiting.
`mergeBy` folds two into one. `expiring(ttl)` and `capped(max)` compose onto
any of them.

The coalescer takes what is waiting and what arrived, and returns the
backlog. It may drop, merge or reorder, and the order it returns is the
order work is taken in. It sees only what is *waiting*: the observation a
turn is working on is not in the list, so a superseding policy cannot merge
away the very thing being worked on. Rendering happens when an observation
is taken, so one that is coalesced away is never rendered at all.

## Terminating

An agent id is not always a long-lived name. A browser session, one review
by a judging agent, a single request: those have to be able to end.

```java
harness.terminate(agentId);
```

It takes effect at once if the agent is idle. One mid-turn stops accepting
and ends when the turn it already owes an outcome for is finished, because
an effect that has been written down cannot be cancelled, and abandoning it
would leave a row nobody will ever discharge. Nothing is written to the
story: what ended is the agent, not its conversation. Terminating is
idempotent and irreversible, and an observation arriving afterwards is
refused.

The rows stay. Terminating ends an agent's activity, it does not delete its
history; retention is a policy your operators own, applied to the tables
directly. `nessy-examples/chat-web` shows the shape: its "New chat" button
terminates the old conversation rather than walking away from it.

## Hearing back

Listeners are how an answer reaches you. Attach one to the engine to hear
every harness, or to a harness to hear its agents alone; a harness's
listeners are told after the engine's, in order, on one thread per harness
that is not the thread folding the turn.

```java
config.listener(AgentEventListener.of(on -> on
        .onContentDelta((type, id, delta) -> out.print(delta.text()))
        .onApprovalSought((type, id, sought) -> desk.show(id, sought.action()))
        .onTurnEnded((type, id, ended) -> out.println())));
```

A listener that does its own slow work, such as a summariser making a model
call, wraps itself with `async()` and is told on a virtual thread per event.
See [Events](events.md).

## The console: the whole application in one call

For a terminal agent, `Repl.run` raises a minimal Boot context around
itself, finds the provider and the `DataSource` in the environment, builds
the harness and runs the loop:

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

An easy button may *default* a component, but it is never the only way to
get one: `ReplConfig.harness(customizer)` reaches the full `HarnessConfig`,
and `dataSource(...)` replaces the one Boot found.

## Writing an approver

An approver answers a question about one call. It can answer now:

```java
Approver always = request -> Awaited.ready(ApprovalResult.approved());
```

or later:

```java
Approver desk = request -> {
    pending.save(request, request.replyToken());
    return Awaited.deferred();
};
```

Deferring parks the call and frees the agent. How long the call waits is
the binding's decision, not the approver's:

```java
.tool(restart, binding -> binding
        .approver(desk, terms -> terms.timeout(Duration.ofDays(3))))
```

Days later, whoever holds the token answers:

```java
replies.approve(token, ApprovalResult.denied("not this time"));
```

**A denial is an answer, not an absence.** The model is told the call was
refused, with the reason, and decides what to do about that. It is not a
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

- [Getting Started](getting-started.md), the shortest path to a running agent
- [Tools](../concepts/tools.md), writing tools, and deferring
- [Authorization](../concepts/authorization.md), grants and approvers
- [Memory](../concepts/memory.md), what a model call is built from
- [Storage](../concepts/storage.md), the tables, and applying the schema
- [Spring Boot](spring-boot.md), the starter
