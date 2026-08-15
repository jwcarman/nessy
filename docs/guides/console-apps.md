# Console Apps

`nessy-console` is the terminal front door: read a line, tell the agent,
render deltas, prompt again. Three examples in this repository hand-rolled
that loop before this module existed, `ConsoleApprover` included; depend on
`nessy-core` alone, and console chat with any `Agent<String>` is a builder
chain.

## The builder chain

```java
ConsoleRepl.of(agent)
    .banner("scout — ask about any public GitHub repo")
    .prompt("you> ")
    .exitOn("exit", "quit")
    .run();
```

- `ConsoleRepl.of(agent)` starts the builder. It opens one conversation and
  drives it for the life of `run()`.
- `.banner(String)` — the line printed once, before the first prompt. Empty
  (the default) prints nothing.
- `.prompt(String)` — the line printed before every read. Defaults to
  `"> "`.
- `.exitOn(String...)` — the words (after trimming) that end the loop.
  Defaults to `"exit"`, `"quit"`. Throws if given zero words: a loop with no
  way out is a trap, not a valid configuration.
- `.renderer(TurnObserver)` — overrides the default renderer wholesale.
- `.plan(PlanStore)` — opts into the end-of-turn plan checklist, described
  below. Throws `IllegalStateException` if called twice.
- `.run()` — the real-console entry point: a thin adapter over `System.in`
  and `System.out`.

`run()` prints the banner (if any), then loops: print the prompt, read a
line, an exit word ends the loop, a blank line reprompts without telling the
agent, anything else is told with the default renderer watching.

## The look

Assistant prose streams plain, word by word, the way a real chat client
shows it. Thinking deltas render dim-italic. Tool activity gets one dim
`⚙ tool:` line per requested/completed/parked event:

```

⚙ tool: clock requested

⚙ tool: clock completed
```

A `FAILED` turn ending renders one red line with its reason; every other
ending renders nothing here. Between sending and the first token, a
`\r`-based spinner fills the wait on its own virtual thread, erased the
instant the first `TurnEvent` arrives.

The approval gate, `ConsoleApprover`, prints its prompt bold-yellow — the one
place the default look reaches for two styles at once:

```

approve: read the current time
y/n>
```

Garbage input reprompts (`please answer y or n`) rather than reading as a
denial; only `y`/`n`, case-insensitive and trimmed, settle the question. End
of input (an unattended pipe, a closed terminal) reads as a denial with
reason `"declined at the console"`.

## The SGR-only covenant

Styling yes, terminal takeover no. `Ansi` wraps text in SGR codes (`bold`,
`dim`, `italic`, a four-color palette), plus the spinner's own `\r`
overwrite — nothing here does cursor addressing, raw mode, or an alternate
screen. Styling is disabled automatically when no real console is attached,
when `NO_COLOR` is set, or when `TERM=dumb`; disabled, every wrapper is an
exact pass-through and the spinner writes zero bytes, so piping this
module's output to a file or another process yields clean, colorless text.

## The plan checklist

Grant the model `update_plan` (see [Planning](../concepts/planning.md)) and
hand `ConsoleRepl.Builder#plan` the same `PlanStore`, and the REPL prints the
checklist in the terminal itself — not just recalled into the model's own
context. The checklist renders **at most once per turn**, at the very end,
after `conversation.tell` has returned and only when the plan changed since
the last one printed:

```
you> add 2 and 3, then tell me the time

⚙ tool: add requested
⚙ tool: add completed
⚙ tool: clock requested

approve: read the current time
y/n> y

⚙ tool: clock completed

it's 2:00 PM.

  [x] add 2 and 3
  [x] tell the time

you>
```

A turn whose plan didn't change, or that never wrote one, prints no
checklist at all — quiet turns stay quiet.

```java
DemoAgent.Built built = DemoAgent.agentFor(provider, model);

ConsoleRepl.of(built.agent())
    .banner("Nessy demo. Ask for something multi-step to watch it plan.")
    .prompt("you> ")
    .plan(built.planStore())
    .run();
```

`nessy-examples/chat-cli`'s `Chat` main is this exact wiring: the same
`PlanStore` the agent's `update_plan` tool writes through is the one handed
to `.plan(...)`, so the checklist the REPL prints and the checklist the model
recalls into its own context are reading the same durable state.

## Composing the renderer elsewhere

`ConsoleRenderer.observer(Writer)` is the default renderer, built on
`TurnObserver.builder()`. Reach for it directly — rather than
`ConsoleRepl`'s default — when an application wants the console look
composed alongside another concern, such as a transcript file or a metrics
counter, on the same observer.

## Where next

- [Planning](../concepts/planning.md) — `update_plan`, the injected context
  block, and the console checklist's other half.
- [Getting Started](getting-started.md) — building the `Agent<String>` a
  `ConsoleRepl` drives.
- [Tools and Grants](../concepts/tools-and-grants.md) — `UsagePolicy.requireApproval()`,
  the gate `ConsoleApprover` answers.
