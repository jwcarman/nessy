# The Harness

There are two harness doors, and they are peers — neither is a special case
of the other. `DirectHarness<I, O>` is for a caller standing there waiting
on an answer. `QueuedHarness<I>` is for work nobody is waiting on. Both are
built once per agent type from a factory, kept for the life of the process,
and both drop the per-agent handle: there is no object per conversation to
hold, because a handle is a thing that can go stale and the agent's row
already knows where it lives.

```java
public interface DirectHarness<I, O> {
  Outcome<O> ask(AgentId agent, I input);
  TerminationOutcome terminate(AgentId agent);
}

public interface QueuedHarness<I> {
  void tell(AgentId agentId, I input);
  void terminate(AgentId agentId);
}
```

`ask` always accepts a call, does the turn, and returns; `tell` always
accepts an input and does nothing else. What `DirectHarness` does because a
caller is blocked — answering, refusing, admission control — is not part of
`QueuedHarness`'s job, and what `QueuedHarness` does because nobody is
waiting — a backlog, deferred answers reaching it later — is not part of
`DirectHarness`'s.

## Kept, not closed

Build a harness once per agent type and keep it. Both factories close over
the provider, the tools, the prompt and the context policy; building one
per request would rebuild all of that and buy nothing.

`DirectHarnessFactory` is implemented by `DefaultDirectHarnessFactory`, in
`nessy-engine`'s `...harness.direct` package. `QueuedHarnessFactory` is
implemented by `DefaultQueuedHarnessFactory`, in `...harness.queued`. Both
are `AutoCloseable`: a direct factory's `close()` shuts down the executor its
harnesses enforce deadlines on, and a queued factory's stops the scheduler
that looks for due work. Neither has to be closed — a caller that never does
loses nothing that outlives its own turns — but a container managing the
lifecycle should.

## The direct door: ask, and get an `Outcome`

```java
DirectHarnessFactory factory = DefaultDirectHarnessFactory.of(config -> config
        .backend(backend)
        .provider(providerId, provider));

DirectHarness<String, String> harness = factory.<String>create(
        new AgentType("assistant"),
        config -> config
                .systemPrompt("You are a terse assistant.")
                .inference(in -> in.provider(providerId.value()).model("claude-sonnet-5-5"))
                .tool(new AddTool()));

Outcome<String> outcome = harness.ask(AgentId.random(), "what is 2+2?");
```

