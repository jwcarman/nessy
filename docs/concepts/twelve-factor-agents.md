# 12-Factor Agents

[12-Factor Agents](https://github.com/humanlayer/12-factor-agents) is a set
of twelve principles, plus one appendix factor, for building agents that hold
up in production. Dex Horthy of HumanLayer wrote it, and its content is
licensed CC BY-SA 4.0. This page checks Nessy against each factor in turn.
For every one it says what the factor asks, what an application built on
Nessy gets, and what it does not. The factors are restated here in short, in
our own words; each section links to the original.

Four standings are used. `Meets` means Nessy does what the factor asks.
`Partly` means it does some of it. `Differs` means Nessy deliberately does
something else. `Not yet` means the capability is absent.

## Summary

| # | Factor | Standing | Why |
|---|---|---|---|
| 1 | Natural Language to Tool Calls | Meets | Tools are typed records; arguments are bound before `call` runs |
| 2 | Own your prompts | Partly | The system prompt is yours; the text Nessy writes around it is mostly fixed |
| 3 | Own your context window | Differs | Six strata in a fixed order, so prompt caching holds; you fill them |
| 4 | Tools are just structured outputs | Meets | A tool call is stored as data and your code runs it |
| 5 | Unify execution state and business state | Partly | Execution state is folded from the same events as the conversation; business state stays yours |
| 6 | Launch/Pause/Resume with simple APIs | Meets | `ask` and `tell` launch; `Awaited.deferred()` pauses; `Replies` resumes |
| 7 | Contact humans with tool calls | Partly | Approvals and deferring tools reach people; no built-in ask-a-human tool |
| 8 | Own your control flow | Differs | The engine runs the loop; you bound it and gate its steps |
| 9 | Compact Errors into Context Window | Partly | Failures reach the model, capped; no consecutive-error counter |
| 10 | Small, Focused Agents | Partly | One agent type per job; no agent-to-agent calls or workflow |
| 11 | Trigger from anywhere, meet users where they are | Partly | Two Java doors any trigger can call; no channel adapters ship |
| 12 | Make your agent a stateless reducer | Meets | The state machine is a pure function over stored events |
| 13 | Pre-fetch all the context you might need | Meets | Memory, state and ambient sources run before every model call |

## 1. Natural Language to Tool Calls

**Meets.** [Factor 1](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-01-natural-language-to-tool-calls.md)
asks that a plain-language request become a structured call, with
deterministic code routing it by name.

A `Tool<I>` is a name, a description, an input type and a method. The input
type becomes the JSON schema the model is offered, and the model's arguments
are bound into that type before `call` runs. A tool never parses a string.

```java
record Add(int left, int right) {}

class AddTool implements Tool<Add> {
    public ToolName name() { return new ToolName("add"); }
    public String description() { return "Adds two integers"; }
    public Class<Add> inputType() { return Add.class; }

    public Awaited<ToolResult> call(ToolCallRequest<Add> request) {
        Add input = request.input();
        return Awaited.ready(ToolResult.ok(new Block.Text(String.valueOf(input.left() + input.right()))));
    }
}
```

The fallback cases the factor asks for are handled. A call to a tool that is
not granted is answered with a failure the model reads, `there is no tool
named '<name>'`. Arguments that do not bind are answered with `the arguments
could not be read:` and the parser's message. Neither ends the turn.

What to accept:

- The only check on arguments is that they bind into the record. A tool
  validates anything beyond that itself.
- The schema's root must be an object. A sealed interface, an enum or a
  `String` as the whole input is refused when the tool is granted; wrap it
  in a record.
- Every granted tool is offered on every call, and an application cannot
  force the model to pick one.

See [Tools](tools.md#the-input-type-is-the-contract).

## 2. Own your prompts

**Partly.** [Factor 2](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-02-own-your-prompts.md)
asks that prompts be first-class code you write, read and test, not text a
framework hides.

The system prompt is entirely yours. It is a string passed to
`systemPrompt(...)`, with `instructions(...)` adding sections after it, and
nothing is appended. `nessy-prompt` adds templates with holes and refuses to
build when a hole is unfilled:

```java
SystemPrompt prompt = TemplatedSystemPrompt.render(
        new SpringPromptTemplateFactory(),
        "You are a concise assistant for ${company}.",
        PromptVariables.of(Map.of("company", "Acme")));

config.systemPrompt(prompt.value());
```

Nessy also writes text the model reads. Whether an application can replace
it varies:

| Text | Written by | Replaceable |
|---|---|---|
| System prompt, instruction sections | You | Yours |
| Tool descriptions and field descriptions | You | Yours |
| Chapter summariser prompt (`ProseSummarizer.PROMPT`) | Nessy | Yes. Pass your own to the four-argument `ProseSummarizer` constructor, or write a `Summarizer` |
| The summariser's closing ask and its line forms (`assistant did: ...`) | Nessy | Only by writing your own `Summarizer` |
| Descriptions of the notebook, plan and `begin_chapter` tools | Nessy | No setting. Write your own tool instead |
| What the model reads after a failure, such as `there is no tool named`, `the call failed:`, `the tool failed and gave no message` | Nessy | No |
| Each adapter's framing: the `<summary>`, `<memory>` and `<state>` tags, the `Error: ` prefix, the denial sentence | Nessy | No |

The system prompt is fixed when a harness is built, because a provider caches
a request's leading text and a prompt that changed per call would void it.
What varies by agent goes in a `StateSource`; what varies by the moment goes
in an `AmbientSource`.

The cost: an application can read and test its own prompts, but it cannot
rewrite every sentence the model sees. See
[Prompts](../guides/prompts.md#why-it-cannot-change) and
[Context](context.md#the-default-summary).

## 3. Own your context window

**Differs.** [Factor 3](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-03-own-your-context-window.md)
asks that you design the whole context format yourself, including custom
structures such as XML or YAML, and decide exactly what enters it.

Nessy owns the shape and you own the content. Every request is built from six
strata in one order: instructions, history, memory, state, the active turn,
ambient. The order runs from least to most often changing, so a provider's
prefix cache survives as much as it can. Each adapter decides where a stratum
lands on its wire and how it is labelled. See
[Where each stratum goes](../guides/providers.md#where-each-stratum-goes).

What an application controls:

- The content of memory, state and ambient, through `MemorySource`,
  `StateSource` and `AmbientSource`. Each is asked again on every call and
  may read a table or call a service.
- How an input becomes blocks, through `inputRenderer(...)`.
- How much history is shown whole (`maxTail`) and how older turns are
  summarised (`ChapterPolicy`, `Summarizer`, `withoutChapters()`).
- What a tool result says. It reaches the model whole.

What it cannot control through configuration: the order of the strata, the
adapter's labels, or the form of the history. The assembler is built from the
history, the tail limit and the three lists of sources, and nothing else. No
hook sits between the stored story and the request, so there is no place to
redact a stored turn before the model reads it.

The trade: a fixed layout keeps cached prefixes stable and lets every
adapter place content correctly for its vendor. The cost is that an
application cannot try a different overall format for the context.

See [Context](context.md).

## 4. Tools are just structured outputs

**Meets.** [Factor 4](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-04-tools-are-structured-outputs.md)
asks that a tool call be treated as JSON the model emits, with ordinary code
deciding what happens next.

That is the shape here. The model's request for calls is recorded as an event
(`ActionsRequested`, holding an `ActionRequest.ToolCall` for each call with
its name and a one-line action). The engine then looks each name up among
the granted tools and runs it. Between the model's words and your code there
is a typed record and an effect row, not a function the model calls itself.

A final answer can be structured too. Naming a type when the harness is
built constrains the answer with a generated schema and parses it before the
caller sees it:

```java
public record Weather(String summary, int highF, int lowF) {}

DirectHarness<String, Weather> forecaster = factory.create(
        new AgentType("forecaster"),
        Weather.class,
        config -> config.systemPrompt("Answer with today's weather, nothing else."));
```

What differs: Nessy uses the vendor's separate-tool-per-function convention.
It does not ask the model for one JSON object that unions every next step,
as the factor's examples do. Ending the turn is the model answering, not a
`done_for_now` tool.

See [Structured Output](../guides/structured-output.md#naming-a-shape).

## 5. Unify execution state and business state

**Partly.** [Factor 5](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-05-unify-execution-state.md)
asks that execution state, such as the current step and what is waiting, be
inferred from the conversation thread instead of kept in a separate store.

Nessy goes most of the way for execution state. An agent's state is never
stored. It is rebuilt by replaying the append-only `nessy_agent_event`
stream, from the last turn that started, and the history shown to the model
is read from the same events. Which phase an agent is in, and which calls it
waits on, are facts in that stream. See
[Agent as Scope](agent-as-scope.md#where-an-agent-is-and-how-it-is-known) and
[Events](events.md).

Where it departs:

- Work the agent owes is a row in `nessy_agent_effect`, written in the same
  transaction as the event that decided on it. Content lives in
  `nessy_payload`, summaries in `nessy_chapter`, locks and leases in their
  own tables. The conversation is not the only store.
- Business state is not unified with anything. Nessy keeps the agent's
  story; an application's own records stay in its own tables, and reach the
  model through tools or sources.
- The JDBC backend runs on PostgreSQL only. An in-memory backend exists for
  tests and loses everything when the process stops.

See [Storage](storage.md).

## 6. Launch/Pause/Resume with simple APIs

**Meets.** [Factor 6](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-06-launch-pause-resume.md)
asks that an agent launch through a simple API, pause for long operations,
and resume from an external trigger, including the gap between choosing a
tool and running it.

- **Launch.** `DirectHarness.ask(agent, input)` returns an `Outcome`.
  `QueuedHarness.tell(agent, input)` returns once the input is durable.
- **Pause.** A tool or an approver returns `Awaited.deferred()`. The call is
  parked as a row, holds no thread, and survives a restart.
- **Resume.** Whoever holds the call's `ReplyToken` answers through
  `Replies`, from any process.
- **Between selection and execution.** An approver is asked before a gated
  tool runs, and can defer for days.

```java
harness.tell(agentId, "the porch light came on");

// later, from any process that holds the token
replies.approve(token, ApprovalResult.approved());
replies.complete(token, ToolResult.ok(new Block.Text("the vendor shipped it")));
```

Things to know:

- Only the queued door can park a call. Behind a `DirectHarness`, a deferral
  fails the call at once, because nothing is there to wait for the answer.
- Reply tokens are sealed with a key. By default the key is minted fresh per
  process, so a restart makes every parked call unanswerable. Configure keys
  with `ReplyTokens.withKeys(...)` for anything that waits across a restart.
- A deferred call still has a deadline: 30 seconds for a tool and 10 minutes
  for an approver by default, set on the binding. When it passes, the call is
  recorded as failed.
- Execution is at-least-once. A tool that was running when its process died
  runs again; `turn()` and `callId()` together are stable across a re-drive
  so a tool can deduplicate.

See [Durable Computation](durable-computation.md#deferring) and
[Two Doors](two-doors.md).

## 7. Contact humans with tool calls

**Partly.** [Factor 7](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-07-contact-humans-with-tools.md)
asks that reaching a human be an explicit structured output of the model,
with answers returning through a webhook into the same thread.

Nessy covers two of the three pieces. Approvals are a gate the engine puts
in front of a tool call: an `Approver` is shown a one-line `action()` and
answers now or defers. The model does not choose to ask. A person can also be
asked by the model if you write the tool, since a deferring tool is the same
mechanism:

```java
record AskHuman(String question) {}

class AskHumanTool implements Tool<AskHuman> {
    public ToolName name() { return new ToolName("ask_human"); }
    public String description() { return "Ask the user a question and wait for the reply"; }
    public Class<AskHuman> inputType() { return AskHuman.class; }

    public Awaited<ToolResult> call(ToolCallRequest<AskHuman> request) {
        inbox.post(request.input().question(), request.replyToken());   // inbox is yours
        return Awaited.deferred();
    }
}

config.tool(new AskHumanTool(), binding -> binding.timeout(Duration.ofDays(1)));

// when the person answers, from any process
replies.complete(token, ToolResult.ok(new Block.Text("Yes, go ahead")));
```

What is missing: there is no built-in ask-a-human tool, no delivery to
Slack, email or any channel, and no webhook receiver. The application
supplies the `inbox` and the endpoint that calls `Replies`. In the examples,
`nessy-examples/watchman` stores pending approvals in a table and answers
them over HTTP, so they survive a restart. `nessy-examples/chat-web` holds
the request open while a card waits in the browser, so its approvals do not.

A tool's default timeout is 30 seconds, so a human-facing tool needs a
longer one, as above.

See [Authorization](authorization.md) and
[Tools](tools.md#answering-now-or-later).

## 8. Own your control flow

**Differs.** [Factor 8](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-08-own-your-control-flow.md)
asks that you write the loop that drives the agent, so you can interrupt it
between choosing a tool and running it, manage context, and pause and resume.

In Nessy the engine runs the loop: ask the model, record the calls it
requests, ask approvers, run the tools, ask the model again. An application
does not write it and cannot reorder it. What an application decides is:

- **When to stop.** A `TurnPolicy` is consulted before each further model
  call and answers `Continue`, `AnswerNow` or `FailTurn`. The default is
  `TurnPolicy.calls(20, 25)`.
- **Which calls run.** An `Approver` on a tool's binding gates each call,
  with `timeout` and `retryPolicy` on the same binding.
- **What the agent has.** The tools, model and context policy, fixed when
  the harness is built.

```java
config.turnPolicy((stats, now) ->
    stats.elapsed(now).compareTo(Duration.ofMinutes(5)) >= 0
        ? new TurnDecision.FailTurn("the turn ran past five minutes")
        : new TurnDecision.Continue());

config.tool(new SendEmailTool(), binding -> binding.approver(desk));
```

What an application cannot do: end a turn from the result of a particular
tool, choose the next tool in code, change the tools offered mid-conversation,
or insert a step between a result and the next model call. A listener sees
what happens but changes nothing.

What is kept from the factor: the interruption point between selection and
invocation exists as the approver, and pausing and resuming are durable
([factor 6](#6-launchpauseresume-with-simple-apis)).

The trade: because the engine owns the loop, a crash at any point recovers
without application code, and both doors fold identically. The cost is
control. A flow that needs a fixed sequence of steps belongs in your code
around the harness, not inside it.

See [Turns](turns.md) and [Turn Policy](turn-policy.md).

## 9. Compact Errors into Context Window

**Partly.** [Factor 9](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-09-compact-errors.md)
asks that failures be put into the context, compactly, so the model can
correct itself, with a limit on consecutive errors.

Tool failures reach the model. `ToolResult.Failure(message)` is a result,
not an exception, and the turn carries on. On the queued door, a thrown
exception is retried under the binding's `RetryPolicy` (default `Never`);
the direct door never retries. It is then reported as `the
call failed: <message>`. A call that outlives its deadline is reported as
`the call did not complete before its deadline; whether it ran is not known`.
A denial reaches the model with its reason. Every failure message is capped
at 1,000 characters, with the middle dropped.

Adapters mark a failure so the model can tell: Anthropic and Bedrock set an
error status on the result, the OpenAI adapters prefix `Error: `, and Gemini
sends `{"error": message}`.

Model-side failures are handled differently. A failed inference is not shown
to the model. A failure the adapter classifies as transient can be retried
under the inference `retryPolicy`, which defaults to `Never`; otherwise the
turn ends with `Outcome.Failed`. A reply cut off at the output limit is
delivered as the answer, with a WARN in the log and the finish reason on the
trace. `Outcome` carries no flag for it.

What is missing: there is no consecutive-error counter. The only bound on a
model that keeps failing a tool is the turn policy, which counts model
calls (answer at 20, fail at 25 by default). `TurnStats` counts tool calls
asked for and failed inference attempts, not failed tool calls, so a policy
such as "stop after three failed tools in a row" cannot be written from it.

```java
config.turnPolicy(TurnPolicy.calls(8, 12));
```

See [Tools](tools.md#results) and [Turn Policy](turn-policy.md).

## 10. Small, Focused Agents

**Partly.** [Factor 10](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-10-small-focused-agents.md)
asks for agents that do one job in a handful of steps, composed into a larger
deterministic system.

An agent type is a recipe: a system prompt, tools, a model and a context
policy, built into a harness once. Each type can use a different model, and
each agent id is its own isolated story with one turn running at a time. A
turn is bounded to 20 model calls before it is asked to answer and 25 before
it fails, by default, which keeps agents from sprawling.

What is absent: no agent can call another agent through Nessy, and there is
no workflow, graph or delegation feature. Composition is ordinary Java
around the harnesses. A tool is arbitrary code, so one could call another
harness, but nothing in Nessy models, limits or traces that relation.

See [Agent as Scope](agent-as-scope.md).

## 11. Trigger from anywhere, meet users where they are

**Partly.** [Factor 11](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-11-trigger-from-anywhere.md)
asks that agents start from any channel or event, human or not, and answer
on the same channel.

Nessy has two entry points and both are plain Java calls, so anything that
can run Java can be a trigger. `ask` is for a caller standing there for the
answer. `tell` is for work nobody is waiting on. The repository shows four
uses:

- A console loop (`nessy-console`, used by `nessy-examples/chat-cli`).
- An HTTP endpoint that calls `ask` (`nessy-examples/chat-web`).
- A schedule that calls `tell` (`nessy-examples/watchman`, with Spring's
  `@Scheduled`).
- An HTTP endpoint that answers a parked approval (`watchman` and
  `chat-web`).

What does not exist: webhook receivers, email, Slack or SMS adapters, queue
consumers, and an A2A or MCP server door. The code supplies none of them.
Replying on the originating channel is also the application's job. `ask`
returns the answer to its caller; a queued turn reports through a listener,
and the `Answered` narration does not carry the answer, which is read from
the story.

One sharp edge for event sources: `tell` takes no idempotency key. A source
that redelivers a message re-tells it, and the agent runs it twice.

See [Two Doors](two-doors.md#choosing-a-door).

## 12. Make your agent a stateless reducer

**Meets.** [Factor 12](https://github.com/humanlayer/12-factor-agents/blob/main/content/factor-12-stateless-reducer.md)
frames the agent as a reducer: state in, event in, new state out, with nothing
held in the process.

The state machine is built that way. `AgentState` is a sealed interface
(`Idle`, `Inferring`, `AwaitingActions`, `Terminal`). `apply(event)` folds one
stored event into the next state, and `execute(command, policy, now)` returns
a `Decision` of events to append and effects now owed. It has no clock, store
or thread of its own. The shell around it takes a lock, replays the state,
asks the function, and commits the result in one transaction. A redelivered
outcome answers `Ignore` and writes nothing.

Model calls and tool runs are effects outside the reducer. That is why a
three-day parked approval and a crash mid-call are ordinary tests.

The reducer is the engine's own. An application supplies tools, sources and
policies and does not write or replace it.

See [Agent as Scope](agent-as-scope.md#phases-are-data-and-a-decision-is-a-pure-function).

## 13. Pre-fetch all the context you might need

**Meets.** [Factor 13](https://github.com/humanlayer/12-factor-agents/blob/main/content/appendix-13-pre-fetch.md)
(an appendix) asks that data the model will probably need be fetched
deterministically up front, not through tool calls.

That is what the three sources do. They run before each model call, off the
agent's lock, and may read a table or call a service. `MemorySource` is
handed the turn being answered, so it can fetch what bears on the question.

```java
MemorySource order = new MemorySource() {
    public String kind() { return "order"; }

    public Optional<Memory> forAgent(AgentId agentId, Turn current) {
        return orders.forAgent(agentId).map(o -> Memory.text("order", o.summary()));   // orders is yours
    }
};

config.memory(order);
```

Things to know:

- A source is asked on every model call, not once per turn. A slow or costly
  fetch repeats within a turn unless the source caches.
- What a source returns is shown and discarded. It is not stored in the
  story.
- Where it lands matters for caching. Memory and state sit at the head of the
  active turn, and ambient sits at the very end of the request. See
  [Which stratum something belongs in](context.md#which-stratum-something-belongs-in).

To record the fetched data in the story instead, fetch it before `ask` and
put it in the input, which `inputRenderer(...)` turns into blocks.

See [Memory](memory.md#memory-and-state).

## Where next

- [Context](context.md), the six strata and how a request is built
- [Memory](memory.md), the sources an application fills
- [Durable Computation](durable-computation.md), parking, deadlines and answering from outside
- [Turn Policy](turn-policy.md), the bound an application sets on a turn
