# Nessy Example: Chat CLI

The second example, and the interactive terminal front door: one agent
definition (`DemoAgent`), one main (`Chat`), driven by `nessy-console`'s
`ConsoleRepl`. No Spring, no HTTP, no hand-rolled loop — the loop, the
streaming renderer, and the spinner all come from the library now; this
module supplies only the agent and the banner.

Switching providers means switching the environment variable, not the
command line: `Chat` calls `EnvModelProviders.fromEnv()`
(`nessy-model-env`), which picks Anthropic or OpenAI by which API key is
set. There is no more `-Dexec.mainClass` juggling between two mains — one
main, one run command, either provider:

## Run it

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

```bash
OPENAI_API_KEY=… ./mvnw -q -pl nessy-examples/chat-cli -am compile exec:java
```

The pom pins `exec.mainClass` to `org.jwcarman.nessy.examples.Chat`, so
neither run needs `-Dexec.mainClass` on the command line — the provider
choice moved to which API key is set, not which main you run. Set both keys
and `NESSY_PROVIDER=openai` (or `anthropic`) to be explicit about the tie;
leave it unset with both keys present and the demo defaults to Anthropic,
noisily, on one `WARN` log line.

The `-am` flag also builds this module's reactor dependencies — the first
run compiles that whole upstream chain and takes noticeably longer; every
run after is fast, since Maven only recompiles what changed.

No Docker, no database: the conversation is JVM-lifetime state, same as
`night-watchman` — nothing here asks for durability, and there's nothing to
stand up beyond the JVM itself.

## The lesson survived: live narration vs. the settled fact log

`AnthropicChat` and `OpenAiChat` used to exist as two parallel mains,
differing only in which provider module they imported — a needlessly
literal way to teach "you can point this at either provider." Collapsing to
one main and letting `fromEnv()` make the choice is strictly better
teaching, so that's what shipped; the two-main contrast survives here in
prose, not in two copies of the same loop.

What both old mains taught about **live narration** survives untouched,
now via `ConsoleRepl`'s default renderer: assistant prose streams as
`TextDelta`s arrive, so it appears on screen the same way it appears in a
real chat client — word by word, not as one block after the turn settles.
That's a different vocabulary from `TurnObserver.logging(Logger, prefix)`,
which only narrates settled facts (`AssistantSaid`, tool
requested/completed/parked, the ending), never deltas — a streaming REPL's
whole point is the deltas.

`AnthropicChat` additionally subscribed to `Conversation#events()` for
`ToolFinished`, printing it as a second, independent line — the fact-log
side of the same story, alongside whatever the turn observer rendered live.
That two-watching-surfaces lesson survives in `DemoAgent`, relocated: since
`ConsoleRepl` now owns conversation construction end to end (one
conversation built inside its own `run()`, no instance handed back to the
caller), there is no live `Conversation` left at the call site to attach a
per-conversation `events()` subscription to. The equivalent channel is now a
build-time `AgentBuilder#listen(Class, Consumer)` declaration on `DemoAgent`
instead — the same `ListenerRegistry` delivery, just declared once on the
agent rather than attached once per conversation. It announces
`ConversationEvent.ModelResponded`'s token usage, a fact the turn narration
never shows, rather than `ToolFinished` (which `ConsoleRenderer`'s default
already narrates live as a dim `⚙ tool: <name> completed` line — announcing
it again here would narrate the same completion twice):

```
you> what time is it?
⚙ tool: clock requested

⚙ tool: clock completed

it's 2:00 PM.

tokens: 142 in / 18 out (0 cached)
```

## `java.lang.IO`

`DemoAgent`'s usage announcement prints with a bare `IO.println(...)` call —
no `System.out`, no import. That's `java.lang.IO`, the console I/O class
JEP 512 (Compact Source Files and Instance Main Methods, finalized in JDK
25) makes implicitly available everywhere `java.lang.*` already is.

## The approval prompt

`ClockTool` is granted `UsagePolicy.requireApproval()` (on purpose — every
demo run exercises the gate), so asking the model for the time parks the
turn on `ConsoleApprover` — now `nessy-console`'s public library class, not
a module-private copy — which prints the request and blocks on an answer:

```
you> what time is it?
⚙ tool: clock requested

approve: read the current time
y/n>
```

`y` allows the call; `n` or end of input (EOF) denies it
(`"declined at the console"`); anything else reprompts with
`please answer y or n` rather than being read as a denial. Once the call is
allowed or denied, the conversation continues from wherever the model takes
it, same as any other turn.