Here `backend`, `provider` and `providerId` are as built in
[Getting Started](getting-started.md#what-the-factory-needs). A harness's own
`.inference(...)` wins when it states a provider or a model; whatever it
leaves out falls back to what the factory was given through
`config.inference(ProviderId, InferenceOptions)`, the same fallback the
queued door has. With neither, `create` throws `IllegalStateException`,
because nothing names a provider.

### Never inside a transaction

`ask` throws `IllegalStateException` when the calling thread has a
transaction open, before it writes anything. A turn makes at least one
model call, a network call that can take seconds, and a transaction should
not stay open across one. On a JDBC store it would not work either: the
turn's first step joins the caller's transaction, and the model call runs on
another connection that cannot see what that step wrote. Nessy sees any
transaction Spring manages. If you manage JDBC transactions by hand, keep the
rule yourself.

To ask from code that runs inside a transaction, suspend it for the call:

```java
TransactionTemplate outside = new TransactionTemplate(transactionManager);
outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
Outcome<String> outcome = outside.execute(status -> harness.ask(agent, input));
```

The queued door is the other way round: `tell` joins the caller's
transaction on purpose, so consuming a message and telling an agent about it
commit or roll back together.

### Outcome

`ask` never throws for anything it understands. What it hands back is one of
four arms:

| Arm | What it means |
|---|---|
| `Answered<T>(T value, TurnStats stats)` | The model answered, parsed into `T` if a shape was asked for |
| `Refused<T>(String category, TurnStats stats)` | The model declined, and would decline again |
| `Failed<T>(String reason, TurnStats stats)` | The turn ended without an answer — worth retrying, unlike a refusal |
| `Busy<T>()` | Somebody else is already running a turn on this agent; nothing happened |

`Busy` is the only arm where no turn ran at all: nothing was appended,
nothing was spent, nothing about the agent changed. That is also what makes
it the only one worth simply asking again for — the other three are
answers, and asking again gets another one.

A sealed interface, so a caller can match every arm and the compiler holds
it to that:

```java
switch (outcome) {
    case Outcome.Answered<String>(String said, _) -> System.out.println(said);
    case Outcome.Refused<String>(String category, _) -> System.out.println("refused: " + category);
    case Outcome.Failed<String>(String reason, _) -> System.out.println("failed: " + reason);
    case Outcome.Busy<String> _ -> System.out.println("busy; try again");
}
```

`Busy` carries nothing, because no turn ran to tally. The other three end in
a `TurnStats`, what the turn did and what it cost; the `_` ignores it here.

### Answering in a shape

`create` takes a Java type for the answer, and the shape reaches the
provider as a schema it is asked to constrain its answer to:

```java
record Verdict(boolean approved, String reason) {}

DirectHarness<String, Verdict> harness = factory.<String, Verdict>create(
        new AgentType("reviewer"), Verdict.class,
        config -> config
                .systemPrompt("You review a request and decide.")
                .inference(in -> in.provider(providerId.value()).model("claude-sonnet-5-5")));

Outcome<Verdict> outcome = harness.ask(AgentId.random(), "may I deploy on a Friday?");
```

A vendor or model that will not constrain an answer ends the turn
`Outcome.Failed` rather than handing back something that does not fit the
shape.

### Terminating a direct harness

```java
TerminationOutcome result = harness.terminate(agentId);
```

`terminate` returns `TerminationOutcome` rather than nothing, because a
caller asking to end an agent somebody else is still asking is an ordinary
race, and being told so is the whole point of the type:

| Arm | What it means |
|---|---|
| `Ended()` | This call is the one that ended the agent |
| `AlreadyEnded()` | It was already over before this call |
| `Busy()` | A turn is in flight; nothing was written, ask again |

An agent is only ever ended from idle: a turn in flight is owed its
outcome, so a request to end a busy agent is refused rather than queued.
The direct door has nowhere to record that somebody asked — unlike the
queued door, which writes the ending down and honours it once the agent
falls idle — so a `Busy` termination here is simply refused, and a caller
that means it must ask again.

## The queued door: tell, and let it happen later

```java
QueuedHarnessFactory factory = DefaultQueuedHarnessFactory.of(config -> config
        .backend(backend)
        .provider(providerId, provider)
        .inference(providerId, InferenceOptions.of("claude-sonnet-5-5")));

QueuedHarness<String> harness = factory.create(new AgentType("watchman"), config -> config
        .systemPrompt("You watch a house."));

harness.tell(AgentId.random(), "the porch light came on");
```

`tell` is a post, not a call: it returns as soon as the input is durable,
and the turn happens afterwards on the harness's own dispatcher. There is
nothing to return, because by the time the turn runs whoever spoke has
gone — the answer reaches a caller through a listener instead (see
[Narration](narration.md)).

`QueuedHarnessFactory.create(agentType, customizer)` takes a model and
token cap from the factory's own `inference(providerId, options)` unless
the harness says otherwise — the same fallback the direct door offers
through `DirectHarnessFactoryConfig.inference(providerId, options)`.

### Terminating a queued harness

```java
harness.terminate(agentId);
```

Takes effect at once if the agent is idle. One mid-turn stops accepting
immediately and ends once the turn it already owes an outcome for is
finished — an effect already written down cannot be cancelled, and
abandoning it would leave a row nobody will ever discharge. Nothing is
written to the story: what ended is the agent, not its conversation.
Idempotent and irreversible; an input arriving afterwards is refused,
whenever it arrives.

## Coalescing: what happens to what is already waiting

An agent works one turn at a time, so on the queued door an input arriving
mid-turn waits in a backlog. This is a queued-door concern only — a backlog
exists because input can arrive while nobody is there to receive it, and on
the direct door the caller *is* the only thing that can start a turn, so
there is never anything waiting to coalesce.

What should happen to that backlog is a `BacklogPolicy<I>`, set on
`QueuedHarnessConfig`:

```java
config.backlogPolicy(BacklogPolicy.keepAll());                      // the default
config.backlogPolicy(BacklogPolicy.<Reading, String>replaceBy(Reading::sensor));
config.backlogPolicy(BacklogPolicy.<Tick, String>dropRepeats(Tick::kind).capped(50));
```

| Policy | What it keeps |
|---|---|
| `keepAll()` | Every input, in order. The default, and right for anything a person said |
| `keepLatest()` | Only the newest input; anything waiting is superseded on arrival |
| `bounded(max)` | At most `max`, dropping the oldest once full |
| `replaceBy(key)` | The newest input per key, in the position the first one held |
| `dropRepeats(key)` | The first input per key; a later one with the same key is discarded |
| `mergeBy(key, merge)` | Combines an arrival with the pending input sharing its key |

`expiring(ttl)` and `capped(max)` are default methods on `BacklogPolicy`
that wrap any of the above: `policy.expiring(Duration.ofMinutes(5))` first
drops anything older than the TTL, and `policy.capped(50)` bounds whatever
comes out of the policy afterward.

A policy is handed a `Coalescing<I>` — a handle on the backlog rather than a
list, so the cheap cases (`append`, `replaceAll`) never decode a row. Only
`Coalescing.all()` reads the whole backlog, and the policies that need it
(`replaceBy`, `dropRepeats`, `mergeBy`) say so plainly in what they cost.

The policy sees only what is *waiting*: the input a turn is already working
on is never in the backlog, so a policy that drops or replaces cannot
discard the very thing being worked on. An input coalesced away is never
rendered to the model at all.

## Configuration surface

Both configs share `agentType()`, `tool(...)`, `instructions(...)`,
`memory(...)`, `state(...)`, `ambient(...)`, `chapterPolicy(...)`,
`summarizer(...)` and `turnPolicy(...)` (declared on the common
`HarnessConfig<SELF>`; see [Turn Policy](../concepts/turn-policy.md)), plus a
system prompt, an `inputRenderer`, an `inputLabel`, an `inference(...)`
customizer and a `listener(...)`. The system prompt is fixed when the harness
is built; see [Prompts](prompts.md). The rest of the context settings (`maxTail`,
`maxChapterLength`, `chapterLeaseTtl`, `withoutChapters`) are on
`in.context(...)` inside the `inference(...)` customizer. What differs is what each door alone can produce:

**`DirectHarnessConfig<I>`**

| Setting | What it decides |
|---|---|
| `maxInFlight` | How many effects this harness may have running at once, across every concurrent `ask` call. Defaults to 64 — an admission bound against a runaway harness, not a concurrency limit; a caller is blocked on its own turn, so a low bound here would make a fifth caller wait before its own turn could even start |
| `listener` | Watches a turn arrive while a caller is already going to get the answer back — the only part worth showing before the end is the streamed deltas |

**`QueuedHarnessConfig<I>`**

| Setting | What it decides |
|---|---|
| `backlogPolicy` | What an arriving input does to the ones already waiting; defaults to `BacklogPolicy.keepAll()` |
| `effects` | `pollInterval` (default one second) and `maxInFlight` (default 4) — how often the dispatcher looks for due work, and how much of any kind it runs at once, bounding the agent type rather than one call |

The defaults that matter on both doors: a tool call gets 30 seconds, an
approver 10 minutes, a model call 5 minutes, none of them retried by
default. History is cut into chapters every 20 turns, each summarised by the
agent's own model, a chapter is at most 30 turns long unless `maxChapterLength` says
otherwise (so a `ChapterPolicy.every(50)` is cut at 30), and the tail shown whole is at most
40 completed turns.
Chapters are on unless `withoutChapters()` is set, so an agent spends tokens
on summaries by default. See [Context](../concepts/context.md#chapters).

### Labelling an input

Each turn's start says what started it, in a few words, so a reader of the
story can tell turns apart without opening their inputs. Give each input a
label with `inputLabel`:

```java
harness
    .systemPrompt("You settle invoices.")
    .inputLabel(invoice -> "Invoice " + invoice.number());
```

A label is made one line and cut to 1000 characters, as an action line is. With
no label configured, the label is the input's simple class name. It is the same
when the label throws, returns null or returns a blank string. The turn runs as
usual in each case, and only a label that throws is warned about, with a message
that names the agent type. The turn's start also records
when its input arrived. On the queued door that is when `tell` was called, which
can be well before the turn opened.

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

!!! warning "Deferring only works on the queued door"
    A caller standing at the direct door is already waiting, so there is
    nowhere to send a late answer: an approver or tool that returns
    `Awaited.deferred()` behind a `DirectHarness` fails the call
    immediately rather than parking it. Gate a tool that may need a person
    to think it over on the queued door.

Deferring parks the call and frees the agent. How long the call waits is
the binding's decision, not the approver's:

```java
.tool(restart, binding -> binding
        .approver(desk, terms -> terms.timeout(Duration.ofDays(3))))
```

Days later, whoever holds the token answers, through the queued factory's
`Replies`:

```java
factory.replies().approve(token, ApprovalResult.denied("not this time"));
```

**A denial is an answer, not an absence.** The model is told the call was
refused, with the reason, and decides what to do about that. It is not a
failed turn, and it is not a broken tool.

## Describing what is being approved

A person consents to a sentence, so write the sentence:

```java
.tool(sendEmail, binding -> binding
        .approver(desk)
        .action(email -> "Send an email to %s, subject \"%s\": %s"
                .formatted(email.to(), email.subject(), email.body())))
```

Consenting to a message you have not read is not consent. Include the body.
The sentence is stored as one line. With a stringifier named, a line is cut
to at most 1,000 characters; with none named, the cut is at 255. The model
that summarises a chapter reads it too. A stringifier that throws makes a
gated call refused without asking the approver. See
[Tools](../concepts/tools.md#what-a-call-leaves-behind) for the limits and for
how to cut it.

## The console: the whole application in one call

`Repl.run` builds a `DirectHarness<String, String>` and runs a read-line
loop around it. For an application that is already Spring Boot, hand over a
`DirectHarnessFactory` and the model to use:

```java
public static void main(String[] args) {
    new SpringApplicationBuilder(Chat.class).web(WebApplicationType.NONE).run(args);
}

@Bean
CommandLineRunner terminal(DirectHarnessFactory harnesses, @Value("${nessy.model}") String model) {
    return _ -> Repl.run(harnesses, model, config -> config
            .banner("nessy chat -- type /exit or press Ctrl-D to leave")
            .agent(new AgentType("chat"))
            .tool(new AddTool())
            .tool(new SendEmailTool(), binding -> binding
                    .approver(ConsoleApprover.atTheTerminal())
                    .action(email -> "Send an email to " + email.to())));
}
```

For a program that is not Spring Boot and does not want to become one,
`Repl.run(customizer)` raises just enough of a context to find an
`InferenceProvider`, then does the rest itself. The conversation stays in
memory and ends with the process; a `DataSource` is optional, for tools that
bring their own store. It needs `nessy-console` and one provider adapter on
the classpath:

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

`nessy-examples/chat-cli` is exactly the first shape, with a notebook, a
plan and the date, given as ambient background, added.

## Where next

- [Getting Started](getting-started.md), the shortest path to a running agent
- [Tools](../concepts/tools.md), writing tools, and deferring
- [Authorization](../concepts/authorization.md), grants and approvers
- [Context](../concepts/context.md), what a model call is built from
- [Storage](../concepts/storage.md), the tables, and applying the schema
- [Spring Boot](spring-boot.md), the starter
